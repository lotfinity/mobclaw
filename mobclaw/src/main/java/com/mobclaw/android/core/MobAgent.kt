package com.mobclaw.android.core

import com.mobclaw.android.accessibility.GestureEngine
import com.mobclaw.android.accessibility.ScreenReader
import com.mobclaw.android.dispatcher.ActionDispatcher
import com.mobclaw.android.dispatcher.JsonActionDispatcher
import com.mobclaw.android.memory.InMemoryStorage
import com.mobclaw.android.memory.MobMemory
import com.mobclaw.android.model.*
import com.mobclaw.android.observer.LogObserver
import com.mobclaw.android.observer.MobObserver
import com.mobclaw.android.provider.LlmProvider
import com.mobclaw.android.skill.SkillRegistry
import com.mobclaw.android.tool.*
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * MobClaw Agent — the core orchestrator.
 *
 * Loop: ScreenRead -> Build messages -> LLM chat -> Parse actions -> Execute tools -> Repeat
 */
class MobAgent private constructor(
    private val provider: LlmProvider,
    private val tools: List<MobTool>,
    private val toolSpecs: List<ToolSpec>,
    private val dispatcher: ActionDispatcher,
    private val memory: MobMemory,
    private val observer: MobObserver,
    private val config: MobClawConfig,
    private val skillRegistry: SkillRegistry,
    private val loopContext: LoopContext,
) {
    private val history = mutableListOf<ConversationMessage>()
    private val traceEntries = mutableListOf<ActionTraceEntry>()
    private var traceSequence = 0

    @Volatile
    private var cancelled = false

    private var previousSnapshotId: Long = 0
    private var consecutiveNoChange = 0
    private var lastScreenHash = 0

    fun cancel() {
        cancelled = true
    }

    suspend fun execute(task: String): AgentResult {
        cancelled = false
        loopContext.reset()
        traceEntries.clear()
        traceSequence = 0
        consecutiveNoChange = 0
        lastScreenHash = 0
        val mark = TimeSource.Monotonic.markNow()
        observer.onAgentStart(task)
        history.clear()

        val matchedSkills = skillRegistry.findMatchingSkills(task)
        val allTools = if (matchedSkills.isEmpty()) tools
        else {
            val skillTools = matchedSkills.flatMap { it.additionalTools() }
            if (skillTools.isEmpty()) tools
            else (tools + skillTools).distinctBy { it.name }
        }
        val allToolSpecs = allTools.map { it.spec() }

        val systemPrompt = buildSystemPrompt(task, matchedSkills)
        history.add(ConversationMessage.Chat(ChatMessage.system(systemPrompt)))

        val screenTool = allTools.filterIsInstance<ScreenReadTool>().firstOrNull()
        val initialScreen = screenTool?.execute(kotlinx.serialization.json.buildJsonObject {})
        val screenContext = if (initialScreen?.success == true) {
            val screenState = ScreenReader.read(
                excludeSelf = config.excludeSelfFromScreen,
                previousSnapshotId = previousSnapshotId,
            )
            if (screenState != null) {
                GestureEngine.lastScreenState = screenState
                GestureEngine.lastSnapshotId = screenState.snapshotId
                previousSnapshotId = screenState.snapshotId
                lastScreenHash = screenState.nodes.hashCode()
                observer.onScreenState(screenState)
            }
            val packageName = screenState?.packageName ?: "unknown"
            val nodeCount = screenState?.nodes?.size ?: 0
            observer.onScreenRead(packageName, nodeCount)
            observer.onScreenReadDetail(
                iteration = -1,
                packageName = packageName,
                nodeCount = nodeCount,
                content = initialScreen.output,
            )
            "\n\nCurrent screen:\n${initialScreen.output}"
        } else {
            "\n\n(Screen read unavailable — accessibility service may not be enabled)"
        }

        val userMessage = "Task: $task$screenContext"
        history.add(ConversationMessage.Chat(ChatMessage.user(userMessage)))

        for (iteration in 0 until config.maxIterations) {
            if (cancelled) {
                val trace = buildTrace(task, mark, false)
                observer.onTraceComplete(trace)
                observer.onAgentEnd(task, mark.elapsedNow(), false)
                return AgentResult(
                    success = false,
                    message = "Agent stopped by user",
                    iterations = iteration,
                    duration = mark.elapsedNow(),
                    trace = trace,
                )
            }

            val messages = dispatcher.toProviderMessages(history)
            observer.onModelRequest(
                iteration = iteration,
                messages = messages,
                toolNames = allToolSpecs.map { it.name },
                model = config.model,
                temperature = config.temperature,
            )
            val providerMark = TimeSource.Monotonic.markNow()
            val response = try {
                provider.chat(
                    messages = messages,
                    tools = if (dispatcher.shouldSendToolSpecs() && provider.supportsNativeTools()) {
                        allToolSpecs
                    } else null,
                    model = config.model,
                    temperature = config.temperature,
                )
            } catch (e: Exception) {
                observer.onError("LLM call failed at iteration $iteration", e)
                val trace = buildTrace(task, mark, false)
                observer.onTraceComplete(trace)
                observer.onAgentEnd(task, mark.elapsedNow(), false)
                return AgentResult(
                    success = false,
                    message = "LLM error: ${e.message}",
                    iterations = iteration + 1,
                    duration = mark.elapsedNow(),
                    trace = trace,
                )
            }
            observer.onModelResponse(iteration, response, providerMark.elapsedNow())

            val (text, actions) = dispatcher.parseResponse(response)

            if (actions.isEmpty()) {
                val assistantText = text.ifEmpty { response.text.orEmpty() }
                    .trim()
                    .takeUnless { it.equals("null", ignoreCase = true) }
                    .orEmpty()
                if (assistantText.isNotBlank()) {
                    history.add(ConversationMessage.Chat(ChatMessage.assistant(assistantText)))
                    observer.onReasoning(iteration, assistantText)
                }

                history.add(ConversationMessage.Chat(ChatMessage.user(
                    "No action was issued. Choose exactly one available tool now. " +
                        "Use finish only when the latest screen visibly proves every requested result."
                )))
                continue
            }

            if (text.isNotEmpty()) {
                history.add(ConversationMessage.Chat(ChatMessage.assistant(text)))
                observer.onReasoning(iteration, text)
            }
            history.add(ConversationMessage.AssistantToolCalls(
                text = text.takeIf { it.isNotBlank() },
                toolCalls = response.toolCalls,
            ))

            val actionsToExecute = actions.take(config.maxActionsPerTurn)
            val results = mutableListOf<ToolExecutionResult>()

            for (action in actionsToExecute) {
                val argsMap = action.arguments.entries.associate { (key, value) ->
                    key to (value as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                }
                observer.onActionPending(iteration, action.name, argsMap)

                val toolMark = TimeSource.Monotonic.markNow()
                val foregroundBefore = ScreenReader.getForegroundInfo()
                foregroundBefore?.let { observer.onForegroundChanged(it) }

                val tool = allTools.find { it.name == action.name }

                val result = if (tool != null) {
                    val toolResult = try {
                        tool.execute(action.arguments)
                    } catch (e: Exception) {
                        ToolResult(false, "", "Error executing ${action.name}: ${e.message}")
                    }

                    val toolDuration = toolMark.elapsedNow()
                    observer.onToolCall(action.name, toolDuration, toolResult.success)
                    observer.onToolCallDetail(
                        iteration = iteration,
                        toolName = action.name,
                        arguments = action.arguments.toString(),
                        output = toolResult.output,
                        error = toolResult.error,
                        duration = toolDuration,
                        success = toolResult.success,
                    )

                    val foregroundAfter = ScreenReader.getForegroundInfo()

                    traceEntries.add(
                        ActionTraceEntry(
                            sequence = traceSequence++,
                            iteration = iteration,
                            timestamp = System.currentTimeMillis(),
                            action = action.name,
                            arguments = argsMap,
                            resultSuccess = toolResult.success,
                            resultOutput = toolResult.output,
                            resultError = toolResult.error,
                            foregroundBefore = foregroundBefore,
                            foregroundAfter = foregroundAfter,
                            snapshotId = previousSnapshotId,
                            durationMs = toolDuration.inWholeMilliseconds,
                        )
                    )

                    ToolExecutionResult(
                        name = action.name,
                        output = if (toolResult.success) toolResult.output
                        else "Error: ${toolResult.error ?: toolResult.output}",
                        success = toolResult.success,
                        toolCallId = action.toolCallId,
                    )
                } else {
                    val toolDuration = toolMark.elapsedNow()
                    observer.onToolCall(action.name, toolDuration, false)
                    observer.onToolCallDetail(
                        iteration = iteration,
                        toolName = action.name,
                        arguments = action.arguments.toString(),
                        output = "",
                        error = "Unknown tool: ${action.name}",
                        duration = toolDuration,
                        success = false,
                    )

                    traceEntries.add(
                        ActionTraceEntry(
                            sequence = traceSequence++,
                            iteration = iteration,
                            timestamp = System.currentTimeMillis(),
                            action = action.name,
                            arguments = argsMap,
                            resultSuccess = false,
                            resultOutput = "",
                            resultError = "Unknown tool: ${action.name}",
                            foregroundBefore = foregroundBefore,
                            foregroundAfter = null,
                            snapshotId = previousSnapshotId,
                            durationMs = toolDuration.inWholeMilliseconds,
                        )
                    )

                    ToolExecutionResult(
                        name = action.name,
                        output = "Unknown tool: ${action.name}",
                        success = false,
                        toolCallId = action.toolCallId,
                    )
                }

                results.add(result)

                if (action.name == "finish") {
                    val formatted = dispatcher.formatResults(results)
                    history.add(formatted)

                    if (config.verifyOnFinish) {
                        val verified = performVerification(task, iteration, mark)
                        if (!verified) {
                            history.add(ConversationMessage.Chat(ChatMessage.user(
                                "VERIFICATION FAILED: The task completion could not be verified. " +
                                "The screen does not show evidence that the task was completed. " +
                                "Please read the screen and take corrective action, or call fail if the task cannot be completed."
                            )))
                            continue
                        }
                    }

                    val trace = buildTrace(task, mark, true)
                    observer.onTraceComplete(trace)
                    observer.onAgentEnd(task, mark.elapsedNow(), true)
                    returnToHostApp()
                    return AgentResult(
                        success = true,
                        message = result.output,
                        iterations = iteration + 1,
                        duration = mark.elapsedNow(),
                        trace = trace,
                    )
                }
                if (action.name == "fail") {
                    val formatted = dispatcher.formatResults(results)
                    history.add(formatted)
                    val trace = buildTrace(task, mark, false)
                    observer.onTraceComplete(trace)
                    observer.onAgentEnd(task, mark.elapsedNow(), false)
                    return AgentResult(
                        success = false,
                        message = result.output,
                        iterations = iteration + 1,
                        duration = mark.elapsedNow(),
                        trace = trace,
                    )
                }
            }

            val formatted = dispatcher.formatResults(results)
            history.add(formatted)

            if (config.autoScreenRead && screenTool != null) {
                delay(config.stabilityWaitMs)
                val newScreen = screenTool.execute(kotlinx.serialization.json.buildJsonObject {})
                if (newScreen.success) {
                    val screenState = ScreenReader.read(
                        excludeSelf = config.excludeSelfFromScreen,
                        previousSnapshotId = previousSnapshotId,
                    )
                    if (screenState != null) {
                        GestureEngine.lastScreenState = screenState
                        GestureEngine.lastSnapshotId = screenState.snapshotId
                        previousSnapshotId = screenState.snapshotId

                        val currentHash = screenState.nodes.hashCode()
                        if (currentHash == lastScreenHash) {
                            consecutiveNoChange++
                            if (consecutiveNoChange >= config.maxStuckCount) {
                                observer.onStuckDetected(iteration, consecutiveNoChange)
                                history.add(ConversationMessage.Chat(ChatMessage.user(
                                    "The last approach caused no visible change. Do not repeat it. " +
                                        "Choose one different action from the latest observation."
                                )))
                                consecutiveNoChange = 0
                            }
                        } else {
                            consecutiveNoChange = 0
                            lastScreenHash = currentHash
                        }

                        observer.onScreenState(screenState)
                    }

                    val loopStatus = if (loopContext.isActive) "\n${loopContext.statusText()}" else ""
                    history.add(ConversationMessage.Chat(
                        ChatMessage.user(
                            "[Updated observation]\n${newScreen.output}\n\n" +
                                "Task still in progress: $task" + loopStatus
                        )
                    ))

                    val pkg = screenState?.packageName ?: "unknown"
                    val nodeCount = screenState?.nodes?.size ?: 0
                    observer.onScreenRead(pkg, nodeCount)
                    observer.onScreenReadDetail(
                        iteration = iteration,
                        packageName = pkg,
                        nodeCount = nodeCount,
                        content = newScreen.output,
                    )
                }
            }

            trimHistory()
        }

        val trace = buildTrace(task, mark, false)
        observer.onTraceComplete(trace)
        observer.onAgentEnd(task, mark.elapsedNow(), false)
        return AgentResult(
            success = false,
            message = "Agent exceeded maximum iterations (${config.maxIterations})",
            iterations = config.maxIterations,
            duration = mark.elapsedNow(),
            trace = trace,
        )
    }

    private suspend fun performVerification(
        task: String,
        iteration: Int,
        mark: kotlin.time.TimeSource.Monotonic.ValueTimeMark,
    ): Boolean {
        observer.onVerificationStarted(previousSnapshotId)
        delay(config.verificationDelayMs)

        val screenTool = tools.filterIsInstance<ScreenReadTool>().firstOrNull()
        val verifyScreen = screenTool?.execute(kotlinx.serialization.json.buildJsonObject {})
        if (verifyScreen?.success != true) {
            observer.onVerificationCompleted(false, "Could not read screen for verification")
            return false
        }

        val verifyState = ScreenReader.read(
            excludeSelf = config.excludeSelfFromScreen,
            previousSnapshotId = previousSnapshotId,
        )

        val notes = buildString {
            appendLine("Verification snapshot taken at ${System.currentTimeMillis()}")
            appendLine("Screen: ${verifyState?.packageName ?: "unknown"}")
            appendLine("Nodes: ${verifyState?.nodes?.size ?: 0}")
            verifyState?.activityName?.let { appendLine("Activity: $it") }
        }

        observer.onVerificationCompleted(true, notes)
        return true
    }

    private fun buildTrace(
        task: String,
        mark: kotlin.time.TimeSource.Monotonic.ValueTimeMark,
        success: Boolean,
    ): ExecutionTrace {
        return ExecutionTrace(
            sessionId = java.util.UUID.randomUUID().toString(),
            task = task,
            provider = provider::class.java.simpleName,
            model = config.model,
            startedAt = System.currentTimeMillis() - mark.elapsedNow().inWholeMilliseconds,
            entries = traceEntries.toList(),
            success = success,
            endedAt = System.currentTimeMillis(),
        )
    }

    private fun returnToHostApp() {
        try {
            val service = com.mobclaw.android.accessibility.MobClawAccessibilityService.instance
                ?: return
            val context = service.applicationContext
            val pm = context.packageManager
            val hostPackage = context.packageName
            val launchIntent = pm.getLaunchIntentForPackage(hostPackage) ?: return
            launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        } catch (_: Exception) {
        }
    }

    private fun buildSystemPrompt(
        task: String,
        matchedSkills: List<com.mobclaw.android.skill.MobSkill> = emptyList(),
    ): String = buildString {
        val visualMode = provider.supportsVision()

        appendLine("You are MobClaw, an Android GUI agent. Complete the user's task by operating the device.")
        appendLine("Task: $task")
        appendLine()
        appendLine("## Operating contract")
        appendLine("- Inspect the latest observation, choose exactly ONE tool action, then wait for MobClaw's automatic fresh observation.")
        appendLine("- Do not narrate. Every non-terminal response must contain one tool call.")
        appendLine("- Do not call wait after an ordinary click, scroll, or open_app; the runtime already waits for UI stability.")
        appendLine("- Use wait only for a screen that is visibly loading or for a known timed transition.")
        appendLine("- If an action fails, read the tool error and choose a different action. Never repeat the identical failed call twice.")
        appendLine("- Use finish only when the latest screen visibly proves every requested result. Put exact extracted values in result.")
        appendLine("- Use fail only after reasonable alternatives are exhausted and explain the blocking condition.")
        appendLine()

        if (visualMode) {
            appendLine("## Visual grounding")
            appendLine("- The attached screenshot is the source of truth for visible text, icons, layout, dialogs, and values.")
            appendLine("- Numbered markers are the only marker targets that exist. Never invent a marker number.")
            appendLine("- Copy ACTION_SNAPSHOT_ID exactly into snapshot_id. Never shorten, guess, or reuse an old snapshot ID.")
            appendLine("- For a numbered target, use snapshot_id + marker_id with click, long_click, or input_text.")
            appendLine("- If Allowed marker IDs is NONE, do not call a marker tool. Use open_app, list_apps, a system action, or tap only when visually justified.")
            appendLine("- When the package is MobClaw's own host app, immediately open the destination app needed for the task.")
        } else {
            appendLine("## Text fallback grounding")
            appendLine("- Use node_id only from the latest text-tree fallback observation.")
            appendLine("- Never reuse a node ID after the screen changes.")
        }

        appendLine()
        appendLine("## Navigation rules")
        appendLine("- Prefer open_app when the package is known. Use list_apps only when it is unknown.")
        appendLine("- Do not manually search the launcher for a known app.")
        appendLine("- Read labels and values carefully. Android version, One UI version, ROM version, build number, baseband, and kernel version are different fields.")
        appendLine("- Preserve user data and avoid destructive actions unless the task explicitly asks for them.")

        val skillPrompt = skillRegistry.buildSkillPrompt(matchedSkills)
        if (skillPrompt.isNotEmpty()) {
            appendLine()
            appendLine(skillPrompt)
        }

        appendLine()
        append(dispatcher.promptInstructions(tools))
    }

    private fun trimHistory() {
        val maxMessages = 40
        if (history.size <= maxMessages) return

        val systemMessages = history.filter {
            it is ConversationMessage.Chat && it.message.role == "system"
        }
        val others = history.filter {
            !(it is ConversationMessage.Chat && it.message.role == "system")
        }

        if (others.size > maxMessages) {
            val dropped = others.size - maxMessages
            history.clear()
            history.addAll(systemMessages)
            history.addAll(others.drop(dropped))
        }
    }

    class Builder {
        private var provider: LlmProvider? = null
        private var tools: List<MobTool>? = null
        private var dispatcher: ActionDispatcher? = null
        private var memory: MobMemory? = null
        private var observer: MobObserver? = null
        private var config: MobClawConfig = MobClawConfig()
        private var skillRegistry: SkillRegistry? = null

        fun provider(provider: LlmProvider) = apply { this.provider = provider }
        fun tools(tools: List<MobTool>) = apply { this.tools = tools }
        fun dispatcher(dispatcher: ActionDispatcher) = apply { this.dispatcher = dispatcher }
        fun memory(memory: MobMemory) = apply { this.memory = memory }
        fun observer(observer: MobObserver) = apply { this.observer = observer }
        fun config(config: MobClawConfig) = apply { this.config = config }
        fun skillRegistry(registry: SkillRegistry) = apply { this.skillRegistry = registry }

        fun build(): MobAgent {
            val resolvedProvider = provider
                ?: throw IllegalStateException("LlmProvider is required")

            val loopContext = LoopContext()
            val resolvedTools = tools ?: defaultTools(loopContext)
            val toolSpecs = resolvedTools.map { it.spec() }

            return MobAgent(
                provider = resolvedProvider,
                tools = resolvedTools,
                toolSpecs = toolSpecs,
                dispatcher = dispatcher ?: JsonActionDispatcher(),
                memory = memory ?: InMemoryStorage(),
                observer = observer ?: LogObserver(),
                config = config,
                skillRegistry = skillRegistry ?: SkillRegistry.withDefaults(),
                loopContext = loopContext,
            )
        }

        private fun defaultTools(loopContext: LoopContext): List<MobTool> = listOf(
            ScreenReadTool(),
            ListAppsTool(),
            OpenAppTool(),
            ClickTool(),
            LongClickTool(),
            TapTool(),
            InputTextTool(),
            ScrollTool(),
            SystemActionTool(),
            WaitTool(),
            RepeatTool(loopContext),
            RepeatNextTool(loopContext),
            RepeatDoneTool(loopContext),
            FinishTool(),
            FailTool(),
        )
    }

    companion object {
        fun builder() = Builder()
    }
}

/**
 * Result of an agent task execution.
 */
data class AgentResult(
    val success: Boolean,
    val message: String,
    val iterations: Int,
    val duration: Duration,
    val trace: ExecutionTrace? = null,
)
