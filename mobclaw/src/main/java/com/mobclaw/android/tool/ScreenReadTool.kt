package com.mobclaw.android.tool

import com.mobclaw.android.accessibility.ScreenReader
import com.mobclaw.android.accessibility.VisualScreenReader
import com.mobclaw.android.model.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Observe the current Android screen.
 *
 * Vision-capable providers receive a full annotated screenshot through the
 * provider message adapter. The textual result is intentionally only a compact
 * marker index. If screenshot capture is unavailable, MobClaw falls back to
 * the existing Accessibility tree representation.
 */
class ScreenReadTool : MobTool {

    override val name = "screen_read"

    override val description =
        "Capture the current full Android screen as an annotated screenshot. " +
            "Numbered markers identify actionable elements; the compact result lists marker IDs and supported actions."

    override fun parametersSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val visual = VisualScreenReader.capture()
        if (visual != null) {
            return ToolResult(true, visual.toPromptText())
        }

        val state = ScreenReader.read()
            ?: return ToolResult(
                false,
                "",
                "Accessibility service is not running. Enable MobClaw in Settings → Accessibility.",
            )

        return ToolResult(
            true,
            "[VISUAL SCREENSHOT UNAVAILABLE — TEXT FALLBACK]\n${state.toPromptText()}",
        )
    }
}
