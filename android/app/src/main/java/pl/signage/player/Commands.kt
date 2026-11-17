package pl.signage.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Polecenia zdalne z panelu www. */
object Commands {
    private fun ok(data: JSONObject? = null) = JSONObject().put("ok", true).apply { if (data != null) put("data", data) }
    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg)
    private fun text(t: String) = ok(JSONObject().put("text", t))
    private const val NO_ROOT = "Wymaga root na tablecie"

    fun exec(s: SignageService, cmd: String, a: JSONObject): JSONObject {
        val pkg = s.packageName
        val root = Root.available == true
        return when (cmd) {
            "reload" -> { Sync.run(true); s.notifyPlayer(); ok() }
            "identify" -> { s.main.post { PlayerActivity.get()?.identify() ?: s.bringToFront() }; ok() }
            "screen_on" -> { screenOn(s); ok() }
            "screen_off" -> { screenOff(s); ok() }
            "restart_app" -> {
                L.i("Restart aplikacji na polecenie", true)
                if (root) Root.detached("sleep 1; am force-stop $pkg; sleep 1; am start -n $pkg/.PlayerActivity")
                else { App.scheduleRestart(s, 2000); s.main.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 800) }
                ok()
            }
            "reboot" -> if (!root) err(NO_ROOT) else { L.i("Restart tabletu na polecenie", true); Root.detached("sleep 2; reboot"); ok() }
            "screenshot" -> screenshot(s)
            "tap" -> input(root) { val (w, h) = Net.screenSize(s); "input tap ${(a.optDouble("x") * w).toInt()} ${(a.optDouble("y") * h).toInt()}" }
            "swipe" -> input(root) {
                val (w, h) = Net.screenSize(s)
                "input swipe ${(a.optDouble("x1") * w).toInt()} ${(a.optDouble("y1") * h).toInt()} ${(a.optDouble("x2") * w).toInt()} ${(a.optDouble("y2") * h).toInt()} ${a.optInt("ms", 300)}"
            }
            "key" -> input(root) {
                val code = when (a.optString("key")) { "back" -> 4; "home" -> 3; "recents" -> 187; "power" -> 26; else -> a.optInt("code", 4) }
                "input keyevent $code"
            }
            "text" -> input(root) {
                val t = a.optString("text").replace(" ", "%s").replace("'", "'\\''")
                "input text '$t'"
            }
            "shell" -> {
                val c = a.optString("cmd")
                if (c.isBlank()) err("Puste polecenie") else {
                    L.i("Polecenie powłoki: $c")
                    val r = if (root) Root.run(c, 30000) else Root.sh(c, 30000)
                    text((if (r.ok) "" else "[kod błędu]\n") + r.out.take(60000))
                }
            }
            "logs" -> text(L.tail(300))
            "clear_cache" -> { Sync.clearCache(); Sync.run(true); s.notifyPlayer(); ok() }
            "update" -> update(s, a)
            else -> err("Nieznane polecenie: $cmd")
        }
    }

    private fun input(root: Boolean, cmd: () -> String): JSONObject {
        if (!root) return err(NO_ROOT)
        val r = Root.run(cmd(), 15000)
        return if (r.ok) ok() else err(r.out.take(300))
    }

    fun screenOn(s: SignageService) {
        if (Root.available == true) Root.run("input keyevent 224", 10000) // WAKEUP
        s.main.post { PlayerActivity.get()?.setScreenOff(false) }
        if (!PlayerActivity.resumed) s.bringToFront()
    }

    fun screenOff(s: SignageService) {
        val l = CountDownLatch(1)
        s.main.post { PlayerActivity.get()?.setScreenOff(true); l.countDown() }
        l.await(3, TimeUnit.SECONDS)
        if (Root.available == true) Root.run("input keyevent 223", 10000) // SLEEP
    }

    private fun screenshot(s: SignageService): JSONObject {
        var bmp: Bitmap? = null
        if (Root.available == true) {
            val png = Root.bytes("screencap -p", 20000)
            if (png != null && png.size > 1000) bmp = BitmapFactory.decodeByteArray(png, 0, png.size)
        }
        if (bmp == null) {
            val l = CountDownLatch(1)
            s.main.post { bmp = PlayerActivity.get()?.capture(); l.countDown() }
            l.await(5, TimeUnit.SECONDS)
        }
        val b = bmp ?: return err("Nie udało się pobrać obrazu ekranu")
        val scale = (1024f / maxOf(b.width, b.height)).coerceAtMost(1f)
        val sc = if (scale < 1f) Bitmap.createScaledBitmap(b, (b.width * scale).toInt(), (b.height * scale).toInt(), true) else b
        val out = ByteArrayOutputStream()
        sc.compress(Bitmap.CompressFormat.JPEG, 65, out)
        return ok(JSONObject().put("jpeg", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)).put("w", b.width).put("h", b.height))
    }

    private fun update(s: SignageService, a: JSONObject): JSONObject {
        if (Root.available != true) return err("Zdalna aktualizacja wymaga root")
        val f = File(s.cacheDir, "update.apk")
        val req = Request.Builder().url("${Cfg.server}/api/device/apk").header("Authorization", "Bearer ${Cfg.token}").build()
        Net.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return err("Pobieranie APK: HTTP ${r.code}")
            val md = MessageDigest.getInstance("SHA-256")
            r.body!!.byteStream().use { ins -> f.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n); out.write(buf, 0, n) }
            } }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            val want = a.optString("sha256")
            if (want.isNotEmpty() && want != hex) { f.delete(); return err("Błędna suma kontrolna APK") }
        }
        val pkg = s.packageName
        val t = "/data/local/tmp/signage-update.apk"
        val cp = Root.run("cp '${f.absolutePath}' $t && chmod 644 $t", 30000)
        f.delete()
        if (!cp.ok) return err("Kopiowanie APK: ${cp.out.take(200)}")
        L.i("Instaluję aktualizację", true)
        Root.detached("sleep 1; pm install -r -d $t > /data/local/tmp/signage-update.log 2>&1; sleep 2; am start -n $pkg/.PlayerActivity; rm -f $t")
        return ok(JSONObject().put("text", "Pobrano, instaluję"))
    }
}
