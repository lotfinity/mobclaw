package com.mobclaw.android.accessibility

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Central AccessibilityService for MobClaw.
 *
 * Must be declared in the app's AndroidManifest.xml and enabled by the user
 * in Settings → Accessibility. Provides global UI tree access, screenshot
 * capture, and gesture dispatch.
 */
class MobClawAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Events are consumed on-demand via ScreenReader, not reactively.
    }

    override fun onInterrupt() {
        // No-op
    }

    /** Get the root AccessibilityNodeInfo for the current active window. */
    fun getRootNode(): AccessibilityNodeInfo? = rootInActiveWindow

    /**
     * Captures only the active application window on Android 14+, so MobClaw's
     * own accessibility overlay is not painted into the model image. Android
     * 11-13 fall back to a full default-display screenshot.
     */
    @SuppressLint("NewApi")
    suspend fun captureCurrentWindowBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        val root = getRootNode()
        val windowId = root?.windowId
        root?.recycle()

        return suspendCancellableCoroutine { continuation ->
            val callback = object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hardwareBuffer = screenshot.hardwareBuffer
                    val bitmap = try {
                        Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (_: Throwable) {
                        null
                    } finally {
                        hardwareBuffer.close()
                    }

                    if (continuation.isActive) continuation.resume(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && windowId != null) {
                    takeScreenshotOfWindow(windowId, mainExecutor, callback)
                } else {
                    takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
                }
            } catch (_: Throwable) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    companion object {
        /** Singleton reference to the running service instance. */
        @Volatile
        var instance: MobClawAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
