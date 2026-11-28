package com.sotigram.next

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SotigramSession(val accessToken: String, val userId: String, val username: String)

class SupabaseApi(context: Context) {
    companion object {
        private const val BASE_URL = "https://tryhiueaukgahtonattf.supabase.co"
        private const val PUBLIC_KEY = "sb_publishable_u5mIqR_t_UTj2rIgcCqR7A_tmIbTXSs"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
    private val prefs = context.getSharedPreferences("sotigram_session", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    fun savedSession(): SotigramSession? {
        val token = prefs.getString("token", null) ?: return null
        val uid = prefs.getString("uid", null) ?: return null
        val username = prefs.getString("username", null) ?: return null
        return SotigramSession(token, uid, username)
    }

    fun signOut() { prefs.edit().clear().apply() }

    @Synchronized
    fun register(usernameRaw: String, password: String, displayName: String): SotigramSession {
        val username = usernameRaw.trim().removePrefix("@").lowercase()
        require(username.matches(Regex("^[a-z0-9_]{3,32}$"))) { "Логин: 3–32 символа, латиница, цифры и _" }
        require(password.length >= 8) { "Пароль должен содержать минимум 8 символов" }
        val email = "$username@sotigram.app"
        val body = JSONObject().put("email", email).put("password", password)
            .put("data", JSONObject().put("username", username).put("display_name", displayName.ifBlank { username }))
        val auth = call("/auth/v1/signup", body, null)
        val access = auth.optString("access_token")
        val user = auth.optJSONObject("user") ?: auth
        val uid = user.optString("id")
        if (access.isBlank() || uid.isBlank()) {
            throw IllegalStateException("Регистрация создана, но Supabase не выдал сессию. Проверь подтверждение email в настройках Auth.")
        }
        val profile = JSONObject().put("id", uid).put("username", username)
            .put("display_name", displayName.ifBlank { username })
            .put("collection_number", "ST-" + uid.replace("-", "").take(8).uppercase())
        call("/rest/v1/profiles?on_conflict=id", profile, access, "POST", "resolution=merge-duplicates,return=minimal")
        return save(access, uid, username)
    }

    @Synchronized
    fun login(usernameRaw: String, password: String): SotigramSession {
        val username = usernameRaw.trim().removePrefix("@").lowercase()
        require(username.matches(Regex("^[a-z0-9_]{3,32}$"))) { "Проверь логин" }
        val body = JSONObject().put("email", "$username@sotigram.app").put("password", password)
        val auth = call("/auth/v1/token?grant_type=password", body, null)
        val access = auth.optString("access_token")
        val uid = auth.optJSONObject("user")?.optString("id").orEmpty()
        if (access.isBlank() || uid.isBlank()) throw IllegalStateException("Не удалось войти")
        return save(access, uid, username)
    }

    private fun save(token: String, uid: String, username: String): SotigramSession {
        prefs.edit().putString("token", token).putString("uid", uid).putString("username", username).apply()
        return SotigramSession(token, uid, username)
    }

    private fun call(path: String, json: JSONObject, token: String?, method: String = "POST", prefer: String? = null): JSONObject {
        val builder = Request.Builder().url(BASE_URL + path)
            .addHeader("apikey", PUBLIC_KEY)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .method(method, json.toString().toRequestBody(JSON))
        if (token != null) builder.addHeader("Authorization", "Bearer $token")
        if (prefer != null) builder.addHeader("Prefer", prefer)
        http.newCall(builder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = try { JSONObject(raw).optString("msg").ifBlank { JSONObject(raw).optString("message") } } catch (_: Exception) { raw }
                throw IllegalStateException(message.ifBlank { "Ошибка сервера: ${response.code}" })
            }
            return if (raw.isBlank()) JSONObject() else JSONObject(raw)
        }
    }
}
