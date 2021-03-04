/*
 * Copyright (c) 2017, 2021, Oracle and/or its affiliates.
 * Copyright (c) 2013, Regents of the University of California
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.graal.python.runtime;

import static com.oracle.graal.python.builtins.objects.thread.PThread.GRAALPYTHON_THREADS;
import static com.oracle.graal.python.nodes.BuiltinNames.BUILTINS;
import static com.oracle.graal.python.nodes.BuiltinNames.__BUILTINS__;
import static com.oracle.graal.python.nodes.BuiltinNames.__MAIN__;
import static com.oracle.graal.python.nodes.SpecialAttributeNames.__ANNOTATIONS__;
import static com.oracle.graal.python.nodes.SpecialAttributeNames.__FILE__;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.nio.file.LinkOption;
import java.text.MessageFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.options.OptionKey;

import com.oracle.graal.python.PythonLanguage;
import com.oracle.graal.python.builtins.Python3Core;
import com.oracle.graal.python.builtins.objects.PNone;
import com.oracle.graal.python.builtins.objects.PythonAbstractObject;
import com.oracle.graal.python.builtins.objects.cext.PythonNativeClass;
import com.oracle.graal.python.builtins.objects.cext.capi.CApiContext;
import com.oracle.graal.python.builtins.objects.cext.capi.PThreadState;
import com.oracle.graal.python.builtins.objects.cext.capi.PythonNativeWrapper;
import com.oracle.graal.python.builtins.objects.cext.hpy.GraalHPyContext;
import com.oracle.graal.python.builtins.objects.common.HashingCollectionNodes.GetDictStorageNode;
import com.oracle.graal.python.builtins.objects.common.HashingStorage;
import com.oracle.graal.python.builtins.objects.common.HashingStorageLibrary;
import com.oracle.graal.python.builtins.objects.common.SequenceStorageNodes;
import com.oracle.graal.python.builtins.objects.dict.PDict;
import com.oracle.graal.python.builtins.objects.frame.PFrame;
import com.oracle.graal.python.builtins.objects.frame.PFrame.Reference;
import com.oracle.graal.python.builtins.objects.function.PKeyword;
import com.oracle.graal.python.builtins.objects.list.PList;
import com.oracle.graal.python.builtins.objects.module.PythonModule;
import com.oracle.graal.python.builtins.objects.object.PythonObjectLibrary;
import com.oracle.graal.python.builtins.objects.str.PString;
import com.oracle.graal.python.builtins.objects.thread.PLock;
import com.oracle.graal.python.nodes.SpecialAttributeNames;
import com.oracle.graal.python.nodes.SpecialMethodNames;
import com.oracle.graal.python.nodes.attributes.ReadAttributeFromObjectNode;
import com.oracle.graal.python.nodes.call.CallNode;
import com.oracle.graal.python.runtime.AsyncHandler.AsyncAction;
import com.oracle.graal.python.runtime.exception.ExceptionUtils;
import com.oracle.graal.python.runtime.exception.PException;
import com.oracle.graal.python.runtime.object.IDUtils;
import com.oracle.graal.python.util.Consumer;
import com.oracle.graal.python.util.ShutdownHook;
import com.oracle.graal.python.util.Supplier;
import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.TruffleLogger;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.ExceptionType;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.utilities.CyclicAssumption;

public final class PythonContext {
    private static final Source IMPORT_WARNINGS_SOURCE = Source.newBuilder(PythonLanguage.ID, "import warnings\n", "<internal>").internal(true).build();
    private static final Source FORCE_IMPORTS_SOURCE = Source.newBuilder(PythonLanguage.ID, "import site\n", "<internal>").internal(true).build();
    private static final TruffleLogger LOGGER = PythonLanguage.getLogger(PythonContext.class);
    private volatile boolean finalizing;

    private static final class PythonThreadState {

        /*
         * A thread state may be owned by multiple threads if we know that these threads won't run
         * concurrently.
         */
        final List<WeakReference<Thread>> owners;

        /*
         * The reference to the last top frame on the Python stack during interop calls. Initially,
         * this is EMPTY representing the top frame.
         */
        PFrame.Reference topframeref = Reference.EMPTY;

        WeakReference<PLock> sentinelLock;

        /* corresponds to 'PyThreadState.curexc_*' */
        PException currentException;

        /* corresponds to 'PyThreadState.exc_*' */
        PException caughtException;

        /* set to emulate Py_ReprEnter/Leave */
        HashSet<Object> reprObjectSet;

        PythonThreadState() {
            owners = new LinkedList<>();
        }

        PythonThreadState(Thread owner) {
            this();
            addOwner(owner);
        }

        void addOwner(Thread owner) {
            owners.add(new WeakReference<>(owner));
        }

        void removeOwner(Thread thread) {
            owners.removeIf(item -> item.get() == thread);
        }

        boolean isOwner(Thread thread) {
            for (WeakReference<Thread> owner : owners) {
                if (owner.get() == thread) {
                    return true;
                }
            }
            return false;
        }

        boolean hasOwners() {
            // first, remove all gone weak references
            owners.removeIf(item -> item.get() == null);
            return !owners.isEmpty();
        }

        List<WeakReference<Thread>> getOwners() {
            return owners;
        }

        @TruffleBoundary
        boolean reprEnter(Object item) {
            if (reprObjectSet == null) {
                reprObjectSet = new HashSet<>();
            }
            return reprObjectSet.add(item);
        }

        @TruffleBoundary
        void reprLeave(Object item) {
            reprObjectSet.remove(item);
        }
    }

    private static final class AtExitHook {
        final Object callable;
        final Object[] arguments;
        final PKeyword[] keywords;
        final CallTarget ct;

        AtExitHook(Object callable, Object[] arguments, PKeyword[] keywords, CallTarget ct) {
            this.callable = callable;
            this.arguments = arguments;
            this.keywords = keywords;
            this.ct = ct;
        }
    }

    static final String PREFIX = "/";
    static final String LIB_PYTHON_3 = "/lib-python/3";
    static final String LIB_GRAALPYTHON = "/lib-graalpython";
    static final String NO_CORE_FATAL = "could not determine Graal.Python's core path - you must pass --python.CoreHome.";
    static final String NO_PREFIX_WARNING = "could not determine Graal.Python's sys prefix path - you may need to pass --python.SysPrefix.";
    static final String NO_CORE_WARNING = "could not determine Graal.Python's core path - you may need to pass --python.CoreHome.";
    static final String NO_STDLIB = "could not determine Graal.Python's standard library path. You need to pass --python.StdLibHome if you want to use the standard library.";
    static final String NO_CAPI = "could not determine Graal.Python's C API library path. You need to pass --python.CAPI if you want to use the C extension modules.";

    private final PythonLanguage language;
    private PythonModule mainModule;
    private final Python3Core core;
    private final List<ShutdownHook> shutdownHooks = new ArrayList<>();
    private final List<AtExitHook> atExitHooks = new ArrayList<>();
    private final HashMap<PythonNativeClass, CyclicAssumption> nativeClassStableAssumptions = new HashMap<>();
    private final ThreadGroup threadGroup = new ThreadGroup(GRAALPYTHON_THREADS);
    private final IDUtils idUtils = new IDUtils();

    @CompilationFinal private PosixSupport posixSupport;
    @CompilationFinal private NFIZlibSupport nativeZlib;
    @CompilationFinal private NFIBz2Support nativeBz2lib;

    // if set to 0 the VM will set it to whatever it likes
    private final AtomicLong pythonThreadStackSize = new AtomicLong(0);
    private final Assumption nativeObjectsAllManagedAssumption = Truffle.getRuntime().createAssumption("all C API objects are managed");

    @CompilationFinal private TruffleLanguage.Env env;

    /* this will be the single thread state if running single-threaded */
    private final PythonThreadState singleThreadState = new PythonThreadState();

    /* for fast access to the PythonThreadState object by the owning thread */
    private ThreadLocal<PythonThreadState> threadState;

    /* map of thread IDs to indices for array 'threadStates' */
    private Map<Long, PythonThreadState> threadStateMapping;

    private final ReentrantLock importLock = new ReentrantLock();
    @CompilationFinal private boolean isInitialized = false;

    @CompilationFinal private PythonModule builtinsModule;
    @CompilationFinal private PDict sysModules;

    private OutputStream out;
    private OutputStream err;
    private InputStream in;
    @CompilationFinal private CApiContext cApiContext;
    @CompilationFinal private GraalHPyContext hPyContext;
    private final Assumption singleThreaded = Truffle.getRuntime().createAssumption("single Threaded");

    private static final Assumption singleNativeContext = Truffle.getRuntime().createAssumption("single native context assumption");

    /* A lock for interop calls when this context is used by multiple threads. */
    private ReentrantLock interopLock;

    /** The thread-local state object. */
    private ThreadLocal<PThreadState> customThreadState;

    /** Native wrappers for context-insensitive singletons like {@link PNone#NONE}. */
    @CompilationFinal(dimensions = 1) private final PythonNativeWrapper[] singletonNativePtrs = new PythonNativeWrapper[PythonLanguage.getNumberOfSpecialSingletons()];

    // The context-local resources
    private EmulatedPosixSupport resources;
    private final AsyncHandler handler;
    private final AsyncHandler.SharedFinalizer sharedFinalizer;

    // decides if we run the async weakref callbacks and destructors
    private boolean gcEnabled = true;

    // A thread-local to store the full path to the currently active import statement, for Jython
    // compat
    private final ThreadLocal<ArrayDeque<String>> currentImport = new ThreadLocal<>();

    @CompilationFinal(dimensions = 1) private Object[] optionValues;

    public PythonContext(PythonLanguage language, TruffleLanguage.Env env, Python3Core core) {
        this.language = language;
        this.core = core;
        this.env = env;
        this.handler = new AsyncHandler(this);
        this.sharedFinalizer = new AsyncHandler.SharedFinalizer(this);
        this.optionValues = PythonOptions.createOptionValuesStorage(env);
        this.in = env.in();
        this.out = env.out();
        this.err = env.err();
    }

    public ThreadGroup getThreadGroup() {
        return threadGroup;
    }

    @TruffleBoundary(allowInlining = true)
    public long getPythonThreadStackSize() {
        return pythonThreadStackSize.get();
    }

    public long getAndSetPythonsThreadStackSize(long value) {
        return pythonThreadStackSize.getAndSet(value);
    }

    public long getNextObjectId() {
        return idUtils.getNextObjectId();
    }

    public long getNextObjectId(Object object) {
        return idUtils.getNextObjectId(object);
    }

    public long getNextStringId(String string) {
        return idUtils.getNextStringId(string);
    }

    public <T> T getOption(OptionKey<T> key) {
        assert !PythonOptions.isEngineOption(key) : "Querying engine option via context.";
        if (CompilerDirectives.inInterpreter()) {
            return getEnv().getOptions().get(key);
        } else {
            return PythonOptions.getOptionUnrolling(this.optionValues, PythonOptions.getOptionKeys(), key);
        }
    }

    public PythonLanguage getLanguage() {
        return language;
    }

    public ReentrantLock getImportLock() {
        return importLock;
    }

    public PDict getImportedModules() {
        return sysModules;
    }

    public PDict getSysModules() {
        return sysModules;
    }

    public PythonModule getBuiltins() {
        return builtinsModule;
    }

    public Object getPosixSupport() {
        return posixSupport;
    }

    public boolean isNativeAccessAllowed() {
        return env.isNativeAccessAllowed();
    }

    public NFIZlibSupport getNFIZlibSupport() {
        return nativeZlib;
    }

    public NFIBz2Support getNFIBz2Support() {
        return nativeBz2lib;
    }

    public TruffleLanguage.Env getEnv() {
        return env;
    }

    public void setEnv(TruffleLanguage.Env newEnv) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        env = newEnv;
        in = env.in();
        out = env.out();
        err = env.err();
        resources.setEnv(env);
        posixSupport.setEnv(env);
        optionValues = PythonOptions.createOptionValuesStorage(newEnv);
    }

    /**
     * Just for testing
     */
    public void setOut(OutputStream out) {
        this.out = out;
    }

    /**
     * Just for testing
     */
    public void setErr(OutputStream err) {
        this.err = err;
    }

    public PythonModule getMainModule() {
        return mainModule;
    }

    public PythonCore getCore() {
        return core;
    }

    public InputStream getStandardIn() {
        return in;
    }

    public OutputStream getStandardErr() {
        return err;
    }

    public OutputStream getStandardOut() {
        return out;
    }

    public void setCurrentException(PException e) {
        getThreadState().currentException = e;
    }

    public PException getCurrentException() {
        return getThreadState().currentException;
    }

    public void setCaughtException(PException e) {
        getThreadState().caughtException = e;
    }

    public PException getCaughtException() {
        return getThreadState().caughtException;
    }

    public void setTopFrameInfo(PFrame.Reference topframeref) {
        getThreadState().topframeref = topframeref;
    }

    public PFrame.Reference popTopFrameInfo() {
        PythonThreadState ts = getThreadState();
        PFrame.Reference ref = ts.topframeref;
        ts.topframeref = null;
        return ref;
    }

    public PFrame.Reference peekTopFrameInfo() {
        return getThreadState().topframeref;
    }

    public boolean reprEnter(Object item) {
        return getThreadState().reprEnter(item);
    }

    public void reprLeave(Object item) {
        getThreadState().reprLeave(item);
    }

    public boolean isInitialized() {
        return isInitialized;
    }

    public void initialize() {
        initializePosixSupport();
        core.initialize(this);
        setupRuntimeInformation(false);
        core.postInitialize();
        if (!ImageInfo.inImageBuildtimeCode()) {
            importSiteIfForced();
        } else if (posixSupport instanceof ImageBuildtimePosixSupport) {
            ((ImageBuildtimePosixSupport) posixSupport).checkLeakingResources();
        }
    }

    public void patch(Env newEnv) {
        setEnv(newEnv);
        setupRuntimeInformation(true);
        core.postInitialize();
        importSiteIfForced();
    }

    private void importSiteIfForced() {
        if (getOption(PythonOptions.ForceImportSite)) {
            CallTarget site = env.parsePublic(FORCE_IMPORTS_SOURCE);
            site.call();
        }
        if (!getOption(PythonOptions.WarnOptions).isEmpty()) {
            // we must force an import of the warnings module here if warnings were passed
            CallTarget site = env.parsePublic(IMPORT_WARNINGS_SOURCE);
            site.call();
        }
    }

    /**
     * During pre-initialization, we're also loading code from the Python standard library. Since
     * some of those modules may be packages, they will have their __path__ attribute set to the
     * absolute path of the package on the build system. We use this function to patch the paths
     * during build time and after starting up from a pre-initialized context so they point to the
     * run-time package paths.
     */
    private void patchPackagePaths(String from, String to) {
        for (Object v : HashingStorageLibrary.getUncached().values(sysModules.getDictStorage())) {
            if (v instanceof PythonModule) {
                // Update module.__path__
                Object path = ((PythonModule) v).getAttribute(SpecialAttributeNames.__PATH__);
                if (path instanceof PList) {
                    Object[] paths = SequenceStorageNodes.CopyInternalArrayNode.getUncached().execute(((PList) path).getSequenceStorage());
                    for (int i = 0; i < paths.length; i++) {
                        Object pathElement = paths[i];
                        String strPath;
                        if (pathElement instanceof PString) {
                            strPath = ((PString) pathElement).getValue();
                        } else if (pathElement instanceof String) {
                            strPath = (String) pathElement;
                        } else {
                            continue;
                        }
                        if (strPath.startsWith(from)) {
                            paths[i] = strPath.replace(from, to);
                        }
                    }
                    ((PythonModule) v).setAttribute(SpecialAttributeNames.__PATH__, core.factory().createList(paths));
                }

                // Update module.__file__
                Object file = ((PythonModule) v).getAttribute(SpecialAttributeNames.__FILE__);
                String strFile = null;
                if (file instanceof PString) {
                    strFile = ((PString) file).getValue();
                } else if (file instanceof String) {
                    strFile = (String) file;
                }
                if (strFile != null) {
                    ((PythonModule) v).setAttribute(SpecialAttributeNames.__FILE__, strFile.replace(from, to));
                }
            }
        }
    }

    private void setupRuntimeInformation(boolean isPatching) {
        nativeZlib = NFIZlibSupport.createNative(this, "");
        nativeBz2lib = NFIBz2Support.createNative(this, "");
        PythonModule sysModule = core.lookupBuiltinModule("sys");
        sysModules = (PDict) sysModule.getAttribute("modules");

        builtinsModule = core.lookupBuiltinModule(BUILTINS);

        mainModule = core.factory().createPythonModule(__MAIN__);
        mainModule.setAttribute(__BUILTINS__, builtinsModule);
        mainModule.setAttribute(__ANNOTATIONS__, core.factory().createDict());
        try {
            PythonObjectLibrary.getUncached().setDict(mainModule, core.factory().createDictFixedStorage(mainModule));
        } catch (UnsupportedMessageException e) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new IllegalStateException("This cannot happen - the main module doesn't accept a __dict__", e);
        }

        sysModules.setItem(__MAIN__, mainModule);

        final String stdLibPlaceholder = "!stdLibHome!";
        if (ImageInfo.inImageBuildtimeCode()) {
            // Patch any pre-loaded packages' paths if we're running
            // pre-initialization
            patchPackagePaths(getStdlibHome(), stdLibPlaceholder);
        } else if (isPatching && ImageInfo.inImageRuntimeCode()) {
            // Patch any pre-loaded packages' paths to the new stdlib home if
            // we're patching a pre-initialized context
            patchPackagePaths(stdLibPlaceholder, getStdlibHome());
        }

        applyToAllThreadStates(ts -> ts.currentException = null);
        isInitialized = true;
    }

    private void initializePosixSupport() {
        String option = getLanguage().getEngineOption(PythonOptions.PosixModuleBackend);
        PosixSupport result;
        // The resources field will be removed once all posix builtins go through PosixSupport
        switch (option) {
            case "java":
                result = resources = new EmulatedPosixSupport(this, false);
                break;
            case "native":
            case "llvm":
                // TODO this condition will be moved into a factory method in NFIPosixBackend
                // for now it's here because we still need to expose the emulated backend as
                // 'resources'
                if (ImageInfo.inImageBuildtimeCode()) {
                    EmulatedPosixSupport emulatedPosixSupport = new EmulatedPosixSupport(this, false);
                    NFIPosixSupport nativePosixSupport = new NFIPosixSupport(this, option);
                    result = new ImageBuildtimePosixSupport(nativePosixSupport, emulatedPosixSupport);
                    resources = emulatedPosixSupport;
                } else if (ImageInfo.inImageRuntimeCode()) {
                    NFIPosixSupport nativePosixSupport = new NFIPosixSupport(this, option);
                    result = new ImageBuildtimePosixSupport(nativePosixSupport, null);
                    resources = new EmulatedPosixSupport(this, true);
                    resources.setEnv(env);
                } else {
                    if (!getOption(PythonOptions.RunViaLauncher)) {
                        writeWarning("Native Posix backend is not fully supported when embedding. For example, standard I/O always uses file " +
                                        "descriptors 0, 1 and 2 regardless of stream redirection specified in Truffle environment");
                    }
                    result = new NFIPosixSupport(this, option);
                    resources = new EmulatedPosixSupport(this, true);
                    resources.setEnv(env);
                }
                break;
            default:
                throw new IllegalStateException(String.format("Wrong value for the PosixModuleBackend option: '%s'", option));
        }
        if (LoggingPosixSupport.isEnabled()) {
            posixSupport = new LoggingPosixSupport(result);
        } else {
            posixSupport = result;
        }
    }

    private String sysPrefix, basePrefix, coreHome, stdLibHome, capiHome;

    public void initializeHomeAndPrefixPaths(Env newEnv, String languageHome) {
        sysPrefix = newEnv.getOptions().get(PythonOptions.SysPrefix);
        basePrefix = newEnv.getOptions().get(PythonOptions.SysBasePrefix);
        coreHome = newEnv.getOptions().get(PythonOptions.CoreHome);
        stdLibHome = newEnv.getOptions().get(PythonOptions.StdLibHome);
        capiHome = newEnv.getOptions().get(PythonOptions.CAPI);

        PythonCore.writeInfo(() -> MessageFormat.format("Initial locations:" +
                        "\n\tLanguage home: {0}" +
                        "\n\tSysPrefix: {1}" +
                        "\n\tBaseSysPrefix: {2}" +
                        "\n\tCoreHome: {3}" +
                        "\n\tStdLibHome: {4}" +
                        "\n\tCAPI: {5}", languageHome, sysPrefix, basePrefix, coreHome, stdLibHome, capiHome));

        String envHome = null;
        try {
            envHome = System.getenv("GRAAL_PYTHONHOME");
        } catch (SecurityException e) {
        }

        final TruffleFile home;
        if (languageHome != null && envHome == null) {
            home = newEnv.getInternalTruffleFile(languageHome);
        } else if (envHome != null) {
            boolean envHomeIsDirectory = false;
            TruffleFile envHomeFile = null;
            try {
                envHomeFile = newEnv.getInternalTruffleFile(envHome);
                envHomeIsDirectory = envHomeFile.isDirectory();
            } catch (SecurityException e) {
            }
            home = envHomeIsDirectory ? envHomeFile : null;
        } else {
            home = null;
        }

        if (home != null) {
            if (sysPrefix.isEmpty()) {
                sysPrefix = home.getAbsoluteFile().getPath();
            }

            if (basePrefix.isEmpty()) {
                basePrefix = home.getAbsoluteFile().getPath();
            }

            if (coreHome.isEmpty()) {
                try {
                    for (TruffleFile f : home.list()) {
                        if (f.getName().equals("lib-graalpython") && f.isDirectory()) {
                            coreHome = f.getPath();
                            break;
                        }
                    }
                } catch (SecurityException | IOException e) {
                }
            }

            if (stdLibHome.isEmpty()) {
                try {
                    outer: for (TruffleFile f : home.list()) {
                        if (f.getName().equals("lib-python") && f.isDirectory()) {
                            for (TruffleFile f2 : f.list()) {
                                if (f2.getName().equals("3") && f.isDirectory()) {
                                    stdLibHome = f2.getPath();
                                    break outer;
                                }
                            }
                        }
                    }
                } catch (SecurityException | IOException e) {
                }
            }

            if (capiHome.isEmpty()) {
                capiHome = coreHome;
            }
        }

        if (ImageInfo.inImageBuildtimeCode()) {
            // use relative paths at buildtime to avoid freezing buildsystem paths
            TruffleFile base = newEnv.getInternalTruffleFile(basePrefix).getAbsoluteFile();
            newEnv.setCurrentWorkingDirectory(base);
            basePrefix = ".";
            sysPrefix = base.relativize(newEnv.getInternalTruffleFile(sysPrefix)).getPath();
            if (sysPrefix.isEmpty()) {
                sysPrefix = ".";
            }
            coreHome = base.relativize(newEnv.getInternalTruffleFile(coreHome)).getPath();
            stdLibHome = base.relativize(newEnv.getInternalTruffleFile(stdLibHome)).getPath();
            capiHome = base.relativize(newEnv.getInternalTruffleFile(capiHome)).getPath();
        }

        PythonCore.writeInfo(() -> MessageFormat.format("Updated locations:" +
                        "\n\tLanguage home: {0}" +
                        "\n\tSysPrefix: {1}" +
                        "\n\tBaseSysPrefix: {2}" +
                        "\n\tCoreHome: {3}" +
                        "\n\tStdLibHome: {4}" +
                        "\n\tExecutable: {5}" +
                        "\n\tCAPI: {6}", home != null ? home.getPath() : "", sysPrefix, basePrefix, coreHome, stdLibHome, newEnv.getOptions().get(PythonOptions.Executable), capiHome));
    }

    @TruffleBoundary
    public String getSysPrefix() {
        if (sysPrefix.isEmpty()) {
            writeWarning(NO_PREFIX_WARNING);
            sysPrefix = PREFIX;
        }
        return sysPrefix;
    }

    @TruffleBoundary
    public String getSysBasePrefix() {
        if (basePrefix.isEmpty()) {
            String homePrefix = language.getHome();
            if (homePrefix == null || homePrefix.isEmpty()) {
                homePrefix = PREFIX;
            }
            basePrefix = homePrefix;
        }
        return basePrefix;
    }

    @TruffleBoundary
    public String getCoreHome() {
        if (coreHome.isEmpty()) {
            writeWarning(NO_CORE_WARNING);
            coreHome = LIB_GRAALPYTHON;
        }
        return coreHome;
    }

    @TruffleBoundary
    public String getStdlibHome() {
        if (stdLibHome.isEmpty()) {
            writeWarning(NO_STDLIB);
            stdLibHome = LIB_PYTHON_3;
        }
        return stdLibHome;
    }

    @TruffleBoundary
    public String getCoreHomeOrFail() {
        if (coreHome.isEmpty()) {
            throw new RuntimeException(NO_CORE_FATAL);
        }
        return coreHome;
    }

    @TruffleBoundary
    public String getCAPIHome() {
        if (capiHome.isEmpty()) {
            writeWarning(NO_CAPI);
            return coreHome;
        }
        return capiHome;
    }

    private static void writeWarning(String warning) {
        LOGGER.warning(warning);
    }

    @TruffleBoundary
    public void registerAtexitHook(ShutdownHook shutdownHook) {
        shutdownHooks.add(shutdownHook);
    }

    @TruffleBoundary
    public void registerAtexitHook(Object callable, Object[] arguments, PKeyword[] keywords, CallTarget ct) {
        atExitHooks.add(new AtExitHook(callable, arguments, keywords, ct));
    }

    @TruffleBoundary
    public void unregisterAtexitHook(Object callable) {
        atExitHooks.removeIf(hook -> hook.callable == callable);
    }

    @TruffleBoundary
    public void clearAtexitHooks() {
        atExitHooks.clear();
    }

    @TruffleBoundary
    public void finalizeContext() {
        finalizing = true;
        shutdownThreads();
        runShutdownHooks();
    }

    @TruffleBoundary
    public int getAtexitHookCount() {
        return atExitHooks.size();
    }

    @TruffleBoundary
    public void runAtexitHooks() {
        // run atExitHooks in reverse order they were registered
        PException lastException = null;
        for (int i = atExitHooks.size() - 1; i >= 0; i--) {
            AtExitHook hook = atExitHooks.get(i);
            try {
                hook.ct.call(hook.callable, hook.arguments, hook.keywords);
            } catch (PException e) {
                lastException = e;
            }
        }
        atExitHooks.clear();
        if (lastException != null) {
            throw lastException;
        }
    }

    @TruffleBoundary
    public void runShutdownHooks() {
        handler.shutdown();
        try {
            runAtexitHooks();
        } catch (PException e) {
            // It was printed already, so just discard
        }
        for (ShutdownHook h : shutdownHooks) {
            h.call(this);
        }
        // destroy thread state
        if (customThreadState != null) {
            customThreadState.set(null);
        }
    }

    @TruffleBoundary
    private void shutdownThreads() {
        LOGGER.fine("shutting down threads");
        PDict importedModules = getImportedModules();
        HashingStorage dictStorage = GetDictStorageNode.getUncached().execute(importedModules);
        Object value = HashingStorageLibrary.getUncached().getItem(dictStorage, "threading");
        if (value != null) {
            Object attrShutdown = ReadAttributeFromObjectNode.getUncached().execute(value, SpecialMethodNames.SHUTDOWN);
            if (attrShutdown == PNone.NO_VALUE) {
                LOGGER.fine("threading module has no member " + SpecialMethodNames.SHUTDOWN);
                return;
            }
            try {
                CallNode.getUncached().execute(null, attrShutdown);
            } catch (Exception | StackOverflowError e) {
                try {
                    boolean exitException = InteropLibrary.getUncached().isException(e) && InteropLibrary.getUncached().getExceptionType(e) == ExceptionType.EXIT;
                    if (!exitException) {
                        ExceptionUtils.printPythonLikeStackTrace(e);
                        if (PythonOptions.isWithJavaStacktrace(getLanguage())) {
                            e.printStackTrace(new PrintWriter(getStandardErr()));
                        }
                    }
                } catch (UnsupportedMessageException unsupportedMessageException) {
                    throw CompilerDirectives.shouldNotReachHere();
                }
                throw e;
            }
        } else {
            // threading was not imported; this is
            LOGGER.finest("threading module was not imported");
        }
        LOGGER.fine("successfully shut down all threads");

        if (!singleThreaded.isValid()) {
            // collect list of threads to join in synchronized block
            LinkedList<WeakReference<Thread>> threadList = new LinkedList<>();
            synchronized (this) {
                for (PythonThreadState ts : threadStateMapping.values()) {
                    // do not join the initial thread; this could cause a dead lock
                    if (ts != singleThreadState) {
                        threadList.addAll(ts.getOwners());
                    }
                }
            }

            // join threads outside the synchronized block otherwise we could run into a dead lock
            try {
                for (WeakReference<Thread> threadRef : threadList) {
                    Thread thread = threadRef.get();
                    if (thread != null) {
                        LOGGER.finest("joining thread " + thread);
                        thread.join();
                    }
                }
            } catch (InterruptedException e) {
                LOGGER.finest("got interrupt while joining threads");
            }
        }
    }

    @TruffleBoundary
    public PThreadState getCustomThreadState() {
        if (customThreadState == null) {
            ThreadLocal<PThreadState> threadLocal = new ThreadLocal<>();
            threadLocal.set(new PThreadState());
            customThreadState = threadLocal;
        }
        return customThreadState.get();
    }

    public void initializeMainModule(String path) {
        if (path != null) {
            mainModule.setAttribute(__FILE__, path);
        }
    }

    public static Assumption getSingleNativeContextAssumption() {
        return singleNativeContext;
    }

    public Assumption getSingleThreadedAssumption() {
        return singleThreaded;
    }

    public Assumption getNativeObjectsAllManagedAssumption() {
        return nativeObjectsAllManagedAssumption;
    }

    public boolean isExecutableAccessAllowed() {
        return getEnv().isHostLookupAllowed() || getEnv().isNativeAccessAllowed();
    }

    public EmulatedPosixSupport getResources() {
        return resources;
    }

    /**
     * Trigger any pending asynchronous actions
     */
    public void triggerAsyncActions(VirtualFrame frame) {
        handler.triggerAsyncActions(frame);
    }

    public AsyncHandler getAsyncHandler() {
        return handler;
    }

    public void registerAsyncAction(Supplier<AsyncAction> actionSupplier) {
        handler.registerAction(actionSupplier);
    }

    @TruffleBoundary
    public CyclicAssumption getNativeClassStableAssumption(PythonNativeClass cls, boolean createOnDemand) {
        CyclicAssumption assumption = nativeClassStableAssumptions.get(cls);
        if (assumption == null && createOnDemand) {
            assumption = new CyclicAssumption("Native class " + cls + " stable");
            nativeClassStableAssumptions.put(cls, assumption);
        }
        return assumption;
    }

    public void setSingletonNativeWrapper(PythonAbstractObject obj, PythonNativeWrapper nativePtr) {
        assert PythonLanguage.getSingletonNativeWrapperIdx(obj) != -1 : "invalid special singleton object";
        assert singletonNativePtrs[PythonLanguage.getSingletonNativeWrapperIdx(obj)] == null;
        singletonNativePtrs[PythonLanguage.getSingletonNativeWrapperIdx(obj)] = nativePtr;
    }

    public PythonNativeWrapper getSingletonNativeWrapper(PythonAbstractObject obj) {
        int singletonNativePtrIdx = PythonLanguage.getSingletonNativeWrapperIdx(obj);
        if (singletonNativePtrIdx != -1) {
            return singletonNativePtrs[singletonNativePtrIdx];
        }
        return null;
    }

    @TruffleBoundary
    public void acquireInteropLock() {
        interopLock.lock();
    }

    @TruffleBoundary
    public void releaseInteropLock() {
        if (interopLock.isLocked()) {
            interopLock.unlock();
        }
    }

    /**
     * This is like {@code Env#getPublicTruffleFile(String)} but also allows access to files in the
     * language home directory matching one of the given file extensions. This is mostly useful to
     * access files of the {@code stdlib}, {@code core} or similar.
     */
    @TruffleBoundary
    public TruffleFile getPublicTruffleFileRelaxed(String path, String... allowedSuffixes) {
        TruffleFile f = env.getInternalTruffleFile(path);
        // 'isDirectory' does deliberately not follow symlinks because otherwise this could allow to
        // escape the language home directory.
        // Also, during image build time, we allow full internal access.
        if (ImageInfo.inImageBuildtimeCode() || isPyFileInLanguageHome(f) && (f.isDirectory(LinkOption.NOFOLLOW_LINKS) || hasAllowedSuffix(path, allowedSuffixes))) {
            return f;
        } else {
            return env.getPublicTruffleFile(path);
        }
    }

    @TruffleBoundary(allowInlining = true)
    private static boolean hasAllowedSuffix(String path, String[] allowedSuffixes) {
        for (String suffix : allowedSuffixes) {
            if (path.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tests if the given {@code TruffleFile} is located in the language home directory.
     */
    @TruffleBoundary
    public boolean isPyFileInLanguageHome(TruffleFile path) {
        assert !ImageInfo.inImageBuildtimeCode() : "language home won't be available during image build time";
        String languageHome = language.getHome();

        // The language home may be 'null' if an embedder uses Python. In this case, IO must just be
        // allowed.
        if (languageHome != null) {
            // This deliberately uses 'getAbsoluteFile' and not 'getCanonicalFile' because if, e.g.,
            // 'path' is a symlink outside of the language home, the user should not be able to read
            // the symlink if 'allowIO' is false.
            TruffleFile coreHomePath = env.getInternalTruffleFile(languageHome).getAbsoluteFile();
            TruffleFile absolutePath = path.getAbsoluteFile();
            return absolutePath.startsWith(coreHomePath);
        }
        LOGGER.log(Level.FINE, () -> "Cannot access file " + path + " because there is no language home.");
        return false;
    }

    @TruffleBoundary
    public String getCurrentImport() {
        ArrayDeque<String> ci = currentImport.get();
        if (ci == null || ci.isEmpty()) {
            return "";
        } else {
            return ci.peek();
        }
    }

    @TruffleBoundary
    public void pushCurrentImport(String object) {
        ArrayDeque<String> ci = currentImport.get();
        if (ci == null) {
            ci = new ArrayDeque<>();
            currentImport.set(ci);
        }
        ci.push(object);
    }

    @TruffleBoundary
    public void popCurrentImport() {
        assert currentImport.get() != null && currentImport.get().peek() != null : "invalid popCurrentImport without push";
        currentImport.get().pop();
    }

    public Thread[] getThreads() {
        CompilerAsserts.neverPartOfCompilation();
        if (singleThreaded.isValid()) {
            return new Thread[]{Thread.currentThread()};
        } else {
            Set<Thread> threads = new HashSet<>();
            for (PythonThreadState ts : threadStateMapping.values()) {
                for (WeakReference<Thread> thRef : ts.getOwners()) {
                    Thread th = thRef.get();
                    if (th != null) {
                        threads.add(th);
                    }
                }
            }
            return threads.toArray(new Thread[0]);
        }
    }

    private PythonThreadState getThreadState() {
        if (singleThreaded.isValid()) {
            return singleThreadState;
        }
        return getThreadStateMultiThreaded();
    }

    @TruffleBoundary
    private PythonThreadState getThreadStateMultiThreaded() {
        PythonThreadState curThreadState = threadState.get();
        if (curThreadState == null) {
            // this should happen just the first time the current thread accesses the thread state
            curThreadState = getThreadStateFullLookup();
            threadState.set(curThreadState);
        }
        assert curThreadState.isOwner(Thread.currentThread());
        return curThreadState;
    }

    private void applyToAllThreadStates(Consumer<PythonThreadState> action) {
        if (singleThreaded.isValid()) {
            action.accept(singleThreadState);
        } else {
            synchronized (this) {
                for (PythonThreadState ts : threadStateMapping.values()) {
                    action.accept(ts);
                }
            }
        }
    }

    @TruffleBoundary
    private synchronized PythonThreadState getThreadStateFullLookup() {
        return threadStateMapping.get(Thread.currentThread().getId());
    }

    public void setSentinelLockWeakref(WeakReference<PLock> sentinelLock) {
        getThreadState().sentinelLock = sentinelLock;
    }

    @TruffleBoundary
    public void initializeMultiThreading() {
        interopLock = new ReentrantLock();
        singleThreaded.invalidate();
        threadState = new ThreadLocal<>();
        synchronized (this) {
            threadStateMapping = new HashMap<>();
            for (WeakReference<Thread> ownerRef : singleThreadState.getOwners()) {
                Thread owner = ownerRef.get();
                if (owner != null) {
                    threadStateMapping.put(owner.getId(), singleThreadState);
                }
            }
        }
    }

    public synchronized void attachThread(Thread thread) {
        CompilerAsserts.neverPartOfCompilation();
        if (singleThreaded.isValid()) {
            assert threadStateMapping == null;

            // n.b.: Several threads may be attached to the context but we may still be in the
            // 'singleThreaded' mode because the threads won't run concurrently. For this case, we
            // map each attached thread to index 0.
            singleThreadState.addOwner(thread);
        } else {
            assert threadStateMapping != null;
            threadStateMapping.put(thread.getId(), new PythonThreadState(thread));
        }
    }

    public synchronized void disposeThread(Thread thread) {
        CompilerAsserts.neverPartOfCompilation();
        long threadId = thread.getId();
        // check if there is a live sentinel lock
        if (singleThreaded.isValid()) {
            assert threadStateMapping == null;
            singleThreadState.removeOwner(thread);
            // only release sentinel lock if all owners are gone
            if (!singleThreadState.hasOwners()) {
                releaseSentinelLock(singleThreadState.sentinelLock);
            }
        } else {
            PythonThreadState ts = threadStateMapping.get(threadId);
            assert ts != null : "thread was not attached to this context";
            ts.removeOwner(thread);
            threadStateMapping.remove(threadId);
            if (!ts.hasOwners()) {
                releaseSentinelLock(ts.sentinelLock);
            }
        }
    }

    private static void releaseSentinelLock(WeakReference<PLock> sentinelLockWeakref) {
        if (sentinelLockWeakref != null) {
            PLock sentinelLock = sentinelLockWeakref.get();
            if (sentinelLock != null) {
                // release the sentinel lock
                sentinelLock.release();
            }
        }
    }

    public boolean hasCApiContext() {
        return cApiContext != null;
    }

    public CApiContext getCApiContext() {
        return cApiContext;
    }

    public void setCapiWasLoaded(Object capiLibrary) {
        assert cApiContext == null : "tried to create new C API context but it was already created";
        cApiContext = new CApiContext(this, capiLibrary);
    }

    public boolean hasHPyContext() {
        return hPyContext != null;
    }

    public void createHPyContext(Object hpyLibrary) {
        assert hPyContext == null : "tried to create new HPy context but it was already created";
        hPyContext = new GraalHPyContext(this, hpyLibrary);
    }

    public GraalHPyContext getHPyContext() {
        assert hPyContext != null : "tried to get HPy context but was not created yet";
        return hPyContext;
    }

    public boolean isGcEnabled() {
        return gcEnabled;
    }

    public void setGcEnabled(boolean flag) {
        gcEnabled = flag;
    }

    public AsyncHandler.SharedFinalizer getSharedFinalizer() {
        return sharedFinalizer;
    }

    public boolean isFinalizing() {
        return finalizing;
    }
}
