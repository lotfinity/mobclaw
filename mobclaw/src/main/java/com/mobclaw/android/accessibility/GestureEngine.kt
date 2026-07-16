package com.mobclaw.android.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Translates logical agent actions into Android gestures.
 */
object GestureEngine {

    private val service: MobClawAccessibilityService?
        get() = MobClawAccessibilityService.instance

    @Volatile
    var lastScreenState: com.mobclaw.android.model.ScreenState? = null

    @Volatile
    var lastSnapshotId: Long = 0

    fun isStale(requestedSnapshotId: Long): Boolean {
        return requestedSnapshotId != lastSnapshotId
    }

    suspend fun clickNode(nodeId: String, snapshotId: Long = 0): Boolean {
        if (snapshotId > 0 && isStale(snapshotId)) {
            return false
        }

        val service = service ?: return false
        val root = service.getRootNode() ?: return false

        var idCounter = 0
        val target = findNodeById(root, nodeId, { idCounter++ })
        if (target != null) {
            if (target.isClickable) {
                val result = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                target.recycle()
                root.recycle()
                return result
            }
            val bounds = Rect()
            target.getBoundsInScreen(bounds)
            target.recycle()
            root.recycle()
            return tap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        }

        root.recycle()
        return tapSavedBounds(nodeId)
    }

    suspend fun longClickNode(nodeId: String, snapshotId: Long = 0): Boolean {
        if (snapshotId > 0 && isStale(snapshotId)) {
            return false
        }

        val service = service ?: return false
        val root = service.getRootNode() ?: return false

        var idCounter = 0
        val target = findNodeById(root, nodeId, { idCounter++ })
        if (target != null) {
            if (target.isLongClickable) {
                val result = target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                target.recycle()
                root.recycle()
                return result
            }
            val bounds = Rect()
            target.getBoundsInScreen(bounds)
            target.recycle()
            root.recycle()
            return longPress(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        }

        root.recycle()
        return longPressSavedBounds(nodeId)
    }

    private suspend fun tapSavedBounds(nodeId: String): Boolean {
        val node = lastScreenState?.nodes?.find { it.id == nodeId } ?: return false
        val b = node.bounds
        return tap(b.centerX().toFloat(), b.centerY().toFloat())
    }

    private suspend fun longPressSavedBounds(nodeId: String): Boolean {
        val node = lastScreenState?.nodes?.find { it.id == nodeId } ?: return false
        val b = node.bounds
        return longPress(b.centerX().toFloat(), b.centerY().toFloat())
    }

    suspend fun longPress(x: Float, y: Float): Boolean {
        val service = service ?: return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()
        return dispatchGesture(service, gesture)
    }

    suspend fun tap(x: Float, y: Float): Boolean {
        val service = service ?: return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        return dispatchGesture(service, gesture)
    }

    fun inputText(nodeId: String, text: String, snapshotId: Long = 0): Boolean {
        if (snapshotId > 0 && isStale(snapshotId)) {
            return false
        }

        val service = service ?: return false
        val root = service.getRootNode() ?: return false

        var idCounter = 0
        val target = findNodeById(root, nodeId, { idCounter++ })
        if (target != null) {
            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val result = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            target.recycle()
            root.recycle()
            return result
        }

        root.recycle()
        return false
    }

    suspend fun scroll(direction: String): Boolean {
        val service = service ?: return false
        val dm = service.resources.displayMetrics
        val centerX = dm.widthPixels / 2f
        val centerY = dm.heightPixels / 2f
        val distance = dm.heightPixels / 3f

        val path = Path()
        when (direction.lowercase()) {
            "up" -> {
                path.moveTo(centerX, centerY)
                path.lineTo(centerX, centerY + distance)
            }
            "down" -> {
                path.moveTo(centerX, centerY)
                path.lineTo(centerX, centerY - distance)
            }
            "left" -> {
                path.moveTo(centerX, centerY)
                path.lineTo(centerX + distance, centerY)
            }
            "right" -> {
                path.moveTo(centerX, centerY)
                path.lineTo(centerX - distance, centerY)
            }
            else -> return false
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        return dispatchGesture(service, gesture)
    }

    fun systemAction(action: String): Boolean {
        val service = service ?: return false
        val actionId = when (action.lowercase()) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "power_dialog" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            else -> return false
        }
        return service.performGlobalAction(actionId)
    }

    private fun findNodeById(
        root: AccessibilityNodeInfo,
        targetId: String,
        counter: () -> Int,
    ): AccessibilityNodeInfo? {
        val isVisible = root.isVisibleToUser
        val isInteractive = root.isClickable || root.isLongClickable ||
            root.isScrollable || root.isEditable || root.isCheckable ||
            root.isFocusable
        val hasText = !root.text.isNullOrBlank() ||
            !root.contentDescription.isNullOrBlank()
        val hasResourceId = !root.viewIdResourceName.isNullOrBlank()

        val bounds = Rect()
        root.getBoundsInScreen(bounds)
        val hasSize = bounds.width() > 0 && bounds.height() > 0

        val meetsInclusion = isVisible && hasSize && (isInteractive || hasText || hasResourceId)

        if (meetsInclusion) {
            val currentId = "n${counter()}"
            if (currentId == targetId) {
                return root
            }
        }

        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeById(child, targetId, counter)
            if (found != null) return found
            child.recycle()
        }

        return null
    }

    private suspend fun dispatchGesture(
        service: AccessibilityService,
        gesture: GestureDescription,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }
        service.dispatchGesture(gesture, callback, null)
    }
}
