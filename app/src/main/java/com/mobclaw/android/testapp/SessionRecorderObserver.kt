package com.mobclaw.android.testapp

import android.content.Context
import com.mobclaw.android.model.ChatMessage
import com.mobclaw.android.model.ChatResponse
import com.mobclaw.android.model.ExecutionTrace
import com.mobclaw.android.model.ForegroundInfo
import com.mobclaw.android.model.ScreenState
import com.mobclaw.android.observer.MobObserver
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import kotlin.time.Duration

@Serializable
data class RecordedSession(
    val schemaVersion: Int = 2,
    val sessionId: String,
    val provider: String,
    val model: String?,
    val task: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    val durationMs: Long? = null,
    val success: Boolean? = null,
    val events: List<RecordedSessionEvent> = emptyList(),
    val traceJson: String? = null,
)

@Serializable
data class RecordedSessionEvent(
    val sequence: Int,
    val timestampEpochMs: Long,
    val type: String,
    val iteration: Int? = null,
    val data: JsonObject = JsonObject(emptyMap()),
)

class SessionRecorderObserver(
    context: Context,
    private val provider: String,
    private val model: String?,
) : MobObserver {
    private val directory = SessionStore.sessionDirectory(context)
    private var session: RecordedSession? = null
    private var sessionFile: File? = null
    private var eventSequence = 0
    private var currentTrace: ExecutionTrace? = null

    @Synchronized
    override fun onAgentStart(task: String) {
        val id = UUID.randomUUID().toString()
        sessionFile = File(directory, "$id.json")
        session = RecordedSession(
            sessionId = id,
            provider = provider,
            model = model,
            task = task,
            startedAtEpochMs = System.currentTimeMillis(),
        )
        eventSequence = 0
        appendEvent("agent_start", data = buildJsonObject { put("task", limited(task)) })
    }

    override fun onToolCall(toolName: String, duration: Duration, success: Boolean) = Unit

    override fun onScreenRead(packageName: String, nodeCount: Int) = Unit

    @Synchronized
    override fun onAgentEnd(task: String, duration: Duration, success: Boolean) {
        appendEvent(
            "agent_end",
            data = buildJsonObject {
                put("success", success)
                put("durationMs", duration.inWholeMilliseconds)
            },
        )
        session = session?.copy(
            endedAtEpochMs = System.currentTimeMillis(),
            durationMs = duration.inWholeMilliseconds,
            success = success,
            traceJson = currentTrace?.let { JSON.encodeToString(it) },
        )
        persist()
    }

    override fun onError(message: String, throwable: Throwable?) {
        appendEvent(
            "error",
            data = buildJsonObject {
                put("message", limited(message))
                throwable?.let {
                    put("exception", it::class.java.name)
                    put("exceptionMessage", limited(it.message.orEmpty()))
                }
            },
        )
    }

    override fun onModelRequest(
        iteration: Int,
        messages: List<ChatMessage>,
        toolNames: List<String>,
        model: String?,
        temperature: Double,
    ) {
        appendEvent(
            type = "model_request",
            iteration = iteration,
            data = buildJsonObject {
                put("model", model ?: this@SessionRecorderObserver.model.orEmpty())
                put("temperature", temperature)
                put("toolNames", JSON.encodeToJsonElement(toolNames))
                put("messages", JSON.encodeToJsonElement(messages.map { it.copy(content = limited(it.content)) }))
            },
        )
    }

    override fun onModelResponse(
        iteration: Int,
        response: ChatResponse,
        duration: Duration,
    ) {
        appendEvent(
            type = "model_response",
            iteration = iteration,
            data = buildJsonObject {
                put("durationMs", duration.inWholeMilliseconds)
                put("text", limited(response.text.orEmpty()))
                put("toolCalls", JSON.encodeToJsonElement(response.toolCalls))
            },
        )
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
        appendEvent(
            type = "tool_call",
            iteration = iteration,
            data = buildJsonObject {
                put("tool", toolName)
                put("arguments", limited(arguments))
                put("output", limited(output))
                error?.let { put("error", limited(it)) }
                put("durationMs", duration.inWholeMilliseconds)
                put("success", success)
            },
        )
    }

    override fun onScreenReadDetail(
        iteration: Int,
        packageName: String,
        nodeCount: Int,
        content: String,
    ) {
        appendEvent(
            type = "screen_read",
            iteration = iteration,
            data = buildJsonObject {
                put("packageName", packageName)
                put("nodeCount", nodeCount)
                put("content", limited(content))
            },
        )
    }

    override fun onReasoning(iteration: Int, text: String) {
        appendEvent(
            type = "assistant_text",
            iteration = iteration,
            data = buildJsonObject { put("text", limited(text)) },
        )
    }

    override fun onActionPending(
        iteration: Int,
        toolName: String,
        arguments: Map<String, String>,
    ) {
        appendEvent(
            type = "action_pending",
            iteration = iteration,
            data = buildJsonObject {
                put("tool", toolName)
                put("arguments", JSON.encodeToJsonElement(arguments))
            },
        )
    }

    override fun onForegroundChanged(foreground: ForegroundInfo) {
        appendEvent(
            type = "foreground_changed",
            data = buildJsonObject {
                put("packageName", foreground.packageName)
                foreground.activityName?.let { put("activityName", it) }
            },
        )
    }

    override fun onStuckDetected(iteration: Int, consecutiveNoChange: Int) {
        appendEvent(
            type = "stuck_detected",
            iteration = iteration,
            data = buildJsonObject {
                put("consecutiveNoChange", consecutiveNoChange)
            },
        )
    }

    override fun onVerificationStarted(snapshotId: Long) {
        appendEvent(
            type = "verification_started",
            data = buildJsonObject { put("snapshotId", snapshotId) },
        )
    }

    override fun onVerificationCompleted(passed: Boolean, notes: String) {
        appendEvent(
            type = "verification_completed",
            data = buildJsonObject {
                put("passed", passed)
                put("notes", limited(notes))
            },
        )
    }

    override fun onTraceComplete(trace: ExecutionTrace) {
        currentTrace = trace
        appendEvent(
            type = "trace_complete",
            data = buildJsonObject {
                put("sessionId", trace.sessionId)
                put("success", trace.success)
                put("totalActions", trace.totalActions)
                put("successfulActions", trace.successfulActions)
                put("failedActions", trace.failedActions)
                put("durationMs", trace.durationMs)
            },
        )
    }

    @Synchronized
    private fun appendEvent(
        type: String,
        iteration: Int? = null,
        data: JsonObject = JsonObject(emptyMap()),
    ) {
        val current = session ?: return
        val event = RecordedSessionEvent(
            sequence = eventSequence++,
            timestampEpochMs = System.currentTimeMillis(),
            type = type,
            iteration = iteration,
            data = data,
        )
        session = current.copy(events = current.events + event)
        persist()
    }

    private fun persist() {
        val current = session ?: return
        val destination = sessionFile ?: return
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.writeText(JSON.encodeToString(current))
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
    }

    private fun limited(value: String): String =
        if (value.length <= MAX_EVENT_TEXT_LENGTH) value else value.take(MAX_EVENT_TEXT_LENGTH) + "\n[truncated]"

    private companion object {
        private const val MAX_EVENT_TEXT_LENGTH = 250_000
        private val JSON = Json { prettyPrint = true }
    }
}

object SessionStore {
    private val json = Json { ignoreUnknownKeys = true }

    fun sessionDirectory(context: Context): File =
        File(context.filesDir, "mobclaw-sessions").apply { mkdirs() }

    fun sessionCount(context: Context): Int = sessionFiles(context).size

    fun listSessions(context: Context): List<RecordedSession> {
        return sessionFiles(context).mapNotNull { file ->
            runCatching { json.decodeFromString<RecordedSession>(file.readText()) }.getOrNull()
        }.sortedByDescending { it.startedAtEpochMs }
    }

    fun loadSession(context: Context, sessionId: String): RecordedSession? {
        val file = File(sessionDirectory(context), "$sessionId.json")
        return if (file.exists()) {
            runCatching { json.decodeFromString<RecordedSession>(file.readText()) }.getOrNull()
        } else null
    }

    fun exportAllJson(context: Context): File {
        val sessions = listSessions(context)
        val export = buildJsonObject {
            put("schemaVersion", 2)
            put("exportedAtEpochMs", System.currentTimeMillis())
            put("sessionCount", sessions.size)
            put("sessions", JsonArray(sessions.map { json.encodeToJsonElement(it) }))
        }
        val exportDirectory = File(context.cacheDir, "exports").apply { mkdirs() }
        return File(exportDirectory, "mobclaw-sessions-${System.currentTimeMillis()}.json").apply {
            writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), export))
        }
    }

    fun exportAllMarkdown(context: Context): File {
        val sessions = listSessions(context)
        val exportDirectory = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(exportDirectory, "mobclaw-sessions-${System.currentTimeMillis()}.md")

        file.writeText(buildString {
            appendLine("# MobClaw Session Export")
            appendLine()
            appendLine("Exported at: ${java.util.Date()}")
            appendLine("Total sessions: ${sessions.size}")
            appendLine()

            for (session in sessions) {
                appendLine("---")
                appendLine()
                appendLine("## Session: ${session.sessionId.take(8)}...")
                appendLine()
                appendLine("- **Task**: ${session.task}")
                appendLine("- **Provider**: ${session.provider}")
                session.model?.let { appendLine("- **Model**: $it") }
                appendLine("- **Started**: ${java.util.Date(session.startedAtEpochMs)}")
                session.endedAtEpochMs?.let { appendLine("- **Ended**: ${java.util.Date(it)}") }
                session.durationMs?.let { appendLine("- **Duration**: ${it}ms") }
                appendLine("- **Success**: ${session.success ?: "unknown"}")
                appendLine()

                appendLine("### Events")
                appendLine()
                for (event in session.events) {
                    appendLine("- [${event.type}] (iter=${event.iteration ?: "-"}, seq=${event.sequence})")
                }
                appendLine()
            }
        })

        return file
    }

    fun clear(context: Context) {
        sessionFiles(context).forEach(File::delete)
    }

    private fun sessionFiles(context: Context): List<File> =
        sessionDirectory(context)
            .listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }
}
