package com.mobclaw.android.model

import android.graphics.Rect

/**
 * One actionable target drawn onto an annotated screenshot.
 *
 * [markerId] is the human/LLM-facing number. [sourceNodeId] remains private
 * grounding metadata for Accessibility actions such as ACTION_SET_TEXT.
 */
data class VisualTarget(
    val markerId: Int,
    val sourceNodeId: String,
    val bounds: Rect,
    val label: String?,
    val resourceId: String?,
    val className: String,
    val canClick: Boolean,
    val canLongClick: Boolean,
    val canInput: Boolean,
    val canToggle: Boolean,
    val enabled: Boolean,
) {
    fun actionSummary(): String = buildList {
        if (canClick) add("click")
        if (canLongClick) add("long-click")
        if (canInput) add("input")
        if (canToggle) add("toggle")
    }.joinToString("/")
}

/**
 * Atomic visual observation sent to a vision-capable LLM.
 *
 * The image is the complete current Android window with numbered markers
 * painted onto a bitmap copy. The Accessibility tree is retained privately as
 * [targets], rather than being dumped into the prompt.
 */
data class ScreenObservation(
    val snapshotId: Long,
    val packageName: String,
    val activityName: String?,
    val width: Int,
    val height: Int,
    val imageDataUrl: String,
    val targets: List<VisualTarget>,
    val capturedAt: Long = System.currentTimeMillis(),
) {
    fun toPromptText(): String = buildString {
        appendLine("## Visual Screen Observation")
        appendLine("ACTION_SNAPSHOT_ID: $snapshotId")
        appendLine("Package: $packageName")
        activityName?.let { appendLine("Activity: $it") }
        appendLine("Image: attached annotated Android screenshot (${width}x${height})")
        appendLine("Numbered targets: ${targets.size}")
        appendLine("Allowed marker IDs: ${targets.joinToString(", ") { it.markerId.toString() }.ifBlank { "NONE" }}")
        appendLine()
        appendLine("Copy ACTION_SNAPSHOT_ID exactly into snapshot_id. Never shorten, guess, or reuse it.")
        appendLine("Only marker IDs listed above exist. Read all visible text and layout directly from the screenshot.")

        if (targets.isEmpty()) {
            appendLine("NO MARKERS: do not call click, long_click, or input_text with an invented marker.")
            if (packageName == "com.mobclaw.android.testapp" || packageName == "com.mobclaw.android") {
                appendLine("This is the MobClaw host screen. Use open_app immediately for a known destination, or list_apps if its package is unknown.")
            }
        }

        if (targets.isNotEmpty()) appendLine()
        targets.forEach { target ->
            append("[${target.markerId}] ${target.actionSummary().ifBlank { "target" }}")
            target.label?.takeIf { it.isNotBlank() }?.let { append(" — $it") }
            if (!target.enabled) append(" — DISABLED")
            appendLine()
        }
    }
}

/** Latest immutable marker map. Actions must resolve against this exact snapshot. */
object ScreenObservationStore {
    @Volatile
    var current: ScreenObservation? = null
}