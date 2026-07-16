from pathlib import Path

root = Path(__file__).resolve().parents[1]
recorder = root / "app/src/main/java/com/mobclaw/android/testapp/SessionRecorderObserver.kt"
workflow = root / ".github/workflows/apply-verification-screenshot.yml"
text = recorder.read_text()
old = '''    override fun onVerificationCompleted(passed: Boolean, notes: String) {
        appendEvent(
            type = "verification_completed",
            data = buildJsonObject {
                put("passed", passed)
                put("notes", limited(notes))
            },
        )
    }
'''
new = '''    override fun onVerificationCompleted(passed: Boolean, notes: String) {
        val observation = ScreenObservationStore.current
        val screenshot = observation?.let(::persistObservationScreenshot)

        appendEvent(
            type = "verification_completed",
            data = buildJsonObject {
                put("passed", passed)
                put("notes", limited(notes))
                observation?.let {
                    put("snapshotId", it.snapshotId)
                    put("packageName", it.packageName)
                    put("screenshotWidth", it.width)
                    put("screenshotHeight", it.height)
                    put("screenshotAnnotated", true)
                }
                screenshot?.let {
                    put("screenshotFile", it.name)
                    put("screenshotMimeType", "image/jpeg")
                    put("screenshotBytes", it.length())
                }
            },
        )
    }
'''
if old not in text:
    raise SystemExit("verification block not found")
recorder.write_text(text.replace(old, new, 1))
Path(__file__).unlink(missing_ok=True)
workflow.unlink(missing_ok=True)
print("verification screenshot patch applied")
