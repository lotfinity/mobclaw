package com.mobclaw.android.overlay

import com.mobclaw.android.model.ForegroundInfo
import com.mobclaw.android.model.ScreenState
import com.mobclaw.android.observer.MobObserver
import kotlin.time.Duration

/**
 * MobObserver implementation that feeds real-time LLM actions to the AgentOverlay.
 */
class OverlayObserver(private val overlay: AgentOverlay) : MobObserver {

    @Volatile
    var currentScreenState: ScreenState? = null

    private val hiddenTools = setOf("screen_read")

    override fun onAgentStart(task: String) {
        overlay.show()
        overlay.clearActions()
        overlay.updateStatus("Running: $task")
    }

    override fun onToolCall(toolName: String, duration: Duration, success: Boolean) {
        if (toolName !in hiddenTools) {
            overlay.markActionComplete(success)
        }
    }

    override fun onScreenRead(packageName: String, nodeCount: Int) {
    }

    override fun onAgentEnd(task: String, duration: Duration, success: Boolean) {
        val status = if (success) "Done" else "Failed"
        overlay.updateStatus("$status (${duration.inWholeSeconds}s)")
    }

    override fun onError(message: String, throwable: Throwable?) {
        overlay.updateStatus("Error: $message")
    }

    override fun onReasoning(iteration: Int, text: String) {
        overlay.showReasoning(text)
    }

    override fun onActionPending(
        iteration: Int,
        toolName: String,
        arguments: Map<String, String>,
    ) {
        if (toolName in hiddenTools) return
        val description = buildActionDescription(toolName, arguments)
        overlay.showAction(toolName, description, isPending = true)
    }

    override fun onScreenState(state: ScreenState) {
        currentScreenState = state
    }

    override fun onForegroundChanged(foreground: ForegroundInfo) {
        overlay.updateStatus("Foreground: ${foreground.packageName}")
    }

    override fun onStuckDetected(iteration: Int, consecutiveNoChange: Int) {
        overlay.updateStatus("Stuck detected ($consecutiveNoChange unchanged)")
    }

    private fun buildActionDescription(toolName: String, args: Map<String, String>): String {
        val nodeId = args["node_id"]
        val resolvedText = nodeId?.let { resolveNodeText(it) }

        return when (toolName) {
            "click", "long_click" -> {
                if (nodeId != null) {
                    val label = resolvedText ?: "?"
                    "$nodeId \"$label\""
                } else "?"
            }
            "input_text" -> {
                val text = args["text"] ?: ""
                val label = resolvedText ?: nodeId ?: "?"
                "$label <- \"$text\""
            }
            "tap" -> {
                val x = args["x"] ?: "?"
                val y = args["y"] ?: "?"
                "($x, $y)"
            }
            "scroll" -> args["direction"] ?: "?"
            "system_action" -> args["action"] ?: "?"
            "open_app" -> args["package_name"]?.substringAfterLast('.') ?: "?"
            "list_apps" -> args["filter"] ?: "all"
            "wait" -> "${args["milliseconds"] ?: "?"}ms"
            "finish" -> args["reason"]?.take(40) ?: "done"
            "fail" -> args["reason"]?.take(40) ?: "failed"
            else -> args.entries.joinToString(", ") { "${it.key}=${it.value}" }.take(50)
        }
    }

    private fun resolveNodeText(nodeId: String): String? {
        val state = currentScreenState ?: return null
        val node = state.nodes.find { it.id == nodeId } ?: return null
        return node.text?.take(30)
            ?: node.contentDescription?.take(30)
            ?: node.resourceId?.substringAfterLast('/')?.take(30)
    }
}
