package com.mobclaw.android.overlay

import com.mobclaw.android.model.ForegroundInfo
import com.mobclaw.android.model.ScreenObservationStore
import com.mobclaw.android.model.ScreenState
import com.mobclaw.android.observer.MobObserver
import kotlin.time.Duration

/** Feeds concise real-time agent activity to the floating overlay. */
class OverlayObserver(private val overlay: AgentOverlay) : MobObserver {

    @Volatile
    var currentScreenState: ScreenState? = null

    private val hiddenTools = setOf("screen_read")

    override fun onAgentStart(task: String) {
        overlay.show()
        overlay.clearActions()
        overlay.updateStatus("Running")
    }

    override fun onToolCall(toolName: String, duration: Duration, success: Boolean) {
        if (toolName !in hiddenTools) overlay.markActionComplete(success)
    }

    override fun onScreenRead(packageName: String, nodeCount: Int) = Unit

    override fun onAgentEnd(task: String, duration: Duration, success: Boolean) {
        val status = if (success) "Done" else "Failed"
        overlay.updateStatus("$status (${duration.inWholeSeconds}s)")
        overlay.hideAfter(if (success) 1800L else 4000L)
    }

    override fun onError(message: String, throwable: Throwable?) {
        overlay.updateStatus("Error: $message")
        overlay.hideAfter(4000L)
    }

    override fun onReasoning(iteration: Int, text: String) {
        if (text.isNotBlank() && !text.equals("null", ignoreCase = true)) {
            overlay.showReasoning(text)
        }
    }

    override fun onActionPending(
        iteration: Int,
        toolName: String,
        arguments: Map<String, String>,
    ) {
        if (toolName in hiddenTools) return
        overlay.showAction(toolName, buildActionDescription(toolName, arguments), isPending = true)
    }

    override fun onScreenState(state: ScreenState) {
        currentScreenState = state
    }

    override fun onForegroundChanged(foreground: ForegroundInfo) {
        overlay.updateStatus(foreground.packageName.substringAfterLast('.'))
    }

    override fun onStuckDetected(iteration: Int, consecutiveNoChange: Int) {
        overlay.updateStatus("Trying another route")
    }

    private fun buildActionDescription(toolName: String, args: Map<String, String>): String {
        val markerId = args["marker_id"]?.toIntOrNull()
        val markerLabel = markerId?.let { id ->
            ScreenObservationStore.current?.targets?.firstOrNull { it.markerId == id }?.label
        }
        val nodeId = args["node_id"]
        val nodeLabel = nodeId?.let { resolveNodeText(it) }

        return when (toolName) {
            "click", "long_click" -> when {
                markerId != null -> "#$markerId ${markerLabel.orEmpty()}".trim()
                nodeId != null -> "$nodeId ${nodeLabel.orEmpty()}".trim()
                else -> ""
            }
            "input_text" -> {
                val target = markerLabel ?: nodeLabel ?: markerId?.let { "#$it" } ?: nodeId.orEmpty()
                "$target <- ${args["text"].orEmpty()}".take(60)
            }
            "tap" -> "(${args["x"] ?: "?"}, ${args["y"] ?: "?"})"
            "scroll" -> args["direction"] ?: ""
            "system_action" -> args["action"] ?: ""
            "open_app" -> args["package_name"]?.substringAfterLast('.') ?: ""
            "list_apps" -> args["filter"] ?: "all"
            "wait" -> "${args["milliseconds"] ?: "?"}ms"
            "finish" -> args["result"]?.take(50) ?: args["reason"]?.take(50).orEmpty()
            "fail" -> args["reason"]?.take(50).orEmpty()
            else -> args.entries.joinToString(", ") { "${it.key}=${it.value}" }.take(60)
        }
    }

    private fun resolveNodeText(nodeId: String): String? {
        val node = currentScreenState?.nodes?.find { it.id == nodeId } ?: return null
        return node.text?.take(30)
            ?: node.contentDescription?.take(30)
            ?: node.resourceId?.substringAfterLast('/')?.take(30)
    }
}