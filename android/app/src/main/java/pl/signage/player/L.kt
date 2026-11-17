package pl.signage.player

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale

/** Dziennik zdarzeń: pamięć + plik (rotowany). */
object L {
    private val lines = LinkedList<String>()
    private var file: File? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    /** Wywoływane dla ważnych zdarzeń, które warto wysłać na serwer. */
    @Volatile var remote: ((String) -> Unit)? = null

    fun init(c: Context) { file = File(c.filesDir, "log.txt") }

    @Synchronized
    fun i(msg: String, toServer: Boolean = false) {
        val line = "${fmt.format(Date())} $msg"
        android.util.Log.i("Signage", msg)
        lines.add(line)
        while (lines.size > 500) lines.removeFirst()
        try {
            val f = file ?: return
            if (f.length() > 512 * 1024) f.renameTo(File(f.parentFile, "log.old.txt"))
            f.appendText(line + "\n")
        } catch (_: Exception) {}
        if (toServer) try { remote?.invoke(msg) } catch (_: Exception) {}
    }

    @Synchronized
    fun tail(n: Int = 200): String = lines.takeLast(n).joinToString("\n")
}
