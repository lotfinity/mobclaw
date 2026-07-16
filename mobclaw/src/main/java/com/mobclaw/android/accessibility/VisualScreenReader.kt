package com.mobclaw.android.accessibility

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Base64
import com.mobclaw.android.model.ScreenNode
import com.mobclaw.android.model.ScreenObservation
import com.mobclaw.android.model.ScreenObservationStore
import com.mobclaw.android.model.ScreenState
import com.mobclaw.android.model.VisualTarget
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Produces one atomic vision observation:
 * Accessibility tree + current window screenshot + numbered marker overlay.
 *
 * The Accessibility tree is used privately for target grounding. The LLM gets
 * the complete annotated screenshot and only a compact marker index.
 */
object VisualScreenReader {

    private const val MAX_TARGETS = 96
    private const val JPEG_QUALITY = 92

    suspend fun capture(excludeSelf: Boolean = true): ScreenObservation? {
        val state = ScreenReader.read(excludeSelf = excludeSelf) ?: return null
        val service = MobClawAccessibilityService.instance ?: return null
        val capture = service.captureCurrentWindowBitmap() ?: return null
        val screenshot = capture.bitmap

        val targets = buildTargets(state, capture.sourceBounds)
        val annotated = renderMarkers(
            source = screenshot,
            sourceBounds = capture.sourceBounds,
            targets = targets,
        )
        if (annotated !== screenshot && !screenshot.isRecycled) screenshot.recycle()

        val imageDataUrl = try {
            encodeAsDataUrl(annotated)
        } finally {
            if (!annotated.isRecycled) annotated.recycle()
        }

        val observation = ScreenObservation(
            snapshotId = state.snapshotId,
            packageName = state.packageName,
            activityName = state.activityName,
            width = capture.bitmap.width,
            height = capture.bitmap.height,
            imageDataUrl = imageDataUrl,
            targets = targets,
            capturedAt = state.timestamp,
        )
        ScreenObservationStore.current = observation
        return observation
    }

    private fun buildTargets(state: ScreenState, sourceBounds: Rect): List<VisualTarget> {
        val candidates = state.nodes.filter { node ->
            node.isVisibleToUser &&
                node.isEnabled &&
                node.bounds.width() > 0 &&
                node.bounds.height() > 0 &&
                Rect.intersects(sourceBounds, node.bounds) &&
                (node.isClickable || node.isLongClickable || node.isEditable || node.isCheckable)
        }

        // Android often exposes a clickable row and one or more duplicate nodes
        // with exactly the same bounds. Keep the most informative target only.
        val bestByBounds = linkedMapOf<String, ScreenNode>()
        candidates.forEach { node ->
            val b = node.bounds
            val key = "${b.left},${b.top},${b.right},${b.bottom}"
            val previous = bestByBounds[key]
            if (previous == null || targetScore(node) > targetScore(previous)) {
                bestByBounds[key] = node
            }
        }

        return bestByBounds.values
            .sortedWith(compareBy<ScreenNode>({ it.bounds.top }, { it.bounds.left }, { it.bounds.bottom }))
            .take(MAX_TARGETS)
            .mapIndexed { index, node ->
                VisualTarget(
                    markerId = index + 1,
                    sourceNodeId = node.id,
                    bounds = Rect(node.bounds),
                    label = labelFor(node, state.nodes),
                    resourceId = node.resourceId,
                    className = node.className,
                    canClick = node.isClickable || node.isCheckable || node.isEditable,
                    canLongClick = node.isLongClickable,
                    canInput = node.isEditable,
                    canToggle = node.isCheckable,
                    enabled = node.isEnabled,
                )
            }
    }

    private fun targetScore(node: ScreenNode): Int {
        var score = 0
        if (node.isEditable) score += 16
        if (node.isCheckable) score += 12
        if (node.isClickable) score += 8
        if (node.isLongClickable) score += 4
        if (!node.text.isNullOrBlank()) score += 3
        if (!node.contentDescription.isNullOrBlank()) score += 2
        if (!node.hintText.isNullOrBlank()) score += 1
        return score
    }

    /**
     * Prefer the target's own accessible name. Clickable list rows often keep
     * their visible label on child TextViews, so infer a short label from text
     * nodes geometrically contained inside the target bounds.
     */
    private fun labelFor(node: ScreenNode, allNodes: List<ScreenNode>): String? {
        val own = sequenceOf(
            node.text,
            node.contentDescription,
            node.hintText,
            node.stateDescription,
        ).firstOrNull { !it.isNullOrBlank() }

        val raw = own ?: allNodes.asSequence()
            .filter { child ->
                child.id != node.id &&
                    child.isVisibleToUser &&
                    node.bounds.contains(child.bounds) &&
                    (!child.text.isNullOrBlank() || !child.contentDescription.isNullOrBlank())
            }
            .flatMap { child -> sequenceOf(child.text, child.contentDescription) }
            .filterNotNull()
            .filter { it.isNotBlank() }
            .distinct()
            .take(2)
            .joinToString(" · ")
            .ifBlank { null }
            ?: node.resourceId?.substringAfterLast('/')

        return raw
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(100)
    }

    private fun renderMarkers(
        source: Bitmap,
        sourceBounds: Rect,
        targets: List<VisualTarget>,
    ): Bitmap {
        val mutable = source.copy(Bitmap.Config.ARGB_8888, true)
            ?: Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawBitmap(source, 0f, 0f, null)
            }
        val canvas = Canvas(mutable)

        val scaleX = mutable.width.toFloat() / max(1, sourceBounds.width())
        val scaleY = mutable.height.toFloat() / max(1, sourceBounds.height())
        val markerHeight = max(30f, mutable.width / 30f)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = markerHeight * 0.62f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 16, 16, 16)
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 214, 0)
            style = Paint.Style.STROKE
            strokeWidth = max(2f, mutable.width / 540f)
        }

        targets.forEach { target ->
            val number = target.markerId.toString()
            val textWidth = textPaint.measureText(number)
            val markerWidth = max(markerHeight, textWidth + markerHeight * 0.55f)
            val b = target.bounds
            val left = (b.left - sourceBounds.left) * scaleX
            val top = (b.top - sourceBounds.top) * scaleY
            val bottom = (b.bottom - sourceBounds.top) * scaleY

            var markerLeft = left + 3f
            var markerTop = if (top >= markerHeight + 5f) top - markerHeight - 3f else top + 3f

            if (markerLeft + markerWidth > mutable.width) markerLeft = mutable.width - markerWidth - 2f
            if (markerTop + markerHeight > mutable.height) markerTop = bottom - markerHeight - 2f
            markerLeft = markerLeft.coerceAtLeast(1f)
            markerTop = markerTop.coerceAtLeast(1f)

            val rect = RectF(markerLeft, markerTop, markerLeft + markerWidth, markerTop + markerHeight)
            val radius = markerHeight * 0.22f
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
            canvas.drawRoundRect(rect, radius, radius, strokePaint)

            val baseline = rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText(number, rect.left + (markerWidth - textWidth) / 2f, baseline, textPaint)
        }

        return mutable
    }

    private fun encodeAsDataUrl(bitmap: Bitmap): String {
        val bytes = ByteArrayOutputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) {
                "Could not encode annotated screenshot"
            }
            stream.toByteArray()
        }
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
