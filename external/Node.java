package com.samsung.android.camera.core2.node;

import android.media.Image;

import androidx.annotation.CallSuper;
import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.samsung.android.camera.core2.container.ExtraBundle;
import com.samsung.android.camera.core2.exception.InvalidOperationException;
import com.samsung.android.camera.core2.local.vendorkey.metadata.CaptureMetadataKey;
import com.samsung.android.camera.core2.local.vendorkey.metadata.RequiredCaptureMetadataProvider;
import com.samsung.android.camera.core2.ml.CaptureMetrics;
import com.samsung.android.camera.core2.ml.DraftSequenceExecutionProfiler;
import com.samsung.android.camera.core2.ml.DraftSequenceExecutionSession;
import com.samsung.android.camera.core2.ml.DeviceStateReader;
import com.samsung.android.camera.core2.ml.DraftSequenceMetrics;
import com.samsung.android.camera.core2.ml.NodeParams;
import com.samsung.android.camera.core2.node.NodeFeature.NodeFeatureVersion;
import com.samsung.android.camera.core2.util.CLog;
import com.samsung.android.camera.core2.util.ConditionChecker;
import com.samsung.android.camera.core2.util.DebugUtils;
import com.samsung.android.camera.core2.util.ImageBuffer;
import com.samsung.android.camera.core2.util.ImageFile;
import com.samsung.android.camera.core2.util.ImageInfo;
import com.samsung.android.camera.core2.util.SemImageFormat;
import com.samsung.android.camera.core2.util.TimeoutExecutor;
import com.sec.android.app.TraceWrapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * <div class="camera_en">
 * Node.
 * Manager connected port and other resource related with Node's operation.
 * </div>
 *
 * <div class="camera_kr" style="display:none;">
 * 노드.
 * 연결된 포트와 노드 동작 등을 관장한다.
 * </div>
 */
public abstract class Node implements PictureFormatProcessableInterface {
    private static final long NATIVE_NODE_INIT_TIMEOUT_S = 7L; // sec
    private static final long NODE_INIT_TIMEOUT_S = 5L;

    public static final PortType<Image> PORT_TYPE_PREVIEW = new PortType<>("PREVIEW") {};
    public static final PortType<ImageBuffer> PORT_TYPE_BACKGROUND_PREVIEW = new PortType<>("BACKGROUND_PREVIEW") {};
    public static final PortType<ImageBuffer> PORT_TYPE_PICTURE = new PortType<>("PICTURE") {};
    public static final PortType<ImageFile> PORT_TYPE_PICTURE_FILE = new PortType<>("PICTURE_FILE") {};

    protected static final boolean DEBUG = !DebugUtils.isShipMode();

    public final OutputPort<Image> OUTPUTPORT_PREVIEW = new OutputPort<>(PORT_TYPE_PREVIEW);
    public final OutputPort<ImageBuffer> OUTPUTPORT_BACKGROUND_PREVIEW = new OutputPort<>(PORT_TYPE_BACKGROUND_PREVIEW);
    public final OutputPort<ImageBuffer> OUTPUTPORT_PICTURE = new OutputPort<>(PORT_TYPE_PICTURE);
    public final OutputPort<ImageFile> OUTPUTPORT_PICTURE_FILE = new OutputPort<>(PORT_TYPE_PICTURE_FILE);

    public final InputPort<Image> INPUTPORT_PREVIEW =
            new InputPort<>(PORT_TYPE_PREVIEW, new PreviewProcessCore(this), OUTPUTPORT_PREVIEW);

    public final InputPort<ImageBuffer> INPUTPORT_BACKGROUND_PREVIEW =
            new InputPort<>(PORT_TYPE_BACKGROUND_PREVIEW, new BackgroundPreviewProcessCore(this), OUTPUTPORT_BACKGROUND_PREVIEW);

    public final InputPort<ImageBuffer> INPUTPORT_PICTURE =
            new InputPort<>(PORT_TYPE_PICTURE, new PictureProcessCore(this), OUTPUTPORT_PICTURE);

    public final InputPort<ImageFile> INPUTPORT_PICTURE_FILE =
            new InputPort<>(PORT_TYPE_PICTURE_FILE, new PictureFileProcessCore(this), OUTPUTPORT_PICTURE_FILE);

    protected final ReentrantLock mStateLock = new ReentrantLock();
    protected final Condition mInitializedCond = mStateLock.newCondition();

    @GuardedBy("mInitParams")
    protected final List<NativeNode.Command<?>> mPropertyTypeCommands = new ArrayList<>();
    protected final Map<NativeNode.Command<?>, Object[]> mInitParams = new HashMap<>();

    protected final NodeId mNodeId;
    protected final boolean mHasNativeNode;
    protected final String mTag;
    protected final Map<Integer, NativeNode.NativeCallback<?, ?, ?>> mNativeCallbacks = new HashMap<>();

    private final Map<PortType, InputPort> mInputPortMap = new HashMap<>();
    private final Map<PortType, OutputPort> mOutputPortMap = new HashMap<>();
    private final Map<PortType, CoreInterface> mCoreInterfaceMap = new HashMap<>();
    private final Map<SemImageFormat, BiFunction<ImageBuffer, ExtraBundle, ImageBuffer>> mProcessPictureMap
            = new EnumMap<>(SemImageFormat.class) {
        {
            put(SemImageFormat.YCBCR_P010, Node.this::processPictureYuv);
            put(SemImageFormat.YUV_420_888, Node.this::processPictureYuv);
            put(SemImageFormat.JPEG, Node.this::processPictureJpeg);
            put(SemImageFormat.JPEG_R, Node.this::processPictureJpeg);
            put(SemImageFormat.RAW_SENSOR, Node.this::processPictureRaw);
            put(SemImageFormat.RAW10, Node.this::processPictureRaw);
            put(SemImageFormat.RAW12, Node.this::processPictureRaw);
            put(SemImageFormat.RAW14, Node.this::processPictureRaw);
            put(SemImageFormat.HEIC, Node.this::processPictureHeic);
            put(SemImageFormat.HEIC_ULTRAHDR, Node.this::processPictureHeic);
        }
    };

    protected final EnumSet<ImageInfo.CameraUsage> mSupportedCamType = EnumSet.of(ImageInfo.CameraUsage.MAIN_CAM, ImageInfo.CameraUsage.SUB_CAM);

    protected NativeNode mNativeNode;

    protected State mState = State.DEINITIALIZED;

    protected long mInitializingThreadId = -1L;

    protected TimeoutExecutor mInitTimeoutExecutor;
    protected TimeoutExecutor mPictureProcessTimeoutExecutor;

    /**
     * <div class="camera_en">
     * Get RuntimeProperties.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * RuntimeProperties 을 얻는다.
     * </div>
     *
     * @param nodeId node id.
     * @return RuntimeProperties. or empty list if any properties doesn't exist.
     */
    @NonNull
    public static List<NodeFeature.RuntimeProperty> getRuntimeProperties(@NonNull NodeId nodeId) {
        // TODO: if There is a Node using java libraries(like jar or aar) instead of native library and They support RuntimeProperties,
        //  then You should implement here the code getting RuntimeProperties from the java libraries and putting them on this list.
        return Arrays.stream(NativeNode.getRuntimeProperties(nodeId.getId())).collect(Collectors.toList());
    }

    /**
     * <div class="camera_en">
     * Node class's constructor.
     * Create NativeNode correspond to input nodeId.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 노드 class 의 생성자.
     * 입력된 nodeId 에 해당하는 NativeNode 를 생성한다.
     * </div>
     *
     * @param nodeId        Node's Identifier.
     * @param nodeTag       Node's Tag.
     * @param hasNativeNode if Node has NativeNode, set true.
     */
    protected Node(@NonNull NodeId nodeId, @NonNull String nodeTag, boolean hasNativeNode) {
        mTag = nodeTag;

        CLog.v(getNodeTag(), "%s - id %d, hasNativeNode %b", getNodeTag(), nodeId.getId(), hasNativeNode);

        mNodeId = Optional.ofNullable(NodeFeatureUtil.getNodeFeatureVersion(this.getClass()))
                .map(NodeFeatureVersion::getTargetNodeId)
                .orElse(nodeId);
        mHasNativeNode = hasNativeNode;

        registerOutputPort(OUTPUTPORT_PREVIEW,
                OUTPUTPORT_BACKGROUND_PREVIEW,
                OUTPUTPORT_PICTURE,
                OUTPUTPORT_PICTURE_FILE);

        registerInputPort(INPUTPORT_PREVIEW,
                INPUTPORT_BACKGROUND_PREVIEW,
                INPUTPORT_PICTURE,
                INPUTPORT_PICTURE_FILE);
    }

    /**
     * <div class="camera_en">
     * In debug mode, picture process timeout executor will be checked out
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 디버그 모드에서 picture process timeout executor 가 timeout 을 체크한다.
     * </div>
     */
    protected void checkPictureProcessTimeout() {
        final TimeoutExecutor pictureProcessTimeoutExecutor = mPictureProcessTimeoutExecutor;
        if (null != pictureProcessTimeoutExecutor) {
            pictureProcessTimeoutExecutor.checkTimeout();
        }
    }

    /**
     * <div class="camera_en">
     * In debug mode, picture process timeout executor will be cancelled
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 디버그 모드에서 picture process timeout executor 가 timeout 체크를 취소한다.
     * </div>
     */
    protected void cancelPictureProcessTimeout() {
        final TimeoutExecutor pictureProcessTimeoutExecutor = mPictureProcessTimeoutExecutor;
        if (null != pictureProcessTimeoutExecutor) {
            pictureProcessTimeoutExecutor.cancelTimeout();
        }
    }

    /**
     * <div class="camera_en">
     * Connect OutputPort to InputPort.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * OutputPort 에 InputPort 를 연결한다.
     * </div>
     *
     * @param outputPort OutputPort that want to connect to InputPort.
     * @param inputPort  InputPort that want to connect with InputPort.
     */
    public static <T> void connectPort(@NonNull OutputPort<T> outputPort, InputPort<T> inputPort) {
        outputPort.connectInputPort(inputPort);
    }

    /**
     * <div class="camera_en">
     * Set data into OutputPort.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * OutputPort 에 data 를 set 한다.
     * </div>
     *
     * @param outputPort OutputPort that want to set data.
     * @param data       Data that want to set.
     * @param bundle     ExtraBundle.
     * @return result data.
     */
    public static <T> Object set(@NonNull OutputPort<T> outputPort, @NonNull T data, @NonNull ExtraBundle bundle) {
        return outputPort.set(data, bundle);
    }

    /**
     * <div class="camera_en">
     * Set data into OutputPort.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * OutputPort 에 data 를 set 한다.
     * </div>
     *
     * @param outputPort OutputPort that want to set data.
     * @param data       Data that want to set.
     * @return result data.
     */
    public static <T> Object set(@NonNull OutputPort<T> outputPort, @NonNull T data) {
        return outputPort.set(data, new ExtraBundle());
    }

    /**
     * <div class="camera_en">
     * Set data into InputPort.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * InputPort 에 data 를 set 한다.
     * </div>
     *
     * @param inputPort InputPort that want to set data.
     * @param data      Data that want to set.
     * @param bundle    ExtraBundle.
     * @return result data.
     */
    public static <T> Object set(@NonNull InputPort<T> inputPort, @Nullable T data, @NonNull ExtraBundle bundle) {
        return inputPort.set(data, bundle);
    }

    /**
     * <div class="camera_en">
     * Set data into InputPort.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * InputPort 에 data 를 set 한다.
     * </div>
     *
     * @param inputPort InputPort that want to set data.
     * @param data      Data that want to set.
     * @return result data.
     */
    public static <T> Object set(@NonNull InputPort<T> inputPort, @NonNull T data) {
        return inputPort.set(data, new ExtraBundle());
    }

    /**
     * <div class="camera_en">
     * Set PortDataCallback at OutputPort.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * OutputPort 에 PortDataCallback 을 설정한다.
     * </div>
     *
     * @param outputPort OutputPort that want to set PortDataCallback.
     * @param callback   PortDataCallback
     */
    // FIXME: Change access modifier to protected
    public static <T> void setOutputPortDataCallback(@NonNull OutputPort<T> outputPort, OutputPort.PortDataCallback<T> callback) {
        outputPort.setDataCallback(callback);
    }

    /**
     * <div class="camera_en">
     * De-initialize Node.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Node 를 초기화 해제 한다.
     * </div>
     *
     * @throws IllegalStateException     If current state is invalid for de-initializing.
     * @throws InvalidOperationException If de-initializing fail.
     */
    public void deinitialize() {
        if (mNodeId == NodeId.NODE_DUMMY) {
            dummyDeinitialize();
            return;
        }

        mStateLock.lock();
        try {
            CLog.i(getNodeTag(), "deinitialize");

            if (isDeInitialized()) {
                CLog.w(getNodeTag(), "deinitialize - already node is deInitialized, ignore");
                return;
            } else if (isDeInitializing()) {
                CLog.w(getNodeTag(), "deinitialize - already node is deInitializing, ignore");
                return;
            }

            // Although node is not supported asyncInit and if this method call tid is different with initialize method call tid,
            // then current thread must wait for finish initialize method call.
            if (isInitializing()) {
                try {
                    if (!mInitializedCond.await(NODE_INIT_TIMEOUT_S, TimeUnit.SECONDS)) {
                        throw new InvalidOperationException(String.format(Locale.UK, "%s: deinitialize fail - waiting time(%d sec) for node initializing is expired",
                                getNodeTag(), NODE_INIT_TIMEOUT_S));
                    }
                } catch (InterruptedException e) {
                    throw new InvalidOperationException(String.format(Locale.UK, "%s: deinitialize fail - get interrupt during waiting for node initializing, %s",
                            getNodeTag(), e));
                }
            }

            mState.checkTransitState(State.DEINITIALIZING);
            mState = State.DEINITIALIZING;
        } catch (IllegalStateException e) {
            throw new IllegalStateException(String.format(Locale.UK, "%s: deinitialize fail - state transition error, %s", getNodeTag(), e));
        } finally {
            mStateLock.unlock();
        }

        if (DEBUG) {
            if (null != mInitTimeoutExecutor) {
                mInitTimeoutExecutor.shutdown();
                mInitTimeoutExecutor = null;
            }
            if (null != mPictureProcessTimeoutExecutor) {
                mPictureProcessTimeoutExecutor.shutdown();
                mPictureProcessTimeoutExecutor = null;
            }
        }

        TraceWrapper.traceBegin(getNodeTag() + "-onDeinitialized");
        // Refer to onInitialized comment in initialize method.
        // Don't move onDeinitialized() to mState lock region.
        onDeinitialized();
        TraceWrapper.traceEnd();

        mStateLock.lock();
        try {
            mInitializingThreadId = -1L;
            releaseNativeNode();
            mState = State.DEINITIALIZED;
        } finally {
            mStateLock.unlock();
        }
    }

    /**
     * <div class="camera_en">
     * Get coreInterface which is adapted with {@code type}.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * {@code type} 에 맞는 coreInterface 를 얻어온다.
     * </div>
     *
     * @return coreInterface or null when fail to find the port.
     */
    public <T> CoreInterface<T> getCoreInterface(@NonNull PortType<T> type) {
        return mCoreInterfaceMap.get(type);
    }

    /**
     * <div class="camera_en">
     * Get inputPort which is adapted with {@code type}.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * {@code type} 에 맞는 inputPort 를 얻어온다.
     * </div>
     *
     * @return inputPort or null when fail to find the port.
     */
    public <T> InputPort<T> getInputPort(@NonNull PortType<T> type) {
        return mInputPortMap.get(type);
    }

    /**
     * <div class="camera_en">
     * Get outputPort which is adapted with {@code type}.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * {@code type} 에 맞는 outputPort 를 얻어온다.
     * </div>
     *
     * @return outputPort or null when fail to find the port.
     */
    public <T> OutputPort<T> getOutputPort(@NonNull PortType<T> type) {
        return mOutputPortMap.get(type);
    }

    /**
     * <div class="camera_en">
     * Initialize Node.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Node 를 초기화한다.
     * </div>
     *
     * @param activate need activate after initialization.
     * @throws IllegalStateException     if current state is invalid for initializing.
     * @throws InvalidOperationException if initialization fail.
     */
    public void initialize(final boolean activate) {
        initialize(activate, /*asyncInit*/false);
    }

    /**
     * <div class="camera_en">
     * Initialize Node.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Node 를 초기화한다.
     * </div>
     *
     * @param activate  need activate after initialization.
     * @param asyncInit async initialize.
     * @throws IllegalStateException     If current state is invalid for initializing.
     * @throws InvalidOperationException If initialization fail.
     */
    public void initialize(final boolean activate, boolean asyncInit) {
        initialize(activate, asyncInit, /*delay*/0);
    }

    /**
     * <div class="camera_en">`
     * Initialize Node.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Node 를 초기화한다.
     * </div>
     *
     * @param activate  need activate after initialization.
     * @param asyncInit async initialize.
     * @param delay     async delay
     * @throws IllegalStateException     If current state is invalid for initializing.
     * @throws InvalidOperationException If initialization fail.
     */
    public void initialize(final boolean activate, boolean asyncInit, long delay) {
        if (mNodeId == NodeId.NODE_DUMMY) {
            dummyInitialize();
            return;
        }

        mStateLock.lock();
        try {
            CLog.i(getNodeTag(), "initialize - activate %b, asyncInit %b(delay %dms)", activate, asyncInit, delay);

            if (isInitializing()) {
                CLog.w(getNodeTag(), "initialize - already node is initializing, ignore");
                return;
            } else if (isInitialized()) {
                if (isActivated() != activate) {
                    setActivate(activate);
                }
                CLog.w(getNodeTag(), "initialize - already node is initialized, ignore");
                return;
            }

            mState.checkTransitState(State.INITIALIZING);

            if (asyncInit) {
                mInitializingThreadId = -1; // dummyId for wait init
                new Timer().schedule(new TimerTask() {
                    @Override
                    public void run() {
                        mStateLock.lock();
                        try {
                            mInitializingThreadId = Thread.currentThread().threadId();
                        } finally {
                            mStateLock.unlock();
                        }

                        try {
                            initializeInternal();
                        } catch (Exception e) {
                            handleInitializingFailed(e);
                            throw e;
                        }

                        mStateLock.lock();
                        try {
                            setActivate(activate);
                            mInitializedCond.signalAll();
                        } finally {
                            mStateLock.unlock();
                        }

                        TraceWrapper.asyncTraceEnd(getNodeTag() + "-initializingThread - delay(" + delay + "ms)", 0);
                    }
                }, delay);

                TraceWrapper.asyncTraceBegin(getNodeTag() + "-initializingThread - delay(" + delay + "ms)", 0);
                mState = State.INITIALIZING;
                return;
            }

            mInitializingThreadId = Thread.currentThread().threadId();
            mState = State.INITIALIZING;
        } catch (IllegalStateException e) {
            throw new IllegalStateException(String.format(Locale.UK, "%s: initialize fail - state transition error, %s", getNodeTag(), e));
        } finally {
            mStateLock.unlock();
        }

        try {
            initializeInternal();
        } catch (Exception e) {
            handleInitializingFailed(e);
            throw e;
        }

        mStateLock.lock();
        try {
            setActivate(activate);
            mInitializedCond.signalAll();
        } finally {
            mStateLock.unlock();
        }
    }

    /**
     * <div class="camera_en">
     * Initialize/deInitialize Node according to {@param enable}.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Node 를 {@param enable} 에 따라 initialize/deInitialize 한다.
     * </div>
     *
     * @param enable    enable.
     * @param asyncInit asyncInit.
     */
    public void initializeNode(boolean enable, boolean asyncInit) {
        if (enable) {
            initialize(/*activate*/true, asyncInit);
        } else {
            deinitialize();
        }
    }

    /**
     * <div class="camera_en">
     * Initialize Node.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Node 를 초기화한다.
     * </div>
     */
    protected final void initializeInternal() {
        if (DEBUG) {
            mInitTimeoutExecutor = new TimeoutExecutor(() -> {
                throw new InvalidOperationException(String.format(Locale.UK,
                        "%s initialization time(%d sec) has expired", getNodeTag(), NODE_INIT_TIMEOUT_S));
            }, NODE_INIT_TIMEOUT_S, TimeUnit.SECONDS);
            mPictureProcessTimeoutExecutor = new TimeoutExecutor(() -> {
                throw new InvalidOperationException(String.format(Locale.UK,
                        "%s picture processing time(%d sec) has expired", getNodeTag(), NODE_INIT_TIMEOUT_S));
            }, NODE_INIT_TIMEOUT_S, TimeUnit.SECONDS);
        }

        if (DEBUG) {
            mInitTimeoutExecutor.checkTimeout();
        }

        TraceWrapper.traceBegin(getNodeTag() + "-initNativeNode");
        initNativeNode();
        TraceWrapper.traceEnd();

        TraceWrapper.traceBegin(getNodeTag() + "-onInitialized");
        // If child node api(this sync -> state lock) called and initialize node (state lock -> this sync) called at the same time.
        // Deadlock issue will be occurred, so Don't move onInitialized() to mState lock region.
        onInitialized(mInitParams);
        TraceWrapper.traceEnd();

        if (DEBUG) {
            mInitTimeoutExecutor.cancelTimeout();
        }
    }

    /**
     * <div class="camera_en">
     * Handle when initializing node failed, it lead to deinitializing node.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Node 초기화 실패를 처리한다, 노드 초기화 해제로 이어진다.
     * </div>
     *
     * @param throwable throwable which makes initializing failed.
     */
    protected void handleInitializingFailed(@NonNull Throwable throwable) {
        mStateLock.lock();
        try {
            CLog.e(getNodeTag(), "handleInitializingFailed - initializing failed by %s", throwable.toString());

            mInitializingThreadId = -1L;
            releaseNativeNode();

            mState.checkTransitState(State.DEINITIALIZED);
            mState = State.DEINITIALIZED;

            mInitializedCond.signalAll();
        } finally {
            mStateLock.unlock();
        }
    }

    /**
     * <div class="camera_en">
     * Check Node is activated.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Node 가 활성화된 상태인지 확인한다.
     * </div>
     *
     * @return if node status is in ACTIVATED then true or false.
     */
    @GuardedBy("mStateLock")
    public boolean isActivated() {
        return mState.compareState(State.ACTIVATED);
    }

    /**
     * <div class="camera_en">
     * Check if Node is initializing.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Node 가 초기화중인 상태인지 확인한다.
     * </div>
     *
     * @return if node status is in INITIALIZING then true or false.
     */
    @GuardedBy("mStateLock")
    public boolean isInitializing() {
        return mState.compareState(State.INITIALIZING);
    }

    /**
     * <div class="camera_en">
     * Check if Node is initialized.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Node 가 초기화된 상태인지 확인한다.
     * </div>
     *
     * @return if node status is in INITIALIZED or ACTIVATED then true or false.
     */
    @GuardedBy("mStateLock")
    public boolean isInitialized() {
        return mState.compareState(State.INITIALIZED)
                || mState.compareState(State.ACTIVATED);
    }

    /**
     * <div class="camera_en">
     * Check if Node is deInitializing.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Node 가 초기화 해재중인 상태인지 확인한다.
     * </div>
     *
     * @return if node status is in DEINITIALIZING then true or false.
     */
    @GuardedBy("mStateLock")
    public boolean isDeInitializing() {
        return mState.compareState(State.DEINITIALIZING);
    }

    /**
     * <div class="camera_en">
     * Check if Node is deInitialized.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Node 가 초기화 해재된 상태인지 확인한다.
     * </div>
     *
     * @return if node status is in DEINITIALIZED then true or false.
     */
    @GuardedBy("mStateLock")
    public boolean isDeInitialized() {
        return mState.compareState(State.DEINITIALIZED);
    }

    /**
     * <div class="camera_en">
     * check to need processing background preview.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Background processPreview 가 진행되야 하는지 여부를 확인한다.
     * </div>
     *
     * @return if node need processing the background preview.
     */
    @CallSuper
    public boolean needProcessBackgroundPreview() {
        return isActivated();
    }

    /**
     * <div class="camera_en">
     * check to need processing custom.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * processCustom 이 진행되야 하는지 여부를 확인한다.
     * </div>
     *
     * @return if node need processing the custom.
     */
    @CallSuper
    public boolean needProcessCustom() {
        return isActivated();
    }

    /**
     * <div class="camera_en">
     * check to need processing depthMap.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * processDepthMap 가 진행되야 하는지 여부를 확인한다.
     * </div>
     *
     * @return if node need processing the depthMap.
     */
    @CallSuper
    public boolean needProcessDepthMap() {
        return isActivated();
    }

    /**
     * <div class="camera_en">
     * check to need processing picture.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * processPicture가 진행되야 하는지 여부를 확인한다.
     * </div>
     *
     * @return if node need processing the picture.
     */
    @CallSuper
    public boolean needProcessPicture() {
        mStateLock.lock();
        try {
            if (isInitializing()) {
                try {
                    if (!mInitializedCond.await(NODE_INIT_TIMEOUT_S, TimeUnit.SECONDS)) {
                        throw new InvalidOperationException(String.format(Locale.UK, "%s: needProcessPicture fail - waiting time(%d sec) for node initializing is expired",
                                getNodeTag(), NODE_INIT_TIMEOUT_S));
                    }
                } catch (InterruptedException e) {
                    throw new InvalidOperationException(String.format(Locale.UK, "%s: needProcessPicture fail - get interrupt during waiting for node initializing",
                            getNodeTag()));
                }
            }

            return mState.compareState(State.ACTIVATED);
        } finally {
            mStateLock.unlock();
        }
    }

    /**
     * <div class="camera_en">
     * check to need processing preview.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * ProcessPreview 가 진행되야 하는지 여부를 확인한다.
     * </div>
     *
     * @return if node need processing the preview.
     */
    @CallSuper
    public boolean needProcessPreview() {
        return isActivated();
    }

    /**
     * <div class="camera_en">
     * Register InputPort.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Register InputPort 를 등록한다.
     * </div>
     *
     * @param ports InputPorts.
     */
    public void registerInputPort(@NonNull InputPort... ports) {
        for (InputPort port : ports) {
            mInputPortMap.put(port.getPortType(), port);
            mCoreInterfaceMap.put(port.getPortType(), port.getCoreInterface());
        }
    }

    /**
     * <div class="camera_en">
     * Register OutputPort.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Register OutputPort 를 등록한다.
     * </div>
     *
     * @param ports OutputPorts.
     */
    public void registerOutputPort(@NonNull OutputPort... ports) {
        for (OutputPort port : ports) {
            mOutputPortMap.put(port.getPortType(), port);
        }
    }

    /**
     * <div class="camera_en">
     * Release the NativeNode.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * NativeNode 를 해제한다.
     * </div>
     */
    @CallSuper
    public void release() {
        CLog.i(getNodeTag(), "release");
        try {
            deinitialize();
        } catch (IllegalStateException e) {
            // ignore
        }
    }

    /**
     * <div class="camera_en">
     * Abort the NativeNode.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * NativeNode 를 중단한다.
     * </div>
     */
    public final void abort() {
        CLog.i(getNodeTag(), "abort E");

        if(!isActivated()) {
            CLog.i(getNodeTag(), "abort X - skip : inactivated");
            return;
        }

        if (!Node.getRuntimeProperties(mNodeId).contains(NodeFeature.RuntimeProperty.ABORTABLE)) {
            CLog.i(getNodeTag(), "abort X - skip : not supported");
            return;
        }

        if (DEBUG) {
            cancelPictureProcessTimeout();
        }

        abortProcess();
        CLog.i(getNodeTag(), "abort X");
    }

    /**
     * <div class="camera_en">
     * Abort the NativeNode. <br>
     * need to separate the thread using {@link Node2#nativeCall2(NativeNode.Command, Object...)}. <br>
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * NativeNode를 중단한다. <br>
     * {@link Node2#nativeCall2(NativeNode.Command, Object...)}. 를 사용해서 thread를 분리해야 한다. <br>
     * </div>
     */
    protected void abortProcess() {
        //ignore if not supported.
    }

    /**
     * <div class="camera_en">
     * Activate/Deactivate Node.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Node 를 활성화/비활성 한다.
     * </div>
     *
     * @param activate true means activation or deactivation.
     */
    public void setActivate(boolean activate) {
        mStateLock.lock();
        try {
            CLog.i(getNodeTag(), "setActivate - activate %b", activate);

            final State nextState = activate ? State.ACTIVATED : State.INITIALIZED;
            mState.checkTransitState(nextState);
            mState = nextState;
        } catch (IllegalStateException e) {
            // CLog.w(getNodeTag(), "setActivate fail - " + e);
        } finally {
            mStateLock.unlock();
        }
    }

    /**
     * <div class="camera_en">
     * de-initialize for dummy node.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Dummy node 의 초기화 해제.
     * </div>
     *
     * @throws IllegalStateException always throw exception.
     */
    protected void dummyDeinitialize() {
        printDummyMethodCallingMessage("deinitialize");
        throw new IllegalStateException(getNodeTag() + " can't deinitialize");
    }

    /**
     * <div class="camera_en">
     * initialize for dummy node.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Dummy node 의 초기화.
     * </div>
     *
     * @throws IllegalStateException always throw exception.
     */
    protected void dummyInitialize() {
        final String libName = Optional.ofNullable(this.getClass().getSuperclass())
                .map(NodeFeature.NodeFeatureGroup::find)
                .map(NodeFeature.NodeFeatureGroup::getFeatureLibName)
                .orElse("invalid");

        printDummyMethodCallingMessage(" initialize(nodeFeature:" + libName + ")");
        throw new IllegalStateException(getNodeTag() + " can't initialize. need to check nodeFeature(" + libName + ") in VENDOR_LIB_INFO.");
    }

    /**
     * <div class="camera_en">
     * Get Node tag
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Node tag 값을 얻어온다.
     * </div>
     *
     * @return node tag
     */
    protected String getNodeTag() {
        return mTag;
    }

    /**
     * <div class="camera_en">
     * Get Tag Name
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Tag Name 값을 얻어온다.
     * </div>
     *
     * @return Tag Name without version
     */
    protected String getNodeTagNameWithoutVersion() {
        final String[] tag = mTag.split("/");
        if (tag.length < 1) {
            return "";
        }

        return tag[tag.length - 1];
    }

    /**
     * <div class="camera_en">
     * Initialize nativeNode.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * nativeNode 를 초기화 한다.
     * </div>
     *
     * @throws InvalidOperationException if loading nativeNode instance fail.
     */
    protected void initNativeNode() {
        if (mHasNativeNode) {
            mNativeNode = new NativeNode(mNodeId, mTag);
            mNativeCallbacks.forEach(this::setNativeCallback);
        }
    }

    /**
     * <div class="camera_en">
     * Call native function.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * native function 을 호출한다.
     * </div>
     *
     * @param command command inform which native function would be executed.
     * @param args    arguments.
     * @return result of native function.
     * @throws InvalidOperationException if node is not initialized or waiting for initializing fail.
     */
    protected <RET> RET nativeCall(@NonNull NativeNode.Command<RET> command, Object... args) throws InvalidOperationException {
        return nativeCallInternal("nativeCall", mStateLock, mInitializedCond, command, args);
    }

    protected final <RET> RET nativeCallInternal(@NonNull String name, @NonNull ReentrantLock lock, @NonNull Condition condition, @NonNull NativeNode.Command<RET> command,
                                                 Object... args) throws InvalidOperationException {
        lock.lock();
        try {
            final boolean needWaitingForInitializing = isInitializing() && mInitializingThreadId != Thread.currentThread().threadId();

            if (needWaitingForInitializing) {
                try {
                    if (!condition.await(NATIVE_NODE_INIT_TIMEOUT_S, TimeUnit.SECONDS)) {
                        throw new InvalidOperationException(String.format(Locale.UK, "%s: %s(%s) fail - waiting time (%d sec) for node initializing is expired",
                                getNodeTag(), name, command, NATIVE_NODE_INIT_TIMEOUT_S));
                    }
                } catch (InterruptedException e) {
                    throw new InvalidOperationException(String.format(Locale.UK, "%s: %s(%s) fail - get interrupt during waiting for node initializing",
                            getNodeTag(), name, command));
                }
            }

            if (isDeInitialized()) {
                if (needWaitingForInitializing) {
                    // we can't guarantee success for nativeCall if raceCondition occurred between nativeCall() and deinitialize() in each thread,
                    // although call order is right(initialize() -> nativeCall()) and nativeCall() is called before deinitialize() in each thread.
                    // since we can't assume which thread would wake up early, so discard this call.
                    CLog.w(getNodeTag(), "%s(%s) fail - node is deinitialized after waiting for initializing, discard this call", name, command);
                    return null;
                } else {
                    throw new InvalidOperationException(String.format(Locale.UK, "%s: %s(%s) fail - node is deinitialized",
                            getNodeTag(), name, command));
                }
            }

            return mNativeNode.nativeCall(command, args);
        } finally {
            lock.unlock();
        }
    }

    /**
     * <div class="camera_en">
     * Callback for node is de-initialized.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Node 가 초기화 해제 되었을때 받는 콜백이다.
     * </div>
     *
     * @throws InvalidOperationException if de-initializing fail.
     */
    protected void onDeinitialized() {
    }

    /**
     * <div class="camera_en">
     * Callback for node is initialized.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Node 가 초기화 되었을때의 받는 콜백이다.
     * </div>
     *
     * @param initParams initParams has native commands and values what were used nativeCall before node is initialized.
     * @throws InvalidOperationException if initializing fail.
     */
    @CallSuper
    protected void onInitialized(@NonNull Map<NativeNode.Command<?>, Object[]> initParams) {
        synchronized (initParams) {
            if (mHasNativeNode) {
                for (Map.Entry<NativeNode.Command<?>, Object[]> entry : initParams.entrySet()) {
                    if (mPropertyTypeCommands.contains(entry.getKey()) && entry.getValue().length > 0) {
                        final Map<Object, Object[]> propertyCommandMap = (Map<Object, Object[]>) entry.getValue()[0];
                        for (Map.Entry<Object, Object[]> propertyTypeEntry : propertyCommandMap.entrySet()) {
                            nativeCall(entry.getKey(), propertyTypeEntry.getValue());
                        }
                    } else {
                        nativeCall(entry.getKey(), entry.getValue());
                    }
                }
            }
            initParams.clear();
        }
    }

    /**
     * <div class="camera_en">
     * Print warning message when method in dummy node is called.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Dummy node 의 method 가 호출될때의 경고 메시지를 출력한다.
     * </div>
     *
     * @param methodName method name.
     */
    protected void printDummyMethodCallingMessage(@NonNull String methodName) {
        CLog.w(getNodeTag(), methodName + " is called in dummy node");
    }

    /**
     * <div class="camera_en">
     * Process the Background preview.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * Background preview 를 처리한다.
     * </div>
     *
     * @param backgroundPreview previewData.
     * @param bundle            ExtraBundle.
     * @return backgroundPreview.
     */
    protected ImageBuffer processBackgroundPreview(@NonNull ImageBuffer backgroundPreview, @NonNull ExtraBundle bundle) {
        return backgroundPreview;
    }

    /**
     * <div class="camera_en">
     * Process the custom data.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * custom data 를 처리한다.
     * </div>
     *
     * @param preview previewData.
     * @param bundle  ExtraBundle.
     * @return preview.
     */
    protected ImageBuffer processCustom(@NonNull ImageBuffer preview, @NonNull ExtraBundle bundle) {
        return preview;
    }

    /**
     * <div class="camera_en">
     * Process the depth map
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * depth map 을 처리한다.
     * </div>
     *
     * @param depthMap depthMapData.
     * @param bundle   ExtraBundle.
     * @return depthMap.
     */
    protected ImageBuffer processDepthMap(@NonNull ImageBuffer depthMap, @NonNull ExtraBundle bundle) {
        return depthMap;
    }

    /**
     * <div class="camera_en">
     * Process the picture.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * picture 를 처리한다.
     * </div>
     *
     * @param picture pictureData.
     * @param bundle  ExtraBundle.
     * @return picture.
     */
    @CallSuper
    protected ImageBuffer processPicture(@NonNull ImageBuffer picture, @NonNull ExtraBundle bundle) {
        final SemImageFormat imgFormat = picture.getImageInfo().getFormat();
        final BiFunction<ImageBuffer, ExtraBundle, ImageBuffer> executor = mProcessPictureMap.get(imgFormat);
        if (null == executor) {
            return picture;
        }

        final CaptureMetrics captureMetrics = bundle.get(ExtraBundle.DATA_CAPTURE_METRICS);
        final DeviceStateReader deviceStateReader = bundle.get(ExtraBundle.DATA_DEVICE_STATE_READER);
        final DraftSequenceMetrics draftSequenceMetrics = Optional.ofNullable(captureMetrics)
                .map(CaptureMetrics::getDraftSequenceMetrics)
                .orElse(null);
        final NodeParams nodeParams = getNodeParams(bundle);
        if (null == draftSequenceMetrics || null == deviceStateReader || nodeParams == NodeParams.None.INSTANCE) {
            return executor.apply(picture, bundle);
        }

        // DualBokeh is gated on its combined (bokeh + encoding + saving) admission; every other node
        // reports shouldRun == true and merely feeds its observed cost back into the model.
        final DraftSequenceExecutionProfiler draftSequenceExecutionProfiler = new DraftSequenceExecutionProfiler(deviceStateReader);
        final DraftSequenceExecutionSession session = draftSequenceExecutionProfiler.profileNodeExecution(
                captureMetrics,
                mNodeId,
                nodeParams,
                captureMetrics.getTimeoutTimestampMs(),
                picture.getImageInfo().getSize());
        if (!session.getShouldRun()) {
            return picture;
        }
        final ImageBuffer resultBuffer = executor.apply(picture, bundle);
        if (null != resultBuffer) {
            session.complete();
        }
        return resultBuffer;
    }

    @NonNull
    protected NodeParams getNodeParams(@NonNull ExtraBundle extraBundle) {
        return NodeParams.None.INSTANCE;
    }

    /**
     * <div class="camera_en">
     * Process the picture.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * picture 를 처리한다.
     * </div>
     *
     * @param picture pictureData.
     * @param bundle  ExtraBundle.
     * @return picture.
     */
    protected ImageFile processPicture(@NonNull ImageFile picture, @NonNull ExtraBundle bundle) {
        return picture;
    }

    /**
     * <div class="camera_en">
     * Process the preview.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * preview 를 처리한다.
     * </div>
     *
     * @param preview previewData.
     * @param bundle  ExtraBundle.
     * @return preview.
     */
    protected Image processPreview(@NonNull Image preview, @NonNull ExtraBundle bundle) {
        return preview;
    }

    /**
     * <div class="camera_en">
     * check if picture dump is required.
     * if dump is not required, override and return to false.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * picture dump 가 필요한 지 확인 한다.
     * dump 가 필요 없다면 override 해서 return 을 false 로 한다.
     * </div>
     *
     * @return true if picture dump is needed, false otherwise
     */
    protected boolean needPictureDump() {
        return DEBUG;
    }

    /**
     * <div class="camera_en">
     * Release nativeNode.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * nativeNode 를 해제 한다.
     * </div>
     */
    protected void releaseNativeNode() {
        if (null != mNativeNode) {
            mNativeNode.releaseNode();
            mNativeNode = null;
        }
    }

    /**
     * <div class="camera_en">
     * Set NativeCallback.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * NativeCallback 을 설정한다.
     * </div>nativeCall
     *
     * @param key key
     * @param nativeCallback nativeCallback.
     */
    private void setNativeCallback(int key, @NonNull NativeNode.NativeCallback nativeCallback) {
        // don't check state for just performance.
        mNativeNode.setNativeCallback(key, nativeCallback);
    }

    /**
     * <div class="camera_en">
     * convert CaptureMetadata of ImageBuffer to Required CaptureMetadata.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * ImageBuffer의 CaptureMetadata를 요구되는 CaptureMetadata 로 변경한다.
     * </div>
     *
     * @param imageBuffer     imageBuffer
     */
    protected void convertRequiredCaptureMetadata(@NonNull ImageBuffer imageBuffer) {
        final ImageInfo imageInfo = imageBuffer.getImageInfo();
        Optional.ofNullable(imageInfo.getCaptureMetadata())
                .ifPresent(captureMetadata -> {
                    final Set<CaptureMetadataKey> captureMetadataKeySet = RequiredCaptureMetadataProvider.get(this.getClass());
                    imageInfo.setCaptureMetadata(captureMetadata.with(captureMetadataKeySet));
                });
    }

    /**
     * <div class="camera_en">
     * revert required CaptureMetadata of ImageBuffer to total CaptureMetadata.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * ImageBuffer의 Required CaptureMetadata를 전체 CaptureMetadata로 되돌린다.
     * </div>
     *
     * @param imageBuffer imageBuffer
     */
    protected void revertRequiredCaptureMetadata(@Nullable ImageBuffer imageBuffer) {
        if (null == imageBuffer) {
            return;
        }

        final ImageInfo imageInfo = imageBuffer.getImageInfo();
        Optional.ofNullable(imageInfo.getCaptureMetadata())
                .map(captureMetadata -> captureMetadata.with(Collections.emptySet()))
                .ifPresent(imageInfo::setCaptureMetadata);
    }

    /**
     * <div class="camera_en">
     * Call native function If it is possible, or {@code command} and {@code args} will be saved as ActivateParams.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 가능하다면 native function 을 호출한다. 아니라면 {@code command} 와 {@code args} 을 ActivateParams 으로 저장한다.
     * </div>
     *
     * @param command command inform which native function would be executed.
     * @param args    arguments.
     * @return result of native function, if it's not possible to call native function return null.
     * @throws InvalidOperationException if waiting for initializing fail.
     */
    protected <RET> RET tryNativeCall(@NonNull NativeNode.Command<RET> command, Object... args) {
        return tryNativeCallInternal("tryNativeCall", mStateLock, mInitializedCond, command, args);
    }

    protected final <RET> RET tryNativeCallInternal(@NonNull String name, @NonNull ReentrantLock lock, @NonNull Condition condition, @NonNull NativeNode.Command<RET> command,
                                                    Object... args) {
        lock.lock();
        try {
            final boolean needWaitingForInitializing = isInitializing() && mInitializingThreadId != Thread.currentThread().threadId();

            if (needWaitingForInitializing) {
                try {
                    if (!condition.await(NATIVE_NODE_INIT_TIMEOUT_S, TimeUnit.SECONDS)) {
                        throw new InvalidOperationException(String.format(Locale.UK, "%s: %s(%s) fail - waiting time(%d sec) for node initializing is expired",
                                getNodeTag(), name, command, NATIVE_NODE_INIT_TIMEOUT_S));
                    }
                } catch (InterruptedException e) {
                    throw new InvalidOperationException(String.format(Locale.UK, "%s: %s(%s) fail - get interrupt during waiting for node initializing",
                            getNodeTag(), name, command));
                }
            }

            if (isDeInitialized()) {
                if (!needWaitingForInitializing) {
                    synchronized (mInitParams) {
                        if (mPropertyTypeCommands.contains(command) && args.length > 0) {
                            final Map<Object, Object[]> propertyCommandMap;
                            if (null == mInitParams.get(command)) {
                                propertyCommandMap = new HashMap<>();
                                propertyCommandMap.put(args[0], args);
                            } else {
                                propertyCommandMap = (Map<Object, Object[]>) mInitParams.get(command)[0];
                            }
                            propertyCommandMap.put(args[0], args);
                            args = new Object[]{propertyCommandMap};
                        }
                        mInitParams.put(command, args);
                    }
                } else {
                    CLog.w(getNodeTag(), "%s(%s) fail - node is deinitialized after waiting for initializing, discard this call", name, command);
                }
                return null;
            }

            return mNativeNode.nativeCall(command, args);
        } finally {
            lock.unlock();
        }
    }

    /**
     * <div class="camera_en">
     * prepare capture.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 촬영을 위한 준비를 한다.
     * </div>
     *
     * @param dsMode            dsMode.
     * @param mainProcessCount  mainProcessCount.
     * @param subProcessCount   subProcessCount.
     */
    @CallSuper
    public void prepareProcessCapture(int dsMode, int mainProcessCount, int subProcessCount) {
        setSupportedCamType(dsMode);
        CLog.i(getNodeTag(), "prepareProcessCapture: {supported camType : %s}", mSupportedCamType);
    }

    /**
     * <div class="camera_en">
     * set supported camera type.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 지원되는 카메라 타입을 설정한다.
     * </div>
     *
     * @param dsMode dsMode.
     */
    protected void setSupportedCamType(int dsMode) {
        // do nothing
    }

    /**
     * <div class="camera_en">
     * returns whether supported camera type or not
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * 지원되는 카메라 타입인지를 반환한다.
     * </div>
     */
    protected boolean isSupportedCamType(ImageInfo.CameraUsage cameraUsage) {
        try {
            ConditionChecker.checkNotEmpty(mSupportedCamType, "SupportedCamType");
        } catch (IllegalArgumentException e) {
            throw new AssertionError(String.format(Locale.UK, "%s: %s", getNodeTag(), e.getMessage()));
        }
        CLog.i(getNodeTag(), "isSupportedCamType : " + mSupportedCamType + ", cameraUsage :" + cameraUsage);
        return mSupportedCamType.contains(cameraUsage);
    }

    /**
     * <div class="camera_en">
     * State.
     * </div>
     *
     * <div class="camera_kr" style="display:none;">
     * State.
     * </div>
     */
    protected enum State {
        DEINITIALIZED,
        INITIALIZING,
        INITIALIZED,
        DEINITIALIZING,
        ACTIVATED;

        /**
         * <div class="camera_en">
         * Check that current state could be transit to new state.
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         * 현재 state 에서 새로운 state 로 전이 가능한지 확인한다.
         * </div>
         *
         * @param newState state that needs to checked to transit.
         * @throws IllegalStateException if state can't transit to newState.
         */
        public void checkTransitState(@NonNull State newState) {
            if (!Objects.requireNonNull(Rule.POSSIBLE_NEXT_STATES.get(this)).contains(newState)) {
                throw new IllegalStateException(String.format(Locale.UK, "invalid state %s -> %s", name(), newState.name()));
            }
        }

        /**
         * <div class="camera_en">
         * compared current state with {@code state}.
         * </div>
         *
         * <div class="camera_kr" style="display:none;">
         * 현재 state 가 state 와 비교하여 같다면 true 를 리턴한다.
         * </div>
         *
         * @param state checked state.
         * @return true if current state equals {@code state}, false otherwise
         */
        public boolean compareState(@NonNull State state) {
            return this == state;
        }

        private static class Rule {

            private static final EnumMap<State, List<State>> POSSIBLE_NEXT_STATES;

            static {
                POSSIBLE_NEXT_STATES = new EnumMap<>(State.class);

                for (State state : values()) {
                    final List<State> possibleNextStates = switch (state) {
                        case DEINITIALIZED -> Collections.singletonList(State.INITIALIZING);
                        case INITIALIZING -> Arrays.asList(State.ACTIVATED, State.INITIALIZED, State.DEINITIALIZED);
                        case INITIALIZED -> Arrays.asList(State.ACTIVATED, State.DEINITIALIZING);
                        case DEINITIALIZING -> Collections.singletonList(State.DEINITIALIZED);
                        case ACTIVATED -> Arrays.asList(State.INITIALIZED, State.DEINITIALIZING);
                    };

                    POSSIBLE_NEXT_STATES.put(state, possibleNextStates);
                }
            }
        }
    }
}