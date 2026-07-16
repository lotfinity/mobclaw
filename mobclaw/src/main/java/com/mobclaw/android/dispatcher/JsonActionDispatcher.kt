package com.mobclaw.android.dispatcher

import com.mobclaw.android.model.*
import com.mobclaw.android.tool.MobTool
import kotlinx.serialization.json.*

/**
 * Dispatcher that parses JSON tool calls from LLM responses.
 * Supports native function calling and XML-style fallback.
 */
class JsonActionDispatcher : ActionDispatcher {

    override fun parseResponse(response: ChatResponse): Pair<String, List<MobAction>> {
        val text = response.textOrEmpty().cleanAssistantText()

        if (response.hasToolCalls()) {
            val actions = response.toolCalls.map { tc ->
                val args = try {
                    Json.parseToJsonElement(tc.arguments).jsonObject
                } catch (_: Exception) {
                    buildJsonObject {}
                }
                MobAction(
                    name = tc.name,
                    arguments = args,
                    toolCallId = tc.id,
                )
            }
            return Pair(text, actions)
        }

        val calls = mutableListOf<MobAction>()
        val textParts = mutableListOf<String>()
        var remaining = text

        while (true) {
            val start = remaining.indexOf("<tool_call>")
            if (start == -1) break

            val before = remaining.substring(0, start).trim()
            if (before.isNotEmpty()) textParts.add(before)

            val end = remaining.indexOf("</tool_call>", start)
            if (end == -1) break

            val inner = remaining.substring(start + 11, end).trim()
            try {
                val parsed = Json.parseToJsonElement(inner).jsonObject
                val name = parsed["name"]?.jsonPrimitive?.content ?: continue
                val arguments = parsed["arguments"]?.jsonObject ?: buildJsonObject {}
                calls.add(MobAction(name = name, arguments = arguments))
            } catch (_: Exception) {
                // Malformed tool call, skip.
            }

            remaining = remaining.substring(end + 12)
        }

        val after = remaining.trim()
        if (after.isNotEmpty()) textParts.add(after)

        return Pair(textParts.joinToString("\n").cleanAssistantText(), calls)
    }

    override fun formatResults(results: List<ToolExecutionResult>): ConversationMessage {
        val messages = results.map { result ->
            ToolResultMessage(
                toolCallId = result.toolCallId ?: "unknown",
                content = result.output,
            )
        }
        return ConversationMessage.ToolResults(messages)
    }

    override fun promptInstructions(tools: List<MobTool>): String = buildString {
        appendLine("## Tool protocol")
        appendLine("Use exactly ONE tool call per turn. MobClaw automatically captures a fresh screen after each action.")
        appendLine("For native tool calling, call the function directly. For text fallback, use:")
        appendLine("<tool_call>{\"name\":\"tool_name\",\"arguments\":{}}</tool_call>")
        appendLine()
        appendLine("### Available tools")
        tools.forEach { tool ->
            appendLine("- ${tool.name}: ${tool.description}")
            appendLine("  schema: ${tool.parametersSchema()}")
        }
    }

    override fun toProviderMessages(history: List<ConversationMessage>): List<ChatMessage> {
        val messages = history.flatMap { msg ->
            when (msg) {
                is ConversationMessage.Chat -> {
                    val cleaned = msg.message.content.cleanAssistantText()
                    if (cleaned.isBlank() && msg.message.role == "assistant") emptyList()
                    else listOf(msg.message.copy(content = cleaned))
                }
                is ConversationMessage.AssistantToolCalls -> {
                    val cleaned = msg.text.orEmpty().cleanAssistantText()
                    if (cleaned.isBlank()) emptyList() else listOf(ChatMessage.assistant(cleaned))
                }
                is ConversationMessage.ToolResults -> {
                    val content = msg.results.joinToString("\n") { result ->
                        "<tool_result id=\"${result.toolCallId}\">\n${result.content}\n</tool_result>"
                    }
                    listOf(ChatMessage.user("[Tool results]\n$content"))
                }
            }
        }.toMutableList()

        // Attach only the newest screenshot. Earlier observations stay text-only.
        val observation = ScreenObservationStore.current ?: return messages
        val marker = "ACTION_SNAPSHOT_ID: ${observation.snapshotId}"
        val index = messages.indexOfLast { message ->
            message.role == "user" && message.content.contains(marker)
        }
        if (index >= 0) {
            messages[index] = messages[index].copy(imageDataUrl = observation.imageDataUrl)
        }

        return messages
    }

    override fun shouldSendToolSpecs(): Boolean = true

    private fun String.cleanAssistantText(): String {
        val cleaned = trim()
        return if (cleaned.equals("null", ignoreCase = true)) "" else cleaned
    }
}