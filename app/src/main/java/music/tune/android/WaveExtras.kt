package music.tune.android

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal data class WordCard(val id: String, val text: String, val story: String?, val feedback: String)
internal data class WaveStory(val text: String, val action: Pair<String, String>?)
internal data class WheelItem(val type: String, val id: String, val title: String, val card: Card?, val color: String? = null)
internal data class Wheel(val id: String, val items: List<WheelItem>)

internal fun parseWords(data: JSONArray): Map<String, WordCard> = data.objects().mapNotNull { entry ->
    val track = entry.text("id") ?: return@mapNotNull null
    val item = entry.arr("cards").optJSONObject(0) ?: return@mapNotNull null
    val card = item.obj("card")
    val id = card.text("id") ?: return@mapNotNull null
    val text = card.text("text")?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
    track to WordCard(id, text, card.arr("bigCardIds").opt(0).asId(), item.text("feedbackState") ?: "neutral")
}.toMap()

internal class WaveExtras(private val store: MusicStore) {
    private val _cards = MutableStateFlow<Map<String, WordCard>>(emptyMap())
    val cards = _cards.asStateFlow()
    private val _wheel = MutableStateFlow<List<WheelItem>>(emptyList())
    val wheel = _wheel.asStateFlow()
    private val asked = mutableSetOf<String>()
    private val showing = linkedSetOf<String>()
    private val seen = linkedSetOf<String>()
    private val seenStories = linkedMapOf<String, String>()
    private val stories = mutableMapOf<String, WaveStory>()
    private val rateLock = Mutex()
    private var wordJob: Job? = null
    private var wave: String? = null
    private var currentUser: String? = null
    private var activeWheel: Wheel? = null
    private var requestedSeeds: List<String>? = null
    private var wheelJob: Job? = null
    private val feedback = mutableListOf<JSONObject>()

    fun resetUser(uid: String?) {
        if (currentUser == uid) return
        currentUser = uid
        resetWords(null)
        stories.clear()
        showing.clear()
        seen.clear()
        seenStories.clear()
        wheelJob?.cancel()
        activeWheel = null
        requestedSeeds = null
        _wheel.value = emptyList()
        feedback.clear()
    }

    private fun resetWords(session: String?) {
        wave = session
        wordJob?.cancel()
        wordJob = null
        _cards.value = emptyMap()
        asked.clear()
    }

    fun prefetch(session: String?, queue: List<Track>) {
        if (wave != session) resetWords(session)
        if (session == null || !store.preferences.getBoolean("waveWords", true) || store.offline || wordJob?.isActive == true) return
        val ids = queue.take(10).filterNot { it.uploaded }.map { it.id }
            .filter { it.all(Char::isDigit) && it.isNotBlank() && it !in asked }.distinct()
        if (ids.isEmpty()) return
        asked += ids
        val viewed = seen.toList()
        val viewedStories = seenStories.toMap()
        wordJob = store.scope.launch {
            val result = optional { store.online { wordsCards(ids, viewed, viewedStories) } }
            if (result == null) {
                asked.removeAll(ids.toSet())
            } else if (wave == session) {
                seen.removeAll(viewed.toSet())
                viewedStories.keys.forEach(seenStories::remove)
                _cards.value = _cards.value + result
            }
        }
    }

    fun shown(card: WordCard) {
        if (showing.add(card.id)) seen += card.id
    }

    suspend fun story(track: Track, card: WordCard): WaveStory? {
        val big = card.story ?: return null
        val user = currentUser
        val result = stories[card.id] ?: store.online { wordsStory(track.id, card.id, big) }
        if (user == currentUser) {
            stories[card.id] = result
            seenStories[card.id] = big
        }
        return result
    }

    suspend fun rate(track: Track, card: WordCard, feedback: String) = rateLock.withLock {
        val current = cards.value[track.id]?.takeIf { it.id == card.id } ?: return@withLock
        val next = if (current.feedback == feedback) "neutral" else feedback
        _cards.value = cards.value + (track.id to current.copy(feedback = next))
        try {
            store.online { wordsFeedback(card.id, next) }
            if (next == "like") store.message("Спасибо за оценку")
            if (next == "dislike") store.message("Спасибо, что помогаете мне стать лучше")
        } catch (error: Exception) {
            val active = cards.value[track.id]
            if (active?.id == card.id) _cards.value = cards.value + (track.id to current)
            throw error
        }
    }

    fun loadWheel(seeds: List<String>) {
        if (store.offline || !store.preferences.getBoolean("waveWheel", true) || requestedSeeds == seeds) return
        wheelJob?.cancel()
        requestedSeeds = seeds
        val sent = feedback.toList()
        wheelJob = store.scope.launch {
            val loaded = optional { store.online { wheel(seeds, sent) } }
            if (loaded == null) {
                if (requestedSeeds == seeds) requestedSeeds = null
                return@launch
            }
            feedback.removeAll(sent.toSet())
            activeWheel = loaded
            _wheel.value = loaded.items.distinctBy { it.type to it.id }
        }
    }

    fun wheelEvent(item: WheelItem, event: String) {
        val wheel = activeWheel ?: return
        val position = wheel.items.indexOfFirst { it.type == item.type && it.id == item.id }
        if (position < 0) return
        if (event == "VIEW" && feedback.any {
            it.text("wheelId") == wheel.id && it.text("eventType") == event && it.optInt("position") == position
        }) return
        feedback += JSONObject().put("wheelId", wheel.id).put("timestamp", System.currentTimeMillis())
            .put("eventType", event).put("position", position)
            .put("item", JSONObject().put("type", item.type).put("id", item.id))
        if (event == "CLICK" && item.type != "WAVE") {
            val sent = feedback.toList()
            store.scope.launch {
                if (optional { store.online { wheelFeedbacks(sent) }; true } == true) feedback.removeAll(sent.toSet())
            }
        }
    }
}
