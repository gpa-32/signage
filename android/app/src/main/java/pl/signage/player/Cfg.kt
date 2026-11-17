package pl.signage.player

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/** Ustawienia lokalne tabletu: adres serwera i dane dostępowe. Treść i ustawienia ekranu przychodzą z serwera. */
object Cfg {
    private lateinit var p: SharedPreferences
    private lateinit var dir: File

    fun init(c: Context) {
        p = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        dir = c.filesDir
    }

    var server: String
        get() = p.getString("server", null) ?: BuildConfig.DEFAULT_SERVER
        set(v) { p.edit().putString("server", v.trim().trimEnd('/')).apply() }
    var token: String
        get() = p.getString("token", "") ?: ""
        set(v) { p.edit().putString("token", v).commit() }
    var deviceId: String
        get() = p.getString("deviceId", "") ?: ""
        set(v) { p.edit().putString("deviceId", v).apply() }
    var name: String
        get() = p.getString("name", "") ?: ""
        set(v) { p.edit().putString("name", v).apply() }
    var pairId: String
        get() = p.getString("pairId", "") ?: ""
        set(v) { p.edit().putString("pairId", v).apply() }

    val paired: Boolean get() = token.isNotEmpty() && server.isNotEmpty()

    fun unpair() {
        p.edit().remove("token").remove("deviceId").remove("pairId").remove("name").commit()
        manifestFile.delete()
    }

    val manifestFile: File get() = File(dir, "manifest.json")
    val mediaDir: File get() = File(dir, "media").apply { mkdirs() }
    val heartbeat: File get() = File(dir, "heartbeat")
}
