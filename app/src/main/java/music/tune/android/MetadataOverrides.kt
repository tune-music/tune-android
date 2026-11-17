package music.tune.android

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.net.URI

internal data class MetadataOverride(
    val title: String = "",
    val artist: String = "",
    val cover: String = "",
    val tracks: List<String> = emptyList(),
    val albums: List<String> = emptyList(),
    val usePlugin: Boolean = true,
) {
    fun stored() = JSONObject().put("title", title).put("artist", artist).put("cover", cover)
        .put("tracks", org.json.JSONArray(tracks)).put("albums", org.json.JSONArray(albums)).put("usePlugin", usePlugin)
}

internal class MetadataOverrides(private val context: Context) {
    private val prefs = context.getSharedPreferences("overrides", Context.MODE_PRIVATE)

    fun read(uid: String, type: String, id: String): MetadataOverride {
        val fields = optional { JSONObject(prefs.getString(key(uid, type, id), "{}").orEmpty()) } ?: JSONObject()
        return MetadataOverride(fields.text("title").orEmpty(), fields.text("artist").orEmpty(), fields.text("cover").orEmpty(),
            fields.arr("tracks").items().mapNotNull(Any?::asId), fields.arr("albums").items().mapNotNull(Any?::asId),
            fields.optBoolean("usePlugin", true))
    }

    fun write(uid: String, type: String, id: String, data: MetadataOverride?) {
        val key = key(uid, type, id)
        val oldCover = read(uid, type, id).cover
        val editor = prefs.edit()
        if (data == null) editor.remove(key) else editor.putString(key, data.stored().toString())
        editor.apply()
        if (oldCover != data?.cover) image(context, oldCover)?.delete()
    }

    fun storeImage(jpeg: ByteArray): String {
        val directory = File(context.filesDir, "overrides").apply { mkdirs() }
        val name = java.util.UUID.randomUUID().toString().replace("-", "") + ".jpg"
        val file = AtomicFile(File(directory, name))
        val out = file.startWrite()
        try {
            out.write(jpeg)
            file.finishWrite(out)
        } catch (error: java.io.IOException) {
            file.failWrite(out)
            throw error
        }
        return "$PREFIX$name"
    }

    private fun key(uid: String, type: String, id: String) = digest("$uid:$type:$id")

    companion object {
        private const val PREFIX = "tune-cover:"
        private val imageName = Regex("[0-9a-f]{32}\\.jpg")

        fun image(context: Context, value: String?): File? {
            if (value?.startsWith(PREFIX) != true) return null
            val name = value.removePrefix(PREFIX)
            if (!name.matches(imageName)) return null
            return File(File(context.filesDir, "overrides"), name).takeIf(File::isFile)
        }

        fun ids(text: String, kind: String): List<String> = text.split(Regex("[,\\s]+")).mapNotNull { token ->
            if (token.matches(Regex("[0-9]{1,20}"))) return@mapNotNull token
            val uri = optional { URI(token) } ?: return@mapNotNull null
            if (uri.scheme != "https" || uri.host !in setOf("music.yandex.ru", "music.yandex.com", "music.yandex.kz")) return@mapNotNull null
            val segments = uri.path.split('/')
            segments.getOrNull(segments.lastIndexOf(kind) + 1)?.takeIf { it.matches(Regex("[0-9]{1,20}")) }
        }.distinct().take(300)
    }
}
