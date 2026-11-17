package music.tune.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal data class AppUpdate(val version: String, val downloadUrl: String, val notes: String)

internal object AppUpdates {
    private const val MAX_FEED_BYTES = 64L * 1024
    private val http by lazy {
        OkHttpClient.Builder().followSslRedirects(false).callTimeout(15, TimeUnit.SECONDS).build()
    }

    val configured get() = BuildConfig.UPDATE_FEED_URL.isNotBlank()

    suspend fun check(): AppUpdate? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(BuildConfig.UPDATE_FEED_URL).header("Accept", "application/json").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw MusicError("Не удалось проверить обновления")
            val source = response.body?.source() ?: throw invalidFeed()
            if (source.request(MAX_FEED_BYTES + 1)) throw invalidFeed()
            val feed = runCatching { JSONObject(source.readUtf8()) }.getOrElse { throw invalidFeed() }
            parse(feed, BuildConfig.APPLICATION_ID, BuildConfig.VERSION_CODE)
        }
    }

    fun parse(feed: JSONObject, applicationId: String, installedVersionCode: Int): AppUpdate? {
        if (feed.opt("packageName") != applicationId) throw invalidFeed()
        val versionCode = (feed.opt("versionCode") as? Int)?.takeIf { it > 0 } ?: throw invalidFeed()
        if (versionCode <= installedVersionCode) return null
        val version = (feed.opt("versionName") as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: throw invalidFeed()
        val url = (feed.opt("apkUrl") as? String)?.toHttpUrlOrNull()?.takeIf {
            it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null
        } ?: throw invalidFeed()
        val notes = (feed.opt("notes") as? String).orEmpty().trim()
        return AppUpdate(version, url.toString(), notes)
    }

    private fun invalidFeed() = MusicError("Источник обновлений Tune недоступен или повреждён")
}
