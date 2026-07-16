package com.mobclaw.android.testapp

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.Date

private const val RAW_PREVIEW_LIMIT = 200_000
private val SESSION_EXPORT_JSON = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}

/** Full-screen debug preview for one recorded MobClaw session. */
@Composable
fun SessionPreviewDialog(
    session: RecordedSession,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var selectedTab by remember(session.sessionId) { mutableIntStateOf(0) }
    val rawJson = remember(session) { buildSingleSessionExportJson(session) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Session ${session.sessionId.take(8)}",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            session.task,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close preview")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("${session.provider} · ${session.model ?: "default"}")
                        Text(
                            "${session.events.size} events · ${formatDuration(session.durationMs)} · ${formatSuccess(session.success)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            Date(session.startedAtEpochMs).toString(),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        label = { Text("Timeline") },
                    )
                    FilterChip(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        label = { Text("Raw JSON") },
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = { openRecordedSessionJson(context, session) }) {
                        Icon(Icons.Default.OpenInNew, contentDescription = "Open JSON externally")
                    }
                    IconButton(onClick = { shareRecordedSession(context, session) }) {
                        Icon(Icons.Default.Share, contentDescription = "Share this session")
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                if (selectedTab == 0) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(session.events, key = { it.sequence }) { event ->
                            SessionEventCard(session.startedAtEpochMs, event)
                        }
                    }
                } else {
                    val isTruncated = rawJson.length > RAW_PREVIEW_LIMIT
                    val preview = if (isTruncated) {
                        rawJson.take(RAW_PREVIEW_LIMIT) +
                            "\n\n[Preview truncated in-app. Share or open the JSON file for the complete session.]"
                    } else rawJson

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        Text(
                            preview,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionEventCard(
    startedAtEpochMs: Long,
    event: RecordedSessionEvent,
) {
    var expanded by remember(event.sequence) { mutableStateOf(false) }
    val elapsed = (event.timestampEpochMs - startedAtEpochMs).coerceAtLeast(0L)

    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "#${event.sequence} ${event.type}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "+${elapsed}ms${event.iteration?.let { " · iter $it" } ?: ""}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            val summary = eventSummary(event)
            if (summary.isNotBlank()) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    SESSION_EXPORT_JSON.encodeToString(JsonObject.serializer(), event.data),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

fun shareRecordedSession(context: Context, session: RecordedSession) {
    val file = exportSingleSessionJson(context, session)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "MobClaw session ${session.sessionId.take(8)}")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share this MobClaw session"))
}

fun openRecordedSessionJson(context: Context, session: RecordedSession) {
    val file = exportSingleSessionJson(context, session)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/json")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Open session JSON"))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No JSON viewer installed", Toast.LENGTH_SHORT).show()
    }
}

private fun exportSingleSessionJson(context: Context, session: RecordedSession): File {
    val exportDirectory = File(context.cacheDir, "exports").apply { mkdirs() }
    val fileName = "mobclaw-session-${session.sessionId.take(8)}-${System.currentTimeMillis()}.json"
    return File(exportDirectory, fileName).apply {
        writeText(buildSingleSessionExportJson(session))
    }
}

private fun buildSingleSessionExportJson(session: RecordedSession): String {
    val export = buildJsonObject {
        put("schemaVersion", 2)
        put("exportedAtEpochMs", System.currentTimeMillis())
        put("sessionCount", 1)
        put("sessions", JsonArray(listOf(SESSION_EXPORT_JSON.encodeToJsonElement(session))))
    }
    return SESSION_EXPORT_JSON.encodeToString(JsonObject.serializer(), export)
}

private fun eventSummary(event: RecordedSessionEvent): String = when (event.type) {
    "model_request" -> {
        val model = event.data.string("model")
        val messages = event.data.arraySize("messages")
        val tools = event.data.arraySize("toolNames")
        val temperature = event.data["temperature"]?.jsonPrimitive?.contentOrNull
        listOfNotNull(model, "$messages messages", "$tools tools", temperature?.let { "temp $it" })
            .joinToString(" · ")
    }
    "model_response" -> {
        val duration = event.data.long("durationMs")?.let { "${it}ms" }
        val calls = event.data["toolCalls"]?.jsonArray.orEmpty().mapNotNull { call ->
            call.asObjectOrNull()?.string("name")
        }
        val text = event.data.string("text")?.cleanPreview()
        listOfNotNull(duration, calls.takeIf { it.isNotEmpty() }?.joinToString(prefix = "tools: "), text)
            .joinToString(" · ")
    }
    "tool_call" -> {
        val tool = event.data.string("tool") ?: "tool"
        val success = event.data["success"]?.jsonPrimitive?.contentOrNull
        val duration = event.data.long("durationMs")?.let { "${it}ms" }
        val error = event.data.string("error")?.cleanPreview()
        val output = event.data.string("output")?.cleanPreview()?.takeIf { it.isNotBlank() }
        listOfNotNull(tool, success?.let { if (it == "true") "success" else "failed" }, duration, error ?: output)
            .joinToString(" · ")
    }
    "screen_read" -> {
        val content = event.data.string("content").orEmpty()
        val markerCount = Regex("Numbered targets: (\\d+)").find(content)?.groupValues?.getOrNull(1)
        listOfNotNull(
            event.data.string("packageName"),
            markerCount?.let { "$it markers" },
            event.data.long("nodeCount")?.let { "$it nodes" },
        ).joinToString(" · ")
    }
    "action_pending" -> {
        val tool = event.data.string("tool") ?: "action"
        val args = event.data["arguments"]?.toString()?.cleanPreview()
        listOfNotNull(tool, args).joinToString(" · ")
    }
    "assistant_text" -> event.data.string("text")?.cleanPreview().orEmpty()
    "foreground_changed" -> listOfNotNull(
        event.data.string("packageName"),
        event.data.string("activityName"),
    ).joinToString(" · ")
    "verification_completed" -> listOfNotNull(
        event.data["passed"]?.jsonPrimitive?.contentOrNull?.let { "passed=$it" },
        event.data.string("notes")?.cleanPreview(),
    ).joinToString(" · ")
    "agent_end" -> listOfNotNull(
        event.data["success"]?.jsonPrimitive?.contentOrNull?.let { "success=$it" },
        event.data.long("durationMs")?.let { "${it}ms" },
    ).joinToString(" · ")
    else -> event.data.toString().cleanPreview()
}

private fun JsonObject.string(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.long(key: String): Long? =
    this[key]?.jsonPrimitive?.longOrNull

private fun JsonObject.arraySize(key: String): Int =
    runCatching { this[key]?.jsonArray?.size ?: 0 }.getOrDefault(0)

private fun JsonElement.asObjectOrNull(): JsonObject? =
    runCatching { jsonObject }.getOrNull()

private fun String.cleanPreview(): String =
    replace(Regex("\\s+"), " ").trim().take(220)

private fun formatDuration(durationMs: Long?): String = when {
    durationMs == null -> "running/incomplete"
    durationMs < 1_000 -> "${durationMs}ms"
    else -> "${durationMs / 1_000}s"
}

private fun formatSuccess(success: Boolean?): String = when (success) {
    true -> "success"
    false -> "failed"
    null -> "unknown"
}
