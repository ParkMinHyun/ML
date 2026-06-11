/*
 * PATCH-0608 excerpt of Core2/src/main/java/com/samsung/android/camera/core2/node/Node.java
 * (base revision 7b69ae1162b6bf75f42d393eb558603e23b60fda), updated to the current ML API.
 *
 * Only patch-affected members are shown; unchanged Core2 members are elided. The enclosing
 * method around the original line 1254 is named executeProcessPicture here — the name and
 * signature are approximated from the patch context, the body is the actual change.
 *
 * ML API drift applied (PATCH-0608 -> current):
 *  - DraftSequenceExecutionPerformanceHelper(Supplier<DeviceStateSnapshot>)
 *      -> DraftSequenceExecutionProfiler(DeviceStateReader)
 *    The bundle therefore carries the DeviceStateReader itself (DATA_DEVICE_STATE_READER)
 *    instead of a Supplier<DeviceStateSnapshot> (DATA_DEVICE_STATE_SNAPSHOT).
 *  - predictNodeExecution(NodeId, ..., budget (relative ms), ...)
 *      -> predictNodeExecution(String, ..., timeoutMs (absolute epoch ms), ...)
 *    The remaining budget is now derived inside the profiler at device-state read time,
 *    so the call site passes the deadline timestamp through unchanged.
 *  - session.recordCompletion() -> session.complete()
 *  - CaptureMetrics.getDraftSequenceMetrics() is @Nullable now -> explicit null guard.
 */

package com.samsung.android.camera.core2.node;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.samsung.android.camera.core2.container.ExtraBundle;
import com.samsung.android.camera.core2.ml.CaptureMetrics;
import com.samsung.android.camera.core2.ml.DeviceStateReader;
import com.samsung.android.camera.core2.ml.DraftSequenceExecutionProfiler;
import com.samsung.android.camera.core2.ml.DraftSequenceExecutionSession;
import com.samsung.android.camera.core2.ml.DraftSequenceMetrics;
import com.samsung.android.camera.core2.ml.NodeParams;
import com.samsung.android.camera.core2.util.ImageBuffer;

import java.util.Optional;
import java.util.function.BiFunction;

public abstract class Node {

    /* ... unchanged Core2 fields elided ... */
    protected NodeId mNodeId;

    private ImageBuffer executeProcessPicture(@NonNull ImageBuffer picture,
                                              @NonNull ExtraBundle bundle,
                                              @Nullable BiFunction<ImageBuffer, ExtraBundle, ImageBuffer> executor) {
        if (null == executor) {
            return picture;
        }

        final CaptureMetrics captureMetrics = bundle.get(ExtraBundle.DATA_CAPTURE_METRICS);
        // was: Supplier<DeviceStateSnapshot> via DATA_DEVICE_STATE_SNAPSHOT
        final DeviceStateReader deviceStateReader = bundle.get(ExtraBundle.DATA_DEVICE_STATE_READER);
        if (null != captureMetrics && null != deviceStateReader) {
            // was: non-null on the old CaptureMetrics; nullable now
            final DraftSequenceMetrics draftSequenceMetrics = captureMetrics.getDraftSequenceMetrics();
            // was: long budget = timeoutTimestamp - System.currentTimeMillis() (orElse 7000L);
            // the current API takes the absolute deadline and derives the budget internally.
            final long timeoutMs = Optional.ofNullable(bundle.get(ExtraBundle.DATA_CAPTURE_TIMEOUT_TIMESTAMP))
                    .orElse(System.currentTimeMillis() + 7000L);
            if (null != draftSequenceMetrics && timeoutMs > System.currentTimeMillis()) {
                // was: new DraftSequenceExecutionPerformanceHelper(deviceStateSnapshotSupplier);
                // the profiler is cheap to create per capture — learned state lives in the
                // shared DraftSequenceExecutionPredictionManager.instance.
                final DraftSequenceExecutionProfiler profiler = new DraftSequenceExecutionProfiler(deviceStateReader);
                final DraftSequenceExecutionSession session = profiler.predictNodeExecution(
                        mNodeId.name(),                     // was: mNodeId (model bucket key is a String now)
                        getNodeParams(),
                        timeoutMs,                          // was: budget (relative ms)
                        picture.getImageInfo().getSize(),
                        draftSequenceMetrics);

                // session.getShouldRun() / session.getExecutionPrediction() are available here
                // for admission control; during data collection the node always runs.
                final ImageBuffer resultBuffer = executor.apply(picture, bundle);
                if (null != resultBuffer) {
                    session.complete();                     // was: session.recordCompletion()
                }
                return resultBuffer;
            }
        }
        return executor.apply(picture, bundle);
    }

    /* PATCH-0608 addition (unchanged against current ML API) */
    @NonNull
    protected NodeParams getNodeParams() {
        return NodeParams.None.INSTANCE;
    }

    /* ... rest of Core2 Node elided ... */
}
