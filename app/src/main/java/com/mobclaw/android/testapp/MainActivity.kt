package com.mobclaw.android.testapp

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mobclaw.android.accessibility.MobClawAccessibilityService
import com.mobclaw.android.core.AgentResult
import com.mobclaw.android.core.MobAgent
import com.mobclaw.android.core.MobClawConfig
import com.mobclaw.android.observer.CompositeObserver
import com.mobclaw.android.overlay.AgentOverlay
import com.mobclaw.android.overlay.OverlayObserver
import com.mobclaw.android.provider.AnthropicProvider
import com.mobclaw.android.provider.GeminiProvider
import com.mobclaw.android.provider.LiteLlmProvider
import com.mobclaw.android.provider.LlmProvider
import com.mobclaw.android.provider.ModelInfo
import com.mobclaw.android.provider.NvidiaProvider
import com.mobclaw.android.provider.OllamaProvider
import com.mobclaw.android.provider.OpenAiCompatibleProvider
import com.mobclaw.android.provider.OpenAiProvider
import com.mobclaw.android.provider.OpenRouterProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    @Volatile
    private var activeAgent: MobAgent? = null

    private enum class Screen { Task, Sessions, Providers, Settings }

    private enum class ProviderType(
        val label: String,
        val requiresApiKey: Boolean,
        val apiKeyHint: String,
    ) {
        LITELLM("LiteLLM Gateway", false, "LiteLLM API Key"),
        NVIDIA("NVIDIA NIM", false, "NVIDIA API Key"),
        GEMINI("Gemini", true, "Gemini API Key"),
        OPENAI("OpenAI", true, "OpenAI API Key"),
        ANTHROPIC("Anthropic", true, "Anthropic API Key"),
        OPENROUTER("OpenRouter", true, "OpenRouter API Key"),
        OLLAMA("Ollama", false, "Ollama API Key (optional)"),
        CUSTOM("Custom OpenAI-Compatible", true, "API Key"),
    }

    private enum class TestPreset(
        val label: String,
        val prompt: String,
    ) {
        CUSTOM(
            label = "Custom",
            prompt = "",
        ),
        CAMERA_MODE(
            label = "Camera mode",
            prompt = "Open the Camera app and report which camera mode is currently selected. Do not change anything.",
        ),
        ANDROID_VERSION(
            label = "Android version",
            prompt = "Open Settings and report the exact Android version shown under Software information. Do not confuse it with the One UI version or Build number.",
        ),
        FULL_SYSTEM_INFO(
            label = "Full system info",
            prompt = "Open Settings and report the exact Android version, One UI version, Android security patch level, Baseband version, Kernel version, and Build number. Keep every field separate and do not finish until the latest visible screen supports every answer.",
        ),
        SETTINGS_SEARCH(
            label = "Settings search",
            prompt = "Open Settings, search for \"battery protection\", open the matching result, and report the currently selected protection mode. Do not change it.",
        ),
        NAVIGATION_RECOVERY(
            label = "Navigation recovery",
            prompt = "Find the exact device model name in Settings and report it. Recover correctly regardless of which app or Settings page is currently open.",
        ),
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
            ) {
                MobClawApp()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MobClawApp() {
        var currentScreen by remember { mutableIntStateOf(Screen.Task.ordinal) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("MobClaw") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            },
            bottomBar = {
                Column {
                    NavigationBar {
                        NavigationBarItem(
                            icon = { Text("\u2699\uFE0F") },
                            label = { Text("Task") },
                            selected = currentScreen == Screen.Task.ordinal,
                            onClick = { currentScreen = Screen.Task.ordinal },
                        )
                        NavigationBarItem(
                            icon = { Text("\uD83D\uDCCB") },
                            label = { Text("Sessions") },
                            selected = currentScreen == Screen.Sessions.ordinal,
                            onClick = { currentScreen = Screen.Sessions.ordinal },
                        )
                        NavigationBarItem(
                            icon = { Text("\uD83D\uDD17") },
                            label = { Text("Providers") },
                            selected = currentScreen == Screen.Providers.ordinal,
                            onClick = { currentScreen = Screen.Providers.ordinal },
                        )
                        NavigationBarItem(
                            icon = { Text("\u2699\uFE0F") },
                            label = { Text("Settings") },
                            selected = currentScreen == Screen.Settings.ordinal,
                            onClick = { currentScreen = Screen.Settings.ordinal },
                        )
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = "MobClaw v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TYPE}",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            },
        ) { padding ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                when (currentScreen) {
                    Screen.Task.ordinal -> TaskScreen()
                    Screen.Sessions.ordinal -> SessionsScreen()
                    Screen.Providers.ordinal -> ProvidersScreen()
                    Screen.Settings.ordinal -> SettingsScreen()
                }
            }
        }
    }

    @Composable
    private fun TaskScreen() {
        val nvidiaModels = remember {
            listOf(
                "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning",
                "nvidia/nemotron-nano-12b-v2-vl",
                "meta/llama-3.2-90b-vision-instruct",
            )
        }

        var selectedProvider by remember { mutableStateOf(ProviderType.NVIDIA) }
        var apiKey by remember { mutableStateOf(BuildConfig.NVIDIA_API_KEY) }
        var selectedPreset by remember { mutableStateOf(TestPreset.CUSTOM) }
        var task by remember { mutableStateOf("") }
        var isRunning by remember { mutableStateOf(false) }
        var resultText by remember { mutableStateOf<String?>(null) }

        var providerMenuExpanded by remember { mutableStateOf(false) }
        var presetMenuExpanded by remember { mutableStateOf(false) }
        var liteLlmModel by remember { mutableStateOf(nvidiaModels.first()) }
        var liteLlmModelMenuExpanded by remember { mutableStateOf(false) }
        var customBaseUrl by remember { mutableStateOf("https://api.example.com/v1") }
        var customModel by remember { mutableStateOf("gpt-4o-mini") }
        var availableModels by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
        var modelsLoading by remember { mutableStateOf(false) }
        var modelsError by remember { mutableStateOf<String?>(null) }

        val mobMock = remember { com.mobmock.MobMock(this@MainActivity) }
        var accessibilityOk by remember { mutableStateOf(isAccessibilityEnabled()) }
        var overlayOk by remember { mutableStateOf(isOverlayPermissionGranted()) }
        val lifecycleOwner = LocalLifecycleOwner.current
        val scope = rememberCoroutineScope()
        val context = LocalContext.current

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    accessibilityOk = isAccessibilityEnabled()
                    overlayOk = isOverlayPermissionGranted()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("MobClaw Agent", style = MaterialTheme.typography.headlineSmall)

            if (!accessibilityOk || !overlayOk) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        if (!accessibilityOk) {
                            Text(
                                "Accessibility Service not enabled",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Button(
                                onClick = { openAccessibilitySettings() },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(vertical = 8.dp),
                            ) {
                                Text("Enable Accessibility")
                            }
                        }
                        if (!overlayOk) {
                            Text(
                                "Overlay Permission not granted",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Button(
                                onClick = { openOverlaySettings() },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(vertical = 8.dp),
                            ) {
                                Text("Grant Overlay")
                            }
                        }
                    }
                }
            }

            Text("Provider", style = MaterialTheme.typography.labelLarge)
            Box(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { providerMenuExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(selectedProvider.label)
                }
                DropdownMenu(
                    expanded = providerMenuExpanded,
                    onDismissRequest = { providerMenuExpanded = false },
                ) {
                    ProviderType.entries.forEach { provider ->
                        DropdownMenuItem(
                            text = { Text(provider.label) },
                            onClick = {
                                selectedProvider = provider
                                if (provider == ProviderType.NVIDIA) {
                                    apiKey = BuildConfig.NVIDIA_API_KEY
                                    liteLlmModel = nvidiaModels.first()
                                }
                                providerMenuExpanded = false
                            },
                        )
                    }
                }
            }

            when (selectedProvider) {
                ProviderType.LITELLM, ProviderType.NVIDIA -> {
                    Text("Model", style = MaterialTheme.typography.labelLarge)
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { liteLlmModelMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(liteLlmModel)
                        }
                        DropdownMenu(
                            expanded = liteLlmModelMenuExpanded,
                            onDismissRequest = { liteLlmModelMenuExpanded = false },
                        ) {
                            val models = when (selectedProvider) {
                                ProviderType.NVIDIA -> nvidiaModels
                                else -> listOf(
                                    "qwen/qwen3.5-397b-a17b",
                                    "openai/gpt-oss-120b",
                                    "stepfun-ai/step-3.7-flash",
                                    "nvidia/nemotron-3-ultra-550b-a55b",
                                    "deepseek-ai/deepseek-v4-flash",
                                    "moonshotai/kimi-k2.6",
                                )
                            }
                            models.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model) },
                                    onClick = {
                                        liteLlmModel = model
                                        liteLlmModelMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }

                ProviderType.CUSTOM -> {
                    OutlinedTextField(
                        value = customBaseUrl,
                        onValueChange = { customBaseUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Base URL") },
                        singleLine = true,
                    )

                    Text("Model", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = customModel,
                            onValueChange = { customModel = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("Model Name") },
                            singleLine = true,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                scope.launch {
                                    modelsLoading = true
                                    modelsError = null
                                    try {
                                        val provider = OpenAiCompatibleProvider(
                                            apiKey = apiKey.ifBlank { null },
                                            model = customModel,
                                            baseUrl = customBaseUrl,
                                        )
                                        availableModels = provider.listModels()
                                    } catch (e: Exception) {
                                        modelsError = e.message
                                    } finally {
                                        modelsLoading = false
                                    }
                                }
                            },
                            enabled = !modelsLoading,
                        ) {
                            if (modelsLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.width(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "Fetch models")
                            }
                        }
                    }

                    if (availableModels.isNotEmpty()) {
                        Text("Available Models", style = MaterialTheme.typography.labelSmall)
                        LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                            items(availableModels) { model ->
                                FilterChip(
                                    selected = customModel == model.id,
                                    onClick = { customModel = model.id },
                                    label = {
                                        Text(
                                            model.id,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                )
                            }
                        }
                    }

                    modelsError?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                else -> Unit
            }

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(selectedProvider.apiKeyHint) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )

            Text("Test preset", style = MaterialTheme.typography.labelLarge)
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { presetMenuExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isRunning,
                ) {
                    Text(selectedPreset.label)
                }
                DropdownMenu(
                    expanded = presetMenuExpanded,
                    onDismissRequest = { presetMenuExpanded = false },
                ) {
                    TestPreset.entries.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.label) },
                            onClick = {
                                selectedPreset = preset
                                presetMenuExpanded = false
                                if (preset != TestPreset.CUSTOM) {
                                    task = preset.prompt
                                    resultText = null
                                }
                            },
                        )
                    }
                }
            }
            Text(
                "Selecting a preset fills the editable task field and never starts it automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = task,
                onValueChange = { updatedTask ->
                    task = updatedTask
                    if (selectedPreset != TestPreset.CUSTOM && updatedTask != selectedPreset.prompt) {
                        selectedPreset = TestPreset.CUSTOM
                    }
                    if (!isRunning) resultText = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp),
                label = { Text("Task description") },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        val trimmedKey = apiKey.trim()
                        val trimmedTask = task.trim()

                        if (selectedProvider.requiresApiKey && trimmedKey.isEmpty()) {
                            Toast.makeText(context, "Enter API key", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (trimmedTask.isEmpty()) {
                            Toast.makeText(context, "Enter a task", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (!isAccessibilityEnabled()) {
                            Toast.makeText(context, "Enable accessibility service", Toast.LENGTH_LONG).show()
                            return@Button
                        }
                        if (!isOverlayPermissionGranted()) {
                            Toast.makeText(context, "Grant overlay permission", Toast.LENGTH_LONG).show()
                            return@Button
                        }

                        scope.launch {
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
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isRunning,
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .height(16.dp)
                                .width(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (isRunning) "Running..." else "Execute")
                }

                if (isRunning) {
                    OutlinedButton(
                        onClick = { activeAgent?.cancel() },
                        contentPadding = PaddingValues(vertical = 12.dp),
                    ) {
                        Text("Stop")
                    }
                }
            }

            resultText?.let { message ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }

    @Composable
    private fun SessionsScreen() {
        val context = LocalContext.current
        var sessions by remember { mutableStateOf(SessionStore.listSessions(context)) }
        var selectedSession by remember { mutableStateOf<RecordedSession?>(null) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Sessions (${sessions.size})", style = MaterialTheme.typography.headlineSmall)
                Row {
                    IconButton(onClick = { sessions = SessionStore.listSessions(context) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(
                        onClick = {
                            if (sessions.isNotEmpty()) {
                                val jsonFile = SessionStore.exportAllJson(context)
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    jsonFile,
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    Intent.createChooser(shareIntent, "Export Sessions"),
                                )
                            }
                        },
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Export JSON")
                    }
                    IconButton(
                        onClick = {
                            if (sessions.isNotEmpty()) {
                                val mdFile = SessionStore.exportAllMarkdown(context)
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    mdFile,
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/markdown"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    Intent.createChooser(shareIntent, "Export Markdown"),
                                )
                            }
                        },
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Export Markdown")
                    }
                    IconButton(
                        onClick = {
                            SessionStore.clear(context)
                            sessions = emptyList()
                        },
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear All")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (sessions.isEmpty()) {
                Text(
                    "No sessions yet. Execute a task to create one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sessions) { session ->
                        Card(
                            onClick = { selectedSession = session },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        session.task.take(80),
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        "${session.provider} | ${session.model ?: "default"} | ${session.events.size} events",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        java.util.Date(session.startedAtEpochMs).toString(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(
                                    onClick = { shareRecordedSession(context, session) },
                                ) {
                                    Icon(
                                        Icons.Default.Share,
                                        contentDescription = "Share this session",
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        selectedSession?.let { session ->
            SessionPreviewDialog(
                session = session,
                onDismiss = { selectedSession = null },
            )
        }
    }

    @Composable
    private fun ProvidersScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Providers", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Configure API keys and endpoints for each provider. Keys are stored locally and never committed to source control.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ProviderType.entries.forEach { provider ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(provider.label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (provider.requiresApiKey) "Requires API key" else "No key required",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Agent Configuration", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Max iterations: 60", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Temperature: 0.2 · Stability wait: 500ms",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("Max stuck count: 3", style = MaterialTheme.typography.bodySmall)
                    Text("Verify on finish: enabled", style = MaterialTheme.typography.bodySmall)
                    Text("Max actions per turn: 1", style = MaterialTheme.typography.bodySmall)
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Security", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "API keys are stored in local.properties (not in source control)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "NVIDIA key: set nvidia.apiKey in local.properties",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "LiteLLM key: set litellm.apiKey in local.properties",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Accessibility", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    val isEnabled = isAccessibilityEnabled()
                    Text(
                        if (isEnabled) {
                            "MobClaw accessibility service is ENABLED"
                        } else {
                            "MobClaw accessibility service is DISABLED"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isEnabled) {
                            Color(0xFF1B5E20)
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    Button(
                        onClick = { openAccessibilitySettings() },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        Text("Open Accessibility Settings")
                    }
                }
            }
        }
    }

    private suspend fun executeTask(
        mobMock: com.mobmock.MobMock,
        providerType: ProviderType,
        apiKey: String,
        liteLlmModel: String,
        customBaseUrl: String,
        customModel: String,
        task: String,
    ): AgentResult {
        val agentOverlay = AgentOverlay(applicationContext)
        val provider = buildProvider(
            mobMock = mobMock,
            providerType = providerType,
            apiKey = apiKey,
            liteLlmModel = liteLlmModel,
            customBaseUrl = customBaseUrl,
            customModel = customModel,
        )
        val recorder = SessionRecorderObserver(
            context = applicationContext,
            provider = providerType.label,
            model = when (providerType) {
                ProviderType.LITELLM, ProviderType.NVIDIA -> liteLlmModel
                ProviderType.CUSTOM -> customModel
                else -> null
            },
        )
        val agent = MobAgent.builder()
            .provider(provider)
            .observer(
                CompositeObserver(
                    listOf(
                        OverlayObserver(agentOverlay),
                        recorder,
                    ),
                ),
            )
            .config(MobClawConfig())
            .build()

        agentOverlay.onStopRequested = {
            agent.cancel()
            agentOverlay.updateStatus("Stopping...")
        }

        activeAgent = agent
        return try {
            agent.execute(task)
        } finally {
            activeAgent = null
        }
    }

    private fun buildProvider(
        mobMock: com.mobmock.MobMock,
        providerType: ProviderType,
        apiKey: String,
        liteLlmModel: String,
        customBaseUrl: String,
        customModel: String,
    ): LlmProvider {
        return when (providerType) {
            ProviderType.GEMINI -> GeminiProvider(apiKey = apiKey)
            ProviderType.OPENAI -> OpenAiProvider(apiKey = apiKey)
            ProviderType.LITELLM -> {
                val effectiveKey = apiKey.ifBlank { BuildConfig.LITELLM_API_KEY }
                if (effectiveKey.isBlank()) {
                    throw IllegalArgumentException("Enter a LiteLLM API key")
                }
                LiteLlmProvider(
                    apiKey = effectiveKey,
                    model = liteLlmModel,
                    baseUrl = BuildConfig.LITELLM_BASE_URL,
                )
            }

            ProviderType.NVIDIA -> {
                val effectiveKey = apiKey.ifBlank { BuildConfig.NVIDIA_API_KEY }
                if (effectiveKey.isBlank()) {
                    throw IllegalArgumentException("Enter an NVIDIA API key")
                }
                NvidiaProvider(apiKey = effectiveKey, model = liteLlmModel)
            }

            ProviderType.ANTHROPIC -> AnthropicProvider(apiKey = apiKey)
            ProviderType.OPENROUTER -> OpenRouterProvider(apiKey = apiKey)
            ProviderType.OLLAMA -> {
                if (apiKey.isBlank()) {
                    OllamaProvider()
                } else {
                    OllamaProvider(apiKey = apiKey)
                }
            }

            ProviderType.CUSTOM -> {
                if (apiKey.isBlank()) {
                    throw IllegalArgumentException("Enter an API key for custom provider")
                }
                OpenAiCompatibleProvider(
                    apiKey = apiKey,
                    model = customModel,
                    baseUrl = customBaseUrl,
                )
            }
        }
    }

    private fun isAccessibilityEnabled(): Boolean =
        MobClawAccessibilityService.instance != null

    private fun isOverlayPermissionGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    private fun isSystemInDarkTheme(): Boolean {
        return (
            resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
            ) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
}
