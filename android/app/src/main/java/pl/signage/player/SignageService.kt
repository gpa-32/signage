package pl.signage.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Usługa pierwszoplanowa: połączenie z serwerem, synchronizacja, polecenia, harmonogram ekranu, kiosk, watchdog. */
class SignageService : Service() {

    companion object {
        @Volatile private var inst: SignageService? = null
        @Volatile var connected = false
        @Volatile var kioskPausedUntil = 0L
        fun get() = inst

        fun start(c: Context) {
            val i = Intent(c, SignageService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            } catch (e: Exception) { L.i("Nie można uruchomić usługi: $e") }
        }
    }

    val main = Handler(Looper.getMainLooper())
    private val sched = Executors.newSingleThreadScheduledExecutor()
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var ws: WebSocket? = null
    private var backoff = 2000L
    @Volatile private var nextConnect = 0L
    @Volatile private var lastSync = 0L
    private var lastStatus = 0L
    private var lastScreenWant: Boolean? = null
    private var rebootDay = -1
    @Volatile private var mainBeat = SystemClock.elapsedRealtime()
    private val started = SystemClock.elapsedRealtime()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        inst = this
        startForeground(1, notification())
        L.i("Usługa uruchomiona")
        L.remote = { msg -> send(JSONObject().put("type", "log").put("text", msg)) }
        pool.execute {
            Root.check()
            L.i("Root: ${if (Root.available == true) "tak" else "nie"}")
            if (Root.available == true) rootSetup()
        }
        sched.scheduleWithFixedDelay({ try { tick() } catch (e: Throwable) { L.i("Błąd: $e") } }, 1, 10, TimeUnit.SECONDS)
        // watchdog wątku głównego – gdy aplikacja się zawiesi, restart procesu
        Thread {
            while (true) {
                try { Thread.sleep(10000) } catch (_: InterruptedException) { return@Thread }
                main.post { mainBeat = SystemClock.elapsedRealtime() }
                if (SystemClock.elapsedRealtime() - mainBeat > 45000) {
                    L.i("Wątek główny nie odpowiada – restart aplikacji", true)
                    App.scheduleRestart(this, 2000)
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }
        }.apply { isDaemon = true; name = "watchdog" }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        L.i("Usługa zatrzymana")
        inst = null
        ws?.cancel(); connected = false
        sched.shutdownNow()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("signage", "Signage Player", NotificationManager.IMPORTANCE_MIN))
            Notification.Builder(this, "signage")
        } else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_launcher).setContentTitle("Signage Player").setContentText("Odtwarzacz działa").setOngoing(true).build()
    }

    // ---------- cykl co 10 s ----------
    private fun tick() {
        try { Cfg.heartbeat.writeText(System.currentTimeMillis().toString()) } catch (_: Exception) {}
        if (!Cfg.paired) return
        val now = System.currentTimeMillis()
        if (ws == null && now >= nextConnect) connect()
        if (now - lastSync > 5 * 60000 || (Sync.failedSome() && now - lastSync > 60000) || Sync.current == null && now - lastSync > 30000) syncAsync()
        if (connected && now - lastStatus > 60000) sendStatus()
        val s = (Sync.current ?: Mf.load()?.also { Sync.current = it })?.settings ?: return
        screenSchedule(s)
        rebootSchedule(s)
        kiosk(s)
    }

    fun kick() { nextConnect = 0; lastSync = 0; backoff = 2000 }

    fun syncAsync(force: Boolean = false) {
        lastSync = System.currentTimeMillis()
        pool.execute {
            try { if (Sync.run(force)) notifyPlayer() }
            catch (e: HttpError) { if (e.code == 401) unpaired() else L.i("Serwer: ${e.message}") }
            catch (e: Exception) { L.i("Brak połączenia z serwerem: ${e.message}") }
        }
    }

    fun notifyPlayer() = main.post { PlayerActivity.get()?.onManifest() }

    // ---------- WebSocket ----------
    private fun connect() {
        nextConnect = System.currentTimeMillis() + backoff
        backoff = (backoff * 2).coerceAtMost(60000)
        val req = try {
            Request.Builder().url("${Cfg.server}/ws/device").header("Authorization", "Bearer ${Cfg.token}").build()
        } catch (e: Exception) { L.i("Błędny adres serwera: ${Cfg.server}"); return }
        ws = Net.http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true; backoff = 2000
                L.i("Połączono z serwerem")
                sendStatus()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try { handle(JSONObject(text)) } catch (e: Exception) { L.i("Błędna wiadomość: $e") }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { dropped(webSocket, null) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response?.code == 401) { unpaired(); return }
                dropped(webSocket, t.message)
            }
        })
    }

    private fun dropped(w: WebSocket, why: String?) {
        if (w !== ws) return
        if (connected) L.i("Rozłączono z serwerem${if (why != null) ": $why" else ""}")
        ws = null; connected = false
    }

    private fun handle(m: JSONObject) {
        when (m.optString("type")) {
            "hello" -> if (m.optString("manifestRev") != Sync.current?.rev) syncAsync()
            "manifest" -> syncAsync()
            "unpaired" -> unpaired()
            "cmd" -> pool.execute {
                val id = m.optString("id")
                val res = try { Commands.exec(this, m.optString("cmd"), m.optJSONObject("args") ?: JSONObject()) }
                catch (e: Throwable) { JSONObject().put("ok", false).put("error", e.message ?: e.toString()) }
                send(res.put("type", "result").put("id", id))
            }
        }
    }

    fun send(j: JSONObject) { ws?.send(j.toString()) }

    fun sendStatus() {
        lastStatus = System.currentTimeMillis()
        try { send(JSONObject().put("type", "status").put("data", status())) } catch (e: Exception) { L.i("Status: $e") }
    }

    private fun status(): JSONObject {
        val j = Net.deviceInfo(this)
        val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (bi != null) {
            val lvl = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val sc = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (lvl >= 0) j.put("battery", lvl * 100 / sc.coerceAtLeast(1))
            j.put("charging", bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
        }
        val st = StatFs(filesDir.path)
        j.put("storageFree", st.availableBytes).put("storageTotal", st.totalBytes)
        val c = Sync.cacheStats(); j.put("cacheFiles", c.get("cacheFiles")).put("cacheSize", c.get("cacheSize"))
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        j.put("screenOn", pm.isInteractive && PlayerActivity.get()?.screenOff != true)
        val pi = PlayerActivity.playingItem
        j.put("playing", if (PlayerActivity.playingName.isEmpty()) "" else PlayerActivity.playingName + if (pi.isNotEmpty()) " → $pi" else "")
        j.put("sync", Sync.status).put("rev", Sync.current?.rev ?: "")
        j.put("uptime", SystemClock.elapsedRealtime() / 1000).put("appUptime", (SystemClock.elapsedRealtime() - started) / 1000)
        j.put("foreground", PlayerActivity.resumed)
        return j
    }

    fun unpaired() {
        if (!Cfg.paired) return
        L.i("Tablet odłączony od serwera – ekran rejestracji")
        ws?.cancel(); ws = null; connected = false
        Cfg.unpair(); Sync.current = null; Sync.clearCache()
        main.post {
            PlayerActivity.get()?.finish()
            startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
    }

    // ---------- harmonogramy ----------
    private fun hm(c: Calendar = Calendar.getInstance()) = String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))

    private fun screenSchedule(s: Settings) {
        if (s.screenOn.isEmpty() || s.screenOff.isEmpty()) {
            if (lastScreenWant == false) Commands.screenOn(this)
            lastScreenWant = null; return
        }
        val t = hm()
        val want = if (s.screenOn < s.screenOff) t >= s.screenOn && t < s.screenOff else t >= s.screenOn || t < s.screenOff
        if (want != lastScreenWant) {
            lastScreenWant = want
            L.i("Harmonogram: ekran ${if (want) "włączony" else "wyłączony"}")
            if (want) Commands.screenOn(this) else Commands.screenOff(this)
        }
    }

    private fun rebootSchedule(s: Settings) {
        if (s.rebootAt.isEmpty() || Root.available != true) return
        val c = Calendar.getInstance()
        if (hm(c) == s.rebootAt && c.get(Calendar.DAY_OF_YEAR) != rebootDay && SystemClock.elapsedRealtime() > 30 * 60000) {
            rebootDay = c.get(Calendar.DAY_OF_YEAR)
            L.i("Zaplanowany restart tabletu", true)
            Root.run("reboot")
        }
    }

    private fun kiosk(s: Settings) {
        if (!s.kiosk || System.currentTimeMillis() < kioskPausedUntil || SetupActivity.active || PlayerActivity.resumed) return
        if (System.currentTimeMillis() - PlayerActivity.lastPaused < s.kioskReturnSec * 1000L) return
        if (PlayerActivity.get()?.screenOff == true) return
        bringToFront()
    }

    fun bringToFront() {
        if (Root.available == true) Root.run("am start -n $packageName/.PlayerActivity", 10000)
        else main.post {
            try { startActivity(Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
            catch (e: Exception) { L.i("Nie można wrócić na wierzch: $e") }
        }
        PlayerActivity.lastPaused = System.currentTimeMillis()
    }

    // ---------- konfiguracja z root ----------
    private fun rootSetup() {
        val p = packageName
        Root.run(
            "appops set $p SYSTEM_ALERT_WINDOW allow; dumpsys deviceidle whitelist +$p; " +
                "settings put global stay_on_while_plugged_in 7; pm grant $p android.permission.POST_NOTIFICATIONS; " +
                "pm grant $p android.permission.CAMERA; cmd package set-home-activity $p/.PlayerActivity; locksettings set-disabled true",
            40000
        )
        // zewnętrzny watchdog: brak „tętna” aplikacji przez 120 s → wymuszone zamknięcie i ponowne uruchomienie
        val hb = Cfg.heartbeat.absolutePath
        val script = """
            #!/system/bin/sh
            echo $$ > /data/local/tmp/signage_wd.pid
            while true; do
              sleep 30
              [ -f /data/local/tmp/signage_wd.stop ] && exit 0
              now=${'$'}(date +%s)
              last=${'$'}(stat -c %Y $hb 2>/dev/null || echo 0)
              if [ ${'$'}((now - last)) -gt 120 ]; then
                log -t SignageWD "brak tetna - restart"
                am force-stop $p
                sleep 2
                am start -n $p/.PlayerActivity
                touch $hb
                sleep 60
              fi
            done
        """.trimIndent()
        val f = java.io.File(filesDir, "signage_wd.sh")
        f.writeText(script + "\n")
        val r = Root.run(
            "cp ${f.absolutePath} /data/local/tmp/signage_wd.sh && chmod 755 /data/local/tmp/signage_wd.sh && rm -f /data/local/tmp/signage_wd.stop; " +
                "[ -f /data/local/tmp/signage_wd.pid ] && kill ${'$'}(cat /data/local/tmp/signage_wd.pid) 2>/dev/null; " +
                "nohup sh /data/local/tmp/signage_wd.sh >/dev/null 2>&1 &", 15000
        )
        L.i("Watchdog zewnętrzny ${if (r.ok) "uruchomiony" else "błąd: ${r.out.take(200)}"}")
    }
}
