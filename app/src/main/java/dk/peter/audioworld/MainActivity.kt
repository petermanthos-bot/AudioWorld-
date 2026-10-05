package dk.peter.audioworld

import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var player: ExoPlayer
    private var enhancer: LoudnessEnhancer? = null
    private lateinit var title: TextView
    private lateinit var time: TextView
    private lateinit var seek: SeekBar
    private lateinit var play: Button
    private lateinit var boost: Button
    private lateinit var sleep: Button
    private var boostIndex = 0
    private var sleepIndex = 0
    private val boostDb = intArrayOf(0, 3, 6, 9, 12)
    private val sleepMinutes = intArrayOf(0, 10, 20, 30, 45, 60)
    private val prefs by lazy { getSharedPreferences("book", MODE_PRIVATE) }
    private val handler = android.os.Handler(mainLooper)
    private var sleepRunnable: Runnable? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            prefs.edit().putString("uri", it.toString()).putLong("position", 0).apply()
            load(it, true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        buildUi()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { play.text = if (isPlaying) "⏸  Pause" else "▶  Afspil" }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    setupEnhancer()
                    val d = player.duration
                    if (d in 1..Int.MAX_VALUE.toLong()) seek.max = d.toInt()
                }
            }
        })
        handler.post(update)
        prefs.getString("uri", null)?.let { load(Uri.parse(it), false) }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(36, 48, 36, 32)
            gravity = Gravity.CENTER_HORIZONTAL; setBackgroundColor(0xFF07131E.toInt())
        }
        fun button(label: String) = Button(this).apply { text = label; textSize = 17f; minHeight = 64 }
        val brand = TextView(this).apply { text = "AUDIO WORLD"; textSize = 30f; gravity = Gravity.CENTER; setTextColor(0xFFD6A84B.toInt()) }
        title = TextView(this).apply { text = "Din personlige lydbogsafspiller\nIngen lydbog valgt"; textSize = 19f; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt()); setPadding(0, 12, 0, 28) }
        val open = button("📚  Vælg lydbog")
        time = TextView(this).apply { text = "00:00:00 / 00:00:00"; textSize = 16f; setTextColor(0xFFCCCCCC.toInt()); setPadding(0, 20, 0, 4) }
        seek = SeekBar(this)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val back = button("↶ 30 s"); play = button("▶ Afspil"); val forward = button("30 s ↷")
        controls.addView(back); controls.addView(play); controls.addView(forward)
        val speedLabel = TextView(this).apply { text = "Afspilningshastighed"; setTextColor(0xFFCCCCCC.toInt()); setPadding(0, 22, 0, 4) }
        val speed = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("0,75×", "1,00×", "1,25×", "1,50×", "1,75×", "2,00×", "2,50×", "3,00×")); setSelection(1) }
        boost = button("🔊  Lydboost: Normal")
        sleep = button("🌙  Sleep-timer: Fra")
        root.addView(brand); root.addView(title); root.addView(open, LinearLayout.LayoutParams(-1, -2)); root.addView(time); root.addView(seek, LinearLayout.LayoutParams(-1, -2)); root.addView(controls); root.addView(speedLabel); root.addView(speed, LinearLayout.LayoutParams(-1, -2)); root.addView(boost, LinearLayout.LayoutParams(-1, -2)); root.addView(sleep, LinearLayout.LayoutParams(-1, -2)); setContentView(root)
        open.setOnClickListener { picker.launch(arrayOf("audio/*")) }
        play.setOnClickListener { if (player.isPlaying) player.pause() else player.play() }
        back.setOnClickListener { player.seekTo((player.currentPosition - 30_000).coerceAtLeast(0)) }
        forward.setOnClickListener { val d = player.duration; player.seekTo(if (d > 0) (player.currentPosition + 30_000).coerceAtMost(d) else player.currentPosition + 30_000) }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) player.seekTo(p.toLong()) }; override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {} })
        val speeds = floatArrayOf(.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
        speed.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener { override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}; override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, i: Int, id: Long) { player.setPlaybackSpeed(speeds[i]) } }
        boost.setOnClickListener { boostIndex = (boostIndex + 1) % boostDb.size; applyBoost() }
        sleep.setOnClickListener { sleepIndex = (sleepIndex + 1) % sleepMinutes.size; setSleepTimer() }
    }

    private fun load(uri: Uri, reset: Boolean) { title.text = fileName(uri); player.setMediaItem(MediaItem.fromUri(uri)); player.prepare(); if (!reset) player.seekTo(prefs.getLong("position", 0)); player.play() }
    private fun fileName(uri: Uri): String { contentResolver.query(uri, null, null, null, null)?.use { c -> val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (c.moveToFirst() && i >= 0) return c.getString(i) }; return "Lydbog" }
    private fun setupEnhancer() { try { enhancer?.release(); enhancer = LoudnessEnhancer(player.audioSessionId); applyBoost() } catch (_: Exception) {} }
    private fun applyBoost() { val db = boostDb[boostIndex]; boost.text = if (db == 0) "🔊  Lydboost: Normal" else "🔊  Lydboost: +$db dB"; try { enhancer?.setTargetGain(db * 100); enhancer?.enabled = db > 0 } catch (_: Exception) {} }
    private fun setSleepTimer() { sleepRunnable?.let(handler::removeCallbacks); val m = sleepMinutes[sleepIndex]; sleep.text = if (m == 0) "🌙  Sleep-timer: Fra" else "🌙  Sleep-timer: $m min"; if (m > 0) { sleepRunnable = Runnable { player.pause(); sleepIndex = 0; sleep.text = "🌙  Sleep-timer: Fra" }; handler.postDelayed(sleepRunnable!!, m * 60_000L) } }
    private val update = object : Runnable { override fun run() { val d = player.duration; if (d > 0) { if (d <= Int.MAX_VALUE) { seek.max = d.toInt(); seek.progress = player.currentPosition.coerceAtMost(d).toInt() }; time.text = "${fmt(player.currentPosition)} / ${fmt(d)}"; prefs.edit().putLong("position", player.currentPosition).apply() }; handler.postDelayed(this, 1000) } }
    private fun fmt(ms: Long): String { val s = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0)); return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) }
    override fun onDestroy() { handler.removeCallbacks(update); sleepRunnable?.let(handler::removeCallbacks); prefs.edit().putLong("position", player.currentPosition).apply(); enhancer?.release(); player.release(); super.onDestroy() }
}
