package io.github.kabellosan.p3dj

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** Spotify Web API: login (PKCE, no secret) and the few calls P3 DJ needs. */
object Spotify {
    const val CLIENT_ID = "ee30266f5e654b2a8f222e47efe0cff9"
    const val REDIRECT = "p3dj://callback"
    private const val SCOPES = "user-library-read playlist-read-private playlist-read-collaborative " +
        "user-modify-playback-state user-read-playback-state"

    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) prefs = context.getSharedPreferences("p3dj", Context.MODE_PRIVATE)
    }

    val loggedIn: Boolean get() = prefs.getString("refresh", null) != null
    fun pref(key: String): String? = prefs.getString(key, null)
    fun setPref(key: String, value: String?) = prefs.edit().putString(key, value).apply()

    class ApiError(val code: Int, message: String) : Exception(message)

    // ---------- Login ----------
    fun loginUri(): Uri {
        val verifier = b64(ByteArray(48).also { SecureRandom().nextBytes(it) })
        setPref("verifier", verifier)
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        return Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .build()
    }

    suspend fun finishLogin(code: String) = token(
        "grant_type" to "authorization_code", "code" to code,
        "redirect_uri" to REDIRECT, "code_verifier" to (pref("verifier") ?: ""),
    )

    fun logout() = prefs.edit().remove("access").remove("refresh").remove("expires").apply()

    private fun b64(bytes: ByteArray) =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private suspend fun token(vararg params: Pair<String, String>) = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().add("client_id", CLIENT_ID)
        params.forEach { (k, v) -> form.add(k, v) }
        val req = Request.Builder().url("https://accounts.spotify.com/api/token").post(form.build()).build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string() ?: ""
            if (!r.isSuccessful) throw ApiError(r.code, "Spotify login failed: $body")
            val t = JSONObject(body)
            prefs.edit()
                .putString("access", t.getString("access_token"))
                .putString("expires", (System.currentTimeMillis() + (t.getLong("expires_in") - 120) * 1000).toString())
                .apply {
                    if (t.has("refresh_token")) putString("refresh", t.getString("refresh_token"))
                }.apply()
        }
    }

    private suspend fun accessToken(): String {
        val expires = pref("expires")?.toLongOrNull() ?: 0
        if (System.currentTimeMillis() > expires) {
            val refresh = pref("refresh") ?: throw ApiError(401, "Not logged in to Spotify")
            token("grant_type" to "refresh_token", "refresh_token" to refresh)
        }
        return pref("access")!!
    }

    // ---------- API ----------
    suspend fun api(method: String, path: String, body: JSONObject? = null): JSONObject? = withContext(Dispatchers.IO) {
        val url = if (path.startsWith("http")) path else "https://api.spotify.com/v1$path"
        val rb = when {
            body != null -> body.toString().toRequestBody("application/json".toMediaType())
            method == "GET" -> null
            else -> ByteArray(0).toRequestBody(null)
        }
        val req = Request.Builder().url(url).method(method, rb)
            .header("Authorization", "Bearer ${accessToken()}").build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) throw ApiError(r.code, "Spotify ${r.code}: ${text.take(160)}")
            if (text.isBlank()) null else runCatching { JSONObject(text) }.getOrNull()
        }
    }

    data class Device(val id: String, val name: String, val type: String, val active: Boolean)

    suspend fun devices(): List<Device> {
        val arr = api("GET", "/me/player/devices")?.optJSONArray("devices") ?: JSONArray()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { !it.optBoolean("is_restricted") && !it.isNull("id") }
            .map { Device(it.getString("id"), it.getString("name"), it.getString("type"), it.optBoolean("is_active")) }
    }

    data class Track(val uri: String, val name: String, val seconds: Double)

    /** Your Liked Songs, or a playlist's songs, shuffled. Falls back to Liked Songs. */
    suspend fun loadTracks(playlistId: String?): List<Track> {
        val out = mutableListOf<Track>()
        var next: String? = if (playlistId != null)
            "/playlists/$playlistId/tracks?limit=100&fields=next,items(track(uri,is_local,duration_ms,name))"
        else "/me/tracks?limit=50"
        var fellBack = false
        while (next != null && out.size < 1000) {
            val page = try {
                api("GET", next)
            } catch (e: ApiError) {
                if (playlistId == null || fellBack || out.isNotEmpty()) throw e
                fellBack = true; next = "/me/tracks?limit=50"; continue
            } ?: break
            val items = page.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val t = items.getJSONObject(i).optJSONObject("track") ?: continue
                if (t.optBoolean("is_local")) continue
                out += Track(t.getString("uri"), t.optString("name"), t.optLong("duration_ms") / 1000.0)
            }
            next = page.optString("next").takeIf { it.isNotBlank() && it != "null" }
        }
        return out.shuffled()
    }

    private val lengths = HashMap<String, Double?>()

    /** Full length of a song in seconds, looked up by artist and title. */
    suspend fun songLength(artist: String, title: String?): Double? {
        val key = "$artist|$title"
        if (lengths.containsKey(key)) return lengths[key]
        val q = if (title != null) "track:$title artist:$artist" else artist
        val len = runCatching {
            val items = api("GET", "/search?type=track&limit=5&q=" + Uri.encode(q))
                ?.getJSONObject("tracks")?.getJSONArray("items") ?: JSONArray()
            val list = (0 until items.length()).map { items.getJSONObject(it) }
            val prefix = (title ?: "").lowercase().take(12)
            val hit = list.firstOrNull { it.optString("name").lowercase().startsWith(prefix) } ?: list.firstOrNull()
            hit?.optLong("duration_ms")?.div(1000.0)
        }.getOrNull()
        lengths[key] = len
        return len
    }
}
