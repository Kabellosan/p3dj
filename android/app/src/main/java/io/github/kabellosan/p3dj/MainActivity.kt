package io.github.kabellosan.p3dj

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One screen: P3 status, a big Skip button, Spotify connection, music source and a log. */
class MainActivity : ComponentActivity() {
    private var controller: MediaController? = null
    private lateinit var source: TextView
    private lateinit var now: TextView
    private lateinit var skip: Button
    private lateinit var playStop: Button
    private lateinit var connect: Button
    private lateinit var music: EditText
    private lateinit var logView: TextView

    private val bg = Color.parseColor("#111111")
    private val red = Color.parseColor("#FF001E")
    private val green = Color.parseColor("#1DB954")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Spotify.init(this)
        setContentView(buildUi())
        handleLogin(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        lifecycleScope.launch { Dj.state.collect { render() } }
        lifecycleScope.launch { while (true) { render(); delay(1000) } }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({ controller = runCatching { future.get() }.getOrNull(); render() }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        controller?.release()
        controller = null
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLogin(intent)
    }

    private fun handleLogin(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "p3dj") return
        val code = data.getQueryParameter("code")
        if (code == null) { Dj.log("Spotify login cancelled: ${data.getQueryParameter("error")}"); return }
        lifecycleScope.launch {
            runCatching { Spotify.finishLogin(code) }
                .onSuccess { Dj.log("Spotify connected.") }
                .onFailure { Dj.log(it.message ?: "Spotify login failed") }
            render()
        }
    }

    private fun render() {
        if (!::skip.isInitialized) return
        val st = Dj.state.value
        val c = controller
        val playing = c?.playWhenReady == true && st.mode != Dj.Mode.OFF
        source.text = if (st.mode == Dj.Mode.SPOTIFY) "YOUR SPOTIFY" else "P3 LIVE"
        source.setTextColor(if (st.mode == Dj.Mode.SPOTIFY) green else red)
        now.text = if (st.mode == Dj.Mode.OFF) "Tap play to start" else st.nowPlaying
        when (st.mode) {
            Dj.Mode.OFF -> { skip.text = "Play P3"; tint(skip, green, Color.BLACK) }
            Dj.Mode.P3 -> { skip.text = "Skip song"; tint(skip, green, Color.BLACK) }
            Dj.Mode.SPOTIFY -> {
                val left = ((st.returnAtMs - android.os.SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)
                skip.text = "Back to P3 (%d:%02d)".format(left / 60, left % 60)
                tint(skip, red, Color.WHITE)
            }
        }
        playStop.text = if (playing) "Stop" else "Play"
        connect.text = if (Spotify.loggedIn) "Spotify connected (tap to reconnect)" else "Connect Spotify"
        logView.text = st.log.joinToString("\n")
    }

    private fun play() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) c.setMediaItem(PlaybackService.p3Item())
        c.prepare()
        c.play()
    }

    // ---------- UI ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tint(b: Button, fill: Int, text: Int) {
        b.background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(22).toFloat() }
        b.setTextColor(text)
    }

    private fun button(label: String, fill: Int = Color.parseColor("#333333"), text: Int = Color.WHITE, onClick: () -> Unit) =
        Button(this).apply {
            this.text = label
            isAllCaps = false
            textSize = 16f
            tint(this, fill, text)
            setOnClickListener { onClick() }
        }

    private fun gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(h)) }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(28))
        }
        col.addView(TextView(this).apply { text = "P3 DJ"; textSize = 28f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD })
        col.addView(TextView(this).apply {
            text = "The P3 hosts, live. Bad song? Skip it and your Spotify takes over until the hosts are back. Works with the screen locked, and the car's next-track button skips too."
            setTextColor(Color.parseColor("#9A9A9A")); textSize = 14f
        })
        col.addView(gap(20))
        source = TextView(this).apply { textSize = 13f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.06f }
        col.addView(source)
        now = TextView(this).apply { textSize = 21f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD }
        col.addView(now)
        col.addView(gap(14))
        skip = button("Play P3", green, Color.BLACK) {
            when (Dj.state.value.mode) {
                Dj.Mode.OFF -> play()
                Dj.Mode.P3 -> Dj.skip()
                Dj.Mode.SPOTIFY -> Dj.back("You went back to P3.")
            }
        }.apply { textSize = 28f; layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(120)) }
        col.addView(skip)
        col.addView(gap(10))
        playStop = button("Play") {
            val c = controller ?: return@button
            if (c.playWhenReady && Dj.state.value.mode != Dj.Mode.OFF) { c.stop(); Dj.onStopped() } else play()
        }
        col.addView(playStop, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(gap(20))

        connect = button("Connect Spotify") { startActivity(Intent(Intent.ACTION_VIEW, Spotify.loginUri())) }
        col.addView(connect, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(gap(12))
        col.addView(TextView(this).apply {
            text = "Music for skips: a Spotify playlist link, \"dj\" for Spotify's DJ, or empty for your Liked Songs"
            setTextColor(Color.parseColor("#9A9A9A")); textSize = 14f
        })
        music = EditText(this).apply {
            setText(Spotify.pref("source") ?: "")
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); hint = "Liked Songs"
            isSingleLine = true
        }
        col.addView(music, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(button("Save music choice") {
            Spotify.setPref("source", music.text.toString().trim())
            Dj.resetPool()
            Dj.log("Music for skips: " + (music.text.toString().trim().ifEmpty { "Liked Songs" }))
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        col.addView(gap(16))
        logView = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#9A9A9A")) }
        col.addView(logView)

        return ScrollView(this).apply { setBackgroundColor(bg); addView(col) }.also { render() }
    }
}
