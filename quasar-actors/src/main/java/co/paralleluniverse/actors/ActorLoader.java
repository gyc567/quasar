/*
 * Quasar: lightweight threads and actors for the JVM.
 * Copyright (C) 2013, Parallel Universe Software Co. All rights reserved.
 * 
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *  
 *   or (per the licensee's choosing)
 *  
 * under the terms of the GNU Lesser General Public License version 3.0
 * as published by the Free Software Foundation.
 */
package co.paralleluniverse.actors;

import static co.paralleluniverse.common.reflection.ClassLoaderUtil.isClassFile;
import static co.paralleluniverse.common.reflection.ClassLoaderUtil.resourceToClass;
import co.paralleluniverse.common.util.Exceptions;
import com.google.common.base.Function;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static java.nio.file.StandardWatchEventKinds.*;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.InstanceAlreadyExistsException;
import javax.management.ListenerNotFoundException;
import javax.management.MBeanNotificationInfo;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.Notification;
import javax.management.NotificationBroadcasterSupport;
import javax.management.NotificationEmitter;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import javax.management.ObjectName;
import jsr166e.ConcurrentHashMapV8;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads actor classes for hot code-swapping.
 *
 * @author pron
 */
class ActorLoader extends ClassLoader implements ActorLoaderMXBean, NotificationEmitter {
    /*
     * We load actor classes from the latest module.
     * Non-actor classes are compared against those found in main. 
     * If there's no match, they are loaded from the module that requested them, i.e., non-actor classes are not shared from one module
     * to another.
     */
//    private static final boolean TRY_RELOAD = Boolean.getBoolean("co.paralleluniverse.actors.tryHotSwapReload");
    public static final String MODULE_DIR_PROPERTY = "co.paralleluniverse.actors.moduleDir";
    private static final Path moduleDir;
    private static final Logger LOG = LoggerFactory.getLogger(ActorLoader.class);
    private static final ActorLoader instance;

    static {
        ClassLoader.registerAsParallelCapable();

        instance = new ActorLoader("co.paralleluniverse:type=ActorLoader");

        String moduleDirName = System.getProperty(MODULE_DIR_PROPERTY);
        if (moduleDirName != null) {
            Path mdir = Paths.get(moduleDirName);
            try {
                mdir = mdir.toAbsolutePath();
                Files.createDirectories(mdir);
                mdir = mdir.toRealPath();
            } catch (IOException e) {
                LOG.error("Error findong/creating module directory " + mdir, e);
                mdir = null;
            }
            moduleDir = mdir;

            loadModulesInModuleDir(instance, moduleDir);
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    monitorFilesystem(instance, moduleDir);
                }
            }, "actor-loader-filesystem-monitor");
            t.setDaemon(true);
            t.start();
        } else
            moduleDir = null;
    }

    public static AtomicReference<Class<? extends Actor<?, ?>>> getActorClassRef(String className) {
        return instance.getActorClassRef0(className);
    }

    public static AtomicReference<Class<? extends Actor<?, ?>>> getActorClassRef(Class<?> clazz) {
        return instance.getActorClassRef0(clazz);
    }

    public static <T extends Actor<?, ?>> Class<T> currentClassFor(Class<T> clazz) {
        return instance.getActorClass(clazz);
    }

    public static <T extends Actor<?, ?>> Class<T> currentClassFor(String className) {
        return instance.getActorClass(className);
    }

    public static <T extends Actor<?, ?>> T getReplacementFor(T actor) {
        return instance.getReplacementFor0(actor);
    }
    //
    private final ConcurrentHashMapV8<String, AtomicReference<Class<? extends Actor<?, ?>>>> actorClasses = new ConcurrentHashMapV8<>();
    private final List<ActorModule> modules = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<String, ActorModule> classModule = new ConcurrentHashMapV8<>();
    private final ClassValue<InstanceUpgrader> instanceUpgrader = new ClassValue<InstanceUpgrader>() {
        @Override
        protected InstanceUpgrader computeValue(Class<?> type) {
            return new InstanceUpgrader(type);
        }
    };
    private final ThreadLocal<Boolean> recursive = new ThreadLocal<Boolean>();
    private final NotificationBroadcasterSupport notificationBroadcaster;
    private int notificationSequenceNumber;

    private ActorLoader(String mbeanName) {
        super(ActorLoader.class.getClassLoader());
        MBeanNotificationInfo info = new MBeanNotificationInfo(
                new String[]{ModuleNotification.NAME},
                ModuleNotification.class.getName(),
                "Actor module change");
        this.notificationBroadcaster = new NotificationBroadcasterSupport(info);
        registerMBean(mbeanName);
    }

    private void registerMBean(String mbeanName) {
        try {
            final MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            final ObjectName mxbeanName = new ObjectName(mbeanName);
            mbs.registerMBean(this, mxbeanName);
        } catch (InstanceAlreadyExistsException ex) {
            throw new RuntimeException(ex);
        } catch (MBeanRegistrationException ex) {
            LOG.error("exception while registering MBean " + mbeanName, ex);
        } catch (NotCompliantMBeanException ex) {
            throw new AssertionError(ex);
        } catch (MalformedObjectNameException ex) {
            throw new AssertionError(ex);
        }
    }

    private ActorModule getModule(URL url) {
        for (ActorModule m : modules) {
            if (m.getURL().equals(url))
                return m;
        }
        return null;
    }

    private Map<String, Class<? extends Actor<?, ?>>> checkModule(ActorModule module) {
        Map<String, Class<? extends Actor<?, ?>>> oldClasses = new HashMap<>();
        try {
            for (String className : module.getUpgradeClasses()) {
                Class<? extends Actor<?, ?>> newClass = null;
                try {
                    newClass = (Class<? extends Actor<?, ?>>) module.loadClassInModule(className);
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException("Upgraded class " + className + " is not found in module " + module);
                }
                if (!Actor.class.isAssignableFrom(newClass))
                    throw new RuntimeException("Upgraded class " + className + " in module " + module + " is not an actor");
                Class<? extends Actor<?, ?>> oldClass = null;
                try {
                    oldClass = (Class<? extends Actor<?, ?>>) this.loadClass(className);
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException("Upgraded class " + className + " does not upgrade an existing class");
                }
                if (!Actor.class.isAssignableFrom(oldClass))
                    throw new RuntimeException("Upgraded class " + className + " in module " + module + " does not upgrade an actor");
                oldClasses.put(className, oldClass);
            }
            return oldClasses;
        } catch (Exception e) {
            LOG.error("Error while loading module " + module, e);
            throw e;
        }
    }

    @Override
    public List<String> getLoadedModules() {
        return Lists.transform(modules, new Function<ActorModule, String>() {
            @Override
            public String apply(ActorModule module) {
                return module.getURL().toString();
            }
        });
    }

    @Override
    public synchronized void reloadModule(String jarURL) {
        try {
            reloadModule(new URL(jarURL));
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public synchronized void loadModule(String jarURL) {
        try {
            loadModule(new URL(jarURL));
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public synchronized void unloadModule(String jarURL) {
        try {
            unloadModule(new URL(jarURL));
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public synchronized void reloadModule(URL jarURL) {
        ActorModule oldModule = getModule(jarURL);

        LOG.info("{} module {}.", oldModule == null ? "Loading" : "Reloading", jarURL);
        ActorModule module = new ActorModule(jarURL, this);
        addModule(module);
        if (oldModule != null)
            removeModule(oldModule);
        LOG.info("Module {} {}.", jarURL, oldModule == null ? "loaded" : "reloaded");
        notify(module, oldModule == null ? "loaded" : "reloaded");
    }

    public synchronized void loadModule(URL jarURL) {
        if (getModule(jarURL) != null) {
            LOG.warn("loadModule: module {} already loaded.", jarURL);
            return;
        }
        LOG.info("Loading module {}.", jarURL);
        ActorModule module = new ActorModule(jarURL, this);
        addModule(module);
        LOG.info("Module {} loaded.", jarURL);
        notify(module, "loaded");
    }

    public synchronized void unloadModule(URL jarURL) {
        ActorModule module = getModule(jarURL);
        if (module == null) {
            LOG.warn("removeModule: module {} not loaded.", jarURL);
            return;
        }

        LOG.info("Removing module {}.", jarURL);
        removeModule(module);
        LOG.info("Module {} removed.", jarURL);
        notify(module, "removed");
    }

    private synchronized void addModule(ActorModule module) {
        Map<String, Class<? extends Actor<?, ?>>> oldClasses = checkModule(module);
        modules.add(module);

        for (String className : module.getUpgradeClasses()) {
            Class<? extends Actor> oldClass, newClass;
            try {
                newClass = (Class<? extends Actor>) module.loadClass(className);
                oldClass = (Class<? extends Actor>) oldClasses.get(className);
            } catch (ClassNotFoundException e) {
                throw new AssertionError();
            }
            LOG.info("Upgrading class {} of module {} to that in module {}", className, getModule(oldClass), module);

            classModule.put(className, module);
        }
        performUpgrade(new HashSet<>(oldClasses.values()));
    }

    private synchronized void removeModule(ActorModule module) {
        modules.remove(module);

        Set<Class<? extends Actor<?, ?>>> oldClasses = new HashSet<>();
        for (String className : module.getUpgradeClasses()) {
            if (classModule.get(className) == module) {
                ActorModule newModule = null;
                for (ActorModule m : Lists.reverse(modules)) {
                    if (m.getUpgradeClasses().contains(className)) {
                        newModule = m;
                        break;
                    }
                }
                LOG.info("Downgrading class {} of module {} to that in module {}", className, module, newModule);
                if (newModule != null)
                    classModule.put(className, newModule);
                else
                    classModule.remove(className);

                Class oldClass = module.findLoadedClassInModule(className);
                if (oldClass != null)
                    oldClasses.add(oldClass);
            }
        }
        performUpgrade(oldClasses);
    }

    private void performUpgrade(Set<Class<? extends Actor<?, ?>>> oldClasses) {
//        if (TRY_RELOAD && JavaAgent.isActive()) {
//            try {
//                LOG.info("Attempting to redefine classes");
//                List<ClassDefinition> classDefinitions = new ArrayList<>();
//                for (Class<?> oldClass : oldClasses) {
//                    byte[] classFile = null;
//                    try (InputStream is = getResourceAsStream(toClassFileName(oldClass))) {
//                        classFile = ByteStreams.toByteArray(is);
//                    }
//                    classDefinitions.add(new ClassDefinition(oldClass, classFile));
//                }
//                Retransform.redefine(classDefinitions);
//                LOG.info("Class redefinition succeeded.");
//            } catch (Exception e) {
//                LOG.info("Class redefinition failed due to exception. Upgrading.", e);
//            }
//        }
        for (Class<? extends Actor<?, ?>> oldClass : oldClasses) {
            LOG.debug("Triggering replacement of {} ({})", oldClass, getModule(oldClass));
            getActorClassRef0(oldClass).set(loadCurrentClass(oldClass.getName()));
        }
    }

    public static ActorModule getModule(Class<?> clazz) {
        return clazz.getClassLoader() instanceof ActorModule ? (ActorModule) clazz.getClassLoader() : null;
    }

    <T extends Actor<?, ?>> Class<T> loadCurrentClass(String className) {
        try {
            ActorModule module = classModule.get(className);
            Class<?> clazz;
            if (module != null)
                clazz = (Class<T>) module.loadClass(className);
            else
                clazz = (Class<T>) getParent().loadClass(className);
            LOG.debug("currentClassFor {} - {} {}", className, getModule(clazz), module);
            return (Class<T>) clazz;
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    <T extends Actor<?, ?>> T getReplacementFor0(T actor) {
        Class<T> clazz = (Class<T>) actor.getClass();
        if (clazz.isAnonymousClass())
            return actor;
        Class<? extends T> newClazz = getActorClass(clazz.getName());
        if (newClazz == clazz)
            return actor;
        return (T) instanceUpgrader.get(newClazz).copy(actor);
    }

    <T extends Actor<?, ?>> Class<T> getActorClass(Class<T> clazz) {
        if (clazz.isAnonymousClass())
            return clazz;
        return getActorClass(clazz.getName());
    }

    <T extends Actor<?, ?>> Class<T> getActorClass(String className) {
        AtomicReference<Class<? extends Actor<?, ?>>> ref = getActorClassRef(className);
        Class<? extends Actor<?, ?>> clazz = ref.get();
        if (clazz == null) {
            clazz = loadCurrentClass(className);
            if (!ref.compareAndSet(null, clazz))
                clazz = ref.get();
        }
        assert clazz != null;
        return (Class<T>) clazz;
    }

    AtomicReference<Class<? extends Actor<?, ?>>> getActorClassRef0(Class<?> clazz) {
        if (clazz.isAnonymousClass())
            return null;
        return getActorClassRef0(clazz.getName());
    }

    AtomicReference<Class<? extends Actor<?, ?>>> getActorClassRef0(String className) {
        return actorClasses.computeIfAbsent(className, new ConcurrentHashMapV8.Fun<String, AtomicReference<Class<? extends Actor<?, ?>>>>() {
            @Override
            public AtomicReference<Class<? extends Actor<?, ?>>> apply(String a) {
                return new AtomicReference<>();
            }
        });
//        AtomicReference<Class<?>> ref = actorClasses.get(className);
//        if (ref == null) {
//            ref = new AtomicReference<>();
//            AtomicReference<Class<?>> tmp = actorClasses.putIfAbsent(className, ref);
//            if (tmp != null)
//                ref = tmp;
//        }
//        return ref;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (recursive.get() == Boolean.TRUE)
            throw new ClassNotFoundException(name);

        recursive.set(Boolean.TRUE);
        try {
            ActorModule module = classModule.get(name);
//            ActorModule module = null;
//            for (ActorModule m : Lists.reverse(modules)) {
//                if (m.getUpgradeClasses().contains(name)) {
//                    module = m;
//                    break;
//                }
//            }
            if (module != null)
                return module.loadClassInModule(name);
        } finally {
            recursive.remove();
        }
        return getParent().loadClass(name);
    }

    @Override
    public URL getResource(String name) {
        if (recursive.get() == Boolean.TRUE)
            return null;

        recursive.set(Boolean.TRUE);
        try {
            if (isClassFile(name)) {
                String className = resourceToClass(name);
                ActorModule module = classModule.get(className);
//                ActorModule module = null;
//                for (ActorModule m : Lists.reverse(modules)) {
//                    if (m.getUpgradeClasses().contains(className)) {
//                        module = m;
//                        break;
//                    }
//                }
                if (module != null)
                    return module.getResource(name);
            }
        } finally {
            recursive.remove();
        }
        return super.getResource(name);
    }

    @Override
    public MBeanNotificationInfo[] getNotificationInfo() {
        return notificationBroadcaster.getNotificationInfo();
    }

    @Override
    public void addNotificationListener(NotificationListener listener, NotificationFilter filter, Object handback) throws IllegalArgumentException {
        notificationBroadcaster.addNotificationListener(listener, filter, handback);
    }

    @Override
    public void removeNotificationListener(NotificationListener listener, NotificationFilter filter, Object handback) throws ListenerNotFoundException {
        notificationBroadcaster.removeNotificationListener(listener, filter, handback);
    }

    @Override
    public void removeNotificationListener(NotificationListener listener) throws ListenerNotFoundException {
        notificationBroadcaster.removeNotificationListener(listener);
    }

    private synchronized void notify(ActorModule module, String action) {
        final Notification n = new ModuleNotification(this, notificationSequenceNumber++, System.currentTimeMillis(),
                "Module " + module + " has been " + action);
        notificationBroadcaster.sendNotification(n);
    }

    private static class ModuleNotification extends Notification {
        static final String NAME = "co.paralleluniverse.actors.module";

        public ModuleNotification(String type, Object source, long sequenceNumber, String message) {
            super(NAME, source, sequenceNumber, message);
        }

        public ModuleNotification(Object source, long sequenceNumber, long timeStamp, String message) {
            super(NAME, source, sequenceNumber, timeStamp, message);
        }
    }

    private static void loadModulesInModuleDir(ActorLoader instance, Path moduleDir) {
        LOG.info("scanning module directory " + moduleDir + " for modules.");
        try (DirectoryStream<Path> children = Files.newDirectoryStream(moduleDir)) {
            for (Path child : children) {
                if (isValidFile(child, false)) {
                    try {
                        final URL jarUrl = child.toUri().toURL();
                        instance.reloadModule(jarUrl);
                    } catch (Exception e) {
                        LOG.error("exception while processing " + child, e);
                    }
                } else {
                    LOG.warn("A non-jar item " + child.getFileName() + " found in the modules directory " + moduleDir);
                }
            }
        } catch (Exception e) {
            LOG.error("exception while loading modules in module directory " + moduleDir, e);
        }
    }

    private static void monitorFilesystem(ActorLoader instance, Path moduleDir) {
        try (WatchService watcher = FileSystems.getDefault().newWatchService();) {
            moduleDir.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);

            LOG.info("Filesystem monitor: Watching module directory " + moduleDir + " for changes.");
            for (;;) {
                final WatchKey key = watcher.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    final WatchEvent.Kind<?> kind = event.kind();

                    if (kind == OVERFLOW) { // An OVERFLOW event can occur regardless of registration if events are lost or discarded.
                        LOG.warn("Filesystem monitor: filesystem events may have been missed");
                        continue;
                    }

                    final WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    final Path filename = ev.context(); // The filename is the context of the event.
                    final Path child = moduleDir.resolve(filename); // Resolve the filename against the directory.
                    if (isValidFile(child, kind == ENTRY_DELETE)) {
                        try {
                            final URL jarUrl = child.toUri().toURL();

                            LOG.info("Filesystem monitor: detected module file {} {}", child,
                                    kind == ENTRY_CREATE ? "created"
                                    : kind == ENTRY_MODIFY ? "modified"
                                    : kind == ENTRY_DELETE ? "deleted"
                                    : null);

                            if (kind == ENTRY_CREATE || kind == ENTRY_MODIFY)
                                instance.reloadModule(jarUrl);
                            else if (kind == ENTRY_DELETE)
                                instance.unloadModule(jarUrl);
                        } catch (Exception e) {
                            LOG.error("Filesystem monitor: exception while processing " + child, e);
                        }
                    } else {
                        if (kind == ENTRY_CREATE || kind == ENTRY_MODIFY)
                            LOG.warn("Filesystem monitor: A non-jar item " + child.getFileName() + " has been placed in the modules directory " + moduleDir);
                    }
                }
                if (!key.reset())
                    throw new IOException("Directory " + moduleDir + " is no longer accessible");
            }
        } catch (Exception e) {
            LOG.error("Filesystem monitor thread terminated with an exception", e);
            throw Exceptions.rethrow(e);
        }
    }

    private static boolean isValidFile(Path file, boolean delete) {
        return (delete || Files.isRegularFile(file)) && file.getFileName().toString().endsWith(".jar");
    }
}
