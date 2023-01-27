/*
 * Copyright (c) 2019, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.graal.python.builtins.objects.cext.capi;

import static com.oracle.graal.python.builtins.objects.cext.capi.NativeCAPISymbol.FUN_GET_M_INDEX;
import static com.oracle.graal.python.nodes.SpecialAttributeNames.T___FILE__;
import static com.oracle.graal.python.nodes.SpecialAttributeNames.T___LIBRARY__;
import static com.oracle.graal.python.nodes.StringLiterals.J_GET_;
import static com.oracle.graal.python.nodes.StringLiterals.J_LLVM_LANGUAGE;
import static com.oracle.graal.python.nodes.StringLiterals.J_TYPE_ID;
import static com.oracle.graal.python.util.PythonUtils.EMPTY_TRUFFLESTRING_ARRAY;
import static com.oracle.graal.python.util.PythonUtils.tsArray;

import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

import org.graalvm.collections.EconomicMap;
import org.graalvm.collections.Pair;

import com.oracle.graal.python.PythonLanguage;
import com.oracle.graal.python.builtins.Python3Core;
import com.oracle.graal.python.builtins.PythonBuiltinClassType;
import com.oracle.graal.python.builtins.modules.cext.PythonCextBuiltins;
import com.oracle.graal.python.builtins.modules.cext.PythonCextBuiltins.CApiBuiltinExecutable;
import com.oracle.graal.python.builtins.modules.cext.PythonCextBuiltins.CApiCallPath;
import com.oracle.graal.python.builtins.objects.PNone;
import com.oracle.graal.python.builtins.objects.PNotImplemented;
import com.oracle.graal.python.builtins.objects.cext.PythonAbstractNativeObject;
import com.oracle.graal.python.builtins.objects.cext.capi.CExtNodes.PCallCapiFunction;
import com.oracle.graal.python.builtins.objects.cext.capi.CExtNodesFactory.CreateModuleNodeGen;
import com.oracle.graal.python.builtins.objects.cext.capi.CExtNodesFactory.ToJavaNodeGen;
import com.oracle.graal.python.builtins.objects.cext.capi.CExtNodesFactory.ToNewRefNodeGen;
import com.oracle.graal.python.builtins.objects.cext.capi.DynamicObjectNativeWrapper.PrimitiveNativeWrapper;
import com.oracle.graal.python.builtins.objects.cext.capi.NativeObjectReferenceArrayWrapper.PointerArrayWrapper;
import com.oracle.graal.python.builtins.objects.cext.capi.NativeObjectReferenceArrayWrapper.RefCountArrayWrapper;
import com.oracle.graal.python.builtins.objects.cext.capi.transitions.CApiTransitions;
import com.oracle.graal.python.builtins.objects.cext.common.CExtCommonNodes.CheckFunctionResultNode;
import com.oracle.graal.python.builtins.objects.cext.common.CExtContext;
import com.oracle.graal.python.builtins.objects.cext.common.LoadCExtException.ApiInitException;
import com.oracle.graal.python.builtins.objects.cext.common.LoadCExtException.ImportException;
import com.oracle.graal.python.builtins.objects.cext.common.ReferenceStack;
import com.oracle.graal.python.builtins.objects.cext.hpy.GraalHPyContext;
import com.oracle.graal.python.builtins.objects.dict.PDict;
import com.oracle.graal.python.builtins.objects.ellipsis.PEllipsis;
import com.oracle.graal.python.builtins.objects.frame.PFrame;
import com.oracle.graal.python.builtins.objects.function.PArguments;
import com.oracle.graal.python.builtins.objects.function.Signature;
import com.oracle.graal.python.builtins.objects.method.PBuiltinMethod;
import com.oracle.graal.python.builtins.objects.module.PythonModule;
import com.oracle.graal.python.builtins.objects.thread.PLock;
import com.oracle.graal.python.nodes.ErrorMessages;
import com.oracle.graal.python.nodes.IndirectCallNode;
import com.oracle.graal.python.nodes.PRaiseNode;
import com.oracle.graal.python.nodes.PRootNode;
import com.oracle.graal.python.nodes.call.CallNode;
import com.oracle.graal.python.nodes.call.GenericInvokeNode;
import com.oracle.graal.python.nodes.object.GetClassNode;
import com.oracle.graal.python.runtime.AsyncHandler;
import com.oracle.graal.python.runtime.ExecutionContext.CalleeContext;
import com.oracle.graal.python.runtime.ExecutionContext.IndirectCallContext;
import com.oracle.graal.python.runtime.PythonContext;
import com.oracle.graal.python.runtime.PythonContext.GetThreadStateNode;
import com.oracle.graal.python.runtime.PythonContext.PythonThreadState;
import com.oracle.graal.python.runtime.PythonOptions;
import com.oracle.graal.python.runtime.exception.PException;
import com.oracle.graal.python.util.PythonUtils;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.TruffleLogger;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInterface;
import com.oracle.truffle.api.object.DynamicObjectLibrary;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.Source.SourceBuilder;
import com.oracle.truffle.api.strings.TruffleString;
import com.oracle.truffle.nfi.api.SignatureLibrary;

public final class CApiContext extends CExtContext {

    public static final String LOGGER_CAPI_NAME = "capi";
    private static final TruffleLogger LOGGER = PythonLanguage.getLogger(LOGGER_CAPI_NAME);

    /**
     * A dummy context to disambiguate between <it>context not yet created</it> and <it>context
     * should be looked up lazily</it>
     */
    static final CApiContext LAZY_CONTEXT = new CApiContext();

    public static final long REFERENCE_COUNT_BITS = Integer.SIZE;
    public static final long REFERENCE_COUNT_MARKER = (1L << REFERENCE_COUNT_BITS);
    /* a random number between 1 and 20 */
    private static final int MAX_COLLECTION_RETRIES = 17;

    /** Total amount of allocated native memory (in bytes). */
    private long allocatedMemory = 0;

    private final ReferenceQueue<Object> nativeObjectsQueue;
    private Map<Object, AllocInfo> allocatedNativeMemory;
    private final ReferenceStack<NativeObjectReference> nativeObjectWrapperList;
    private TraceMallocDomain[] traceMallocDomains;

    /** Container of pointers that have seen to be free'd. */
    private Map<Object, AllocInfo> freedNativeMemory;

    @CompilationFinal private RootCallTarget referenceCleanerCallTarget;

    /**
     * This cache is used to cache native wrappers for frequently used primitives. This is strictly
     * defined to be the range {@code [-5, 256]}. CPython does exactly the same (see
     * {@code PyLong_FromLong}; implemented in macro {@code CHECK_SMALL_INT}).
     */
    @CompilationFinal(dimensions = 1) private final PrimitiveNativeWrapper[] primitiveNativeWrapperCache;

    /**
     * Required to emulate PyLongObject's ABI; number of bits per digit (equal to
     * {@code PYLONG_BITS_IN_DIGIT}.
     */
    @CompilationFinal private int pyLongBitsInDigit = -1;

    /** Cache for polyglot types of primitive and pointer types. */
    @CompilationFinal(dimensions = 1) private final Object[] llvmTypeCache;

    /** same as {@code moduleobject.c: max_module_number} */
    private long maxModuleNumber;

    /** Same as {@code import.c: extensions} but we don't keep a PDict; just a bare Java HashMap. */
    private final HashMap<Pair<TruffleString, TruffleString>, Object> extensions = new HashMap<>(4);

    private final ArrayList<Object> modulesByIndex = new ArrayList<>(0);

    public final HashMap<Long, PLock> locks = new HashMap<>();
    public final AtomicLong lockId = new AtomicLong();

    /**
     * Thread local storage for PyThread_tss_* APIs
     */
    private final ConcurrentHashMap<Long, ThreadLocal<Object>> tssStorage = new ConcurrentHashMap<>();
    /**
     * Next key that will be allocated byt PyThread_tss_create
     */
    private final AtomicLong nextTssKey = new AtomicLong();

    private final HashMap<Object, Long> callableClosurePointers = new HashMap<>();
    private final HashSet<Object> callableClosures = new HashSet<>();
    private Object nativeLibrary;
    public RootCallTarget signatureContainer;

    public static TruffleLogger getLogger(Class<?> clazz) {
        return PythonLanguage.getLogger(LOGGER_CAPI_NAME + "." + clazz.getSimpleName());
    }

    /**
     * Private dummy constructor just for {@link #LAZY_CONTEXT}.
     */
    private CApiContext() {
        super(null, null, null);
        nativeObjectsQueue = null;
        nativeObjectWrapperList = null;
        primitiveNativeWrapperCache = null;
        llvmTypeCache = null;
    }

    public CApiContext(PythonContext context, Object hpyLibrary) {
        super(context, hpyLibrary, CAPIConversionNodeSupplier.INSTANCE);
        nativeObjectsQueue = new ReferenceQueue<>();
        nativeObjectWrapperList = new ReferenceStack<>();

        // avoid 0 to be used as ID
        int nullID = nativeObjectWrapperList.reserve();
        assert nullID == 0;

        // initialize primitive and pointer type cache
        llvmTypeCache = new Object[LLVMType.values().length];

        // initialize primitive native wrapper cache
        primitiveNativeWrapperCache = new PrimitiveNativeWrapper[262];
        for (int i = 0; i < primitiveNativeWrapperCache.length; i++) {
            PrimitiveNativeWrapper nativeWrapper = PrimitiveNativeWrapper.createInt(i - 5);
            CApiTransitions.incRef(nativeWrapper, PythonNativeWrapper.IMMORTAL_REFCNT);
            primitiveNativeWrapperCache[i] = nativeWrapper;
        }

        context.registerAsyncAction(() -> {
            Reference<?> reference = null;
            if (PythonOptions.AUTOMATIC_ASYNC_ACTIONS) {
                try {
                    reference = nativeObjectsQueue.remove();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                reference = nativeObjectsQueue.poll();
            }

            ArrayList<NativeObjectReference> refs = new ArrayList<>();
            do {
                if (reference instanceof NativeObjectReference) {
                    refs.add((NativeObjectReference) reference);
                }
                // consume all
                reference = nativeObjectsQueue.poll();
            } while (reference != null);

            if (!refs.isEmpty()) {
                return new CApiReferenceCleanerAction(refs.toArray(new NativeObjectReference[0]));
            }

            return null;
        });
    }

    public int getPyLongBitsInDigit() {
        if (pyLongBitsInDigit < 0) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            pyLongBitsInDigit = (int) CExtNodes.PCallCapiFunction.getUncached().call(NativeCAPISymbol.FUN_GET_LONG_BITS_PER_DIGIT);
        }
        return pyLongBitsInDigit;
    }

    public Object getLLVMTypeID(LLVMType llvmType) {
        return llvmTypeCache[llvmType.ordinal()];
    }

    public void setLLVMTypeID(LLVMType llvmType, Object llvmTypeId) {
        llvmTypeCache[llvmType.ordinal()] = llvmTypeId;
    }

    public long getAndIncMaxModuleNumber() {
        return maxModuleNumber++;
    }

    @TruffleBoundary
    public static Object asHex(Object ptr) {
        if (ptr instanceof Number) {
            return "0x" + Long.toHexString(((Number) ptr).longValue());
        }
        return Objects.toString(ptr);
    }

    /**
     * Tries to convert the object to a pointer (type: {@code long}) to avoid materialization of
     * pointer objects. If that is not possible, the object will be returned as given.
     */
    public static Object asPointer(Object ptr, InteropLibrary lib) {
        if (lib.isPointer(ptr)) {
            try {
                return lib.asPointer(ptr);
            } catch (UnsupportedMessageException e) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException();
            }
        }
        return ptr;
    }

    private RootCallTarget getReferenceCleanerCallTarget() {
        if (referenceCleanerCallTarget == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            referenceCleanerCallTarget = PythonLanguage.get(null).createCachedCallTarget(l -> new CApiReferenceCleanerRootNode(l), CApiReferenceCleanerRootNode.class);
        }
        return referenceCleanerCallTarget;
    }

    public TraceMallocDomain getTraceMallocDomain(int domainIdx) {
        return traceMallocDomains[domainIdx];
    }

    public int findOrCreateTraceMallocDomain(int id) {
        int oldLength;
        if (traceMallocDomains != null) {
            for (int i = 0; i < traceMallocDomains.length; i++) {
                if (traceMallocDomains[i].id == id) {
                    return i;
                }
            }

            // create new domain
            oldLength = traceMallocDomains.length;
            traceMallocDomains = Arrays.copyOf(traceMallocDomains, traceMallocDomains.length + 1);
        } else {
            oldLength = 0;
            traceMallocDomains = new TraceMallocDomain[1];
        }
        traceMallocDomains[oldLength] = new TraceMallocDomain(id);
        return oldLength;
    }

    public long nextTssKey() {
        return nextTssKey.incrementAndGet();
    }

    @TruffleBoundary
    public Object tssGet(long key) {
        ThreadLocal<Object> local = tssStorage.get(key);
        if (local != null) {
            return local.get();
        }
        return null;
    }

    @TruffleBoundary
    public void tssSet(long key, Object object) {
        tssStorage.computeIfAbsent(key, (k) -> new ThreadLocal<>()).set(object);
    }

    @TruffleBoundary
    public void tssDelete(long key) {
        tssStorage.remove(key);
    }

    public PrimitiveNativeWrapper getCachedPrimitiveNativeWrapper(int i) {
        assert CApiGuards.isSmallInteger(i);
        PrimitiveNativeWrapper primitiveNativeWrapper = primitiveNativeWrapperCache[i + 5];
        assert primitiveNativeWrapper.getRefCount() > 0;
        return primitiveNativeWrapper;
    }

    public PrimitiveNativeWrapper getCachedPrimitiveNativeWrapper(long l) {
        assert CApiGuards.isSmallLong(l);
        return getCachedPrimitiveNativeWrapper((int) l);
    }

    @TruffleBoundary
    @Override
    protected Store initializeSymbolCache() {
        PythonLanguage language = getContext().getLanguage();
        Shape symbolCacheShape = language.getCApiSymbolCacheShape();
        // We will always get an empty shape from the language and we do always add same key-value
        // pairs (in the same order). So, in the end, each context should get the same shape.
        Store s = new Store(symbolCacheShape);
        for (NativeCAPISymbol sym : NativeCAPISymbol.getValues()) {
            DynamicObjectLibrary.getUncached().put(s, sym, PNone.NO_VALUE);
        }
        return s;
    }

    public Object getModuleByIndex(int i) {
        if (i < modulesByIndex.size()) {
            return modulesByIndex.get(i);
        }
        return null;
    }

    static class NativeObjectReference extends WeakReference<PythonAbstractNativeObject> {

        /**
         * The associated native pointer object that needs to be released if this reference dies.
         */
        final Object ptrObject;

        /** The ID of this reference, i.e., the index of the ref in the global reference list. */
        final int id;

        /**
         * If {@code true}, the native object should not be released because a new managed ref was
         * created.
         */
        boolean resurrect;

        /**
         * When stealing references, this is the number of stolen reference counts (need to be
         * subtracted in the end).
         */
        long managedRefCount;

        public NativeObjectReference(PythonAbstractNativeObject referent, ReferenceQueue<? super PythonAbstractNativeObject> q, long managedRefCount, int id) {
            super(referent, q);
            this.ptrObject = referent.getPtr();
            this.managedRefCount = managedRefCount;
            this.id = id;
        }

        public Object getPtrObject() {
            return ptrObject;
        }

        public void markAsResurrected() {
            resurrect = true;
        }
    }

    /**
     * Simple root node that executes a reference decrease.
     */
    private static final class CApiReferenceCleanerRootNode extends PRootNode {
        private static final Signature SIGNATURE = new Signature(-1, false, -1, false, tsArray("ptr", "managedRefCount"), EMPTY_TRUFFLESTRING_ARRAY);
        private static final TruffleLogger LOGGER = CApiContext.getLogger(CApiReferenceCleanerRootNode.class);

        @Child private CalleeContext calleeContext;
        @Child private InteropLibrary pointerObjectLib;
        @Child private PCallCapiFunction callBulkSubref;

        protected CApiReferenceCleanerRootNode(PythonLanguage language) {
            super(language);
            this.calleeContext = CalleeContext.create();
            this.callBulkSubref = PCallCapiFunction.create();
        }

        @Override
        public Object execute(VirtualFrame frame) {
            calleeContext.enter(frame);
            try {
                NativeObjectReference[] nativeObjectReferences = (NativeObjectReference[]) PArguments.getArgument(frame, 0);
                int cleaned = 0;
                CApiContext cApiContext = PythonContext.get(this).getCApiContext();
                long allocatedNativeMem = cApiContext.allocatedMemory;
                long startTime = 0;
                long middleTime = 0;
                final int n = nativeObjectReferences.length;
                boolean loggable = LOGGER.isLoggable(Level.FINE);

                if (loggable) {
                    startTime = System.currentTimeMillis();
                }

                /*
                 * Note about the order of operations - we need to call the finalizers first before
                 * removing the objects from the wrapper list because the finalizers may still make
                 * upcalls and those need the wrappers to work correctly.
                 */

                callBulkSubref.call(NativeCAPISymbol.FUN_BULK_SUBREF, new PointerArrayWrapper(nativeObjectReferences), new RefCountArrayWrapper(nativeObjectReferences), (long) n);

                if (loggable) {
                    middleTime = System.currentTimeMillis();
                }

                if (LOGGER.isLoggable(Level.FINER)) {
                    // it's not an OSR loop, so we do this before the loop
                    if (n > 0 && pointerObjectLib == null) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        pointerObjectLib = insert(InteropLibrary.getFactory().create(nativeObjectReferences[0].ptrObject));
                    }

                    for (int i = 0; i < n; i++) {
                        NativeObjectReference nativeObjectReference = nativeObjectReferences[i];
                        Object pointerObject = nativeObjectReference.ptrObject;
                        if (!nativeObjectReference.resurrect) {
                            cApiContext.nativeObjectWrapperList.remove(nativeObjectReference.id);
                            if (!nativeObjectReference.resurrect && !pointerObjectLib.isNull(pointerObject)) {
                                cApiContext.checkAccess(pointerObject, pointerObjectLib);
                                LOGGER.finer(() -> "Cleaning native object reference to " + CApiContext.asHex(pointerObject));
                                cleaned++;
                            }
                        }
                    }
                } else {
                    for (int i = 0; i < n; i++) {
                        NativeObjectReference nativeObjectReference = nativeObjectReferences[i];
                        if (!nativeObjectReference.resurrect) {
                            cApiContext.nativeObjectWrapperList.remove(nativeObjectReference.id);
                        }
                    }
                }

                if (loggable) {
                    final long countDuration = System.currentTimeMillis() - middleTime;
                    final long duration = middleTime - startTime;
                    final int finalCleaned = cleaned;
                    final long freedNativeMemory = allocatedNativeMem - cApiContext.allocatedMemory;
                    LOGGER.fine(() -> "Total queued references: " + n);
                    LOGGER.fine(() -> "Cleaned references: " + finalCleaned);
                    LOGGER.fine(() -> "Free'd native memory: " + freedNativeMemory);
                    LOGGER.fine(() -> "Count duration: " + countDuration);
                    LOGGER.fine(() -> "Duration: " + duration);
                }
            } finally {
                calleeContext.exit(frame, this);
            }
            return PNone.NONE;
        }

        @Override
        public Signature getSignature() {
            return SIGNATURE;
        }

        @Override
        public String getName() {
            return "native_reference_cleaner";
        }

        @Override
        public boolean isInternal() {
            return false;
        }

        @Override
        public boolean isPythonInternal() {
            return false;
        }
    }

    /**
     * Reference cleaner action that will be executed by the {@link AsyncHandler}.
     */
    private static final class CApiReferenceCleanerAction implements AsyncHandler.AsyncAction {

        private final NativeObjectReference[] nativeObjectReferences;

        public CApiReferenceCleanerAction(NativeObjectReference[] nativeObjectReferences) {
            this.nativeObjectReferences = nativeObjectReferences;
        }

        @Override
        public void execute(PythonContext context) {
            System.out.println("CApiReferenceCleanerAction");
            Object[] pArguments = PArguments.create(1);
            PArguments.setArgument(pArguments, 0, nativeObjectReferences);
            GenericInvokeNode.getUncached().execute(context.getCApiContext().getReferenceCleanerCallTarget(), pArguments);
        }
    }

    public NativeObjectReference lookupNativeObjectReference(int idx) {
        return nativeObjectWrapperList.get(idx);
    }

    static long idToRefCnt(int id) {
        long nativeRefCnt = (long) id << REFERENCE_COUNT_BITS;
        assert nativeRefCnt >= REFERENCE_COUNT_MARKER;
        return nativeRefCnt;
    }

    @TruffleBoundary
    public AllocInfo traceFree(Object ptr, @SuppressWarnings("unused") PFrame.Reference curFrame, @SuppressWarnings("unused") TruffleString clazzName) {
        if (allocatedNativeMemory == null) {
            allocatedNativeMemory = new HashMap<>();
        }
        if (freedNativeMemory == null) {
            freedNativeMemory = new HashMap<>();
        }
        AllocInfo allocatedValue = allocatedNativeMemory.remove(ptr);
        Object freedValue = freedNativeMemory.put(ptr, allocatedValue);
        if (freedValue != null) {
            LOGGER.severe(PythonUtils.formatJString("freeing memory that was already free'd %s (double-free)", asHex(ptr)));
        } else if (allocatedValue == null) {
            LOGGER.info(PythonUtils.formatJString("freeing non-allocated memory %s (maybe a double-free or we didn't trace the allocation)", asHex(ptr)));
        }
        return allocatedValue;
    }

    @TruffleBoundary
    public void traceAlloc(Object ptr, PFrame.Reference curFrame, TruffleString clazzName, long size) {
        if (allocatedNativeMemory == null) {
            allocatedNativeMemory = new HashMap<>();
        }
        Object value = allocatedNativeMemory.put(ptr, new AllocInfo(clazzName, curFrame, size));
        if (freedNativeMemory != null) {
            freedNativeMemory.remove(ptr);
        }
        assert value == null : "native memory allocator reserved same memory twice";
    }

    @SuppressWarnings("unused")
    public void trackObject(Object ptr, PFrame.Reference curFrame, TruffleString clazzName) {
        // TODO(fa): implement tracking of container objects for cycle detection
    }

    @SuppressWarnings("unused")
    public void untrackObject(Object ptr, PFrame.Reference curFrame, TruffleString clazzName) {
        // TODO(fa): implement untracking of container objects
    }

    /**
     * Use this method to register memory that is known to be allocated (i.e. static variables like
     * types). This is basically the same as
     * {@link #traceAlloc(Object, PFrame.Reference, TruffleString, long)} but does not consider it
     * to be an error if the memory is already allocated.
     */
    @TruffleBoundary
    public void traceStaticMemory(Object ptr, PFrame.Reference curFrame, TruffleString clazzName) {
        if (allocatedNativeMemory == null) {
            allocatedNativeMemory = new HashMap<>();
        }
        if (freedNativeMemory != null) {
            freedNativeMemory.remove(ptr);
        }
        allocatedNativeMemory.put(ptr, new AllocInfo(curFrame, clazzName));
    }

    @TruffleBoundary
    public boolean isAllocated(Object ptr) {
        if (freedNativeMemory != null && freedNativeMemory.containsKey(ptr)) {
            assert !allocatedNativeMemory.containsKey(ptr);
            return false;
        }
        return true;
    }

    public void increaseMemoryPressure(long size, Node node) {
        PythonContext context = getContext();
        if (allocatedMemory <= context.getOption(PythonOptions.MaxNativeMemory)) {
            allocatedMemory += size;
            return;
        }
        triggerGC(context, size, node);
    }

    public void increaseMemoryPressure(VirtualFrame frame, GetThreadStateNode getThreadStateNode, IndirectCallNode caller, long size) {
        PythonContext context = getContext();
        if (allocatedMemory + size <= context.getOption(PythonOptions.MaxNativeMemory)) {
            allocatedMemory += size;
            return;
        }

        PythonThreadState threadState = getThreadStateNode.execute(context);
        Object savedState = IndirectCallContext.enter(frame, threadState, caller);
        try {
            triggerGC(context, size, caller);
        } finally {
            IndirectCallContext.exit(frame, threadState, savedState);
        }
    }

    @TruffleBoundary
    private void triggerGC(PythonContext context, long size, NodeInterface caller) {
        long delay = 0;
        for (int retries = 0; retries < MAX_COLLECTION_RETRIES; retries++) {
            delay += 50;
            doGc(delay);
            CApiTransitions.pollReferenceQueue();
            PythonContext.triggerAsyncActions((Node) caller);
            if (allocatedMemory + size <= context.getOption(PythonOptions.MaxNativeMemory)) {
                allocatedMemory += size;
                return;
            }
        }
        throw new OutOfMemoryError("native memory");
    }

    public void reduceMemoryPressure(long size) {
        allocatedMemory -= size;
    }

    @TruffleBoundary
    private static void doGc(long millis) {
        LOGGER.fine("full GC due to native memory");
        PythonUtils.forceFullGC();
        try {
            Thread.sleep(millis);
        } catch (InterruptedException x) {
            // Restore interrupt status
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Tests if any read/write access to the given pointer object is invalid. This should be used to
     * test access before getting the type of reference count of native objects.
     */
    public void checkAccess(Object pointerObject, InteropLibrary lib) {
        if (getContext().getOption(PythonOptions.TraceNativeMemory)) {
            Object ptrVal = CApiContext.asPointer(pointerObject, lib);
            if (!isAllocated(ptrVal)) {
                LOGGER.severe(() -> "Access to invalid memory at " + CApiContext.asHex(ptrVal));
            }
        }
    }

    /**
     * Internal method for debugging purposes. This method looks up how many phantom references are
     * currently in the escaped references list. This is useful to check if the current reference
     * count of a native object is consistent with the upcoming decrements.
     */
    @SuppressWarnings("unused")
    public List<Integer> containsAddress(long l) {
        CompilerAsserts.neverPartOfCompilation();
        int i = 0;
        List<Integer> indx = new ArrayList<>();
        InteropLibrary lib = InteropLibrary.getFactory().getUncached();
        for (NativeObjectReference nor : nativeObjectWrapperList) {
            Object obj = nor.ptrObject;

            try {
                if (lib.isPointer(obj) && lib.asPointer(obj) == l) {
                    indx.add(i);
                }
            } catch (UnsupportedMessageException e) {
                // ignore
            }
            i++;
        }
        return indx;
    }

    public static final class AllocInfo {
        public final TruffleString typeName;
        public final PFrame.Reference allocationSite;
        public final long size;

        public AllocInfo(TruffleString typeName, PFrame.Reference allocationSite, long size) {
            this.typeName = typeName;
            this.allocationSite = allocationSite;
            this.size = size;
        }

        public AllocInfo(PFrame.Reference allocationSite, TruffleString typeName) {
            this(typeName, allocationSite, -1);
        }
    }

    public static final class TraceMallocDomain {
        private final int id;
        private final EconomicMap<Object, Long> allocatedMemory;

        public TraceMallocDomain(int id) {
            this.id = id;
            this.allocatedMemory = EconomicMap.create();
        }

        @TruffleBoundary
        public void track(Object pointerObject, long size) {
            allocatedMemory.put(pointerObject, size);
        }

        @TruffleBoundary
        public long untrack(Object pointerObject) {
            Long value = allocatedMemory.removeKey(pointerObject);
            if (value != null) {
                // TODO(fa): be more restrictive?
                return value;
            }
            return 0;
        }

        public int getId() {
            return id;
        }
    }

    /**
     * Enum of basic C types. These type names need to stay in sync with the declarations in
     * 'modsupport.c'.
     */
    public enum LLVMType {
        int_t,
        uint_t,
        int8_t,
        int16_t,
        int32_t,
        int64_t,
        uint8_t,
        uint16_t,
        uint32_t,
        uint64_t,
        long_t,
        ulong_t,
        longlong_t,
        ulonglong_t,
        float_t,
        double_t,
        size_t,
        Py_ssize_t,
        Py_complex,
        PyObject,
        PyMethodDef,
        PyTypeObject,
        PyObject_ptr_t,
        char_ptr_t,
        void_ptr_t,
        int8_ptr_t,
        int16_ptr_t,
        int32_ptr_t,
        int64_ptr_t,
        uint8_ptr_t,
        uint16_ptr_t,
        uint32_ptr_t,
        uint64_ptr_t,
        Py_complex_ptr_t,
        PyObject_ptr_ptr_t,
        float_ptr_t,
        double_ptr_t,
        Py_ssize_ptr_t,
        PyThreadState;

        private final NativeCAPISymbol getter = NativeCAPISymbol.getByName(J_GET_ + name() + J_TYPE_ID);

        public NativeCAPISymbol getGetterFunctionName() {
            if (getter == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw CompilerDirectives.shouldNotReachHere("no getter for LLVMType " + name());
            }
            return getter;
        }

        public static boolean isPointer(LLVMType llvmType) {
            switch (llvmType) {
                case PyObject_ptr_t:
                case char_ptr_t:
                case int8_ptr_t:
                case int16_ptr_t:
                case int32_ptr_t:
                case int64_ptr_t:
                case uint8_ptr_t:
                case uint16_ptr_t:
                case uint32_ptr_t:
                case uint64_ptr_t:
                case Py_complex_ptr_t:
                case PyObject_ptr_ptr_t:
                case float_ptr_t:
                case double_ptr_t:
                case Py_ssize_ptr_t:
                    return true;
            }
            return false;
        }

        public static boolean isPointerToPrimitive(LLVMType llvmType) {
            switch (llvmType) {
                case int8_ptr_t:
                case int16_ptr_t:
                case int32_ptr_t:
                case int64_ptr_t:
                case uint8_ptr_t:
                case uint16_ptr_t:
                case uint32_ptr_t:
                case uint64_ptr_t:
                case float_ptr_t:
                case double_ptr_t:
                case char_ptr_t:
                    return true;
            }
            return false;
        }
    }

    // XXX: We have to hold on to this so that NFI doesn't unload the library again
    static Object nativeLibpython;

    @TruffleBoundary
    public static CApiContext ensureCapiWasLoaded(Node node, PythonContext context, TruffleString name, TruffleString path) throws IOException, ImportException, ApiInitException {
        if (!context.hasCApiContext()) {
            Env env = context.getEnv();

            TruffleFile homePath = env.getInternalTruffleFile(context.getCAPIHome().toJavaStringUncached());
            TruffleFile capiFile = homePath.resolve("libpython" + context.getSoAbi().toJavaStringUncached());
            Object capiLibrary;
            try {
                SourceBuilder capiSrcBuilder = Source.newBuilder(J_LLVM_LANGUAGE, capiFile);
                if (!context.getLanguage().getEngineOption(PythonOptions.ExposeInternalSources)) {
                    capiSrcBuilder.internal(true);
                }
                LOGGER.config(() -> "loading CAPI from " + capiFile);
                CallTarget capiLibraryCallTarget = context.getEnv().parseInternal(capiSrcBuilder.build());
                // keep the call target of 'libpython' alive; workaround until GR-32297 is fixed
                context.getLanguage().capiLibraryCallTarget = capiLibraryCallTarget;
                capiLibrary = capiLibraryCallTarget.call();

                String libpython = System.getProperty("LibPythonNativeLibrary");
                if (libpython != null) {
                    SourceBuilder nfiSrcBuilder = Source.newBuilder("nfi", "load(RTLD_GLOBAL) \"" + libpython + "\"", "<libpython-native>");
                    nativeLibpython = context.getEnv().parseInternal(nfiSrcBuilder.build()).call();
                }

                assert CApiFunction.assertBuiltins(capiLibrary);
                CApiContext cApiContext = new CApiContext(context, capiLibrary);
                context.setCapiWasLoaded(cApiContext);
                return cApiContext;
            } catch (PException e) {
                /*
                 * Python exceptions that occur during the C API initialization are just passed
                 * through
                 */
                throw e;
            } catch (RuntimeException e) {
                if (!context.isNativeAccessAllowed()) {
                    throw new ImportException(null, name, path, ErrorMessages.NATIVE_ACCESS_NOT_ALLOWED);
                }
                throw new ApiInitException(wrapJavaException(e, node), name, ErrorMessages.CAPI_LOAD_ERROR, capiFile.getAbsoluteFile().getPath());
            }
        }
        return context.getCApiContext();
    }

    @TruffleBoundary
    public Object initCApiModule(Node location, Object sharedLibrary, TruffleString initFuncName, ModuleSpec spec, InteropLibrary llvmInteropLib, CheckFunctionResultNode checkFunctionResultNode)
                    throws UnsupportedMessageException, ArityException, UnsupportedTypeException, ImportException {
        PythonContext context = getContext();
        CApiContext cApiContext = context.getCApiContext();
        Object pyinitFunc;
        try {
            pyinitFunc = llvmInteropLib.readMember(sharedLibrary, initFuncName.toJavaStringUncached());
        } catch (UnknownIdentifierException | UnsupportedMessageException e1) {
            throw new ImportException(null, spec.name, spec.path, ErrorMessages.NO_FUNCTION_FOUND, "", initFuncName, spec.path);
        }
        Object nativeResult;
        try {
            nativeResult = InteropLibrary.getUncached().execute(pyinitFunc);
        } catch (UnsupportedMessageException e) {
            Object signature = PythonContext.get(null).getEnv().parseInternal(Source.newBuilder("nfi", "():POINTER", "exec").build()).call();
            Object bound = SignatureLibrary.getUncached().bind(signature, pyinitFunc);
            nativeResult = InteropLibrary.getUncached().execute(bound);
        } catch (ArityException e) {
            // In case of multi-phase init, the init function may take more than one argument.
            // However, CPython gracefully ignores that. So, we pass just NULL pointers.
            Object[] arguments = new Object[e.getExpectedMinArity()];
            Arrays.fill(arguments, PNone.NO_VALUE);
            nativeResult = InteropLibrary.getUncached().execute(pyinitFunc, arguments);
        }

        checkFunctionResultNode.execute(context, initFuncName, nativeResult);

        Object result = CApiTransitions.nativeToPython(nativeResult, false);
        if (!(result instanceof PythonModule)) {
            // Multi-phase extension module initialization

            /*
             * See 'importdl.c: _PyImport_LoadDynamicModuleWithSpec' before
             * 'PyModule_FromDefAndSpec' is called. The 'PyModule_FromDefAndSpec' would initialize
             * the module def as Python object but before that, CPython explicitly checks if the
             * init function did this initialization by calling 'PyModuleDef_Init' on it. So, we
             * must do it here because 'CreateModuleNode' should just ignore this case.
             */
            Object clazz = GetClassNode.getUncached().execute(result);
            if (clazz == PNone.NO_VALUE) {
                throw PRaiseNode.raiseUncached(location, PythonBuiltinClassType.SystemError, ErrorMessages.INIT_FUNC_RETURNED_UNINT_OBJ, initFuncName);
            }

            return CreateModuleNodeGen.getUncached().execute(cApiContext, spec, result, sharedLibrary);
        } else {
            // see: 'import.c: _PyImport_FixupExtensionObject'
            PythonModule module = (PythonModule) result;
            module.setAttribute(T___FILE__, spec.path);
            module.setAttribute(T___LIBRARY__, sharedLibrary);

            // add to 'sys.modules'
            PDict sysModules = context.getSysModules();
            sysModules.setItem(spec.name, result);

            // _PyState_AddModule
            Object moduleDef = module.getNativeModuleDef();
            try {
                Object mIndexObject = PCallCapiFunction.getUncached().call(cApiContext, FUN_GET_M_INDEX, moduleDef);
                int mIndex = InteropLibrary.getUncached().asInt(mIndexObject);
                while (modulesByIndex.size() <= mIndex) {
                    modulesByIndex.add(null);
                }
                modulesByIndex.set(mIndex, module);
            } catch (UnsupportedMessageException e) {
                throw CompilerDirectives.shouldNotReachHere();
            }

            // add to 'import.c: extensions'
            extensions.put(Pair.create(spec.path, spec.name), module.getNativeModuleDef());
            return result;
        }
    }

    private final HashMap<String, Long> typeStorePointers = new HashMap<>();

    public Long getTypeStore(String typename) {
        ensureNative();
        return typeStorePointers.get(typename);
    }

    @ExportLibrary(InteropLibrary.class)
    static final class GetAPI implements TruffleObject {

        @SuppressWarnings("static-method")
        @ExportMessage
        boolean isExecutable() {
            return true;
        }

        @SuppressWarnings("static-method")
        @ExportMessage
        @TruffleBoundary
        Object execute(Object[] arguments) {
            assert arguments.length == 1;
            String name = (String) arguments[0];
            try {
                Object llvmLibrary = PythonContext.get(null).getCApiContext().getLLVMLibrary();
                CApiBuiltinExecutable builtin = PythonCextBuiltins.capiBuiltins.get(name);
                Object result;
                if (builtin != null) {
                    result = builtin;
                    assert builtin.getAnnotation().call() == CApiCallPath.Direct || !InteropLibrary.getUncached().isMemberReadable(llvmLibrary, name) : "name clash in builtin vs. CAPI library: " +
                                    name;
                } else {
                    result = InteropLibrary.getUncached().readMember(llvmLibrary, name);
                }
                InteropLibrary.getUncached().toNative(result);
                long pointer = InteropLibrary.getUncached().asPointer(result);
                LOGGER.finer((builtin != null ? "getAPI(builtin) " : "getAPI(library) ") + name + " -> " + java.lang.Long.toHexString(pointer));
                return pointer;
            } catch (Throwable e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }
    }

    @ExportLibrary(InteropLibrary.class)
    static final class GetType implements TruffleObject {

        @SuppressWarnings("static-method")
        @ExportMessage
        boolean isExecutable() {
            return true;
        }

        @SuppressWarnings("static-method")
        @ExportMessage
        @TruffleBoundary
        Object execute(Object[] arguments) {
            assert arguments.length == 1;
            String typename = (String) arguments[0];

            Object result = null;
            try {
                Python3Core core = PythonContext.get(null).getCore();
                Object llvmLibrary = PythonContext.get(null).getCApiContext().getLLVMLibrary();
                switch (typename) {
                    case "Py_False":
                        result = false;
                        break;
                    case "Py_True":
                        result = true;
                        break;
                    case "PyLong_One":
                    case "_PyTruffle_One":
                        result = 1;
                        break;
                    case "PyLong_Zero":
                    case "_PyTruffle_Zero":
                        result = 0;
                        break;
                    case "Py_NotImplemented":
                        result = PNotImplemented.NOT_IMPLEMENTED;
                        break;
                    case "Py_Ellipsis":
                        result = PEllipsis.INSTANCE;
                        break;
                    case "Py_None":
                        result = PNone.NONE;
                        break;
                    case "capsule":
                        result = InteropLibrary.getUncached().readMember(llvmLibrary, "getPyCapsuleTypeReference");
                        result = InteropLibrary.getUncached().execute(result);
                        result = ToJavaNodeGen.getUncached().execute(result);
                        break;
                }
                if (result == null) {
                    for (PythonBuiltinClassType type : PythonBuiltinClassType.VALUES) {
                        if (type.getName().toJavaStringUncached().equals(typename)) {
                            result = core.lookupType(type);
                            break;
                        }
                    }
                }
                if (result == null) {
                    TruffleString tsTypename = PythonUtils.toTruffleStringUncached(typename);
                    for (TruffleString module : LOOKUP_MODULES) {
                        Object attribute = core.lookupBuiltinModule(module).getAttribute(tsTypename);
                        if (attribute instanceof PBuiltinMethod) {
                            attribute = CallNode.getUncached().execute(attribute);
                        }
                        if (attribute != PNone.NO_VALUE) {
                            result = attribute;
                            break;
                        }
                    }
                }
                if (result == null && resolveConstant(typename) != -1) {
                    // get symbols allocated in bitcode
                    result = InteropLibrary.getUncached().invokeMember(llvmLibrary, "truffle_get_constant", resolveConstant(typename));
                    InteropLibrary.getUncached().toNative(result);
                    return InteropLibrary.getUncached().asPointer(result);
                }
                if (result != null) {
                    result = ToNewRefNodeGen.getUncached().execute(result);
                    long l;
                    if (result instanceof Long) {
                        l = (long) result;
                    } else {
                        InteropLibrary.getUncached().toNative(result);
                        l = InteropLibrary.getUncached().asPointer(result);
                    }
                    LOGGER.finer("getType " + typename + " -> " + java.lang.Long.toHexString(l));
                    return l;
                }
                throw new RuntimeException("type " + typename + " not found");
            } catch (Throwable e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }
    }

    @ExportLibrary(InteropLibrary.class)
    static final class SetTypeStore implements TruffleObject {

        @SuppressWarnings("static-method")
        @ExportMessage
        boolean isExecutable() {
            return true;
        }

        @SuppressWarnings("static-method")
        @ExportMessage
        @TruffleBoundary
        Object execute(Object[] arguments) {
            assert arguments.length == 2;
            String typename = (String) arguments[0];
            long ptr = (long) arguments[1];

            if (!"unimplemented".equals(typename)) {
                CApiContext context = PythonContext.get(null).getCApiContext();
                assert !context.typeStorePointers.containsKey(typename) : typename;
                context.typeStorePointers.put(typename, ptr);
            }
            return 0;
        }
    }

    public void ensureNative() {
        if (nativeLibrary == null) {
            Env env = PythonContext.get(null).getEnv();

            String lib = GraalHPyContext.getJNILibrary();
            SourceBuilder nfiSrcBuilder = Source.newBuilder("nfi", "load(RTLD_GLOBAL) \"" + GraalHPyContext.getJNILibrary() + "\"", "<libpython-native>");
            LOGGER.config("loading native C API support library " + lib);
            try {
                nativeLibrary = env.parseInternal(nfiSrcBuilder.build()).call();
                Object initFunction = InteropLibrary.getUncached().readMember(nativeLibrary, "initNativeForward");

                // PyAPI_FUNC(void) initNativeForward(void* (*getAPI)(const char*), void*
                // (*getType)(const char*), void (*setTypeStore)(const char*, void*))
                Object signature = env.parseInternal(Source.newBuilder("nfi", "((STRING):POINTER,(STRING):POINTER, (STRING,SINT64):VOID):VOID", "exec").build()).call();
                Object bound = SignatureLibrary.getUncached().bind(signature, initFunction);
                InteropLibrary.getUncached().execute(bound, new GetAPI(), new GetType(), new SetTypeStore());
            } catch (IOException | UnsupportedTypeException | ArityException | UnsupportedMessageException | UnknownIdentifierException e) {
                throw CompilerDirectives.shouldNotReachHere(e);
            }
        }
    }

    private static final TruffleString[] LOOKUP_MODULES = tsArray(new String[]{
                    PythonCextBuiltins.PYTHON_CEXT,
                    "_weakref",
                    "builtins"
    });

    private static int resolveConstant(String typename) {
        // this needs to correspond to truffle_get_constant in capi.c
        switch (typename) {
            case "_Py_ascii_whitespace":
                return 0;
            case "_Py_ctype_table":
                return 1;
            case "_Py_ctype_tolower":
                return 2;
            case "_Py_ctype_toupper":
                return 3;
            case "_Py_tracemalloc_config":
                return 4;
            case "_Py_HashSecret":
                return 5;
            case "Py_DebugFlag":
                return 6;
            case "Py_VerboseFlag":
                return 7;
            case "Py_QuietFlag":
                return 8;
            case "Py_InteractiveFlag":
                return 9;
            case "Py_InspectFlag":
                return 10;
            case "Py_OptimizeFlag":
                return 11;
            case "Py_NoSiteFlag":
                return 12;
            case "Py_BytesWarningFlag":
                return 13;
            case "Py_FrozenFlag":
                return 14;
            case "Py_IgnoreEnvironmentFlag":
                return 15;
            case "Py_DontWriteBytecodeFlag":
                return 16;
            case "Py_NoUserSiteDirectory":
                return 17;
            case "Py_UnbufferedStdioFlag":
                return 18;
            case "Py_HashRandomizationFlag":
                return 19;
            case "Py_IsolatedFlag":
                return 20;
        }
        return -1;
    }

    @TruffleBoundary
    public long getClosurePointer(Object callable) {
        return callableClosurePointers.getOrDefault(callable, -1L);
    }

    public void setClosurePointer(Object callable, Object closure, long pointer) {
        CompilerAsserts.neverPartOfCompilation();
        callableClosurePointers.put(callable, pointer);
        callableClosures.add(closure);
    }

    public void retainClosure(Object closure) {
        callableClosures.add(closure);
    }
}
