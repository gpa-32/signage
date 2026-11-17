package pl.signage.player

import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Pobieranie manifestu i plików z serwera do pamięci tabletu (odtwarzanie działa też bez sieci). */
object Sync {
    @Volatile var status = ""
    @Volatile var current: Mf? = null
    @Volatile var lastOk = 0L
    private val lock = Any()

    /** @return true gdy manifest się zmienił. Rzuca HttpError(401), gdy tablet został usunięty z serwera. */
    fun run(force: Boolean = false): Boolean = synchronized(lock) {
        val j = Net.getJson("${Cfg.server}/api/device/manifest", Cfg.token)
        val mf = Mf(j)
        val old = current ?: Mf.load()
        val missing = mf.files().filter { !it.ready }
        var failed = 0
        missing.forEachIndexed { i, it ->
            status = "pobieranie ${i + 1}/${missing.size}: ${it.name}"
            try { download(it) } catch (e: Exception) {
                failed++
                L.i("Błąd pobierania ${it.name}: ${e.message}", true)
            }
        }
        val changed = force || old == null || old.rev != mf.rev || missing.isNotEmpty()
        Cfg.manifestFile.writeText(j.toString())
        current = mf
        if (Cfg.name != mf.deviceName) Cfg.name = mf.deviceName
        if (failed == 0) cleanup(mf)
        status = if (failed > 0) "nie pobrano $failed plików – ponowię" else "aktualne"
        lastOk = System.currentTimeMillis()
        if (changed) L.i("Nowa treść (rev ${mf.rev}, pobrano ${missing.size - failed} plików)")
        return changed
    }

    fun failedSome() = status.startsWith("nie pobrano")

    private fun download(it: Item) {
        val dest = it.localFile ?: return
        val part = File(dest.path + ".part")
        val free = Cfg.mediaDir.usableSpace
        if (free < it.size + 50L * 1024 * 1024) throw Exception("za mało miejsca (${free / 1048576} MB wolne)")
        val req = Request.Builder().url("${Cfg.server}/api/device/file/${it.file}")
            .header("Authorization", "Bearer ${Cfg.token}").build()
        Net.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw HttpError(r.code, "HTTP ${r.code}")
            val md = MessageDigest.getInstance("SHA-256")
            r.body!!.byteStream().use { ins ->
                part.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        md.update(buf, 0, n); out.write(buf, 0, n)
                    }
                }
            }
            val hex = md.digest().joinToString("") { b -> "%02x".format(b) }
            if (it.sha256 != null && hex != it.sha256) { part.delete(); throw Exception("błędna suma kontrolna") }
            if (!part.renameTo(dest)) throw Exception("nie można zapisać pliku")
        }
    }

    private fun cleanup(mf: Mf) {
        val keep = mf.files().mapNotNull { it.file }.toSet()
        Cfg.mediaDir.listFiles()?.forEach { f -> if (f.name !in keep) f.delete() }
    }

    fun clearCache() = synchronized(lock) {
        Cfg.mediaDir.listFiles()?.forEach { it.delete() }
    }

    fun cacheStats(): JSONObject {
        val fs = Cfg.mediaDir.listFiles() ?: emptyArray()
        return JSONObject().put("cacheFiles", fs.size).put("cacheSize", fs.sumOf { it.length() })
    }
}
