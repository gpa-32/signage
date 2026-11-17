package pl.signage.player

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SysSettings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/** Ekran odtwarzania: obrazy (z przenikaniem), wideo, strony www, pasek z tekstem. */
class PlayerActivity : Activity() {

    companion object {
        @Volatile private var ref: WeakReference<PlayerActivity>? = null
        @Volatile var resumed = false
        @Volatile var lastPaused = 0L
        @Volatile var playingName = ""
        @Volatile var playingItem = ""
        fun get(): PlayerActivity? = ref?.get()
    }

    private val h = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var root: FrameLayout
    private lateinit var video: VideoView
    private lateinit var imgA: ImageView
    private lateinit var imgB: ImageView
    private lateinit var web: WebView
    private lateinit var tickerBox: HorizontalScrollView
    private lateinit var tickerText: TextView
    private lateinit var info: TextView
    private lateinit var blackout: View
    private var front: ImageView? = null

    private var mf: Mf? = null
    private var key = "-"
    private var items: List<Item> = emptyList()
    private var idx = -1
    private var current: Item? = null
    var screenOff = false
        private set
    private var identifyUntil = 0L
    private var tickerAnim: ValueAnimator? = null
    private var tickerSig = ""
    private var tapCount = 0
    private var tapStart = 0L
    private var fitCover = false
    private var fade = true

    private val nextR = Runnable { next() }
    private val scheduleR = object : Runnable {
        override fun run() { checkSchedule(); h.postDelayed(this, 15000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Cfg.paired) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish(); return
        }
        ref = WeakReference(this)
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        buildViews()
        setContentView(root)
        hideSystemUi()
        SignageService.start(this)
        mf = Sync.current ?: Mf.load().also { Sync.current = it }
        applyManifest()
        h.postDelayed(scheduleR, 15000)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics)

    private fun buildViews() {
        val match = { FrameLayout.LayoutParams(-1, -1) }
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        // wideo najniżej (SurfaceView), nad nim obrazy i strona
        video = VideoView(this).apply { visibility = View.GONE }
        root.addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        imgA = ImageView(this).apply { alpha = 0f; scaleType = ImageView.ScaleType.FIT_CENTER }
        imgB = ImageView(this).apply { alpha = 0f; scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(imgA, match()); root.addView(imgB, match())
        web = WebView(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
            @Suppress("SetJavaScriptEnabled")
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
        }
        root.addView(web, match())
        tickerText = TextView(this).apply { setSingleLine(true); setPadding(0, dp(6).toInt(), 0, dp(6).toInt()) }
        tickerBox = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(tickerText, FrameLayout.LayoutParams(-2, -2))
            setOnTouchListener { _, _ -> true }
        }
        root.addView(tickerBox, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        info = TextView(this).apply {
            setTextColor(Color.WHITE); gravity = Gravity.CENTER; textSize = 22f
            setBackgroundColor(Color.argb(170, 0, 0, 0)); setPadding(dp(24).toInt(), dp(16).toInt(), dp(24).toInt(), dp(16).toInt())
            visibility = View.GONE
        }
        root.addView(info, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        blackout = View(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        root.addView(blackout, match())
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        hideSystemUi()
        if (current?.type == "video" && !video.isPlaying && !screenOff) h.post(nextR)
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        lastPaused = System.currentTimeMillis()
    }

    override fun onDestroy() {
        if (ref?.get() === this) ref = null
        h.removeCallbacksAndMessages(null)
        tickerAnim?.cancel()
        try { video.stopPlayback(); web.destroy() } catch (_: Exception) {}
        io.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("kiosk")
    override fun onBackPressed() { /* przycisk Wstecz nie zamyka odtwarzacza */ }

    // ---------- treść ----------
    fun onManifest() {
        mf = Sync.current
        applyManifest()
    }

    private fun applyManifest() {
        val m = mf
        if (m == null) { showInfo("Pobieram treść z serwera…"); h.postDelayed({ if (mf == null) { mf = Sync.current; if (mf != null) applyManifest() } }, 5000); return }
        applySettings(m.settings)
        checkSchedule()
    }

    private fun sig(l: List<Item>) = l.joinToString("|") { "${it.id}:${it.duration}:${it.file}:${it.url}:${it.ready}" }

    private fun checkSchedule() {
        val m = mf ?: return
        val (k, name, list) = m.current()
        playingName = name
        val oldSig = sig(items)
        items = list
        when {
            k != key -> { key = k; idx = -1; next() }
            sig(list) != oldSig -> if (current == null || list.none { it.id == current?.id }) next()
            current == null -> next()
        }
    }

    private fun next() {
        h.removeCallbacks(nextR)
        if (screenOff) return
        val list = items.filter { it.ready }
        if (list.isEmpty()) {
            stopAll(); current = null; playingItem = ""
            showInfo(if (items.isEmpty()) "${Cfg.name}\nBrak treści do wyświetlenia" else "${Cfg.name}\nPobieram treść… ${Sync.status}")
            h.postDelayed({ checkSchedule(); if (current == null) next() }, 10000)
            return
        }
        hideInfo()
        idx = (idx + 1) % list.size
        val it = list[idx]
        val c = current
        if (list.size == 1 && c != null && c.id == it.id && c.type != "video") {
            h.postDelayed(nextR, it.duration * 1000L); return
        }
        current = it
        playingItem = it.name
        when (it.type) {
            "image" -> showImage(it)
            "video" -> showVideo(it)
            else -> showWeb(it)
        }
    }

    private fun showImage(it: Item) {
        val f = it.localFile ?: return next()
        val (sw, sh) = Net.screenSize(this)
        io.execute {
            val d = try { decode(f, sw, sh) } catch (e: Throwable) { L.i("Błąd obrazu ${it.name}: $e"); null }
            h.post {
                if (current !== it || isFinishing) return@post
                if (d == null) { h.postDelayed(nextR, 1000); return@post }
                stopVideo(); hideWeb()
                val out = front
                val inV = if (out === imgA) imgB else imgA
                inV.scaleType = if (fitCover) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
                inV.setImageDrawable(d)
                if (Build.VERSION.SDK_INT >= 28 && d is AnimatedImageDrawable) d.start()
                inV.animate().cancel(); out?.animate()?.cancel()
                if (fade && out != null && out.alpha > 0f) {
                    inV.alpha = 0f
                    inV.animate().alpha(1f).setDuration(700).start()
                    out.animate().alpha(0f).setDuration(700).withEndAction { if (front !== out) out.setImageDrawable(null) }.start()
                } else {
                    inV.alpha = 1f
                    out?.alpha = 0f; out?.setImageDrawable(null)
                }
                front = inV
                h.postDelayed(nextR, it.duration * 1000L)
            }
        }
    }

    private fun decode(f: File, sw: Int, sh: Int): Drawable? {
        val n = f.name.lowercase()
        if (Build.VERSION.SDK_INT >= 28 && (n.endsWith(".gif") || n.endsWith(".webp"))) {
            return ImageDecoder.decodeDrawable(ImageDecoder.createSource(f)) { dec, inf, _ ->
                var s = 1
                while (inf.size.width / (s * 2) >= sw && inf.size.height / (s * 2) >= sh) s *= 2
                if (s > 1) dec.setTargetSampleSize(s)
            }
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        val big = maxOf(sw, sh); val small = minOf(sw, sh)
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= big && minOf(o.outWidth, o.outHeight) / (s * 2) >= small) s *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        return BitmapDrawable(resources, bmp)
    }

    private fun hideImages() {
        for (v in listOf(imgA, imgB)) { v.animate().cancel(); v.alpha = 0f; v.setImageDrawable(null) }
        front = null
    }

    private fun showVideo(item: Item) {
        val f = item.localFile ?: return next()
        hideWeb(); hideImages()
        video.visibility = View.VISIBLE
        video.setOnPreparedListener { mp ->
            mp.isLooping = false
            try { mp.setVolume(1f, 1f) } catch (_: Exception) {}
            video.start()
        }
        video.setOnCompletionListener { _ -> if (current === item) next() }
        video.setOnErrorListener { _, what, extra ->
            L.i("Błąd odtwarzania ${item.name} ($what/$extra)", true)
            if (current === item) h.postDelayed(nextR, 1500)
            true
        }
        video.setVideoPath(f.path)
        // limit czasu = czas ustawiony w playliście (dla wideo zwykle równy długości filmu)
        h.postDelayed(nextR, item.duration * 1000L + 1500)
    }

    private fun stopVideo() {
        if (video.visibility == View.VISIBLE) {
            try { video.stopPlayback() } catch (_: Exception) {}
            video.visibility = View.GONE
        }
    }

    private fun showWeb(it: Item) {
        stopVideo(); hideImages()
        web.visibility = View.VISIBLE
        web.loadUrl(it.url ?: "about:blank")
        h.postDelayed(nextR, it.duration * 1000L)
    }

    private fun hideWeb() {
        if (web.visibility == View.VISIBLE) {
            web.visibility = View.GONE
            web.loadUrl("about:blank")
        }
    }

    private fun stopAll() { stopVideo(); hideWeb(); hideImages() }

    // ---------- ustawienia ----------
    private fun applySettings(s: Settings) {
        requestedOrientation = when (s.orientation) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "reverse_landscape" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            "reverse_portrait" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            "auto" -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        if (!screenOff) setBrightness(s.brightness)
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, max * s.volume / 100, 0)
        } catch (_: Exception) {}
        val cover = s.imageFit == "cover"
        if (cover != fitCover) {
            fitCover = cover
            val st = if (cover) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            imgA.scaleType = st; imgB.scaleType = st
        }
        fade = s.transition != "none"
        applyTicker(s.ticker)
    }

    private fun setBrightness(b: Int) {
        val lp = window.attributes
        lp.screenBrightness = if (b < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else (b / 100f).coerceIn(0.01f, 1f)
        window.attributes = lp
    }

    private fun color(s: String, def: Int) = try { Color.parseColor(s) } catch (_: Exception) { def }

    private fun applyTicker(t: Ticker) {
        val sig = "${t.enabled}|${t.text}|${t.speed}|${t.fg}|${t.bg}|${t.size}"
        if (sig == tickerSig) return
        tickerSig = sig
        tickerAnim?.cancel(); tickerAnim = null
        if (!t.enabled || t.text.isBlank()) { tickerBox.visibility = View.GONE; return }
        tickerBox.setBackgroundColor(color(t.bg, Color.BLACK))
        tickerText.setTextColor(color(t.fg, Color.WHITE))
        tickerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.size.toFloat())
        tickerText.text = t.text
        tickerBox.visibility = View.VISIBLE
        tickerText.post {
            val w = root.width.toFloat()
            val tw = tickerText.width.toFloat()
            val pxPerSec = t.speed * 35f * resources.displayMetrics.density
            tickerAnim = ValueAnimator.ofFloat(w, -tw).apply {
                duration = ((w + tw) / pxPerSec * 1000).toLong().coerceAtLeast(1000)
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { a -> tickerText.translationX = a.animatedValue as Float }
                start()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        tickerSig = ""
        mf?.let { m -> root.post { applyTicker(m.settings.ticker) } }
    }

    // ---------- polecenia z serwera ----------
    fun setScreenOff(off: Boolean) {
        if (off == screenOff) return
        screenOff = off
        if (off) {
            h.removeCallbacks(nextR); stopAll(); current = null
            blackout.visibility = View.VISIBLE
            setBrightness(1)
            L.i("Ekran wyłączony")
        } else {
            blackout.visibility = View.GONE
            setBrightness(mf?.settings?.brightness ?: -1)
            idx = -1; next()
            L.i("Ekran włączony")
        }
    }

    fun identify() {
        identifyUntil = System.currentTimeMillis() + 10000
        info.textSize = 48f
        info.text = "${Cfg.name}\n${Net.localIp()}"
        info.visibility = View.VISIBLE
        h.postDelayed({
            if (System.currentTimeMillis() >= identifyUntil) {
                info.textSize = 22f
                if (current != null) info.visibility = View.GONE else next()
            }
        }, 10050)
    }

    private fun showInfo(t: String) {
        if (System.currentTimeMillis() < identifyUntil) return
        info.text = t; info.visibility = View.VISIBLE
    }

    private fun hideInfo() { if (System.currentTimeMillis() >= identifyUntil) info.visibility = View.GONE }

    /** Zrzut ekranu bez root (wideo może wyjść czarne). */
    fun capture(): Bitmap? = try {
        val b = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(b)); b
    } catch (_: Exception) { null }

    // ---------- menu serwisowe: 7 dotknięć w lewy górny róg ----------
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && ev.x < dp(120) && ev.y < dp(120)) {
            val now = System.currentTimeMillis()
            if (now - tapStart > 4000) { tapStart = now; tapCount = 0 }
            if (++tapCount >= 7) { tapCount = 0; serviceMenu() }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun serviceMenu() {
        val opts = arrayOf("Informacje o tablecie", "Wyjdź z trybu kiosku na 15 min", "Ustawienia Androida", "Odłącz od serwera (dodaj ponownie)", "Zamknij")
        AlertDialog.Builder(this).setTitle("Signage Player ${BuildConfig.VERSION_NAME}").setItems(opts) { _, w ->
            when (w) {
                0 -> AlertDialog.Builder(this).setTitle(Cfg.name).setMessage(
                    "Serwer: ${Cfg.server}\nID: ${Cfg.deviceId}\nIP: ${Net.localIp()}\nRoot: ${if (Root.available == true) "tak" else "nie"}\n" +
                        "Połączenie z serwerem: ${if (SignageService.connected) "tak" else "nie"}\nTreść: ${Sync.status}\nTeraz: $playingName / $playingItem"
                ).setPositiveButton("OK", null).show()
                1 -> { SignageService.kioskPausedUntil = System.currentTimeMillis() + 15 * 60000; moveTaskToBack(true) }
                2 -> { SignageService.kioskPausedUntil = System.currentTimeMillis() + 15 * 60000; startActivity(Intent(SysSettings.ACTION_SETTINGS)) }
                3 -> AlertDialog.Builder(this).setMessage("Odłączyć tablet od serwera? Trzeba będzie dodać go ponownie.")
                    .setPositiveButton("Odłącz") { _, _ -> SignageService.get()?.unpaired() ?: run { Cfg.unpair(); startActivity(Intent(this, SetupActivity::class.java)); finish() } }
                    .setNegativeButton("Anuluj", null).show()
            }
        }.show()
    }
}
