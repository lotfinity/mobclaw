package com.mobclaw.android.tool

import com.mobclaw.android.accessibility.GestureEngine
import com.mobclaw.android.model.ScreenObservationStore
import com.mobclaw.android.model.ToolResult
import kotlinx.serialization.json.*

/** Type into a numbered visual input target, with node ID fallback. */
class InputTextTool : MobTool {

    override val name = "input_text"

    override val description =
        "Replace text in an editable target from the annotated screenshot. " +
            "Pass snapshot_id, marker_id, and text."

    override fun parametersSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("snapshot_id", buildJsonObject {
                put("type", "integer")
                put("description", "Snapshot ID printed with the annotated screenshot")
            })
            put("marker_id", buildJsonObject {
                put("type", "integer")
                put("description", "Number printed on the editable screenshot target")
            })
            put("node_id", buildJsonObject {
                put("type", "string")
                put("description", "Fallback node ID, used only when screenshot capture is unavailable")
            })
            put("text", buildJsonObject {
                put("type", "string")
                put("description", "Replacement text")
            })
        })
        put("required", JsonArray(listOf(JsonPrimitive("text"))))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val text = args["text"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(false, "", "Missing required parameter: text")
        val markerId = args["marker_id"]?.jsonPrimitive?.intOrNull

        if (markerId != null) {
            val snapshotId = args["snapshot_id"]?.jsonPrimitive?.longOrNull
                ?: return ToolResult(false, "", "Missing required parameter for marker input: snapshot_id")
            val observation = ScreenObservationStore.current
                ?: return ToolResult(false, "", "No active visual observation; call screen_read again")
            if (observation.snapshotId != snapshotId) {
                return ToolResult(false, "", "Stale snapshot #$snapshotId; current snapshot is #${observation.snapshotId}")
            }
            val target = observation.targets.firstOrNull { it.markerId == markerId }
                ?: return ToolResult(false, "", "Marker $markerId does not exist in snapshot #$snapshotId")
            if (!target.enabled || !target.canInput) {
                return ToolResult(false, "", "Marker $markerId is not an enabled text input")
            }

            // sourceNodeId came from the same Accessibility snapshot used to
            // render the marker. The screenshot marker itself is never exposed
            // as a mutable node identifier.
            val success = GestureEngine.inputText(target.sourceNodeId, text)
            return if (success) {
                ToolResult(true, "Typed text into marker $markerId${target.label?.let { " ($it)" }.orEmpty()} on snapshot #$snapshotId")
            } else {
                ToolResult(false, "", "Failed to type into marker $markerId; refresh the screen observation")
            }
        }

        val nodeId = args["node_id"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(false, "", "Provide snapshot_id + marker_id, or node_id for text fallback")
        val snapshotId = args["snapshot_id"]?.jsonPrimitive?.longOrNull ?: 0L
        val success = GestureEngine.inputText(nodeId, text, snapshotId)
        return if (success) {
            ToolResult(true, "Typed text into fallback node $nodeId")
        } else {
            ToolResult(false, "", "Failed to type into fallback node $nodeId (element may have moved or screen changed)")
        }
    }
}
