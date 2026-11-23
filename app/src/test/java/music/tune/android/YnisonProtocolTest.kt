package music.tune.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class YnisonProtocolTest {
    @Test
    fun usesYandexMusicOriginAndEncodesProtocolsLikeWebClient() {
        assertEquals("https://music.yandex.ru", YnisonProtocol.ORIGIN)

        val header = YnisonProtocol.protocols("tok+1/2", "dev-1", JSONObject().put("Ynison-Redirect-Ticket", "a/b+c"))
        assertTrue(header.startsWith("Bearer, v2, "))

        val encoded = header.removePrefix("Bearer, v2, ")
        assertFalse(encoded.contains("+"))
        assertFalse(encoded.contains("%5C%2F"))

        val decoded = JSONObject(URLDecoder.decode(encoded, "UTF-8"))
        assertEquals("dev-1", decoded.getString("Ynison-Device-Id"))
        assertEquals("OAuth tok+1/2", decoded.getString("authorization"))
        assertEquals("a/b+c", decoded.getString("Ynison-Redirect-Ticket"))
        val info = JSONObject(decoded.getString("Ynison-Device-Info"))
        assertEquals("Desktop", info.getString("app_name"))
        assertEquals("5.120.0", info.getString("app_version"))
    }

    @Test
    fun parsesDevicesActiveDeviceQueueAndStatus() {
        val state = YnisonProtocol.state(JSONObject("""
            {
              "active_device_id_optional": "dev-2",
              "devices": [
                {"is_shadow": false, "capabilities": {"can_be_player": true}, "info": {"device_id": "dev-1", "title": "Tune", "type": "WEB_DESKTOP"}},
                {"is_shadow": false, "capabilities": {"can_be_player": true}, "info": {"device_id": "dev-2", "title": "Станция", "type": "SMART_SPEAKER"}},
                {"is_shadow": true, "capabilities": {"can_be_player": true}, "info": {"device_id": "shadow", "title": "Shadow", "type": "WEB"}}
              ],
              "player_state": {
                "player_queue": {
                  "current_playable_index": 0,
                  "entity_type": "ALBUM",
                  "entity_id": "42",
                  "playable_list": [
                    {"playable_id": "100", "album_id_optional": "42", "title": "Трек", "cover_url_optional": "avatars.yandex.net/1/%%"}
                  ],
                  "version": {"device_id": "dev-2", "version": "11"}
                },
                "status": {
                  "duration_ms": 180000,
                  "progress_ms": 15000,
                  "paused": false,
                  "playback_speed": 1.0,
                  "version": {"device_id": "dev-2", "version": "12"}
                }
              }
            }
        """.trimIndent()))

        assertNotNull(state)
        assertEquals("dev-2", state?.active)
        assertEquals(listOf("dev-1", "dev-2"), state?.devices?.map { it.id })
        assertEquals(1, state?.playback?.songs?.size)
        assertEquals("100", state?.playback?.current?.id)
        assertEquals(15_000L, state?.playback?.position)
        assertFalse(state?.playback?.paused ?: true)
    }
}
