package music.tune.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AppUpdatesTest {
    private val appId = "music.tune.android"

    @Test
    fun onlyOffersHigherAndroidVersionCode() {
        val feed = feed()
        assertNull(AppUpdates.parse(feed, appId, 10))
        assertNull(AppUpdates.parse(feed, appId, 11))
        assertEquals(AppUpdate("0.2.10", "https://example.org/Tune.apk", "Исправления"),
            AppUpdates.parse(feed, appId, 9))
    }

    @Test
    fun rejectsForeignPackageAndInvalidDownloads() {
        assertThrows(MusicError::class.java) { AppUpdates.parse(feed().put("packageName", "other.apk"), appId, 9) }
        assertThrows(MusicError::class.java) { AppUpdates.parse(feed().put("versionCode", "10"), appId, 9) }
        for (url in listOf("http://example.org/Tune.apk", "content://files/Tune.apk", "https://example.org@elsewhere.org/Tune.apk")) {
            assertThrows(MusicError::class.java) { AppUpdates.parse(feed().put("apkUrl", url), appId, 9) }
        }
    }

    private fun feed() = JSONObject().put("packageName", appId).put("versionCode", 10).put("versionName", "0.2.10")
        .put("apkUrl", "https://example.org/Tune.apk").put("notes", "Исправления")
}
