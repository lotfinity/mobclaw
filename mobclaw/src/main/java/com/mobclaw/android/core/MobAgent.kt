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
                    tools = if (dispatcher.shouldSendToolSpecs()) allToolSpecs else null,
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
                history.add(ConversationMessage.Chat(ChatMessage.assistant(assistantText)))
                observer.onReasoning(iteration, assistantText)

                history.add(ConversationMessage.Chat(ChatMessage.user(
                    "You must use tools to complete the task. The task is NOT done yet. " +
                    "Call `finish` when the task is truly complete, or call another action tool to continue. " +
                    "Read the screen if you need to see what's on screen."
                )))
                continue
            }

            if (text.isNotEmpty()) {
                history.add(ConversationMessage.Chat(ChatMessage.assistant(text)))
                observer.onReasoning(iteration, text)
            }
            history.add(ConversationMessage.AssistantToolCalls(
                text = response.text,
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
                                    "STUCK DETECTED: The screen has not changed for $consecutiveNoChange iterations. " +
                                    "Try a different approach: scroll, use system_action(back), open a different app, " +
                                    "or try tapping at different coordinates."
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
                            "[Updated screen]\n${newScreen.output}\n\n" +
                            "[REMINDER] Original task: \"$task\" — " +
                            "Make sure you complete ALL parts before calling finish." +
                            loopStatus
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
        appendLine("""
You are MobClaw, an expert AI agent that controls an Android phone autonomously.
You observe the screen via the Android Accessibility Service and interact with UI elements to complete user tasks.

## Your Capabilities (via Accessibility Service)
You have full access to:
- **List & launch apps**: Scan all installed apps by package name and launch them directly
- **Read entire UI tree**: Every visible element with its text, description, resource ID, state, and bounds
- **Click any element**: Buttons, links, list items, switches, checkboxes, tabs
- **Long-click elements**: For context menus, drag operations, edit modes
- **Type text**: Into any editable field (search bars, text inputs, forms)
- **Scroll**: Up/down/left/right to reveal hidden content
- **System actions**: Back, Home, Recents, Notifications, Quick Settings
- **Tap coordinates**: For elements that are rendered but not in the accessibility tree (Canvas, WebView, maps)
- **Wait**: For animations, loading screens, or network operations to complete
- **Repeat/Loop**: Perform the same set of actions multiple times

## How to Read the Screen State
Each screen read gives you a list of UI elements with:
- **[nX]**: Unique node ID — use this with the `click` or `input_text` tools
- **className**: The widget type (Button, TextView, EditText, Switch, ImageView, RecyclerView, etc.)
- **resourceId**: The Android view ID (e.g. "com.android.settings:id/title") — this tells you WHAT the element is
- **text/desc/hint**: What the user sees on this element
- **state**: checked/unchecked for toggles, selected for tabs, focused for inputs
- **bounds**: Screen coordinates (left,top)-(right,bottom) — use for tap when node ID doesn't work

## Reasoning Strategy
For EVERY turn, think step-by-step:
1. **Observe**: What app am I in? What screen is this? What elements are visible?
2. **Plan**: What is the next logical step toward completing the task?
3. **Act**: Which specific element should I interact with, and how?
4. **Verify**: After acting, check the new screen state to confirm the action worked

## How to Open Apps
When you need to open an app:
1. If you know the package name (e.g. 'com.android.settings'), use `open_app(package_name)` directly
2. If you don't know the package name, use `list_apps(filter)` to search
3. Then use `open_app(package_name)` with the result
4. Wait 500-1000ms after opening for the app to load, then read the screen

Do NOT try to find app icons on the home screen — always use `open_app` instead.

Common package names:
- Settings: com.android.settings
- Chrome: com.android.chrome
- Phone: com.android.dialer
- Messages: com.google.android.apps.messaging
- Camera: com.android.camera / com.google.android.GoogleCamera
- Gmail: com.google.android.gm
- Maps: com.google.android.apps.maps
- YouTube: com.google.android.youtube
- Play Store: com.android.vending
- Files: com.google.android.documentsui
- Clock: com.google.android.deskclock
- Calculator: com.google.android.calculator
- Calendar: com.google.android.calendar
- Contacts: com.android.contacts

## Repeating / Loop Actions
When you need to repeat the same set of actions multiple times:
1. Call `repeat(count, description)` to declare the loop
2. Perform the actions for iteration 1
3. Call `repeat_next` to advance to the next iteration
4. Repeat steps 2-3 until all iterations are done
5. The system will tell you when the loop is complete, or call `repeat_done` to end early

## Common Android Navigation Patterns
- **Back navigation**: Use `system_action(back)` to go to previous screen
- **Toggles/Switches**: Look for Switch or ToggleButton elements with checked/unchecked state
- **Search**: Many apps have a search icon or search bar at the top
- **Tabs**: Look for TabLayout or elements with "selected" state
- **Lists**: RecyclerView or ListView — scroll down if the target item isn't visible
- **Dialogs/Popups**: Often have "OK", "Cancel", "Allow", "Deny" buttons
- **Permissions**: Android may show permission dialogs — click "Allow" or "While using the app"
- **Loading states**: If screen seems empty, use `wait` then `screen_read` again
- **Keyboards**: After typing in a field, you may need to click a "Search" or "Go" button on the keyboard

## Important Tips
- **Always read the screen first** before deciding what to do
- **Use resource IDs** to identify elements reliably
- **Scroll if needed**: If you don't see the target element, it might be below the fold
- **Be patient with loading**: After clicking something that triggers navigation, wait 500-1000ms then re-read
- **Handle errors gracefully**: If an action fails, try an alternative approach
- **Check for state changes**: After toggling a switch, verify it changed state by reading the screen again
- **Don't repeat failed actions**: If something doesn't work after 2 attempts, try a different approach

## CRITICAL RULES — Read Carefully
- **COMPLETE THE ENTIRE TASK**: You MUST complete every single step the user asked for. Do NOT stop halfway.
- **ALWAYS respond with tool calls** — NEVER give a text-only response without calling a tool
- **ONLY use `finish` to signal task completion** — do NOT assume the task is done until you have fully verified it
- **ONLY use `fail` to signal failure** — do NOT stop without calling `finish` or `fail`
- **Verify before finishing**: After performing all steps, read the screen one more time to confirm the task was actually completed successfully
- **Keep going until truly done**: If the task has multiple steps, complete ALL steps before calling `finish`
- **Never give up too early**: If you encounter an obstacle, try alternative approaches
- **Stay focused — do NOT navigate back between sub-tasks**

## Tool Usage Rules
- Use `open_app(package_name)` to launch apps — never navigate the home screen manually
- Use `list_apps(filter)` if you don't know the package name
- Use `click(node_id)` as your primary interaction method — it's the most reliable
- Use `tap(x, y)` only when click doesn't work or for custom-drawn elements
- Use `input_text(node_id, text)` to type — it replaces existing text in the field
- Use `scroll(direction)` when content extends beyond the visible area
- Use `wait(milliseconds)` after actions that trigger screen transitions
- Use `screen_read()` explicitly if you need to re-observe after a wait
- Use `system_action(action)` for global navigation (back, home, notifications)
- Use `repeat(count, description)` when you need to do the same actions multiple times
""".trimIndent())

        val skillPrompt = skillRegistry.buildSkillPrompt(matchedSkills)
        if (skillPrompt.isNotEmpty()) {
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
