package pl.signage.player

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ctx = this
        Cfg.init(this)
        L.init(this)
        L.i("Start aplikacji ${BuildConfig.VERSION_NAME}")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                L.i("AWARIA w wątku ${t.name}: ${e.stackTraceToString().take(1500)}")
                scheduleRestart(this, 3000)
            } catch (_: Throwable) {}
            if (prev != null) prev.uncaughtException(t, e) else android.os.Process.killProcess(android.os.Process.myPid())
        }
        KeepAliveReceiver.schedule(this)
    }

    companion object {
        lateinit var ctx: Context

        /** Uruchom aplikację ponownie po [delayMs] (po awarii, zawieszeniu lub na polecenie). */
        fun scheduleRestart(c: Context, delayMs: Long) {
            val i = Intent(c, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val flags = PendingIntent.FLAG_CANCEL_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            val pi = PendingIntent.getActivity(c, 77, i, flags)
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, pi)
        }
    }
}
