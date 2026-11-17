package pl.signage.player

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.integration.android.IntentIntegrator
import org.json.JSONObject
import java.util.concurrent.Executors

/** Dodawanie tabletu do serwera: kod na ekranie, kod z panelu albo kod QR. */
class SetupActivity : Activity() {
    companion object { @Volatile var active = false }

    private val h = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var server: EditText
    private lateinit var code: TextView
    private lateinit var enroll: EditText
    private lateinit var status: TextView
    private lateinit var permBtn: Button
    @Volatile private var busy = false
    private var lastServer = ""

    private val poll = object : Runnable {
        override fun run() { hello(); h.postDelayed(this, 4000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (resources.displayMetrics.density * 20).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad); setBackgroundColor(Color.parseColor("#111827")) }
        fun tv(t: String, size: Float = 16f, bold: Boolean = false) = TextView(this).apply {
            text = t; textSize = size; setTextColor(Color.WHITE); setPadding(0, pad / 3, 0, pad / 3)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }.also { col.addView(it) }

        tv("Signage Player – dodawanie tabletu", 24f, true)
        tv("Adres serwera:", 14f)
        server = EditText(this).apply {
            setText(Cfg.server); hint = "https://signage.twojadomena.pl"; setSingleLine()
            inputType = InputType.TYPE_TEXT_VARIATION_URI; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        col.addView(server)
        tv("Sposób 1 – wpisz ten kod w panelu (Tablety → Dodaj tablety → Kod z ekranu tabletu):", 14f)
        code = tv("······", 64f, true).apply { gravity = Gravity.CENTER; letterSpacing = 0.2f; setTextColor(Color.parseColor("#60a5fa")) }
        tv("Sposób 2 – kod rejestracji z panelu albo kod QR:", 14f)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        enroll = EditText(this).apply {
            hint = "kod z panelu"; setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        row.addView(enroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply { text = "Dodaj"; setOnClickListener { register(enroll.text.toString()) } })
        col.addView(row)
        col.addView(Button(this).apply { text = "Skanuj kod QR"; setOnClickListener { scan() } })
        permBtn = Button(this).apply { text = "Nadaj uprawnienia (tablet bez root)"; setOnClickListener { permissions() } }
        col.addView(permBtn)
        status = tv("", 14f).apply { setTextColor(Color.parseColor("#9ca3af")) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor("#111827")); addView(col) })

        SignageService.start(this)
        io.execute {
            Root.check()
            h.post { status.text = "Root: ${if (Root.available == true) "tak – uprawnienia nadawane automatycznie" else "nie"}  ·  IP: ${Net.localIp()}  ·  wersja ${BuildConfig.VERSION_NAME}" }
        }
    }

    override fun onResume() {
        super.onResume()
        active = true
        if (Cfg.paired) { done(); return }
        h.post(poll)
    }

    override fun onPause() {
        super.onPause()
        active = false
        h.removeCallbacks(poll)
    }

    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }

    private fun currentServer(): String {
        var s = server.text.toString().trim().trimEnd('/')
        if (s.isNotEmpty() && !s.startsWith("http")) s = "https://$s"
        if (s != lastServer) {
            lastServer = s
            if (s != Cfg.server) { Cfg.server = s; Cfg.pairId = "" }
        }
        return s
    }

    private fun info(): JSONObject = Net.deviceInfo(this)

    /** Sposób 1: serwer nadaje kod, administrator go wpisuje, tablet odpytuje o zatwierdzenie. */
    private fun hello() {
        val srv = currentServer()
        if (srv.isEmpty() || busy) { if (srv.isEmpty()) code.text = "······"; return }
        busy = true
        io.execute {
            try {
                val body = JSONObject().put("info", info())
                if (Cfg.pairId.isNotEmpty()) body.put("pairId", Cfg.pairId)
                val r = Net.postJson("$srv/api/device/hello", body)
                h.post {
                    if (r.optBoolean("approved")) saved(r)
                    else { Cfg.pairId = r.optString("pairId"); code.text = r.optString("code"); setStatus("Połączono z serwerem – czekam na dodanie w panelu") }
                }
            } catch (e: Exception) {
                h.post { code.text = "······"; setStatus("Brak połączenia z serwerem: ${e.message}") }
            } finally { busy = false }
        }
    }

    /** Sposób 2: kod rejestracji z panelu. */
    private fun register(c: String) {
        val srv = currentServer()
        val cc = c.trim().uppercase()
        if (srv.isEmpty() || cc.isEmpty()) { setStatus("Podaj adres serwera i kod"); return }
        setStatus("Rejestruję…")
        io.execute {
            try {
                val r = Net.postJson("$srv/api/device/register", JSONObject().put("code", cc).put("info", info()))
                h.post { saved(r) }
            } catch (e: Exception) { h.post { setStatus("Nie udało się: ${e.message}") } }
        }
    }

    private fun saved(r: JSONObject) {
        Cfg.token = r.getString("token")
        Cfg.deviceId = r.optString("deviceId")
        Cfg.name = r.optString("name")
        Cfg.pairId = ""
        L.i("Tablet dodany do serwera ${Cfg.server} jako ${Cfg.name}")
        SignageService.start(this)
        SignageService.get()?.kick()
        done()
    }

    private fun done() {
        startActivity(Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    private fun setStatus(t: String) {
        val first = status.text.toString().lineSequence().firstOrNull { it.startsWith("Root") }
        status.text = if (first != null) "$first\n$t" else t
    }

    // ---------- QR ----------
    private fun scan() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 3); return
        }
        IntentIntegrator(this).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt("Zeskanuj kod QR z panelu (Tablety → Dodaj tablety)")
            .setBeepEnabled(false).setOrientationLocked(false).initiateScan()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 3 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) scan()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val r = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (r == null) { super.onActivityResult(requestCode, resultCode, data); return }
        val txt = r.contents ?: return
        // oczekiwany format: https://serwer/e/KOD
        val i = txt.indexOf("/e/")
        if (txt.startsWith("http") && i > 0) {
            server.setText(txt.substring(0, i))
            val c = txt.substring(i + 3).trim('/')
            enroll.setText(c)
            register(c)
        } else if (txt.length in 4..10) { enroll.setText(txt); register(txt) }
        else setStatus("Nieznany kod QR: $txt")
    }

    // ---------- uprawnienia bez root ----------
    private fun permissions() {
        try {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return
            }
            if (Build.VERSION.SDK_INT >= 23) {
                @Suppress("BatteryLife")
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4)
        } catch (e: Exception) { setStatus("Ustawienia uprawnień: $e") }
    }
}
