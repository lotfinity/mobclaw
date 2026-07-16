package com.mobclaw.android.observer

import android.util.Log
import kotlin.time.Duration

/**
 * Simple observer that logs events to Android logcat.
 */
class LogObserver : MobObserver {

    companion object {
        private const val TAG = "MobClaw"
    }

    override fun onAgentStart(task: String) {
        Log.i(TAG, "Agent started: $task")
    }

    override fun onToolCall(toolName: String, duration: Duration, success: Boolean) {
        Log.d(TAG, "Tool call: $toolName (${duration.inWholeMilliseconds}ms) success=$success")
    }

    override fun onScreenRead(packageName: String, nodeCount: Int) {
        Log.d(TAG, "Screen read: $packageName ($nodeCount nodes)")
    }

    override fun onAgentEnd(task: String, duration: Duration, success: Boolean) {
        Log.i(TAG, "Agent ended: success=$success (${duration.inWholeSeconds}s)")
    }

    override fun onError(message: String, throwable: Throwable?) {
        Log.e(TAG, "Error: $message", throwable)
    }
}
