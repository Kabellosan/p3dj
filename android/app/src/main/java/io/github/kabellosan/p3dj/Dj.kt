package io.github.kabellosan.p3dj

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The P3 DJ brain, ported from the web app.
 *
 * P3's stream says "/ Artist - Title" when a song starts and goes back to the show
 * description when the hosts return. On Skip we mute P3 (it keeps streaming, so the app
 * stays alive and we stay at the live edge) and Spotify plays a song on this phone.
 * We come back when the song's real length has passed, when the show text returns after
 * most of the song, or when P3 starts the next song.
 */
object Dj {
    private const val OUTRO_S = 4.0
    private const val UNKNOWN_SONG_S = 210.0
    private const val MATCH_GAP_S = 10.0
    private const val DJ_PLAYLIST = "37i9dQZF1EYkqdzj48dyYq"

    enum class Mode { OFF, P3, SPOTIFY }

    data class Song(val title: String, val artist: String, val track: String?, val startMs: Long,
                    val startKnown: Boolean, val len: Double?) {
        val endMs: Long get() = startMs + (((len ?: UNKNOWN_SONG_S) - OUTRO_S) * 1000).toLong()
        val label: String get() = if (track != null) "$artist · $track" else artist
    }

    data class State(val mode: Mode = Mode.OFF, val nowPlaying: String = "", val returnAtMs: Long = 0,
                     val log: List<String> = emptyList())

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var app: Context
    private var player: ExoPlayer? = null
    private var song: Song? = null
    private var skipping: Song? = null
    private var lastSongTitle: String? = null
    private var firstTitle = true
    private var returnJob: Job? = null
    private var pool: List<Spotify.Track> = emptyList()
    private var poolIdx = 0
    private val recent = ArrayDeque<String>()

    private val attrs = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()

    fun attach(context: Context, exo: ExoPlayer) {
        app = context.applicationContext
        Spotify.init(app)
        player = exo
    }

    fun log(msg: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()) + "  " + msg
        _state.value = _state.value.copy(log = (listOf(line) + _state.value.log).take(60))
    }

    private fun now() = SystemClock.elapsedRealtime()
    private fun songPlaying() = song?.let { now() < it.endMs } == true

    private fun publish() {
        val s = song
        _state.value = _state.value.copy(
            nowPlaying = when (_state.value.mode) {
                Mode.SPOTIFY -> skipping?.let { "Skipping: ${it.label}" } ?: "Skipping"
                else -> if (s != null && songPlaying()) s.label else "P3 live"
            })
    }

    // ---------- Called by the player ----------
    fun onPlaying(isPlaying: Boolean) {
        if (isPlaying && _state.value.mode == Mode.OFF) { _state.value = _state.value.copy(mode = Mode.P3); publish() }
    }

    fun onStopped() {
        returnJob?.cancel()
        if (_state.value.mode == Mode.SPOTIFY) scope.launch { runCatching { Spotify.api("PUT", "/me/player/pause") } }
        player?.volume = 1f
        skipping = null
        _state.value = _state.value.copy(mode = Mode.OFF, returnAtMs = 0)
        publish()
    }

    fun onTitle(raw: String?) {
        val title = raw?.trim() ?: ""
        val first = firstTitle
        firstTitle = false
        if (title.startsWith("/")) {
            if (title == lastSongTitle) return
            lastSongTitle = title
            val s = title.removePrefix("/").trim()
            val i = s.indexOf(" - ")
            val artist = if (i >= 0) s.substring(0, i) else s
            val track = if (i >= 0) s.substring(i + 3) else null
            val seen = now()
            scope.launch {
                val len = if (Spotify.loggedIn) Spotify.songLength(artist, track) else null
                val start = if (!first) seen - 3000 else seen - 45000
                song = Song(title, artist, track, start, !first, len)
                log("P3 song: $artist - ${track ?: ""} (${len?.let { "${it.toInt()} s" } ?: "length unknown"})")
                onNewSong()
                publish()
            }
        } else if (title.isNotEmpty()) {
            // Show text is back: usually the hosts, but only trust it late in the song.
            val sk = skipping
            if (_state.value.mode == Mode.SPOTIFY && sk != null) {
                val played = (now() - sk.startMs) / 1000.0
                val enough = sk.len?.let { played >= 0.6 * it } ?: (played >= 90)
                if (enough) back("P3's now-playing says the show is back, back to P3.")
            }
            publish()
        }
    }

    private fun onNewSong() {
        val s = song ?: return
        if (_state.value.mode != Mode.SPOTIFY || skipping?.title == s.title) return
        back("P3 started a new song, back to P3.")
    }

    // ---------- Skip and return ----------
    fun skip() {
        if (_state.value.mode != Mode.P3) return
        if (!Spotify.loggedIn) { log("Connect Spotify first."); return }
        val p = player ?: return
        skipping = if (songPlaying()) song else null
        var returnAt = skipping?.endMs ?: (now() + (UNKNOWN_SONG_S * 1000).toLong())
        if (skipping == null) log("Can't tell which song is on, so skipping for 3:30.")
        else if (!skipping!!.startKnown) log("Started mid-song, so the return time is a guess.")
        _state.value = _state.value.copy(mode = Mode.SPOTIFY, returnAtMs = returnAt)
        publish()
        // Keep P3 streaming silently and stop it from pausing when Spotify takes over the audio.
        p.setAudioAttributes(attrs, false)
        p.volume = 0f
        scope.launch {
            try {
                var target = 0.0
                val sk = skipping
                if (sk?.len != null && sk.startKnown) {
                    target = (sk.startMs + sk.len * 1000 - now()) / 1000.0 - MATCH_GAP_S
                    if (target < 45) target = 0.0
                }
                val t0 = now()
                val (name, len) = startOnPhone(target)
                if (len != null) returnAt = t0 + ((len - 1) * 1000).toLong()
                _state.value = _state.value.copy(returnAtMs = returnAt)
                log("Skipped → Spotify on $name, back in ${(returnAt - now()) / 1000} s")
                scheduleReturn(returnAt)
            } catch (e: Exception) {
                log(e.message ?: "Skip failed")
                restoreP3()
            }
        }
    }

    private fun scheduleReturn(atMs: Long) {
        returnJob?.cancel()
        returnJob = scope.launch {
            delay((atMs - now()).coerceAtLeast(0))
            back("P3's song should be over, back to P3.")
        }
    }

    fun back(reason: String) {
        if (_state.value.mode != Mode.SPOTIFY) return
        log(reason)
        returnJob?.cancel()
        scope.launch { runCatching { Spotify.api("PUT", "/me/player/pause") }.onFailure { log("Couldn't pause Spotify: ${it.message}") } }
        restoreP3()
    }

    private fun restoreP3() {
        skipping = null
        player?.let {
            it.volume = 1f
            it.setAudioAttributes(attrs, true)   // taking audio focus also pauses Spotify
            if (!it.isPlaying) { it.prepare(); it.play() }
        }
        _state.value = _state.value.copy(mode = Mode.P3, returnAtMs = 0)
        publish()
    }

    // ---------- Spotify on this phone ----------
    private fun source(): String = Spotify.pref("source") ?: ""
    private fun isDj() = source().lowercase().let { it == "dj" || it.contains(DJ_PLAYLIST.lowercase()) }
    private fun playlistId(): String? =
        if (isDj()) null else Regex("playlist[/:]([A-Za-z0-9]+)").find(source())?.groupValues?.get(1)

    private suspend fun phoneDevice(): Spotify.Device? {
        val list = Spotify.devices()
        val chosen = Spotify.pref("deviceName")
        val dev = list.firstOrNull { chosen != null && it.name == chosen }
            ?: list.firstOrNull { it.type == "Smartphone" && it.active }
            ?: list.firstOrNull { it.type == "Smartphone" }
        if (dev != null && dev.type == "Smartphone") { Spotify.setPref("phoneId", dev.id); Spotify.setPref("phoneName", dev.name) }
        return dev
    }

    private suspend fun startOnPhone(target: Double): Pair<String, Double?> {
        phoneDevice()?.let { return it.name to startMusic(it.id, target) }
        Spotify.pref("phoneId")?.let { id ->
            try { val len = startMusic(id, target); log("Woke Spotify on your phone."); return (Spotify.pref("phoneName") ?: "your phone") to len }
            catch (e: Exception) { log("Spotify on your phone is asleep, opening it…") }
        }
        app.packageManager.getLaunchIntentForPackage("com.spotify.music")?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { app.startActivity(it) }
        }
        repeat(20) {
            delay(1000)
            val dev = runCatching { phoneDevice() }.getOrNull()
            if (dev != null) return dev.name to startMusic(dev.id, target)
        }
        throw Exception("Spotify didn't show up on your phone. Open Spotify, play anything for a second, then skip again.")
    }

    private fun isDeviceError(e: Exception) = e is Spotify.ApiError && (e.code == 404 || e.message?.contains("device", true) == true)

    private suspend fun ensurePool() {
        if (pool.isEmpty()) {
            pool = Spotify.loadTracks(playlistId())
            poolIdx = 0
            log("Loaded ${pool.size} songs.")
        }
    }
    fun resetPool() { pool = emptyList() }

    private fun nextUris(n: Int): List<String> = (0 until minOf(n, pool.size)).map { pool[(poolIdx++) % pool.size].uri }

    private fun pickForLength(target: Double): Spotify.Track? {
        val fits = pool.filter { it.seconds in (target - 25)..target && it.uri !in recent }
        val pick = fits.randomOrNull() ?: pool.filter { it.seconds <= target && it.uri !in recent }.maxByOrNull { it.seconds }
        pick?.let { recent.addLast(it.uri); if (recent.size > 30) recent.removeFirst() }
        return pick
    }

    /** Starts music on the device. Returns the song length if it picked a length-matched song. */
    private suspend fun startMusic(deviceId: String, target: Double): Double? {
        val url = "/me/player/play?device_id=$deviceId"
        if (target > 0 && !isDj()) {
            ensurePool()
            pickForLength(target)?.let { pick ->
                val uris = org.json.JSONArray((listOf(pick.uri) + nextUris(5).filter { it != pick.uri }))
                Spotify.api("PUT", url, org.json.JSONObject().put("uris", uris))
                log("Picked \"${pick.name}\" (${pick.seconds.toInt() / 60}:${"%02d".format(pick.seconds.toInt() % 60)}) to fit P3's song.")
                return pick.seconds
            }
            log("No song of yours is short enough to fit, playing as usual.")
        }
        if (isDj()) {
            try {
                Spotify.api("PUT", url, org.json.JSONObject().put("context_uri", "spotify:playlist:$DJ_PLAYLIST"))
                log("Started Spotify's DJ."); return null
            } catch (e: Exception) {
                if (isDeviceError(e)) throw e
                log("Spotify wouldn't start the DJ, playing your Liked Songs instead.")
            }
        }
        playlistId()?.let { id ->
            try {
                runCatching { Spotify.api("PUT", "/me/player/shuffle?state=true&device_id=$deviceId") }
                Spotify.api("PUT", url, org.json.JSONObject().put("context_uri", "spotify:playlist:$id"))
                return null
            } catch (e: Exception) {
                if (isDeviceError(e)) throw e
                log("Couldn't play that playlist, using your songs list instead.")
            }
        }
        ensurePool()
        Spotify.api("PUT", url, org.json.JSONObject().put("uris", org.json.JSONArray(nextUris(10))))
        return null
    }
}
