from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def replace_once(relative_path: str, old: str, new: str) -> None:
    path = ROOT / relative_path
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{relative_path}: expected one match, found {count}\n--- pattern ---\n{old[:500]}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def regex_once(relative_path: str, pattern: str, replacement: str) -> None:
    path = ROOT / relative_path
    text = path.read_text(encoding="utf-8")
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.DOTALL)
    if count != 1:
        raise RuntimeError(f"{relative_path}: regex expected one match, found {count}")
    path.write_text(updated, encoding="utf-8")


# MainActivity: non-persistent success UI, working Stop button, visible 1.0.1 defaults.
MAIN = "app/src/main/java/com/mobclaw/android/testapp/MainActivity.kt"
replace_once(MAIN, "import kotlinx.coroutines.launch\n", "import kotlinx.coroutines.delay\nimport kotlinx.coroutines.launch\n")
replace_once(
    MAIN,
    "class MainActivity : ComponentActivity() {\n\n    private enum class Screen",
    "class MainActivity : ComponentActivity() {\n\n    @Volatile\n    private var activeAgent: MobAgent? = null\n\n    private enum class Screen",
)
replace_once(
    MAIN,
    'var resultText by remember { mutableStateOf("Results will appear here...") }',
    'var resultText by remember { mutableStateOf<String?>(null) }',
)
replace_once(
    MAIN,
    """            OutlinedTextField(
                value = task,
                onValueChange = { task = it },
""",
    """            OutlinedTextField(
                value = task,
                onValueChange = {
                    task = it
                    if (!isRunning) resultText = null
                },
""",
)
replace_once(
    MAIN,
    """                        scope.launch {
                            isRunning = true
                            resultText = "Executing..."
                            try {
                                val result = executeTask(
                                    mobMock = mobMock,
                                    providerType = selectedProvider,
                                    apiKey = trimmedKey,
                                    liteLlmModel = liteLlmModel,
                                    customBaseUrl = customBaseUrl,
                                    customModel = customModel,
                                    task = trimmedTask,
                                )
                                val status = if (result.success) "Success" else "Failed"
                                resultText = buildString {
                                    appendLine("$status (${result.iterations} iterations, ${result.duration.inWholeSeconds}s)")
                                    appendLine()
                                    append(result.message)
                                }
                            } catch (e: Exception) {
                                resultText = "Error: ${e.message}"
                            } finally {
                                isRunning = false
                            }
                        }
""",
    """                        scope.launch {
                            isRunning = true
                            resultText = null
                            try {
                                val result = executeTask(
                                    mobMock = mobMock,
                                    providerType = selectedProvider,
                                    apiKey = trimmedKey,
                                    liteLlmModel = liteLlmModel,
                                    customBaseUrl = customBaseUrl,
                                    customModel = customModel,
                                    task = trimmedTask,
                                )
                                val status = if (result.success) "Success" else "Failed"
                                val completedText = buildString {
                                    appendLine("$status (${result.iterations} iterations, ${result.duration.inWholeSeconds}s)")
                                    appendLine()
                                    append(result.message)
                                }
                                resultText = completedText
                                if (result.success) {
                                    scope.launch {
                                        delay(6_000)
                                        if (resultText == completedText) resultText = null
                                    }
                                }
                            } catch (e: Exception) {
                                resultText = "Error: ${e.message}"
                            } finally {
                                isRunning = false
                            }
                        }
""",
)
replace_once(
    MAIN,
    """                    OutlinedButton(
                        onClick = { },
""",
    """                    OutlinedButton(
                        onClick = { activeAgent?.cancel() },
""",
)
replace_once(
    MAIN,
    """            Text(
                text = resultText,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
""",
    """            resultText?.let { message ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
""",
)
replace_once(MAIN, 'Text("Max iterations: 120"', 'Text("Max iterations: 60"')
replace_once(MAIN, 'Text("Stability wait: 500ms"', 'Text("Temperature: 0.2 · Stability wait: 500ms"')
replace_once(
    MAIN,
    """        return agent.execute(task)
""",
    """        activeAgent = agent
        return try {
            agent.execute(task)
        } finally {
            activeAgent = null
        }
""",
)

# AgentOverlay: cancel stale auto-hide callbacks and remove the completion UI automatically.
OVERLAY = "mobclaw/src/main/java/com/mobclaw/android/overlay/AgentOverlay.kt"
replace_once(
    OVERLAY,
    """    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayView: View? = null
""",
    """    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable { hide() }

    private var overlayView: View? = null
""",
)
replace_once(
    OVERLAY,
    """    fun show() {
        if (isShowing) return
""",
    """    fun show() {
        mainHandler.removeCallbacks(autoHideRunnable)
        if (isShowing) return
""",
)
replace_once(
    OVERLAY,
    """    fun hide() {
        if (!isShowing) return
""",
    """    fun hide() {
        mainHandler.removeCallbacks(autoHideRunnable)
        if (!isShowing) return
""",
)
replace_once(
    OVERLAY,
    """    // --- Public update methods (called from any thread) ---

    fun updateStatus(status: String) {
""",
    """    // --- Public update methods (called from any thread) ---

    fun hideAfter(delayMs: Long) {
        mainHandler.removeCallbacks(autoHideRunnable)
        mainHandler.postDelayed(autoHideRunnable, delayMs.coerceAtLeast(0L))
    }

    fun updateStatus(status: String) {
""",
)
replace_once(
    OVERLAY,
    """    fun clearActions() {
        mainHandler.post {
            actionsContainer?.removeAllViews()
            reasoningText?.text = ""
        }
    }
""",
    """    fun clearActions() {
        mainHandler.removeCallbacks(autoHideRunnable)
        mainHandler.post {
            actionsContainer?.removeAllViews()
            reasoningText?.apply {
                text = ""
                visibility = View.GONE
            }
            statusText?.text = "Ready"
        }
    }
""",
)

# MobAgent: compact vision-first contract, native tools when supported, clean history.
AGENT = "mobclaw/src/main/java/com/mobclaw/android/core/MobAgent.kt"
replace_once(
    AGENT,
    """                    tools = if (dispatcher.shouldSendToolSpecs()) allToolSpecs else null,
""",
    """                    tools = if (dispatcher.shouldSendToolSpecs() && provider.supportsNativeTools()) {
                        allToolSpecs
                    } else null,
""",
)
replace_once(
    AGENT,
    """            if (actions.isEmpty()) {
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
""",
    """            if (actions.isEmpty()) {
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
""",
)
replace_once(
    AGENT,
    """            history.add(ConversationMessage.AssistantToolCalls(
                text = response.text,
                toolCalls = response.toolCalls,
            ))
""",
    """            history.add(ConversationMessage.AssistantToolCalls(
                text = text.takeIf { it.isNotBlank() },
                toolCalls = response.toolCalls,
            ))
""",
)
replace_once(
    AGENT,
    """                                    "STUCK DETECTED: The screen has not changed for $consecutiveNoChange iterations. " +
                                    "Try a different approach: scroll, use system_action(back), open a different app, " +
                                    "or try tapping at different coordinates."
""",
    """                                    "The last approach caused no visible change. Do not repeat it. " +
                                        "Choose one different action from the latest observation."
""",
)
replace_once(
    AGENT,
    """                            "[Updated screen]\n${newScreen.output}\n\n" +
                            "[REMINDER] Original task: \"$task\" — " +
                            "Make sure you complete ALL parts before calling finish." +
                            loopStatus
""",
    """                            "[Updated observation]\n${newScreen.output}\n\n" +
                                "Task still in progress: $task" + loopStatus
""",
)
new_prompt = '''    private fun buildSystemPrompt(
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

'''
regex_once(
    AGENT,
    r"    private fun buildSystemPrompt\(.*?\n    private fun trimHistory\(\)",
    new_prompt + "    private fun trimHistory()",
)

# OpenAI-compatible parsing: JSON null / string "null" are absence, not assistant prose.
OPENAI = "mobclaw/src/main/java/com/mobclaw/android/provider/OpenAiCompatibleProvider.kt"
replace_once(
    OPENAI,
    """    private fun parseMessageText(message: JsonObject): String? {
        val content = message["content"] ?: return null
        return when (content) {
            is JsonPrimitive -> content.asStringOrNull()
            is JsonArray -> content.joinToString("\n") { part ->
                part.jsonObject["text"].asStringOrNull().orEmpty()
            }.ifBlank { null }
            else -> null
        }
    }
""",
    """    private fun parseMessageText(message: JsonObject): String? {
        val content = message["content"] ?: return null
        val parsed = when (content) {
            is JsonPrimitive -> content.asStringOrNull()
            is JsonArray -> content.joinToString("\n") { part ->
                part.jsonObject["text"].asStringOrNull().orEmpty()
            }.ifBlank { null }
            else -> null
        }
        return parsed?.trim()?.takeUnless { it.equals("null", ignoreCase = true) || it.isBlank() }
    }
""",
)

# JsonActionDispatcher already supports native calls; let capable providers receive schemas.
DISPATCHER = "mobclaw/src/main/java/com/mobclaw/android/dispatcher/JsonActionDispatcher.kt"
replace_once(
    DISPATCHER,
    "override fun shouldSendToolSpecs(): Boolean = false",
    "override fun shouldSendToolSpecs(): Boolean = true",
)

# Remove the one-shot migration files from the resulting feature commit.
(ROOT / "scripts/apply_v101_polish.py").unlink(missing_ok=True)
(ROOT / ".github/workflows/apply-v101-polish.yml").unlink(missing_ok=True)

print("v1.0.1 polish applied")
