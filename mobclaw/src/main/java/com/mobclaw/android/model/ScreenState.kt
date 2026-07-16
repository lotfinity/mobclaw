package com.mobclaw.android.model

import android.graphics.Rect

/**
 * Represents a UI element on screen, extracted from AccessibilityNodeInfo.
 */
data class ScreenNode(
    val id: String,
    val className: String,
    val resourceId: String?,
    val text: String?,
    val contentDescription: String?,
    val hintText: String?,
    val stateDescription: String?,
    val bounds: Rect,
    val isClickable: Boolean,
    val isLongClickable: Boolean,
    val isScrollable: Boolean,
    val isEditable: Boolean,
    val isCheckable: Boolean,
    val isChecked: Boolean,
    val isSelected: Boolean,
    val isFocused: Boolean,
    val isEnabled: Boolean,
    val isVisibleToUser: Boolean,
    val depth: Int,
    val childCount: Int,
    val packageName: String?,
    val viewIdResourceName: String?,
) {
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()
    val width: Int get() = bounds.width()
    val height: Int get() = bounds.height()
}

/**
 * A snapshot of the current screen state.
 */
data class ScreenState(
    val packageName: String,
    val activityName: String?,
    val nodes: List<ScreenNode>,
    val displayWidth: Int,
    val displayHeight: Int,
    val displayRotation: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val snapshotId: Long = timestamp,
) {
    fun toPromptText(): String = buildString {
        appendLine("## Current Screen: $packageName")
        activityName?.let { appendLine("Activity: $it") }
        appendLine("Display: ${displayWidth}x${displayHeight}, rotation=$displayRotation")
        appendLine("Snapshot: #$snapshotId (${timestamp})")
        appendLine("Found ${nodes.size} UI elements:")
        appendLine()

        for (node in nodes) {
            if (!node.isVisibleToUser) continue

            val indent = "  ".repeat(node.depth.coerceAtMost(4))
            append("$indent[${node.id}] ${node.className}")

            node.resourceId?.let { append(" (${it.substringAfter(":")})") }

            appendLine()

            if (!node.text.isNullOrBlank()) {
                appendLine("$indent  text: \"${node.text}\"")
            }
            if (!node.contentDescription.isNullOrBlank()) {
                appendLine("$indent  desc: \"${node.contentDescription}\"")
            }
            if (!node.hintText.isNullOrBlank()) {
                appendLine("$indent  hint: \"${node.hintText}\"")
            }
            if (!node.stateDescription.isNullOrBlank()) {
                appendLine("$indent  state: \"${node.stateDescription}\"")
            }

            val props = mutableListOf<String>()
            if (node.isClickable) props.add("clickable")
            if (node.isLongClickable) props.add("long-clickable")
            if (node.isScrollable) props.add("scrollable")
            if (node.isEditable) props.add("editable")
            if (node.isCheckable) {
                props.add(if (node.isChecked) "checked" else "unchecked")
            }
            if (node.isSelected) props.add("selected")
            if (node.isFocused) props.add("focused")
            if (!node.isEnabled) props.add("DISABLED")

            if (props.isNotEmpty()) {
                appendLine("$indent  [${props.joinToString(", ")}]")
            }

            val b = node.bounds
            appendLine("$indent  bounds: (${b.left},${b.top})-(${b.right},${b.bottom})")
        }
    }
}

/**
 * Information about the current foreground window.
 */
data class ForegroundInfo(
    val packageName: String,
    val activityName: String?,
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun isMobClaw(): Boolean {
        return packageName == "com.mobclaw.android.testapp" ||
            packageName == "com.mobclaw.android" ||
            activityName?.contains("MobClaw") == true
    }
}

/**
 * Tracks a sequence of actions and their outcomes for structured tracing.
 */
data class ActionTraceEntry(
    val sequence: Int,
    val iteration: Int,
    val timestamp: Long,
    val action: String,
    val arguments: Map<String, String>,
    val resultSuccess: Boolean,
    val resultOutput: String,
    val resultError: String?,
    val foregroundBefore: ForegroundInfo?,
    val foregroundAfter: ForegroundInfo?,
    val snapshotId: Long,
    val durationMs: Long,
)

/**
 * Complete structured trace of an agent execution.
 */
data class ExecutionTrace(
    val sessionId: String,
    val task: String,
    val provider: String,
    val model: String?,
    val startedAt: Long,
    val entries: List<ActionTraceEntry>,
    val verificationSnapshot: ScreenState? = null,
    val verificationPassed: Boolean = false,
    val verificationNotes: String = "",
    val endedAt: Long = System.currentTimeMillis(),
    val success: Boolean = false,
) {
    val durationMs: Long get() = endedAt - startedAt
    val totalActions: Int get() = entries.size
    val successfulActions: Int get() = entries.count { it.resultSuccess }
    val failedActions: Int get() = entries.count { !it.resultSuccess }
}
