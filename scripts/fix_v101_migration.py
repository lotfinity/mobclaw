from pathlib import Path

path = Path("scripts/apply_v101_polish.py")
text = path.read_text(encoding="utf-8")

replacements = {
    '    """                            "[Updated screen]\\n${newScreen.output}\\n\\n" +':
        '    r"""                            "[Updated screen]\\n${newScreen.output}\\n\\n" +',
    '    """                            "[Updated observation]\\n${newScreen.output}\\n\\n" +':
        '    r"""                            "[Updated observation]\\n${newScreen.output}\\n\\n" +',
    '    """    private fun parseMessageText(message: JsonObject): String? {':
        '    r"""    private fun parseMessageText(message: JsonObject): String? {',
}

for old, new in replacements.items():
    count = text.count(old)
    if count == 0:
        raise RuntimeError(f"migration fix pattern not found: {old}")
    text = text.replace(old, new)

path.write_text(text, encoding="utf-8")
print("migration quoting fixed")
