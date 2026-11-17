package pl.signage.player

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Polecenia z uprawnieniami root (su). */
object Root {
    class Res(val ok: Boolean, val out: String)

    @Volatile var available: Boolean? = null
        private set

    fun check(): Boolean {
        val r = run("id", 15000)
        available = r.ok && r.out.contains("uid=0")
        return available == true
    }

    fun run(cmd: String, timeoutMs: Long = 20000): Res {
        val r = exec(arrayOf("su"), cmd, timeoutMs)
        return Res(r.first, String(r.second))
    }

    fun bytes(cmd: String, timeoutMs: Long = 20000): ByteArray? {
        val r = exec(arrayOf("su"), cmd, timeoutMs)
        return if (r.first) r.second else null
    }

    /** Zwykła powłoka (bez root) – gdy root niedostępny. */
    fun sh(cmd: String, timeoutMs: Long = 20000): Res {
        val r = exec(arrayOf("sh"), cmd, timeoutMs)
        return Res(r.first, String(r.second))
    }

    /** Uruchom w tle niezależnie od procesu aplikacji (przetrwa force-stop / reinstalację). */
    fun detached(script: String) {
        val esc = script.replace("'", "'\\''")
        run("nohup sh -c '$esc' >/dev/null 2>&1 &", 10000)
    }

    private fun exec(shell: Array<String>, cmd: String, timeoutMs: Long): Pair<Boolean, ByteArray> {
        return try {
            val p = ProcessBuilder(*shell).redirectErrorStream(true).start()
            val buf = ByteArrayOutputStream()
            val reader = Thread { copy(p.inputStream, buf) }
            reader.start()
            p.outputStream.use { it.write("$cmd\nexit\n".toByteArray()) }
            val end = System.currentTimeMillis() + timeoutMs
            var code: Int? = null
            while (code == null && System.currentTimeMillis() < end) {
                try { code = p.exitValue() } catch (_: IllegalThreadStateException) { Thread.sleep(40) }
            }
            if (code == null) {
                p.destroy()
                return Pair(false, (String(buf.toByteArray()) + "\n[przekroczono czas]").toByteArray())
            }
            reader.join(2000)
            Pair(code == 0, buf.toByteArray())
        } catch (e: Exception) {
            Pair(false, e.toString().toByteArray())
        }
    }

    private fun copy(ins: InputStream, out: ByteArrayOutputStream) {
        try {
            val b = ByteArray(65536)
            while (true) {
                val n = ins.read(b)
                if (n < 0) break
                synchronized(out) { if (out.size() < 64 * 1024 * 1024) out.write(b, 0, n) }
            }
        } catch (_: Exception) {}
    }
}
