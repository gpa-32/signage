package pl.signage.player

import org.json.JSONObject
import java.io.File
import java.util.Calendar

/** Element playlisty. */
class Item(
    val id: String, val type: String, val name: String, val duration: Int,
    val url: String?, val file: String?, val size: Long, val sha256: String?,
) {
    val localFile: File? get() = file?.let { File(Cfg.mediaDir, it) }
    val ready: Boolean get() = type == "web" || (localFile?.let { it.exists() && it.length() == size } ?: false)

    companion object {
        fun of(j: JSONObject, durOverride: Int? = null) = Item(
            j.optString("id"), j.optString("type"), j.optString("name"),
            durOverride ?: j.optInt("duration", 10).coerceAtLeast(1),
            j.optString("url").ifEmpty { null }, j.optString("file").ifEmpty { null },
            j.optLong("size"), j.optString("sha256").ifEmpty { null },
        )
    }
}

class Ticker(j: JSONObject?) {
    val enabled = j?.optBoolean("enabled") ?: false
    val text = j?.optString("text") ?: ""
    val speed = (j?.optInt("speed", 3) ?: 3).coerceIn(1, 10)
    val fg = j?.optString("fg", "#ffffff") ?: "#ffffff"
    val bg = j?.optString("bg", "#000000") ?: "#000000"
    val size = (j?.optInt("size", 28) ?: 28).coerceIn(10, 120)
}

class Settings(j: JSONObject) {
    val orientation = j.optString("orientation", "landscape")
    val volume = j.optInt("volume", 50).coerceIn(0, 100)
    val brightness = j.optInt("brightness", -1)
    val screenOn = j.optString("screenOn")
    val screenOff = j.optString("screenOff")
    val rebootAt = j.optString("rebootAt")
    val imageFit = j.optString("imageFit", "contain")
    val transition = j.optString("transition", "fade")
    val kiosk = j.optBoolean("kiosk", true)
    val kioskReturnSec = j.optInt("kioskReturnSec", 60).coerceAtLeast(10)
    val ticker = Ticker(j.optJSONObject("ticker"))
}

/** Manifest z serwera: ustawienia, harmonogram, playlisty. */
class Mf(val json: JSONObject) {
    val rev: String = json.optString("rev")
    val settings = Settings(json.optJSONObject("settings") ?: JSONObject())
    val deviceName: String = json.optJSONObject("device")?.optString("name") ?: ""
    private val schedule = json.optJSONObject("schedule") ?: JSONObject()
    private val clockOffset = json.optLong("serverTime", 0).let { if (it > 0) it - System.currentTimeMillis() else 0L }
    val playlists: Map<String, Pair<String, List<Item>>> = run {
        val m = HashMap<String, Pair<String, List<Item>>>()
        val pl = json.optJSONObject("playlists") ?: JSONObject()
        for (k in pl.keys()) {
            val p = pl.getJSONObject(k)
            val arr = p.optJSONArray("items")
            val items = ArrayList<Item>()
            if (arr != null) for (i in 0 until arr.length()) items.add(Item.of(arr.getJSONObject(i)))
            m[k] = Pair(p.optString("name"), items)
        }
        m
    }

    /** Nadpisanie z panelu („Pokaż teraz”), jeśli aktywne. */
    fun override(): Item? {
        val o = json.optJSONObject("override") ?: return null
        if (o.optLong("until") < System.currentTimeMillis() + clockOffset) return null
        o.optJSONObject("media")?.let { return Item.of(it, 3600) }
        val url = o.optString("url")
        if (url.startsWith("http")) return Item("override", "web", url, 3600, url, null, 0, null)
        return null
    }

    fun currentPlaylistId(now: Calendar = Calendar.getInstance()): String? {
        val day = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1 // 1 = poniedziałek … 7 = niedziela
        val hm = String.format("%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
        val rules = schedule.optJSONArray("rules")
        if (rules != null) for (i in 0 until rules.length()) {
            val r = rules.getJSONObject(i)
            val days = r.optJSONArray("days")
            if (days != null && days.length() > 0) {
                var ok = false
                for (d in 0 until days.length()) if (days.optInt(d) == day) ok = true
                if (!ok) continue
            }
            val from = r.optString("from"); val to = r.optString("to")
            val inRange = if (from <= to) hm >= from && hm < to else hm >= from || hm < to
            if (inRange) return r.optString("playlistId").ifEmpty { null }
        }
        return if (schedule.isNull("playlistId")) null else schedule.optString("playlistId").ifEmpty { null }
    }

    /** Co ma grać teraz: (klucz, nazwa, elementy). */
    fun current(): Triple<String, String, List<Item>> {
        override()?.let { return Triple("override:${it.id}:${it.url}", "Pokaż teraz: ${it.name}", listOf(it)) }
        val id = currentPlaylistId() ?: return Triple("", "", emptyList())
        val p = playlists[id] ?: return Triple("", "", emptyList())
        return Triple(id, p.first, p.second)
    }

    /** Wszystkie pliki potrzebne lokalnie. */
    fun files(): List<Item> {
        val all = playlists.values.flatMap { it.second }.toMutableList()
        json.optJSONObject("override")?.optJSONObject("media")?.let { all.add(Item.of(it)) }
        return all.filter { it.type != "web" && it.file != null }.distinctBy { it.file }
    }

    companion object {
        fun load(): Mf? = try { Mf(JSONObject(Cfg.manifestFile.readText())) } catch (_: Exception) { null }
    }
}
