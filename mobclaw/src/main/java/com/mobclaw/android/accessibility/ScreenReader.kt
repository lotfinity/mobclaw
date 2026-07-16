package com.mobclaw.android.accessibility

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.mobclaw.android.model.ForegroundInfo
import com.mobclaw.android.model.ScreenNode
import com.mobclaw.android.model.ScreenState

/**
 * Traverses the AccessibilityNodeInfo tree and converts it into a rich
 * ScreenState for LLM consumption.
 */
object ScreenReader {

    private val excludedPackages = setOf(
        "com.mobclaw.android.testapp",
        "com.mobclaw.android",
    )

    fun read(
        excludeSelf: Boolean = true,
        previousSnapshotId: Long = 0,
    ): ScreenState? {
        val service = MobClawAccessibilityService.instance ?: return null
        val root = service.getRootNode() ?: return null

        val packageName = root.packageName?.toString() ?: "unknown"
        val activityName = root.className?.toString()?.substringAfterLast('.')
        val nodes = mutableListOf<ScreenNode>()
        var idCounter = 0

        val dm = service.resources.displayMetrics
        val displayWidth = dm.widthPixels
        val displayHeight = dm.heightPixels
        val displayRotation = service.resources.configuration?.orientation ?: 0

        fun traverse(node: AccessibilityNodeInfo, depth: Int) {
            val isVisible = node.isVisibleToUser
            val isInteractive = node.isClickable || node.isLongClickable ||
                node.isScrollable || node.isEditable || node.isCheckable ||
                node.isFocusable
            val hasText = !node.text.isNullOrBlank() ||
                !node.contentDescription.isNullOrBlank()
            val hasResourceId = !node.viewIdResourceName.isNullOrBlank()

            if (isVisible && (isInteractive || hasText || hasResourceId)) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                if (bounds.width() > 0 && bounds.height() > 0) {
                    val hintText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        node.hintText?.toString()
                    } else null

                    val stateDesc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        node.stateDescription?.toString()
                    } else null

                    nodes.add(
                        ScreenNode(
                            id = "n${idCounter++}",
                            className = node.className?.toString()
                                ?.substringAfterLast('.') ?: "View",
                            resourceId = node.viewIdResourceName,
                            text = node.text?.toString(),
                            contentDescription = node.contentDescription?.toString(),
                            hintText = hintText,
                            stateDescription = stateDesc,
                            bounds = bounds,
                            isClickable = node.isClickable,
                            isLongClickable = node.isLongClickable,
                            isScrollable = node.isScrollable,
                            isEditable = node.isEditable,
                            isCheckable = node.isCheckable,
                            isChecked = node.isChecked,
                            isSelected = node.isSelected,
                            isFocused = node.isFocused,
                            isEnabled = node.isEnabled,
                            isVisibleToUser = isVisible,
                            depth = depth,
                            childCount = node.childCount,
                            packageName = node.packageName?.toString(),
                            viewIdResourceName = node.viewIdResourceName,
                        )
                    )
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverse(child, depth + 1)
                child.recycle()
            }
        }

        try {
            traverse(root, 0)
        } finally {
            root.recycle()
        }

        val filteredNodes = if (excludeSelf) {
            nodes.filter { it.packageName !in excludedPackages }
        } else {
            nodes
        }

        val newSnapshotId = System.currentTimeMillis()
        return ScreenState(
            packageName = packageName,
            activityName = activityName,
            nodes = filteredNodes,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            displayRotation = displayRotation,
            snapshotId = newSnapshotId,
        )
    }

    fun getForegroundInfo(): ForegroundInfo? {
        val service = MobClawAccessibilityService.instance ?: return null
        val root = service.getRootNode() ?: return null

        val pkg = root.packageName?.toString() ?: "unknown"
        val activity = root.className?.toString()?.substringAfterLast('.')
        root.recycle()

        return ForegroundInfo(
            packageName = pkg,
            activityName = activity,
        )
    }
}
