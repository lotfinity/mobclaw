package com.mobclaw.android.observer

import com.mobclaw.android.model.ChatMessage
import com.mobclaw.android.model.ChatResponse
import com.mobclaw.android.model.ExecutionTrace
import com.mobclaw.android.model.ForegroundInfo
import com.mobclaw.android.model.ScreenState
import kotlin.time.Duration

/**
 * Observer interface for recording agent events.
 */
interface MobObserver {

    fun onAgentStart(task: String)

    fun onToolCall(toolName: String, duration: Duration, success: Boolean)

    fun onScreenRead(packageName: String, nodeCount: Int)

    fun onAgentEnd(task: String, duration: Duration, success: Boolean)

    fun onError(message: String, throwable: Throwable? = null)

    fun onModelRequest(
        iteration: Int,
        messages: List<ChatMessage>,
        toolNames: List<String>,
        model: String?,
        temperature: Double,
    ) = Unit

    fun onModelResponse(
        iteration: Int,
        response: ChatResponse,
        duration: Duration,
    ) = Unit

    fun onToolCallDetail(
        iteration: Int,
        toolName: String,
        arguments: String,
        output: String,
        error: String?,
        duration: Duration,
        success: Boolean,
    ) = Unit

    fun onScreenReadDetail(
        iteration: Int,
        packageName: String,
        nodeCount: Int,
        content: String,
    ) = Unit

    fun onScreenState(state: ScreenState) = Unit

    fun onReasoning(iteration: Int, text: String) = Unit

    fun onActionPending(
        iteration: Int,
        toolName: String,
        arguments: Map<String, String>,
    ) = Unit

    fun onForegroundChanged(foreground: ForegroundInfo) = Unit

    fun onStuckDetected(iteration: Int, consecutiveNoChange: Int) = Unit

    fun onVerificationStarted(snapshotId: Long) = Unit

    fun onVerificationCompleted(passed: Boolean, notes: String) = Unit

    fun onTraceComplete(trace: ExecutionTrace) = Unit
}
