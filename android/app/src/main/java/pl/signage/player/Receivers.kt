package pl.signage.player

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/** Start po włączeniu tabletu i po aktualizacji aplikacji. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        L.i("Zdarzenie systemowe: ${i.action}")
        SignageService.start(c)
        try {
            c.startActivity(Intent(c, if (Cfg.paired) PlayerActivity::class.java else SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { L.i("Autostart ekranu: $e") }
        KeepAliveReceiver.schedule(c)
    }
}

/** Alarm co 5 minut – podnosi usługę, jeśli system ją zatrzymał. */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (SignageService.get() == null) {
            L.i("Usługa nie działała – uruchamiam ponownie")
            SignageService.start(c)
        }
    }

    companion object {
        fun schedule(c: Context) {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            val pi = PendingIntent.getBroadcast(c, 5, Intent(c, KeepAliveReceiver::class.java), flags)
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 5 * 60000, 5 * 60000L, pi)
        }
    }
}
