from pathlib import Path

source = Path("source/Sotigram-Android/app/src/main/java/com/sotigram/app/SupabaseApi.kt")
text = source.read_text(encoding="utf-8")
needle = """        validateCredentials(username, password)
        val payload = JSONObject()
"""
replacement = """        validateCredentials(username, password)
        val availability = request(
            "/rest/v1/rpc/sotigram_username_available",
            JSONObject().put("_username", username).toString(),
            "POST",
            null
        ).trim()
        if (availability != "true") {
            throw IOException("Это имя пользователя уже занято или зарезервировано. Выбери другое.")
        }
        val payload = JSONObject()
"""
if needle not in text:
    raise SystemExit("Could not locate registration block; refusing to patch an unknown source version.")
text = text.replace(needle, replacement, 1)
source.write_text(text, encoding="utf-8")
print("Patched username availability check in SupabaseApi.register().")
