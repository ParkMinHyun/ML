/*
 * PATCH-0608 excerpt of Core2/src/main/java/com/samsung/android/camera/core2/node/NodeId.java
 * (base revision 7b69ae1162b6bf75f42d393eb558603e23b60fda), checked against the current ML
 * sources in this repository.
 *
 * Only patch-affected members are shown; unchanged Core2 members are elided.
 *
 * ML API drift in this class: none. Note that the current ML model keys node buckets by a
 * String (NodeExecutionMetrics.nodeId), so Node.java now passes mNodeId.name() and the enum
 * can be restored from a persisted name with the built-in NodeId.valueOf(String).
 * fromId(int) remains the reverse mapping for the integer id.
 */

package com.samsung.android.camera.core2.node;

import androidx.annotation.NonNull;

public enum NodeId {
    NODE_DUMMY(-1),

    /* ... unchanged node constants elided ... */
    ;

    /* existing Core2 members (unchanged, shown for context) */
    private final int id;

    NodeId(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    /* PATCH-0608 addition (unchanged against current ML API) */
    @NonNull
    public static NodeId fromId(int id) {
        for (NodeId nodeId : values()) {
            if (nodeId.getId() == id) {
                return nodeId;
            }
        }
        return NODE_DUMMY;
    }
}
