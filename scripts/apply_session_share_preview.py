from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PREVIEW = ROOT / "app/src/main/java/com/mobclaw/android/testapp/SessionPreviewDialog.kt"
MAIN = ROOT / "app/src/main/java/com/mobclaw/android/testapp/MainActivity.kt"
CHANGELOG = ROOT / "CHANGELOG.md"
WORKFLOW = ROOT / ".github/workflows/apply-session-share-preview.yml"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    if old not in text:
        raise SystemExit(f"Expected block not found in {path}: {old[:100]!r}")
    path.write_text(text.replace(old, new, 1))


# Use the real Compose context and make the preview fill the phone screen.
replace_once(
    PREVIEW,
    "import androidx.compose.ui.Modifier\n",
    "import androidx.compose.ui.Modifier\nimport androidx.compose.ui.platform.LocalContext\n",
)
replace_once(
    PREVIEW,
    "import androidx.compose.ui.window.Dialog\n",
    "import androidx.compose.ui.window.Dialog\nimport androidx.compose.ui.window.DialogProperties\n",
)
replace_once(
    PREVIEW,
    """    var selectedTab by remember(session.sessionId) { mutableIntStateOf(0) }
    val rawJson = remember(session) { buildSingleSessionExportJson(session) }

    Dialog(onDismissRequest = onDismiss) {
""",
    """    val context = LocalContext.current
    var selectedTab by remember(session.sessionId) { mutableIntStateOf(0) }
    val rawJson = remember(session) { buildSingleSessionExportJson(session) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
""",
)
PREVIEW.write_text(
    PREVIEW.read_text()
    .replace("LocalSessionContext.current", "context")
    .replace(
        """/** Local context bridge kept private to this UI file. */
private object LocalSessionContext {
    lateinit var current: Context
}

""",
        "",
    )
)

# Add a dedicated Share button to every session card while keeping card tap = preview.
replace_once(
    MAIN,
    """                        Card(
                            onClick = { selectedSession = session },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
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
                        }
""",
    """                        Card(
                            onClick = { selectedSession = session },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
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
                                IconButton(onClick = { shareRecordedSession(context, session) }) {
                                    Icon(
                                        Icons.Default.Share,
                                        contentDescription = "Share this session",
                                    )
                                }
                            }
                        }
""",
)

# Replace the tiny metadata alert with the full timeline/raw-JSON debugger.
replace_once(
    MAIN,
    """        selectedSession?.let { session ->
            AlertDialog(
                onDismissRequest = { selectedSession = null },
                title = { Text("Session ${session.sessionId.take(8)}") },
                text = {
                    Column {
                        Text("Task: ${session.task}", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Provider: ${session.provider}", style = MaterialTheme.typography.bodySmall)
                        Text("Model: ${session.model ?: "default"}", style = MaterialTheme.typography.bodySmall)
                        Text("Events: ${session.events.size}", style = MaterialTheme.typography.bodySmall)
                        Text("Success: ${session.success ?: "unknown"}", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { selectedSession = null }) {
                        Text("Close")
                    }
                },
            )
        }
""",
    """        selectedSession?.let { session ->
            SessionPreviewDialog(
                session = session,
                onDismiss = { selectedSession = null },
            )
        }
""",
)

# Keep release notes explicit for the final v1.0.1 test build.
changelog = CHANGELOG.read_text()
needle = "## 1.0.1"
if needle in changelog and "Single-session JSON sharing" not in changelog:
    insertion = (
        "\n- Single-session JSON sharing directly from each session card."
        "\n- Full-screen session debugger with timeline and raw JSON preview."
        "\n- External JSON viewer action for inspecting the exact shared file.\n"
    )
    position = changelog.index("\n", changelog.index(needle))
    changelog = changelog[:position] + insertion + changelog[position:]
    CHANGELOG.write_text(changelog)

# Remove this one-shot migration from the resulting feature branch.
Path(__file__).unlink(missing_ok=True)
WORKFLOW.unlink(missing_ok=True)

print("single-session share and preview applied")
