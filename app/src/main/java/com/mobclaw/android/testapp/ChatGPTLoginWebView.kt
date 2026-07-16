package com.mobclaw.android.testapp

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.URLDecoder

private const val CALLBACK_PORT = 1455
private const val CALLBACK_PATH = "/auth/callback"

@Composable
fun ChatGPTLoginWebView(
    authUrl: String,
    onAuthSuccess: (String) -> Unit,
    onAuthCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Opening browser for ChatGPT login...") }
    var serverJob by remember { mutableStateOf<Job?>(null) }

    fun openBrowser() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    LaunchedEffect(authUrl) {
        serverJob = scope.launch {
            runLoopbackServer(
                onStatus = { status = it },
                onCode = { code ->
                    status = "Login callback received."
                    onAuthSuccess(code)
                },
                onCancel = onAuthCancel
            )
        }
        openBrowser()
    }

    DisposableEffect(Unit) {
        onDispose {
            serverJob?.cancel()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("ChatGPT Login", style = MaterialTheme.typography.headlineSmall)
        Text(status)
        Button(
            onClick = { openBrowser() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Open Browser Again")
        }
        Button(
            onClick = onAuthCancel,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cancel Login")
        }
    }
}

private suspend fun runLoopbackServer(
    onStatus: (String) -> Unit,
    onCode: (String) -> Unit,
    onCancel: () -> Unit
) = withContext(Dispatchers.IO) {
    try {
        ServerSocket(CALLBACK_PORT).use { server ->
            onStatus("Waiting for browser callback on http://localhost:$CALLBACK_PORT$CALLBACK_PATH")
            while (isActive) {
                val socket = server.accept()
                socket.use {
                    val reader = BufferedReader(InputStreamReader(it.getInputStream()))
                    val requestLine = reader.readLine().orEmpty()
                    val target = requestLine.split(" ").getOrNull(1).orEmpty()
                    val query = target.substringAfter("?", missingDelimiterValue = "")
                    val params = parseQuery(query)
                    val code = params["code"]
                    val error = params["error"]

                    val body = if (code != null) {
                        onCode(code)
                        "Login complete. You can return to MobClaw."
                    } else {
                        onStatus("Login failed: ${error ?: "missing authorization code"}")
                        onCancel()
                        "Login failed. You can return to MobClaw."
                    }

                    val response = buildString {
                        appendLine("HTTP/1.1 200 OK")
                        appendLine("Content-Type: text/html; charset=utf-8")
                        appendLine("Connection: close")
                        appendLine()
                        appendLine("<!doctype html><html><body><h1>$body</h1></body></html>")
                    }
                    it.getOutputStream().write(response.toByteArray())
                    return@withContext
                }
            }
        }
    } catch (e: Exception) {
        if (isActive) {
            onStatus("Login listener failed: ${e.message}")
            onCancel()
        }
    }
}

private fun parseQuery(query: String): Map<String, String> {
    if (query.isBlank()) return emptyMap()
    return query.split("&")
        .mapNotNull { part ->
            val key = part.substringBefore("=", missingDelimiterValue = "")
            if (key.isBlank()) return@mapNotNull null
            val value = part.substringAfter("=", missingDelimiterValue = "")
            URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }
        .toMap()
}
