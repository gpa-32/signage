package pl.signage.player

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

class HttpError(val code: Int, msg: String) : IOException(msg)

object Net {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun postJson(url: String, body: JSONObject, token: String? = null): JSONObject {
        val b = Request.Builder().url(url).post(body.toString().toRequestBody(JSON))
        if (token != null) b.header("Authorization", "Bearer $token")
        return call(b.build())
    }

    fun getJson(url: String, token: String? = null): JSONObject {
        val b = Request.Builder().url(url)
        if (token != null) b.header("Authorization", "Bearer $token")
        return call(b.build())
    }

    private fun call(req: Request): JSONObject {
        http.newCall(req).execute().use { r ->
            val txt = r.body?.string() ?: ""
            val j = try { JSONObject(txt) } catch (_: Exception) { JSONObject() }
            if (!r.isSuccessful) throw HttpError(r.code, j.optString("error", "HTTP ${r.code}"))
            return j
        }
    }

    fun localIp(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address }?.hostAddress ?: ""
    } catch (_: Exception) { "" }

    fun screenSize(c: Context): Pair<Int, Int> {
        val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
        return Pair(m.widthPixels, m.heightPixels)
    }

    fun deviceInfo(c: Context): JSONObject {
        val (w, h) = screenSize(c)
        return JSONObject()
            .put("model", Build.MODEL)
            .put("manufacturer", Build.MANUFACTURER)
            .put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("ip", localIp())
            .put("width", w).put("height", h)
            .put("rooted", Root.available)
    }
}
