package com.mobclaw.android.observer

import com.mobclaw.android.model.ChatMessage
import com.mobclaw.android.model.ChatResponse
import com.mobclaw.android.model.ExecutionTrace
import com.mobclaw.android.model.ForegroundInfo
import com.mobclaw.android.model.ScreenState
import kotlin.time.Duration

/** Fans each agent event out to multiple observers. */
class CompositeObserver(
    private val observers: List<MobObserver>,
) : MobObserver {
    override fun onAgentStart(task: String) = observers.forEach { it.onAgentStart(task) }

    override fun onToolCall(toolName: String, duration: Duration, success: Boolean) =
        observers.forEach { it.onToolCall(toolName, duration, success) }

    override fun onScreenRead(packageName: String, nodeCount: Int) =
        observers.forEach { it.onScreenRead(packageName, nodeCount) }

    override fun onAgentEnd(task: String, duration: Duration, success: Boolean) =
        observers.forEach { it.onAgentEnd(task, duration, success) }

    override fun onError(message: String, throwable: Throwable?) =
        observers.forEach { it.onError(message, throwable) }

    override fun onModelRequest(
        iteration: Int,
        messages: List<ChatMessage>,
        toolNames: List<String>,
        model: String?,
        temperature: Double,
    ) = observers.forEach { it.onModelRequest(iteration, messages, toolNames, model, temperature) }

    override fun onModelResponse(
        iteration: Int,
        response: ChatResponse,
        duration: Duration,
    ) = observers.forEach { it.onModelResponse(iteration, response, duration) }

    override fun onToolCallDetail(
        iteration: Int,
        toolName: String,
        arguments: String,
        output: String,
        error: String?,
        duration: Duration,
        success: Boolean,
    ) = observers.forEach {
        it.onToolCallDetail(iteration, toolName, arguments, output, error, duration, success)
    }

    override fun onScreenReadDetail(
        iteration: Int,
        packageName: String,
        nodeCount: Int,
        content: String,
    ) = observers.forEach { it.onScreenReadDetail(iteration, packageName, nodeCount, content) }

    override fun onScreenState(state: ScreenState) = observers.forEach { it.onScreenState(state) }

    override fun onReasoning(iteration: Int, text: String) =
        observers.forEach { it.onReasoning(iteration, text) }

    override fun onActionPending(
        iteration: Int,
        toolName: String,
        arguments: Map<String, String>,
    ) = observers.forEach { it.onActionPending(iteration, toolName, arguments) }

    override fun onForegroundChanged(foreground: ForegroundInfo) =
        observers.forEach { it.onForegroundChanged(foreground) }

    override fun onStuckDetected(iteration: Int, consecutiveNoChange: Int) =
        observers.forEach { it.onStuckDetected(iteration, consecutiveNoChange) }

    override fun onVerificationStarted(snapshotId: Long) =
        observers.forEach { it.onVerificationStarted(snapshotId) }

    override fun onVerificationCompleted(passed: Boolean, notes: String) =
        observers.forEach { it.onVerificationCompleted(passed, notes) }

    override fun onTraceComplete(trace: ExecutionTrace) =
        observers.forEach { it.onTraceComplete(trace) }
}
