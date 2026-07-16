package com.mobclaw.android.core

import com.mobclaw.android.dispatcher.JsonActionDispatcher
import com.mobclaw.android.model.ChatMessage
import com.mobclaw.android.model.ChatResponse
import com.mobclaw.android.model.ToolCall
import com.mobclaw.android.model.ToolResult
import com.mobclaw.android.observer.MobObserver
import com.mobclaw.android.provider.LlmProvider
import com.mobclaw.android.tool.MobTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration

private class ScriptedProvider(
    private val responses: MutableList<ChatResponse>,
) : LlmProvider {
    data class ChatCall(
        val tools: List<com.mobclaw.android.model.ToolSpec>?,
    )

    val calls = mutableListOf<ChatCall>()

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<com.mobclaw.android.model.ToolSpec>?,
        model: String?,
        temperature: Double,
    ): ChatResponse {
        calls.add(ChatCall(tools))
        return responses.removeAt(0)
    }

    override fun supportsNativeTools(): Boolean = true
}

private class FakeTool(
    override val name: String,
    override val description: String,
    private val executeImpl: suspend (JsonObject) -> ToolResult,
) : MobTool {
    override fun parametersSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
    }

    override suspend fun execute(args: JsonObject): ToolResult = executeImpl(args)
}

private class RecordingObserver : MobObserver {
    var agentStartCount = 0
    var agentEndCount = 0
    var lastSuccess: Boolean? = null
    val toolCalls = mutableListOf<String>()
    val screenReads = mutableListOf<Pair<String, Int>>()
    val errors = mutableListOf<String>()
    var modelRequestCount = 0
    var modelResponseCount = 0
    var detailedToolCallCount = 0
    var stuckDetectedCount = 0
    var verificationStartedCount = 0
    var verificationCompletedCount = 0

    override fun onAgentStart(task: String) {
        agentStartCount++
    }

    override fun onToolCall(toolName: String, duration: Duration, success: Boolean) {
        toolCalls.add(toolName)
    }

    override fun onScreenRead(packageName: String, nodeCount: Int) {
        screenReads.add(packageName to nodeCount)
    }

    override fun onAgentEnd(task: String, duration: Duration, success: Boolean) {
        agentEndCount++
        lastSuccess = success
    }

    override fun onError(message: String, throwable: Throwable?) {
        errors.add(message)
    }

    override fun onModelRequest(
        iteration: Int,
        messages: List<ChatMessage>,
        toolNames: List<String>,
        model: String?,
        temperature: Double,
    ) {
        modelRequestCount++
    }

    override fun onModelResponse(
        iteration: Int,
        response: ChatResponse,
        duration: Duration,
    ) {
        modelResponseCount++
    }

    override fun onToolCallDetail(
        iteration: Int,
        toolName: String,
        arguments: String,
        output: String,
        error: String?,
        duration: Duration,
        success: Boolean,
    ) {
        detailedToolCallCount++
    }

    override fun onStuckDetected(iteration: Int, consecutiveNoChange: Int) {
        stuckDetectedCount++
    }

    override fun onVerificationStarted(snapshotId: Long) {
        verificationStartedCount++
    }

    override fun onVerificationCompleted(passed: Boolean, notes: String) {
        verificationCompletedCount++
    }
}

class MobAgentTest {

    @Test
    fun execute_returnsSuccessOnFinish() = runBlocking {
        val provider = ScriptedProvider(
            mutableListOf(
                ChatResponse(
                    text = "Done",
                    toolCalls = listOf(ToolCall("1", "finish", "{\"reason\":\"all done\"}")),
                ),
            ),
        )

        val finishTool = FakeTool(
            name = "finish",
            description = "finish",
            executeImpl = { args ->
                val reason = args["reason"]?.jsonPrimitive?.content ?: "complete"
                ToolResult(true, "TASK_COMPLETE: $reason")
            },
        )

        val observer = RecordingObserver()
        val agent = MobAgent.builder()
            .provider(provider)
            .tools(listOf(finishTool))
            .observer(observer)
            .dispatcher(JsonActionDispatcher())
            .config(MobClawConfig(maxIterations = 2, autoScreenRead = false, verifyOnFinish = false))
            .build()

        val result = agent.execute("finish task")

        assertTrue(result.success)
        assertEquals("TASK_COMPLETE: all done", result.message)
        assertEquals(1, result.iterations)
        assertEquals(1, provider.calls.size)
        assertEquals(1, observer.toolCalls.size)
        assertEquals("finish", observer.toolCalls[0])
        assertEquals(1, observer.agentEndCount)
        assertEquals(true, observer.lastSuccess)
        assertEquals(1, observer.modelRequestCount)
        assertEquals(1, observer.modelResponseCount)
        assertEquals(1, observer.detailedToolCallCount)
    }

    @Test
    fun execute_withoutActionsLoopsUntilIterationLimit() = runBlocking {
        val provider = ScriptedProvider(
            mutableListOf(
                ChatResponse(text = "Need more context", toolCalls = emptyList()),
                ChatResponse(text = "Still not done", toolCalls = emptyList()),
            ),
        )

        val agent = MobAgent.builder()
            .provider(provider)
            .tools(emptyList())
            .observer(RecordingObserver())
            .dispatcher(JsonActionDispatcher())
            .config(MobClawConfig(maxIterations = 2, autoScreenRead = false))
            .build()

        val result = agent.execute("loop task")

        assertFalse(result.success)
        assertEquals("Agent exceeded maximum iterations (2)", result.message)
        assertEquals(2, result.iterations)
        assertEquals(2, provider.calls.size)
    }

    @Test
    fun execute_withFailToolReturnsFailureResult() = runBlocking {
        val provider = ScriptedProvider(
            mutableListOf(
                ChatResponse(
                    toolCalls = listOf(ToolCall("1", "fail", "{\"reason\":\"network\"}")),
                ),
            ),
        )

        val failTool = FakeTool(
            name = "fail",
            description = "fail",
            executeImpl = { args ->
                ToolResult(false, "TASK_FAILED: ${args["reason"]?.jsonPrimitive?.content}")
            },
        )

        val observer = RecordingObserver()
        val agent = MobAgent.builder()
            .provider(provider)
            .tools(listOf(failTool))
            .observer(observer)
            .dispatcher(JsonActionDispatcher())
            .config(MobClawConfig(maxIterations = 2, autoScreenRead = false))
            .build()

        val result = agent.execute("fail task")

        assertFalse(result.success)
        assertEquals("Error: TASK_FAILED: network", result.message)
        assertEquals(1, result.iterations)
        assertEquals(1, observer.toolCalls.size)
        assertEquals("fail", observer.toolCalls[0])
        assertEquals(1, observer.agentEndCount)
        assertEquals(false, observer.lastSuccess)
    }

    @Test
    fun execute_withCancelStopsOnNextIteration() = runBlocking {
        val provider = ScriptedProvider(
            mutableListOf(
                ChatResponse(toolCalls = listOf(ToolCall("1", "cancel", "{}"))),
                ChatResponse(toolCalls = listOf(ToolCall("2", "finish", "{}"))),
            ),
        )

        lateinit var agent: MobAgent
        val cancelTool = FakeTool(
            name = "cancel",
            description = "cancel",
            executeImpl = {
                agent.cancel()
                ToolResult(true, "ok")
            },
        )

        val observer = RecordingObserver()
        agent = MobAgent.builder()
            .provider(provider)
            .tools(listOf(cancelTool))
            .observer(observer)
            .dispatcher(JsonActionDispatcher())
            .config(MobClawConfig(autoScreenRead = false, maxIterations = 5))
            .build()

        val result = agent.execute("cancel task")

        assertFalse(result.success)
        assertEquals("Agent stopped by user", result.message)
        assertEquals(1, result.iterations)
        assertEquals(1, provider.calls.size)
        assertEquals(1, observer.toolCalls.size)
        assertEquals(false, observer.lastSuccess)
    }

    @Test
    fun execute_withProviderErrorReturnsFailure() = runBlocking {
        val provider = object : LlmProvider {
            override suspend fun chat(
                messages: List<ChatMessage>,
                tools: List<com.mobclaw.android.model.ToolSpec>?,
                model: String?,
                temperature: Double,
            ): ChatResponse {
                throw IllegalStateException("provider broken")
            }

            override fun supportsNativeTools(): Boolean = true
        }

        val observer = RecordingObserver()
        val agent = MobAgent.builder()
            .provider(provider)
            .tools(emptyList())
            .observer(observer)
            .dispatcher(JsonActionDispatcher())
            .config(MobClawConfig(maxIterations = 1, autoScreenRead = false))
            .build()

        val result = agent.execute("broken task")

        assertFalse(result.success)
        assertEquals("LLM error: provider broken", result.message)
        assertEquals(1, observer.errors.size)
        assertEquals("LLM call failed at iteration 0", observer.errors[0])
        assertEquals(1, observer.modelRequestCount)
        assertEquals(0, observer.modelResponseCount)
        assertEquals(1, observer.agentEndCount)
        assertEquals(false, observer.lastSuccess)
    }

    @Test
    fun execute_providesTraceOnSuccess() = runBlocking {
        val provider = ScriptedProvider(
            mutableListOf(
                ChatResponse(
                    text = "Done",
                    toolCalls = listOf(ToolCall("1", "finish", "{\"reason\":\"complete\"}")),
                ),
            ),
        )

        val finishTool = FakeTool(
            name = "finish",
            description = "finish",
            executeImpl = { ToolResult(true, "TASK_COMPLETE") },
        )

        val agent = MobAgent.builder()
            .provider(provider)
            .tools(listOf(finishTool))
            .observer(RecordingObserver())
            .dispatcher(JsonActionDispatcher())
            .config(MobClawConfig(maxIterations = 5, autoScreenRead = false, verifyOnFinish = false))
            .build()

        val result = agent.execute("test task")

        assertTrue(result.success)
        assertTrue(result.trace != null)
        assertEquals("test task", result.trace?.task)
        assertTrue(result.trace?.success == true)
    }

    @Test
    fun execute_maxActionsPerTurn_limitsActions() = runBlocking {
        val provider = ScriptedProvider(
            mutableListOf(
                ChatResponse(
                    toolCalls = listOf(
                        ToolCall("1", "click", "{\"node_id\":\"n0\"}"),
                        ToolCall("2", "click", "{\"node_id\":\"n1\"}"),
                    ),
                ),
                ChatResponse(
                    text = "Done",
                    toolCalls = listOf(ToolCall("3", "finish", "{\"reason\":\"done\"}")),
                ),
            ),
        )

        val clickTool = FakeTool(
            name = "click",
            description = "click",
            executeImpl = { ToolResult(true, "clicked") },
        )
        val finishTool = FakeTool(
            name = "finish",
            description = "finish",
            executeImpl = { ToolResult(true, "TASK_COMPLETE") },
        )

        val agent = MobAgent.builder()
            .provider(provider)
            .tools(listOf(clickTool, finishTool))
            .observer(RecordingObserver())
            .dispatcher(JsonActionDispatcher())
            .config(MobClawConfig(maxIterations = 5, autoScreenRead = false, maxActionsPerTurn = 1, verifyOnFinish = false))
            .build()

        val result = agent.execute("multi action")

        assertTrue(result.success)
        // First iteration: 2 tool calls but maxActionsPerTurn=1, only click n0 executes
        // Second iteration: finish call executes
        assertEquals(2, result.iterations)
    }
}
