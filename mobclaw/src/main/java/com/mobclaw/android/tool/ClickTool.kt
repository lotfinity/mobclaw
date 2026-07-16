package com.mobclaw.android.tool

import com.mobclaw.android.accessibility.GestureEngine
import com.mobclaw.android.model.ScreenObservationStore
import com.mobclaw.android.model.ToolResult
import kotlinx.serialization.json.*

/** Click a numbered visual marker, with node ID fallback for text-only mode. */
class ClickTool : MobTool {

    override val name = "click"

    override val description =
        "Click a numbered target from the annotated screenshot. Pass snapshot_id and marker_id. " +
            "node_id is supported only for text-tree fallback observations."

    override fun parametersSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("snapshot_id", buildJsonObject {
                put("type", "integer")
                put("description", "Snapshot ID printed with the annotated screenshot")
            })
            put("marker_id", buildJsonObject {
                put("type", "integer")
                put("description", "Number printed on the desired screenshot target")
            })
            put("node_id", buildJsonObject {
                put("type", "string")
                put("description", "Fallback node ID, used only when screenshot capture is unavailable")
            })
        })
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val markerId = args["marker_id"]?.jsonPrimitive?.intOrNull
        if (markerId != null) {
            val snapshotId = args["snapshot_id"]?.jsonPrimitive?.longOrNull
                ?: return ToolResult(false, "", "Missing required parameter for marker click: snapshot_id")
            val observation = ScreenObservationStore.current
                ?: return ToolResult(false, "", "No active visual observation; call screen_read again")
            if (observation.snapshotId != snapshotId) {
                return ToolResult(false, "", "Stale snapshot #$snapshotId; current snapshot is #${observation.snapshotId}")
            }
            val target = observation.targets.firstOrNull { it.markerId == markerId }
                ?: return ToolResult(false, "", "Marker $markerId does not exist in snapshot #$snapshotId")
            if (!target.enabled || !target.canClick) {
                return ToolResult(false, "", "Marker $markerId is not an enabled click target")
            }

            val success = GestureEngine.tap(
                target.bounds.centerX().toFloat(),
                target.bounds.centerY().toFloat(),
            )
            return if (success) {
                ToolResult(true, "Clicked marker $markerId${target.label?.let { " ($it)" }.orEmpty()} on snapshot #$snapshotId")
            } else {
                ToolResult(false, "", "Failed to click marker $markerId")
            }
        }

        val nodeId = args["node_id"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(false, "", "Provide snapshot_id + marker_id, or node_id for text fallback")
        val snapshotId = args["snapshot_id"]?.jsonPrimitive?.longOrNull ?: 0L
        val success = GestureEngine.clickNode(nodeId, snapshotId)
        return if (success) {
            ToolResult(true, "Clicked fallback node $nodeId successfully")
        } else {
            ToolResult(false, "", "Failed to click fallback node $nodeId (element may have moved or screen changed)")
        }
    }
}
