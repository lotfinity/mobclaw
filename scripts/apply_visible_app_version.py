from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/mobclaw/android/testapp/MainActivity.kt"
BUILD = ROOT / "app/build.gradle.kts"
CHANGELOG = ROOT / "CHANGELOG.md"
WORKFLOW = ROOT / ".github/workflows/apply-visible-app-version.yml"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    if old not in text:
        raise SystemExit(f"Expected block not found in {path}: {old[:120]!r}")
    path.write_text(text.replace(old, new, 1))


replace_once(BUILD, '        versionCode = 2\n        versionName = "1.0.1"', '        versionCode = 3\n        versionName = "1.0.2"')

old_bottom = '''            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Text("\\u2699\\uFE0F") },
                        label = { Text("Task") },
                        selected = currentScreen == Screen.Task.ordinal,
                        onClick = { currentScreen = Screen.Task.ordinal },
                    )
                    NavigationBarItem(
                        icon = { Text("\\uD83D\\uDCCB") },
                        label = { Text("Sessions") },
                        selected = currentScreen == Screen.Sessions.ordinal,
                        onClick = { currentScreen = Screen.Sessions.ordinal },
                    )
                    NavigationBarItem(
                        icon = { Text("\\uD83D\\uDD17") },
                        label = { Text("Providers") },
                        selected = currentScreen == Screen.Providers.ordinal,
                        onClick = { currentScreen = Screen.Providers.ordinal },
                    )
                    NavigationBarItem(
                        icon = { Text("\\u2699\\uFE0F") },
                        label = { Text("Settings") },
                        selected = currentScreen == Screen.Settings.ordinal,
                        onClick = { currentScreen = Screen.Settings.ordinal },
                    )
                }
            },
'''

new_bottom = '''            bottomBar = {
                Column {
                    NavigationBar {
                        NavigationBarItem(
                            icon = { Text("\\u2699\\uFE0F") },
                            label = { Text("Task") },
                            selected = currentScreen == Screen.Task.ordinal,
                            onClick = { currentScreen = Screen.Task.ordinal },
                        )
                        NavigationBarItem(
                            icon = { Text("\\uD83D\\uDCCB") },
                            label = { Text("Sessions") },
                            selected = currentScreen == Screen.Sessions.ordinal,
                            onClick = { currentScreen = Screen.Sessions.ordinal },
                        )
                        NavigationBarItem(
                            icon = { Text("\\uD83D\\uDD17") },
                            label = { Text("Providers") },
                            selected = currentScreen == Screen.Providers.ordinal,
                            onClick = { currentScreen = Screen.Providers.ordinal },
                        )
                        NavigationBarItem(
                            icon = { Text("\\u2699\\uFE0F") },
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
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            },
'''
replace_once(MAIN, old_bottom, new_bottom)

changelog = CHANGELOG.read_text()
if "## 1.0.2" not in changelog:
    section = '''## 1.0.2

### Build identification

- Bumped the screenshot-debug build to version `1.0.2` (`versionCode 3`).
- Added an always-visible footer with version name, version code, and build type.
- Installed builds can now be matched immediately against `adb dumpsys package` output.

'''
    changelog = changelog.replace("# Changelog\n\n", "# Changelog\n\n" + section, 1)
    CHANGELOG.write_text(changelog)

Path(__file__).unlink(missing_ok=True)
WORKFLOW.unlink(missing_ok=True)
print("visible app version applied")
