package music.tune.android

import coil.imageLoader
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.Insets
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.Locale

internal enum class Tab(val label: String, val symbol: Symbol) {
    HOME("Главная", Symbol.HOME), SEARCH("Поиск", Symbol.SEARCH), LIBRARY("Коллекция", Symbol.HEART_FILLED)
}

private enum class Screen(val label: String) {
    HOME("Tune"), SEARCH("Поиск"), LIBRARY("Коллекция"), LIKES("Мне нравится"), DISLIKES("Не нравится"),
    HISTORY("История"), DOWNLOADS("Скачанные треки"), ALBUMS("Альбомы"), ARTISTS("Исполнители"),
    SETTINGS("Настройки"), DETAILS(""), ARTIST_TRACKS("Все треки"), SHELF(""), CLIPS("Клипы"), CONCERTS("Концерты"),
    WAVE_ROOMS("Моя волна на двоих"), STATIONS("Все волны"), KIDS("Детям"), STATS("Статистика"), DEVICES("Устройства"),
    PODCASTS("Подкасты"), BOOKS("Аудиокниги"), HIDDEN_ARTISTS("Скрытые исполнители"), STORAGE("Занятое место"),
    REPLACEMENT("Подмена"), ARTIST_COLLECTION("У вас в коллекции"), ARTIST_WAVE("Слушали в Волне"),
    CHANGES("Что нового"), ERROR_LOG("Журнал ошибок")
}

private data class ReplacementTarget(val kind: String, val id: String, val title: String, val track: Track? = null)
private class Route(val screen: Screen, var card: Card? = null, val shelf: Shelf? = null, val replacement: ReplacementTarget? = null) {
    var position: TrackListPosition? = null
    var filter = ""
    var order = TrackOrder.ORIGINAL
    var downloadedOnly = false
    var filterVisible = false
    var chipId: String? = null
}
private data class TrackListPosition(val index: Int, val offset: Int, val trackId: String?)
private data class TrackList(val tracks: List<Track>, val context: PlayContext, val art: Symbol? = null)
private enum class TrackOrder(val label: String) {
    ORIGINAL("Как в списке"), TITLE("По названию"), ARTIST("По исполнителю"),
    RECENT("Сначала новые"), OLDEST("Сначала старые")
}

private enum class SearchPagination { MORE, LOADING, RETRY }
private const val PODCAST_FILTER = 5

private data class SearchPage(
    val results: SearchResults,
    val number: Int,
    var firstVisible: Int = 0,
    var top: Int = 0,
    var retry: Boolean = false,
)

private sealed interface Row {
    class Layout(val cache: Boolean = false, val make: () -> View) : Row {
        private var cached: View? = null
        fun obtain(parent: ViewGroup): View {
            if (!cache) return make()
            val existing = cached
            if (existing != null) {
                val owner = existing.parent as? ViewGroup
                if (owner == null) return existing
                if (owner !== parent) {
                    owner.removeView(existing)
                    return existing
                }
                return make()
            }
            return make().also { cached = it }
        }
    }
    class Music(
        val track: Track,
        val play: () -> Unit,
        val menu: (View) -> Unit,
        val number: Int? = null,
        val reorderIndex: Int? = null
    ) : Row
    class Entry(val card: Card, val open: () -> Unit) : Row
    data object WaveHead : Row
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : ComponentActivity(), Player.Listener {
    internal val store get() = (application as TuneApp).store
    private val appearance: String get() = store.preferences.getString("appearance", "system") ?: "system"
    private val lightMode: Boolean get() = when (appearance) {
        "light" -> true
        "dark" -> false
        else -> resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_NO
    }
    internal val ui by lazy { TuneUi(this, TuneColors(lightMode)) }
    internal val playbackMotion by lazy { PlaybackMotion(this) }
    internal var controller: MediaController? = null
        private set
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var pendingPlayback: (() -> Unit)? = null
    private var tab = Tab.HOME
    private val paths = Tab.entries.associateWith { ArrayDeque<Route>() }
    private val roots = mapOf(Tab.HOME to Route(Screen.HOME), Tab.SEARCH to Route(Screen.SEARCH), Tab.LIBRARY to Route(Screen.LIBRARY))
    private val stack get() = paths.getValue(tab)
    private val route get() = stack.lastOrNull() ?: roots.getValue(tab)
    private var displayedRoute: Route? = null
    private var restorePosition: TrackListPosition? = null
    private var pageHasRows = false
    private var pendingLink: Uri? = null
    private var pendingShortcut: String? = null
    private var pendingVoiceSearch: String? = null
    private var openPlayerOnConnect = false
    private var reopenSettings = false
    private var pageJob: Job? = null
    private var waveJob: Job? = null
    private var loginJob: Job? = null
    private var loginFailed = false
    private var userId: String? = null
    private data class HomeRowsKey(
        val page: HomePage?,
        val trending: Boolean,
        val offline: Boolean,
        val error: String?,
        val downloaded: Int,
        val playlists: List<Card>,
        val loading: Boolean,
    )
    private var home: HomePage? = null
    private var trending = false
    private var waveHead: HomeWaveHead? = null
    private var cachedHomeWaveHead: HomeWaveHead? = null
    private var cachedHomeKey: HomeRowsKey? = null
    private var cachedHomeRows: List<Row>? = null
    private var homeError: String? = null
    private var homeLoaded = false
    private var loadingHome = false
    private var details: MusicPage? = null
    private var reordering = false
    private var reorderJob: Job? = null
    private var detailMenu: View? = null
    private var reorderDone: View? = null
    private var trackList: TrackList? = null
    private var trackFilter = ""
    private var trackOrder = TrackOrder.ORIGINAL
    private var onlyDownloaded = false
    private var trackChips: List<TrackFilter> = emptyList()
    private var selectedTrackChip: String? = null
    private var trackChipsKey: String? = null
    private var trackChipsJob: Job? = null
    private val trackChipsCache = mutableMapOf<String, List<TrackFilter>>()
    private var filterField: EditText? = null
    private var filterBar: View? = null
    private var selecting = false
    private val selectedTracks = linkedSetOf<String>()
    private var selectionBar: LinearLayout? = null
    private var toolbarTitle: TextView? = null
    private var detailToolbar: View? = null
    private var detailTone: Int? = null
    private var detailChrome = Color.TRANSPARENT
    private var list: ListView? = null
    private var refreshLayout: TuneRefreshLayout? = null
    private var adapter: PageAdapter? = null
    private var settingsScroll: ScrollView? = null
    private var settingsBody: LinearLayout? = null
    private var lyricsMirrorField: EditText? = null
    private var pendingSettingsRefresh = false
    private var listHasContent = false
    private val playbackBindings = mutableSetOf<() -> Unit>()
    private var downloadRows = emptyList<Pair<String, String?>>()
    private var searchField: EditText? = null
    private var suggestions: LinearLayout? = null
    private var discovery: List<Shelf> = emptyList()
    private var suggestionJob: Job? = null
    private var searchFilter = 0
    private var searchText = ""
    private var searchDraft = ""
    private val searchPages = mutableMapOf<Int, SearchPage>()
    private var searchHints: Pair<String, List<String>>? = null
    private var cityName = ""
    private var city: String? = null
    private var documentTrack: Track? = null
    private var exportPlaylistText: String? = null
    private var mediaPickerPlaylist: Card? = null
    private var coverPickerTarget: ReplacementTarget? = null
    private var statsDays: Int? = 30
    private var statsClearButton: IconView? = null
    private var reduceAi: Boolean? = null
    private var updateCheck: Job? = null
    private var lastUpdateCheck = 0L
    private var availableUpdate: AppUpdate? = null
    private val errorLog = ArrayDeque<String>()
    private var settingsStorage: Pair<Long, Long>? = null
    private var settingsStorageJob: Job? = null
    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var mini: LinearLayout
    private lateinit var miniPager: MiniTrackPager
    private lateinit var miniToggle: IconView
    private lateinit var miniLike: IconView
    private lateinit var miniProgress: ProgressBar
    private lateinit var navigation: LinearLayout
    private var playerPanel: PlayerPanel? = null
    private var closingPlayer: View? = null
    private var videoPanel: ClipPanel? = null
    internal val hasOverlay get() = playerPanel != null || videoPanel != null
    private var toastPill: TextView? = null
    private var toastHide: Runnable? = null
    private var animateTabFade = false
    private var libraryQueuedCount = -1
    private var systemInsets = Insets.NONE
    private var imeBottom = 0
    private var loginView: View? = null

    private val login = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_FIRST_USER && store.profile.value == null) {
            loginFailed = true
            showLogin()
            return@registerForActivityResult
        }
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val value = result.data?.getStringExtra(LoginActivity.TOKEN) ?: return@registerForActivityResult
        loginJob?.cancel()
        loginJob = lifecycleScope.launch {
            loginFailed = false
            if (store.profile.value == null) showLogin(connecting = true)
            try {
                store.login(value)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                loginFailed = true
                if (store.profile.value == null) showLogin()
                else toast("Войти не получилось. Попробуйте ещё раз.")
            }
        }
    }
    private val importAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val track = documentTrack
        documentTrack = null
        if (uri != null && track != null) action {
            store.files.replace(track, uri)
            route.replacement?.takeIf { it.id == track.id }?.let(::replacement)
            toast("Подмена сохранена. Запустите трек заново")
        }
    }
    private val exportAudio = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val track = documentTrack
        documentTrack = null
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null && track != null) action {
            store.files.export(track, uri)
            toast("Аудиофайл сохранён")
        }
    }
    private val importPlaylistDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) action {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val input = contentResolver.openInputStream(uri) ?: throw MusicError("Не удалось открыть файл")
                input.bufferedReader().use { reader ->
                    val buffer = CharArray(64 * 1024)
                    val result = StringBuilder()
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        result.append(buffer, 0, count)
                        if (result.length > 1_000_000) throw MusicError("Файл плейлиста слишком большой")
                    }
                    result.toString()
                }
            }
            importPlaylist(text)
        }
    }
    private val exportPlaylistDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        val text = exportPlaylistText
        exportPlaylistText = null
        if (uri != null && text != null) action {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val output = contentResolver.openOutputStream(uri, "wt") ?: throw MusicError("Не удалось сохранить плейлист")
                output.bufferedWriter().use { it.write(text) }
            }
            toast("Плейлист сохранён")
        }
    }
    private val uploadTrackDocuments = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { documents ->
        val playlist = mediaPickerPlaylist
        mediaPickerPlaylist = null
        if (documents.isNotEmpty()) action {
            if (playlist != null && playlist.owner != userId) throw MusicError("Выбран другой аккаунт")
            store.uploads.upload(documents.take(100), playlist)
            if (playlist != null && route.card?.key == playlist.key) loadDetails(playlist)
        }
    }
    private val playlistCoverDocument = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val card = mediaPickerPlaylist
        mediaPickerPlaylist = null
        if (uri != null && card != null && card.owner == userId) action {
            val jpeg = imageJpeg(uri)
            val cover = store.online { uploadPlaylistCover(card, jpeg) }
            val changed = card.copy(cover = cover)
            store.changeLocalPlaylist(changed)
            if (route.card?.key == card.key) loadDetails(changed)
        }
    }
    private val localCoverDocument = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = coverPickerTarget
        coverPickerTarget = null
        if (uri != null && target != null) action {
            val uid = store.requireUser().uid
            val bytes = imageJpeg(uri)
            if (store.profile.value?.uid != uid) return@action
            val cover = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.overrides.storeImage(bytes) }
            val before = store.overrides.read(uid, target.kind, target.id)
            saveReplacement(target, before.copy(cover = cover))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (lightMode) setTheme(R.style.TuneLightTheme)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        updateSystemBars()
        tab = savedInstanceState?.getString("tab")?.let { optional { Tab.valueOf(it) } } ?: Tab.HOME
        if (savedInstanceState?.getBoolean("reopenSettings") == true) stack.addLast(Route(Screen.SETTINGS))
        searchDraft = savedInstanceState?.getString("search").orEmpty()
        searchText = searchDraft.trim()
        searchFilter = savedInstanceState?.getInt("searchFilter")?.takeIf { it in FILTERS.indices } ?: 0
        documentTrack = savedInstanceState?.getString("document")?.let { optional { Track.parse(org.json.JSONObject(it)) } }
        mediaPickerPlaylist = savedInstanceState?.getString("mediaPickerPlaylist")?.let { optional { Card.fromCache(org.json.JSONObject(it)) } }
        city = store.preferences.getString("city", null)
        cityName = store.preferences.getString("cityName", "").orEmpty()
        openPlayerOnConnect = savedInstanceState?.getBoolean("player") == true
        root = FrameLayout(this).apply { setBackgroundColor(ui.palette.background) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            systemInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            applySystemInsets()
            insets
        }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    videoPanel != null -> closeVideo()
                    playerPanel != null -> closePlayer(animate = true)
                    reordering -> setReordering(false)
                    selecting -> endSelection()
                    filterBar != null -> hideTrackFilter()
                    stack.isNotEmpty() -> { stack.removeLast(); render() }
                    tab != Tab.HOME -> switchTab(Tab.HOME)
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                }
            }
        })
        if (savedInstanceState == null) accept(intent)
        connectPlayer()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val resumedPage = displayedRoute
                launch { store.profile.collect { user -> showAccount(user) } }
                launch {
                    val uid = store.profile.value?.uid
                    if (!store.offline && uid != null) {
                        optional { store.refreshAccount() }
                        if (uid == userId && resumedPage === displayedRoute && listHasContent &&
                            pageJob?.isActive != true && !reordering && resumedPage?.screen in COLLECTION_REFRESH_PAGES) {
                            load()
                        }
                    }
                }
                launch { store.messages.collect(::toast) }
                launch { store.likes.collect { refreshCollection(); updatePlaying() } }
                launch { store.dislikes.collect { refreshCollection(); playerPanel?.update() } }
                launch { store.savedCards.collect { refreshCollection() } }
                launch { store.ownPlaylists.collect {
                    if (route.screen == Screen.HOME && userId != null) showHome(keepScroll = true)
                    if (route.screen == Screen.LIBRARY && userId != null) library()
                } }
                launch { store.savedPlaylists.collect { if (route.screen == Screen.LIBRARY && userId != null) library() } }
                launch { store.waveExtras.cards.collect { updateWordCard() } }
                launch { store.waveExtras.wheel.collect { waveHead?.bindWheel() } }
                launch { store.ynison.state.collect { if (route.screen == Screen.DEVICES && userId != null) devices() } }
                launch { store.ynison.connection.collect { if (route.screen == Screen.DEVICES && userId != null) devices() } }
                launch { store.ynison.detail.collect { if (route.screen == Screen.DEVICES && userId != null) devices() } }
                launch { store.files.revision.collect {
                    if (route.screen == Screen.DOWNLOADS && userId != null) downloads()
                    if (route.screen == Screen.SETTINGS && userId != null) refreshSettingsStorage()
                    refreshCollection()
                    playerPanel?.update()
                } }
                launch { store.files.status.collect { states ->
                    if (route.screen == Screen.DOWNLOADS && userId != null) updateDownloadRows()
                    val visible = list
                    if (visible != null && route.screen != Screen.DOWNLOADS && route.screen != Screen.HOME) {
                        for (index in 0 until visible.childCount) {
                            (visible.getChildAt(index).tag as? ItemRow)?.updateDownloadStatus(states)
                        }
                    }
                    val queued = states.values.count { it.error == null }
                    if (queued != libraryQueuedCount) {
                        libraryQueuedCount = queued
                        if (route.screen == Screen.LIBRARY && userId != null) library()
                    }
                    playerPanel?.updateDownload()
                } }
                launch { store.sleepTimer.collect { playerPanel?.updateSleepButton() } }
                launch { store.files.paused.collect { if (route.screen == Screen.DOWNLOADS && userId != null) downloads() } }
                launch { store.files.networkWait.collect { if (route.screen == Screen.DOWNLOADS && userId != null) downloads() } }
                launch {
                    while (isActive) {
                        updateProgress()
                        delay(150)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        accept(intent)
    }

    private fun accept(intent: Intent) {
        if (intent.action == OPEN_PLAYER) {
            if (currentTrack() != null && ::mini.isInitialized) showPlayer() else openPlayerOnConnect = true
        } else if (intent.action == Intent.ACTION_VIEW) {
            pendingLink = intent.data
            applyPendingLink()
        } else if (intent.action == Intent.ACTION_SEND) {
            pendingLink = SHARED_LINK.find(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())?.value?.let(Uri::parse)
            if (pendingLink == null) toast("В сообщении нет ссылки на Яндекс Музыку") else applyPendingLink()
        } else if (intent.action in SHORTCUTS) {
            pendingShortcut = intent.action
            applyShortcut()
        } else if (intent.action == android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            pendingVoiceSearch = intent.getStringExtra(android.app.SearchManager.QUERY).orEmpty()
            applyVoiceSearch()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("tab", tab.name)
        outState.putBoolean("reopenSettings", reopenSettings)
        outState.putString("search", searchField?.text?.toString() ?: searchDraft)
        outState.putInt("searchFilter", searchFilter)
        outState.putString("document", documentTrack?.stored()?.toString())
        outState.putString("mediaPickerPlaylist", mediaPickerPlaylist?.cached()?.toString())
        outState.putBoolean("player", playerPanel != null)
    }

    private fun connectPlayer() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (isDestroyed) return@addListener
            try {
                controller = future.get().also { it.addListener(this) }
                pendingPlayback?.invoke()
                pendingPlayback = null
                updatePlaying()
                updateWaveContent()
            } catch (_: Exception) {
                controllerFuture = null
                pendingPlayback = null
                toast("Не удалось подключить плеер")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showAccount(account: Profile?) {
        if (userId != null && userId != account?.uid) {
            closeVideo()
            closePlayer()
            pageJob?.cancel()
            waveJob?.cancel()
            suggestionJob?.cancel()
            pendingPlayback = null
            home = null
            trending = false
            waveHead = null
            cachedHomeWaveHead = null
            cachedHomeKey = null
            cachedHomeRows = null
            homeLoaded = false
            details = null
            paths.values.forEach { it.clear() }
            roots.values.forEach { it.position = null }
            displayedRoute = null
            tab = Tab.HOME
            searchText = ""
            searchDraft = ""
            searchPages.clear()
            searchHints = null
            searchFilter = 0
            discovery = emptyList()
            reduceAi = null
            settingsStorageJob?.cancel()
            settingsStorage = null
            homeError = null
            loadingHome = false
        }
        if (account == null) {
            waveHead = null
            cachedHomeWaveHead = null
            cachedHomeKey = null
            cachedHomeRows = null
            userId = null
            showLogin(loginJob?.isActive == true)
        } else if (account.uid != userId) {
            userId = account.uid
            render()
            applyPendingLink()
            applyShortcut()
            applyVoiceSearch()
        }
    }

    private fun applyVoiceSearch() {
        if (userId == null) return
        val query = pendingVoiceSearch ?: return
        pendingVoiceSearch = null
        if (query.isBlank()) startWave(listOf(MY_WAVE), "Моя волна")
        else action {
            val found = store.online { search(query, "track", 0) }.tracks
            val first = found.indexOfFirst { !it.unavailable }
            if (first < 0) toast("Ничего не найдено") else playTracks(found, first, PlayContext("search", query, "Поиск"))
        }
    }

    private fun applyShortcut() {
        if (userId == null) return
        val command = pendingShortcut ?: return
        pendingShortcut = null
        when (command) {
            "music.tune.android.SHORTCUT_WAVE" -> {
                if (tab != Tab.HOME) switchTab(Tab.HOME)
                startWave(listOf(MY_WAVE), "Моя волна")
            }
            "music.tune.android.SHORTCUT_SEARCH" -> {
                tab = Tab.SEARCH
                stack.clear()
                render()
                searchField?.let { field ->
                    field.requestFocus()
                    field.post { getSystemService(InputMethodManager::class.java).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT) }
                }
            }
            "music.tune.android.SHORTCUT_LIKES" -> {
                tab = Tab.LIBRARY
                stack.clear()
                push(Screen.LIKES)
            }
            OPEN_DOWNLOADS -> {
                tab = Tab.LIBRARY
                stack.clear()
                push(Screen.DOWNLOADS)
            }
        }
    }

    private fun applySystemInsets() {
        val bars = systemInsets
        val bottom = maxOf(bars.bottom, imeBottom)
        if (::content.isInitialized && content.isAttachedToWindow) {
            content.setPadding(bars.left, bars.top, bars.right, bottom)
        }
        loginView?.takeIf { it.isAttachedToWindow }?.setPadding(bars.left, bars.top, bars.right, bottom)
        playerPanel?.applyInsets(bars)
        videoPanel?.applyInsets(bars)
        if (::navigation.isInitialized) {
            navigation.visibility = if (imeBottom > bars.bottom) View.GONE else View.VISIBLE
        }
        if (imeBottom > bars.bottom) {
            lyricsMirrorField?.takeIf { it.hasFocus() }?.let { field ->
                field.post {
                    if (field.isAttachedToWindow && field.hasFocus()) {
                        field.requestRectangleOnScreen(
                            android.graphics.Rect(0, 0, field.width, field.height + ui.dp(16)),
                            false
                        )
                    }
                }
            }
        }
    }

    private fun showLogin(connecting: Boolean = false) {
        root.removeAllViews()
        root.setBackgroundColor(Color.BLACK)
        val landing = loginLanding(this, connecting, loginFailed) {
            if (loginFailed) {
                loginFailed = false
                showLogin()
            }
            login.launch(Intent(this, LoginActivity::class.java))
        }
        loginView = landing
        root.addView(landing, FrameLayout.LayoutParams(-1, -1))
        applySystemInsets()
        ViewCompat.requestApplyInsets(root)
        updateSystemBars()
    }

    private fun applyPendingLink() {
        val uri = pendingLink ?: return
        if (store.profile.value == null) return
        pendingLink = null
        openLink(uri)
    }

    private fun openLink(uri: Uri) {
        val segments = uri.pathSegments.toMutableList()
        when (uri.scheme) {
            "tune", "yandexmusic", "music-application" -> {
                if (uri.host in setOf("album", "artist", "track", "users", "clip", "multivibe")) {
                    segments.add(0, uri.host.orEmpty())
                } else if (uri.scheme == "tune") {
                    toast("Ссылка Tune не распознана")
                    return
                }
            }
            "https" -> if (uri.host !in setOf("music.yandex.ru", "music.yandex.com", "music.yandex.kz")) {
                toast("Введите ссылку на Яндекс Музыку или Tune")
                return
            }
            else -> {
                toast("Введите ссылку на Яндекс Музыку или Tune")
                return
            }
        }
        val type = segments.firstOrNull()
        val id = segments.getOrNull(1)
        if (type == "video" && uri.getQueryParameter("ids").isNullOrBlank()) {
            push(Screen.CLIPS)
            return
        }
        if (type == "multivibe" && id?.matches(ROOM_ID) == true) {
            action {
                val room = store.online { waveRoom(id) }
                showRoom(room, invite = true)
            }
            return
        }
        val trackId = when {
            type == "track" -> id
            type == "album" && segments.getOrNull(2) == "track" -> segments.getOrNull(3)
            else -> null
        }
        if (trackId?.matches(ID) == true) {
            action {
                val tracks = store.online { tracks(listOf(trackId)) }
                val track = tracks.firstOrNull()
                if (track == null) toast("Трек не найден") else {
                    playTracks(tracks, 0, PlayContext(name = track.title))
                    openTrackAlbum(track)
                }
            }
            return
        }
        val card = when (type) {
            "album" -> id?.takeIf { it.matches(ID) }?.let { Card(Kind.ALBUM, it, "Альбом") }
            "artist" -> id?.takeIf { it.matches(ID) }?.let { Card(Kind.ARTIST, it, "Исполнитель") }
            "users" -> if (segments.getOrNull(2) == "playlists") segments.getOrNull(3)?.takeIf { it.matches(ID) }
                ?.let { Card(Kind.PLAYLIST, it, "Плейлист", owner = id) } else null
            "clip" -> id?.takeIf { it.matches(ID) }?.let { Card(Kind.CLIP, it, "Клип") }
            "video" -> selectedClipId(uri.getQueryParameter("ids"), uri.getQueryParameter("activeIndex"))
                ?.let { Card(Kind.CLIP, it, "Клип") }
            else -> null
        }
        if (card == null) toast("Ссылка не распознана") else openCard(card)
    }

    private fun switchTab(next: Tab) {
        hideKeyboard()
        closePlayer()
        if (tab == next) {
            if (stack.isEmpty()) {
                list?.smoothScrollToPosition(0)
                return
            }
            stack.clear()
        } else {
            val destination = paths.getValue(next)
            if (destination.any { it.screen == Screen.SETTINGS }) destination.clear()
            animateTabFade = true
        }
        searchDraft = searchField?.text?.toString() ?: searchDraft
        tab = next
        render()
    }

    private fun hasNetwork(): Boolean = getSystemService(android.net.ConnectivityManager::class.java)?.let { cm ->
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } == true

    private fun push(screen: Screen, card: Card? = null) {
        hideKeyboard()
        closePlayer()
        val offlineTrackList = (store.offline || !hasNetwork()) && (
            screen == Screen.LIKES ||
                screen == Screen.HISTORY ||
                (screen == Screen.DETAILS && card?.kind != Kind.ARTIST) ||
                screen == Screen.ARTIST_TRACKS ||
                screen == Screen.ARTIST_COLLECTION ||
                screen == Screen.ARTIST_WAVE
            )
        stack.addLast(Route(screen, card).apply { downloadedOnly = offlineTrackList })
        render()
    }

    private fun render() {
        saveSearchPosition()
        saveTrackPage()
        val previousScreen = displayedRoute?.screen
        val current = route
        displayedRoute = current
        restorePosition = current.position
        pageHasRows = false
        pageJob?.cancel()
        listHasContent = false
        suggestionJob?.cancel()
        details = null
        reordering = false
        detailMenu = null
        reorderDone = null
        waveHead = null
        trackList = null
        trackFilter = current.filter
        trackOrder = current.order
        onlyDownloaded = current.downloadedOnly
        trackChipsJob?.cancel()
        trackChipsJob = null
        trackChips = emptyList()
        trackChipsKey = null
        selectedTrackChip = current.chipId
        filterField = null
        filterBar = null
        selecting = false
        selectedTracks.clear()
        selectionBar = null
        searchField = null
        suggestions = null
        toolbarTitle = null
        detailToolbar = null
        detailTone = null
        detailChrome = Color.TRANSPARENT
        statsClearButton = null
        loginView = null
        if (previousScreen == Screen.SETTINGS) {
            lyricsMirrorField?.let { field -> saveLyricsMirror(field, quiet = true) }
            hideKeyboard()
        }
        settingsScroll = null
        settingsBody = null
        lyricsMirrorField = null
        pendingSettingsRefresh = false
        list?.adapter = null
        refreshLayout = null
        root.removeAllViews()
        root.setBackgroundColor(ui.palette.background)
        content = ui.column()
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        if (current.screen == Screen.SEARCH) {
            content.isFocusableInTouchMode = true
            content.requestFocus()
            content.addView(searchBar(), ui.full())
            content.addView(ui.column().also { suggestions = it }, ui.full())
            updateSuggestions(searchText)
        } else content.addView(toolbar(current).also { if (current.screen == Screen.DETAILS) detailToolbar = it },
            ui.full(if (current.screen == Screen.HOME) 48 else 56))
        if (current.screen == Screen.SETTINGS) {
            val body = ui.column().apply { setPadding(0, 0, 0, ui.dp(20)) }
            val scroll = ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                clipToPadding = false
                isNestedScrollingEnabled = true
                addView(body, FrameLayout.LayoutParams(-1, -2))
            }
            list = null
            adapter = null
            settingsScroll = scroll
            settingsBody = body
            content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            val pageAdapter = PageAdapter()
            val pageList = (if (current.screen == Screen.DETAILS && current.card?.kind == Kind.PLAYLIST)
                PlaylistTrackList(this, ui) else ListView(this)).apply {
                adapter = pageAdapter
                divider = null
                selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                overScrollMode = View.OVER_SCROLL_NEVER
                cacheColorHint = ui.palette.background
                setPadding(0, 0, 0, ui.dp(8))
                clipToPadding = false
                isNestedScrollingEnabled = true
                if (current.screen == Screen.DETAILS) setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
                    override fun onScrollStateChanged(view: android.widget.AbsListView, state: Int) = Unit
                    override fun onScroll(view: android.widget.AbsListView, first: Int, visible: Int, total: Int) {
                        updateDetailsTitle(current, view, first)
                        updateDetailChrome()
                    }
                })
                if (current.screen == Screen.SEARCH) setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
                    override fun onScrollStateChanged(view: android.widget.AbsListView, state: Int) {
                        if (state == android.widget.AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL &&
                            searchField?.hasFocus() == true) hideKeyboard()
                    }
                    override fun onScroll(view: android.widget.AbsListView, first: Int, visible: Int, total: Int) = Unit
                })
            }
            list = pageList
            adapter = pageAdapter
            val pageContainer: View = if (current.screen in REFRESHABLE_SCREENS) {
                TuneRefreshLayout(ui, pageList, { !reordering && !selecting }, ::refreshCurrentPage).also {
                    refreshLayout = it
                }
            } else {
                pageList
            }
            content.addView(pageContainer, LinearLayout.LayoutParams(-1, 0, 1f))
            if (animateTabFade) {
                animateTabFade = false
                if (ValueAnimator.areAnimatorsEnabled()) {
                    pageContainer.alpha = 0f
                    pageContainer.animate().alpha(1f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
                }
            }
        }
        if (current.filterVisible) showTrackFilter(focus = false)
        content.addView(ui.row().apply {
            background = ui.shape(ui.palette.panel, 20)
            visibility = View.GONE
            selectionBar = this
        }, ui.full(60).apply { setMargins(ui.dp(10), 0, ui.dp(10), ui.dp(6)) })
        addMini()
        addNavigation()
        applySystemInsets()
        ViewCompat.requestApplyInsets(root)
        updatePlaying()
        updateSystemBars()
        if (current.screen == Screen.HOME) updateWaveContent()
        load(current)
        reattachToast()
    }

    private fun toolbar(current: Route): View = if (current.screen == Screen.SETTINGS) {
        FrameLayout(this).apply {
            addView(ui.line(current.screen.label, 17, bold = true), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(ui.icon(Symbol.BACK, "Назад") { onBackPressedDispatcher.onBackPressed() },
                FrameLayout.LayoutParams(ui.dp(48), -1, Gravity.START))
        }
    } else ui.row().apply {
        if (stack.isNotEmpty()) addView(ui.icon(Symbol.BACK, "Назад") { onBackPressedDispatcher.onBackPressed() })
        else setPadding(ui.dp(16), 0, 0, 0)
        val title = when (current.screen) {
            Screen.DETAILS, Screen.HOME -> ""
            Screen.SHELF -> current.shelf?.title.orEmpty()
            else -> current.screen.label
        }
        addView(ui.line(title, if (stack.isEmpty()) 32 else 18, bold = true).also {
            if (current.screen == Screen.DETAILS) {
                it.alpha = 0f
                it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            toolbarTitle = it
        }, ui.weight())
        when (current.screen) {
            Screen.HOME -> addView(ui.icon(Symbol.ACCOUNT, "Настройки и аккаунт") { push(Screen.SETTINGS) })
            Screen.LIBRARY -> addView(ui.icon(Symbol.GEAR, "Настройки") { push(Screen.SETTINGS) })
            Screen.CONCERTS -> addView(ui.button(concertCityLabel(), click = ::chooseCity).apply {
                id = CONCERT_CITY_BUTTON
                maxWidth = ui.dp(148)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(ui.palette.accentText)
                tooltipText = "Выбрать город"
            })
            Screen.ALBUMS, Screen.ARTISTS, Screen.PODCASTS, Screen.BOOKS -> addView(ui.icon(Symbol.SORT, "Сортировка") {
                val choices = if (current.screen == Screen.ARTISTS) listOf("Недавние", "По названию") else listOf("Недавние", "По названию", "По исполнителю")
                val selected = store.preferences.getInt("collectionOrder:${current.screen.name}", 0).takeIf { it in choices.indices } ?: 0
                choose("Сортировка", choices, selected) { order ->
                    store.preferences.edit().putInt("collectionOrder:${current.screen.name}", order).apply()
                    collection(current.screen)
                }
            })
            Screen.DETAILS -> {
                current.card?.takeIf { it.kind == Kind.ALBUM || it.kind == Kind.PLAYLIST && it.owner != userId }?.let { card ->
                    val liked = card.key in store.savedCards.value
                    val like = ui.icon(if (liked) Symbol.HEART_FILLED else Symbol.HEART, "В коллекцию") { action {
                        val selected = store.save(details?.card?.takeIf { it.key == card.key } ?: card)
                        val icon = findViewById<IconView>(FAVORITE)
                        if (icon != null && route.card?.key == card.key) updateCardLike(icon, selected)
                    } }.apply { id = FAVORITE }
                    updateCardLike(like, liked)
                    addView(like)
                }
                if (current.card?.kind != Kind.ARTIST) addView(ui.icon(Symbol.SEARCH, "Поиск в списке") {
                    if (reordering) setReordering(false)
                    showTrackFilter()
                })
                addView(menuButton { anchor -> details?.let { pageMenu(it, anchor) } ?: toast("Дождитесь загрузки") }
                    .also { detailMenu = it })
                if (current.card?.let { it.kind == Kind.PLAYLIST && it.owner == userId } == true) {
                    addView(ui.button("Готово") { setReordering(false) }.apply {
                        visibility = View.GONE
                        textSize = 16f
                        background = ui.touch()
                        reorderDone = this
                    }, LinearLayout.LayoutParams(-2, ui.dp(48)))
                }
            }
            Screen.LIKES, Screen.DISLIKES, Screen.DOWNLOADS, Screen.HISTORY, Screen.ARTIST_TRACKS, Screen.ARTIST_COLLECTION, Screen.ARTIST_WAVE -> {
                addView(ui.icon(Symbol.SEARCH, "Поиск в списке", click = ::showTrackFilter))
                addView(menuButton { anchor -> trackListMenu(anchor = anchor) })
            }
            Screen.STATS -> addView(ui.icon(Symbol.TRASH, "Очистить статистику") {
                confirm("Очистить статистику?", "Очистить", "Она хранится только на этом устройстве, вернуть её не получится.") {
                    action { userId?.let { store.listening.clear(it) }; stats() }
                }
            }.apply {
                isEnabled = false
                alpha = .38f
                statsClearButton = this
            })
            else -> Unit
        }
    }

    private fun menuButton(open: (View) -> Unit) = ui.icon(Symbol.MORE, "Ещё") {}.apply {
        iconSize = 19
        background = ui.touch(ui.palette.panel, 24)
        setOnClickListener { open(it) }
    }

    private fun updateCardLike(button: IconView, liked: Boolean) {
        setIcon(button, if (liked) Symbol.HEART_FILLED else Symbol.HEART,
            if (liked) "Убрать из коллекции" else "В коллекцию", if (liked) ui.palette.accent else ui.palette.text)
    }

    private fun addMini() {
        val ui = TuneUi(this)
        if (::mini.isInitialized) {
            (mini.parent as? ViewGroup)?.removeView(mini)
            content.addView(mini, ui.full().apply { setMargins(ui.dp(10), 0, ui.dp(10), ui.dp(6)) })
            return
        }
        mini = ui.column().apply {
            visibility = View.GONE
            clipToOutline = true
        }
        val surface = ui.shape(ui.palette.panel, 20)
        mini.background = surface
        var tint = ui.palette.panel
        var tintMotion: android.animation.ValueAnimator? = null
        val bar = ui.row().apply { setPadding(0, 0, ui.dp(2), 0) }
        miniPager = MiniTrackPager(ui, { tone ->
            val target = androidx.core.graphics.ColorUtils.blendARGB(tone, ui.palette.panel, .38f)
            tintMotion?.cancel()
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                tintMotion = android.animation.ValueAnimator.ofArgb(tint, target).apply {
                    duration = 300
                    addUpdateListener { tint = it.animatedValue as Int; surface.setColor(tint) }
                    start()
                }
            } else {
                tint = target
                surface.setColor(tint)
            }
        }, ::showPlayer, ::previousTrack, ::next) { true }
        bar.addView(miniPager, LinearLayout.LayoutParams(0, -1, 1f))
        miniLike = ui.icon(Symbol.HEART, "Мне нравится") { currentTrack()?.let { rate(it) } }
        bar.addView(miniLike)
        miniToggle = ui.icon(Symbol.PLAY, "Воспроизвести", click = ::toggle).apply { iconSize = 22 }
        bar.addView(miniToggle)
        mini.addView(bar, ui.full(56))
        miniProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(ui.palette.text)
            progressBackgroundTintList = ColorStateList.valueOf(ui.palette.line)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        mini.addView(miniProgress, ui.full(2).apply { setMargins(ui.dp(18), 0, ui.dp(18), 0) })
        content.addView(mini, ui.full().apply { setMargins(ui.dp(10), 0, ui.dp(10), ui.dp(6)) })
    }

    private fun addNavigation() {
        navigation = ui.row().apply {
            background = ui.shape(ui.palette.panel, 29)
            setPadding(ui.dp(4), ui.dp(3), ui.dp(4), ui.dp(3))
            Tab.entries.forEach { target ->
                val color = if (tab == target) ui.palette.accentText else ui.palette.muted
                val item = ui.column().apply {
                    gravity = Gravity.CENTER
                    background = ui.touch(if (target == tab) ui.palette.lifted else Color.TRANSPARENT, 26)
                    pressFeedback(.92f)
                    contentDescription = target.label
                    isSelected = tab == target
                    addView(IconView(this@MainActivity, target.symbol, color).apply { iconSize = 22 },
                        LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)))
                    addView(ui.space(3))
                    addView(ui.line(target.label, 11, color, bold = true).apply {
                        gravity = Gravity.CENTER
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    })
                    setOnClickListener { switchTab(target) }
                    isFocusable = true
                }
                addView(item, LinearLayout.LayoutParams(0, ui.dp(48), 1f))
            }
        }
        content.addView(navigation, ui.full(54).apply { setMargins(ui.dp(10), 0, ui.dp(10), ui.dp(5)) })
    }

    private fun searchBar(): View = ui.row().apply {
        setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(14))
        val field = EditText(this@MainActivity).apply {
            hint = searchHint()
            setText(searchDraft)
            setSingleLine()
            setTextColor(ui.palette.text)
            setHintTextColor(ui.palette.muted)
            textSize = 17f
            setPadding(0, 0, ui.dp(4), 0)
            background = null
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setOnEditorActionListener { _, id, event ->
                if (id == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                    submitSearch(text.toString())
                    true
                } else false
            }
        }
        val clear = ui.icon(Symbol.CLOSE, "Очистить") { field.text.clear() }.apply { iconSize = 16 }
        val cancel = ui.line("Отмена", 16, bold = true).apply {
            gravity = Gravity.CENTER
            background = ui.touch()
            isFocusable = true
            setOnClickListener {
                field.text.clear()
                searchType(0)
                hideKeyboard()
            }
        }
        fun syncActions() {
            clear.visibility = if (field.text.isNotEmpty()) View.VISIBLE else View.GONE
            cancel.visibility = if (field.hasFocus() || field.text.isNotEmpty()) View.VISIBLE else View.GONE
        }
        field.setOnFocusChangeListener { _, _ -> syncActions() }
        field.doAfterTextChanged { editable ->
            syncActions()
            val value = editable.toString()
            val query = value.trim()
            val link = SHARED_LINK.find(query)?.value ?: TUNE_LINK.find(query)?.value
            if (link != null) {
                searchDraft = ""
                searchText = ""
                searchHints = null
                field.text.clear()
                hideKeyboard()
                openLink(Uri.parse(link))
                return@doAfterTextChanged
            }
            searchDraft = value
            if (query == searchText) return@doAfterTextChanged
            searchText = query
            searchPages.clear()
            loadSearch(debounce = true)
            updateSuggestions(query)
        }
        searchField = field
        addView(ui.row().apply {
            background = ui.shape(ui.palette.panel, 23)
            addView(IconView(this@MainActivity, Symbol.SEARCH, ui.palette.muted).apply { iconSize = 17 },
                LinearLayout.LayoutParams(ui.dp(42), ui.dp(42)))
            addView(field, LinearLayout.LayoutParams(0, -1, 1f))
            addView(clear, LinearLayout.LayoutParams(ui.dp(36), -1))
        }, LinearLayout.LayoutParams(0, ui.dp(46), 1f))
        addView(cancel, LinearLayout.LayoutParams(-2, ui.dp(46)).apply { marginStart = ui.dp(12) })
        syncActions()
    }

    private fun searchHint() = if (FILTERS[searchFilter].second == "lyrics") "Строка из песни" else "Трек, альбом, исполнитель"

    private fun submitSearch(value: String) {
        searchDraft = value
        val changed = searchText != value.trim()
        searchText = value.trim()
        searchField?.let { field ->
            if (field.text.toString() != value) field.setText(value)
            field.setSelection(value.length)
        }
        hideKeyboard()
        if (searchText.isNotEmpty()) {
            val recent = listOf(searchText) + recentSearches().filterNot { it.equals(searchText, ignoreCase = true) }
            store.preferences.edit().putString("recentSearches", JSONArray(recent.take(10)).toString()).apply()
        }
        if (changed) searchPages.clear()
        loadSearch(refreshFirstPage = !changed && searchPages[searchFilter]?.number == 0)
        updateSuggestions(searchText)
    }

    private fun recentSearches() = optional {
        JSONArray(store.preferences.getString("recentSearches", "[]")).items().mapNotNull(Any?::asId)
    }.orEmpty()

    private fun updateSuggestions(query: String) {
        suggestionJob?.cancel()
        val rail = suggestions ?: return
        rail.removeAllViews()
        if (query.length < 2 || store.offline || "://" in query || FILTERS[searchFilter].second == "lyrics") return
        fun show(hints: List<String>) {
            if (hints.isNotEmpty()) rail.addView(horizontal(hints.map { hint ->
                ui.row().apply {
                    gravity = Gravity.CENTER
                    setPadding(ui.dp(12), 0, ui.dp(12), 0)
                    background = GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                        cornerRadius = ui.dp(17).toFloat()
                        setStroke(ui.dp(1), ColorUtils.setAlphaComponent(ui.palette.text, 46))
                    }
                    pressFeedback(.95f)
                    isFocusable = true
                    addView(IconView(this@MainActivity, Symbol.SEARCH, ColorUtils.setAlphaComponent(ui.palette.text, 217)).apply { iconSize = 12 },
                        LinearLayout.LayoutParams(ui.dp(13), ui.dp(13)).apply { marginEnd = ui.dp(6) })
                    addView(ui.line(hint, 14, ColorUtils.setAlphaComponent(ui.palette.text, 217), bold = true))
                    setOnClickListener { submitSearch(hint) }
                }.apply { layoutParams = LinearLayout.LayoutParams(-2, ui.dp(34)) }
            }))
        }
        searchHints?.takeIf { it.first == query }?.let { (_, hints) ->
            show(hints)
            return
        }
        suggestionJob = lifecycleScope.launch {
            delay(220)
            val hints = optional { store.online { suggest(query) } }
                ?.filterNot { it.equals(query, ignoreCase = true) }?.distinct()?.take(8) ?: return@launch
            searchHints = query to hints
            show(hints)
        }
    }

    private fun saveSearchPosition() {
        if (searchField == null) return
        val state = searchPages[searchFilter] ?: return
        val page = list ?: return
        state.firstVisible = page.firstVisiblePosition
        state.top = page.getChildAt(0)?.top ?: 0
    }

    private fun loadSearch(append: Boolean = false, debounce: Boolean = false, refreshFirstPage: Boolean = false) {
        pageJob?.cancel()
        val query = searchText
        if (query.isEmpty()) {
            fun showDiscovery() = rows(buildList {
                add(filterBar())
                val recent = recentSearches()
                if (recent.isNotEmpty()) {
                    add(section("Вы искали", "Очистить") {
                        store.preferences.edit().remove("recentSearches").apply()
                        loadSearch()
                    })
                    recent.forEach { item -> add(shortcut(Symbol.HISTORY, item) { submitSearch(item) }) }
                }
                discovery.forEach { shelf ->
                    add(heading(shelf.title))
                    addAll(waveGridRows(shelf.cards))
                }
                val picks = home?.personal.orEmpty().flatMap { it.cards }.filter { it.kind == Kind.PLAYLIST }.distinctBy { it.key }.take(12)
                if (picks.isNotEmpty()) addAll(shelfRows(listOf(Shelf("Подборки", picks))))
                else if (recent.isEmpty() && discovery.isEmpty()) add(Row.Layout { emptySearch() })
            })
            showDiscovery()
            if ((discovery.isEmpty() || home == null) && userId != null) pageJob = action(onError = {}) {
                if (home == null) {
                    home = store.cachedHome()
                    showDiscovery()
                    if (!store.offline) {
                        val loaded = store.home()
                        if (loaded.hasContent) home = loaded
                    }
                }
                if (discovery.isEmpty() && !store.offline) {
                    discovery = store.online { stations() }.filter { it.title == "Жанры" }
                }
                showDiscovery()
            }
            return
        }
        val type = FILTERS[searchFilter].second
        val filter = searchFilter
        val previous = searchPages[filter]
        if (append && (previous?.results?.hasMore != true || store.offline)) return
        val page = if (append) requireNotNull(previous).number + 1 else 0
        if (!append && previous != null && !refreshFirstPage) {
            searchRows(pagination = if (previous.retry) SearchPagination.RETRY else SearchPagination.MORE)
            list?.setSelectionFromTop(previous.firstVisible, previous.top)
            return
        }
        var visibleResults = previous != null
        when {
            append -> searchRows(keepScroll = true, pagination = SearchPagination.LOADING)
            visibleResults -> searchRows(keepScroll = true)
        }
        pageJob = action(onError = { error ->
            when {
                append -> {
                    previous?.retry = true
                    toast(error)
                    searchRows(keepScroll = true, pagination = SearchPagination.RETRY)
                }
                visibleResults -> if (!store.offline) toast(error)
                else -> rows(listOf(filterBar(), Row.Layout {
                    ui.pagePlaceholder(Symbol.WIFI_OFF, "Не удалось выполнить поиск", error) { loadSearch() }
                }))
            }
        }) {
            val uri = Uri.parse(query)
            if (uri.scheme in setOf("https", "tune", "music-application", "yandexmusic") && !append) {
                rows(listOf(filterBar(), shortcut(Symbol.LINK, query) { openLink(uri) }))
                return@action
            }
            if (!append && previous == null) {
                val cached = if (type == "lyrics") null else store.cachedSearch(query, type)
                if (cached != null) {
                    val hasMore = cached.hasMore ?: (cached.tracks.size + cached.cards.size >= 25)
                    searchPages[filter] = SearchPage(cached.copy(hasMore = hasMore), 0)
                    searchRows()
                    visibleResults = true
                } else rows(listOf(filterBar(), Row.Layout { ui.loadingRows(8) }))
                if (store.offline && cached != null) return@action
            }
            if (debounce) delay(300)
            val found = if (type == "lyrics") {
                val player = controller
                val queue = if (player == null) emptyList() else (0 until minOf(player.mediaItemCount, 500)).mapNotNull { QueueItems.track(player.getMediaItemAt(it)) }
                val local = if (page == 0) store.localLyricsSearch(query, queue) else emptyList()
                val external = if (page == 0 && !store.offline) optional { store.lyricsMatches(query) }.orEmpty() else emptyList()
                val highlights = local + external
                val remote = if (store.offline) null else try {
                    store.online { search(query, "track", page) }
                } catch (error: java.io.IOException) {
                    if (highlights.isEmpty()) throw error
                    null
                }
                SearchResults((highlights + remote?.tracks.orEmpty()).distinctBy { it.id }, emptyList(),
                    hasMore = remote?.let { it.hasMore ?: (it.tracks.size >= 25) } ?: false)
            } else store.searchPage(query, type, page)
            val more = found.hasMore ?: (found.tracks.size + found.cards.size >= 25)
            val base = previous?.results
            val result = if (append && base != null) SearchResults(
                (base.tracks + found.tracks).distinctBy { it.id },
                (base.cards + found.cards).distinctBy { it.key },
                base.best, more
            ) else found.copy(hasMore = more)
            searchPages[filter] = SearchPage(result, page)
            searchRows(keepScroll = visibleResults)
        }
    }

    private fun filterBar() = Row.Layout {
        val selected = searchFilter
        val filters = FILTERS.mapIndexed { index, (label, _) ->
            ui.chip(label, active = index == searchFilter) { searchType(index) }
        }
        horizontal(filters).apply {
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val chip = filters[selected]
                if (chip.right > scrollX + width || chip.left < scrollX) {
                    scrollTo(chip.left - ui.dp(16), 0)
                }
            }
        }
    }

    private fun searchType(index: Int) {
        if (index == searchFilter) return
        saveSearchPosition()
        searchFilter = index
        searchField?.hint = searchHint()
        loadSearch()
        updateSuggestions(searchText)
    }

    private fun emptySearch() = ui.pagePlaceholder(Symbol.SEARCH, "Найдётся всё", "Треки, альбомы, исполнители и плейлисты")

    private fun searchRows(keepScroll: Boolean = false, pagination: SearchPagination = SearchPagination.MORE) {
        val searchResult = searchPages[searchFilter]?.results ?: return
        val all = searchFilter == 0
        val podcastFilter = searchFilter == PODCAST_FILTER
        val songs = searchResult.songs
        val episodes = searchResult.episodes
        val podcasts = searchResult.podcasts
        val searchContext = PlayContext("search", searchText, "Поиск")
        val podcastContext = PlayContext("search", searchText, "Подкасты")
        rows(buildList {
            add(filterBar())
            if (all) searchResult.best?.let { best ->
                add(heading("Лучший результат"))
                val podcast = (best as? SearchBest.Song)?.track?.isPodcastEpisode == true
                add(Row.Layout { bestResult(best, if (podcast) episodes else songs, if (podcast) podcastContext else searchContext) })
            }
            if ((all || searchFilter == 1 || FILTERS[searchFilter].second == "lyrics") && songs.isNotEmpty()) {
                if (all) add(if (songs.size > 5 || searchResult.hasMore == true) section("Треки", "Все") { searchType(1) } else heading("Треки"))
                val tracks = trackRows(songs, searchContext)
                addAll(if (all) tracks.take(5) else tracks)
            }
            listOf(Kind.ARTIST, Kind.ALBUM, Kind.PLAYLIST).forEachIndexed { index, kind ->
                if (!all && searchFilter != index + 2) return@forEachIndexed
                val group = searchResult.cards.filter { it.kind == kind && !it.isPodcast }
                if (group.isNotEmpty()) {
                    val label = when (kind) {
                        Kind.ARTIST -> "Исполнители"; Kind.ALBUM -> "Альбомы"; Kind.PLAYLIST -> "Плейлисты"
                        else -> kind.label
                    }
                    if (all) {
                        add(if (group.size > 3 || searchResult.hasMore == true) section(label, "Все") { searchType(index + 2) } else heading(label))
                        add(shelfRow(group))
                    } else addAll(gridRows(group))
                }
            }
            if (all || podcastFilter) {
                if (podcasts.isNotEmpty()) {
                    if (all) {
                        add(section("Подкасты", "Все") { searchType(PODCAST_FILTER) })
                        add(shelfRow(podcasts))
                    } else addAll(gridRows(podcasts))
                }
                if (episodes.isNotEmpty()) {
                    add(if (all) section("Выпуски", "Все") { searchType(PODCAST_FILTER) } else heading("Выпуски"))
                    val tracks = trackRows(episodes, podcastContext)
                    addAll(if (all) tracks.take(5) else tracks)
                }
            }
            if (size == 1) add(Row.Layout {
                ui.pagePlaceholder(Symbol.SEARCH, "Ничего не найдено",
                    if (FILTERS[searchFilter].second == "lyrics") "Попробуйте другую строку из песни." else "")
            })
            if (searchResult.hasMore == true && searchFilter != 0 && !store.offline) add(Row.Layout {
                if (pagination == SearchPagination.LOADING) ui.searchPageProgress()
                else ui.row().apply {
                    gravity = Gravity.CENTER
                    val label = if (pagination == SearchPagination.RETRY) "Повторить загрузку" else "Показать ещё"
                    addView(ui.button(label) {
                        if (pageJob?.isActive != true) loadSearch(append = true)
                    }.apply {
                        setTextColor(ui.palette.accentText)
                        background = ui.touch()
                    })
                }
            })
        }, keepScroll)
    }

    private fun bestResult(best: SearchBest, tracks: List<Track>, context: PlayContext): View {
        val card = (best as? SearchBest.Entry)?.card
        val track = (best as? SearchBest.Song)?.track
        val title = card?.title ?: track?.title.orEmpty()
        val subtitle = if (card == null) listOfNotNull(
            if (track?.isPodcastEpisode == true) "Выпуск" else "Трек", track?.artistText?.takeIf(String::isNotBlank)
        ).joinToString(" · ") else listOf(if (card.isPodcast) "Подкаст" else card.kind.label, card.subtitle)
            .filter(String::isNotBlank).distinct().joinToString(" · ")
        val open = {
            if (card != null) openCard(card)
            else if (track != null) playTracks(tracks, tracks.indexOfFirst { it.id == track.id }, context)
        }
        return ui.column().apply {
            setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8))
            addView(ui.row().apply {
                setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
                background = ui.touch(ui.palette.panel, 20)
                pressFeedback(.97f)
                addView(ui.art(card?.cover ?: track?.cover, 180, card?.kind == Kind.ARTIST, 12),
                    LinearLayout.LayoutParams(ui.dp(76), ui.dp(76)))
                addView(ui.column().apply {
                    setPadding(ui.dp(14), 0, ui.dp(8), 0)
                    addView(ui.text(title, 19, bold = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                    addView(ui.space(4)); addView(ui.line(subtitle, 14, ui.palette.muted))
                }, ui.weight())
                if (track != null) {
                    val icon = ui.icon(Symbol.PLAY, "Воспроизвести", Color.BLACK) {
                        if (currentTrack()?.id == track.id) toggle() else open()
                    }.apply { iconSize = 15 }
                    val bars = PlayingBars(this@MainActivity, Color.BLACK)
                    addView(FrameLayout(this@MainActivity).apply {
                        background = ui.touch(ui.palette.accent, 19)
                        pressFeedback(.92f)
                        isFocusable = true
                        addView(icon, FrameLayout.LayoutParams(-1, -1))
                        addView(bars, FrameLayout.LayoutParams(ui.dp(16), ui.dp(16), Gravity.CENTER))
                        setOnClickListener { if (currentTrack()?.id == track.id) toggle() else open() }
                        observePlayback {
                            val active = currentTrack()?.id == track.id && controller?.isPlaying == true
                            icon.visibility = if (active) View.GONE else View.VISIBLE
                            bars.visibility = if (active) View.VISIBLE else View.GONE
                            bars.running = active
                        }
                    }, LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)))
                } else {
                    addView(IconView(this@MainActivity, Symbol.BACK, ui.palette.muted).apply {
                        rotation = 180f
                        iconSize = 14
                    }, LinearLayout.LayoutParams(ui.dp(24), ui.dp(38)))
                }
                setOnClickListener { open() }
                isFocusable = true
            }, ui.full())
        }
    }

    private fun load(current: Route = route) {
        when (current.screen) {
            Screen.HOME -> homePage()
            Screen.SEARCH -> loadSearch()
            Screen.LIBRARY -> library()
            Screen.DOWNLOADS -> downloads()
            Screen.SETTINGS -> settings()
            Screen.LIKES, Screen.DISLIKES, Screen.HISTORY -> loadTracks(current.screen)
            Screen.ALBUMS, Screen.ARTISTS, Screen.PODCASTS, Screen.BOOKS -> collection(current.screen)
            Screen.DETAILS -> current.card?.let(::loadDetails)
            Screen.ARTIST_TRACKS -> current.card?.let { card ->
                loadRows {
                    val tracks = store.online { artistTracks(card.id) }
                    loadTrackChips("artist-tracks:${card.id}", tracks)
                    trackList = TrackList(tracks, PlayContext("artist", card.id, card.title))
                    listRows(requireNotNull(trackList))
                }
            }
            Screen.ARTIST_COLLECTION, Screen.ARTIST_WAVE -> current.card?.let { artist -> artistFamiliar(artist, current.screen == Screen.ARTIST_WAVE) }
            Screen.SHELF -> rows(gridRows(current.shelf?.cards.orEmpty()))
            Screen.CLIPS -> loadRows { store.online { clips() }.map(::entry).ifEmpty { listOf(note("Клипов пока нет")) } }
            Screen.CONCERTS -> loadConcerts()
            Screen.WAVE_ROOMS -> loadRooms()
            Screen.STATIONS -> loadRows {
                store.online { stations() }.flatMap { shelf ->
                    listOf(heading(shelf.title)) + waveGridRows(shelf.cards)
                }
            }
            Screen.KIDS -> loadKids()
            Screen.STATS -> stats()
            Screen.DEVICES -> devices()
            Screen.HIDDEN_ARTISTS -> hiddenArtists()
            Screen.STORAGE -> storage()
            Screen.REPLACEMENT -> current.replacement?.let(::replacement)
            Screen.CHANGES -> changes()
            Screen.ERROR_LOG -> errorLogRows()
        }
    }

    private fun homePage(force: Boolean = false) {
        showHome()
        if (homeLoaded && !force) return
        pageJob?.cancel()
        pageJob = action(onError = {
            refreshLayout?.setRefreshing(false)
            homeError = it
            showHome(keepScroll = true)
        }) {
            loadingHome = true
            try {
                if (home == null) {
                    home = store.cachedHome()
                    showHome(keepScroll = true)
                }
                if (store.offline) return@action
                val loaded = store.home()
                if (loaded.hasContent || home?.hasContent != true) home = loaded
                if (home?.trends.isNullOrEmpty()) trending = false
                homeLoaded = true
                homeError = null
            } finally {
                loadingHome = false
                refreshLayout?.setRefreshing(false)
            }
            showHome(keepScroll = true)
        }
    }

    private fun showHome(keepScroll: Boolean = false) {
        val page = home ?: HomePage()
        val offlineOrError = store.offline || homeError != null
        val downloaded = if (!trending && offlineOrError) store.files.downloaded().size else -1
        val own = if (!trending) store.ownPlaylists.value.take(3) else emptyList()
        val key = HomeRowsKey(home, trending, store.offline, homeError, downloaded, own, loadingHome)
        val cached = cachedHomeRows
        if (cachedHomeKey == key && cached != null) {
            if (keepScroll && pageHasRows && adapter?.count == cached.size) return
            rows(cached, keepScroll)
            return
        }
        val shelves = if (trending) page.trends else page.personal
        val stations = shelves.flatMap { it.cards }.filter { it.kind == Kind.WAVE }.distinctBy { it.key }
        val built = buildList {
            if (page.trends.isNotEmpty()) add(Row.Layout(cache = true, ::homeTabs))
            if (!trending) {
                add(Row.WaveHead)
                if (offlineOrError) {
                    add(shortcut(Symbol.DOWNLOAD, "Слушать скачанное",
                        if (downloaded <= 0) "Скачанной музыки пока нет" else "Скачано: ${trackCount(downloaded)}") { push(Screen.DOWNLOADS) })
                }
                if (stations.isNotEmpty()) add(Row.Layout(cache = true) {
                    val allWaves = ui.row().apply {
                        gravity = Gravity.CENTER
                        setPadding(ui.dp(15), 0, ui.dp(15), 0)
                        background = GradientDrawable().apply {
                            setColor(Color.TRANSPARENT)
                            cornerRadius = ui.dp(20).toFloat()
                            setStroke(ui.dp(1), ColorUtils.setAlphaComponent(ui.palette.text, 56))
                        }
                        pressFeedback(.95f)
                        isFocusable = true
                        addView(IconView(this@MainActivity, Symbol.GRID, ui.palette.text).apply { iconSize = 12 },
                            LinearLayout.LayoutParams(ui.dp(13), ui.dp(13)).apply { marginEnd = ui.dp(7) })
                        addView(ui.line("Все волны", 15, ui.palette.text, bold = true))
                        setOnClickListener { push(Screen.STATIONS) }
                    }.apply { layoutParams = LinearLayout.LayoutParams(-2, ui.dp(40)) }
                    val stationCovers = MutableList<CoverView?>(stations.size + 1) { null }
                    val stationTiles = listOf(allWaves) + stations.mapIndexed { index, station ->
                        stationChip(station, lazy = true) { stationCovers[index + 1] = it }
                    }
                    horizontal(stationTiles).also { scroll ->
                        lazyHorizontalCovers(scroll, stationTiles, stationCovers, listOf<String?>(null) + stations.map { it.cover }, 100)
                    }
                })
                addAll(quickRows())
                if (page.recent.isNotEmpty()) {
                    add(section("Вы недавно слушали", "Все") { push(Screen.HISTORY) })
                    add(Row.Layout(cache = true) {
                        val recentCovers = page.recent.map { ui.art(null, 300) }
                        val recentTiles = page.recent.mapIndexed { index, track ->
                            ui.column().apply {
                                layoutParams = LinearLayout.LayoutParams(ui.dp(116), -2)
                                addView(recentCovers[index], ui.full(116))
                                addView(ui.space(8))
                                addView(ui.line(track.title, 14, bold = true))
                                addView(ui.space(4))
                                addView(ui.line(track.artistText, 12, ui.palette.muted))
                                background = ui.touch()
                                setOnClickListener { playTracks(page.recent, index, PlayContext(name = "История")) }
                                isFocusable = true
                            }
                        }
                        horizontal(recentTiles).also { scroll ->
                            lazyHorizontalCovers(scroll, recentTiles, recentCovers, page.recent.map { it.cover }, 300)
                        }
                    })
                }
            }
            addAll(shelfRows(shelves.map { it.copy(cards = it.cards.filterNot { card -> card.kind == Kind.WAVE }) }))
            if (loadingHome && shelves.isEmpty() && !store.offline) add(Row.Layout(make = ::homeSkeleton))
            if (homeError != null && !store.offline) add(shortcut(Symbol.REPEAT, "Обновить главную") { homePage(force = true) })
        }
        cachedHomeKey = key
        cachedHomeRows = built
        rows(built, keepScroll)
    }

    private fun shelfRows(shelves: List<Shelf>) = buildList {
        for (shelf in shelves.filter { it.cards.isNotEmpty() }) {
            add(if (shelf.cards.size > 3) section(shelf.title, "Все") { openShelf(shelf) } else heading(shelf.title))
            add(shelfRow(shelf.cards))
        }
    }

    private fun homeTabs() = ui.column().apply {
        setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(12))
        addView(ui.row().apply {
            setPadding(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2))
            background = ui.shape(ui.palette.panel, 9)
            listOf("Для вас", "Тренды").forEachIndexed { index, label ->
                val selected = (index == 1) == trending
                addView(ui.text(label, 14, bold = selected).apply {
                    gravity = Gravity.CENTER
                    isSelected = selected
                    background = ui.touch(if (selected) ui.palette.lifted else Color.TRANSPARENT, 7)
                    setOnClickListener {
                        trending = index == 1
                        showHome()
                        if (!trending) updateWaveContent()
                    }
                    isFocusable = true
                }, LinearLayout.LayoutParams(0, ui.dp(30), 1f))
            }
        }, ui.full())
    }

    private fun updateWordCard() {
        val track = currentTrack()
        val card = if (store.preferences.getBoolean("waveWords", true)) store.waveExtras.cards.value[track?.id] else null
        waveHead?.words?.bind(track, card)
    }

    private fun updateWaveContent() {
        if (route.screen != Screen.HOME || userId == null || trending) return
        val player = controller
        val context = QueueItems.context(player?.currentMediaItem)
        val seeds = context.item?.split(',')?.filter(String::isNotBlank).orEmpty().ifEmpty { listOf(MY_WAVE) }
        store.waveExtras.loadWheel(if (context.kind == "radio") seeds else listOf(MY_WAVE))
        if (player != null && context.wave != null) {
            val start = player.currentMediaItemIndex.coerceAtLeast(0)
            val upcoming = (start until minOf(start + 10, player.mediaItemCount)).mapNotNull { index ->
                val item = player.getMediaItemAt(index)
                if (QueueItems.context(item).wave == context.wave) QueueItems.track(item) else null
            }
            store.waveExtras.prefetch(context.wave, upcoming)
        } else store.waveExtras.prefetch(null, emptyList())
        updateWordCard()
    }

    private data class HeroState(val context: PlayContext, val active: Boolean, val playing: Boolean, val tuned: Boolean)

    private inner class HomeWaveHead {
        val view = ui.column()
        val words = WaveWordsView(this@MainActivity)
        private val hero = FrameLayout(this@MainActivity)
        private val glow = WaveBackdrop(this@MainActivity, ui.palette.background).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val rail = ui.column()
        private var heroState: HeroState? = null
        private var wheelState: Pair<List<WheelItem>, String?>? = null

        init {
            hero.addView(glow, FrameLayout.LayoutParams(-1, -1))
            view.addView(hero, ui.full(300))
            view.addView(words, ui.full())
            view.addView(rail, ui.full())
        }

        fun resumeGlow() = glow.resumeIfPlaying()

        fun bindHero() {
            val player = controller
            val context = QueueItems.context(player?.currentMediaItem)
            val active = context.kind == "radio" && (context.item == MY_WAVE || context.item == null && context.name == "Моя волна")
            val playing = active && player?.isPlaying == true
            val tuned = store.preferences.getStringSet("waveSettings", emptySet()).orEmpty().isNotEmpty()
            val state = HeroState(context, active, player?.isPlaying == true, tuned)
            if (state == heroState) return
            heroState = state
            glow.playing = playing
            if (hero.childCount > 1) hero.removeViewAt(1)
            hero.addView(waveHero(context, active, playing, tuned), FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            bindWheel()
        }

        fun bindWheel() {
            val player = controller
            val ctx = QueueItems.context(player?.currentMediaItem)
            val key = ctx.item.takeIf { ctx.kind == "radio" }
            val showing = store.preferences.getBoolean("waveWheel", true)
            val items = if (showing) store.waveExtras.wheel.value.ifEmpty {
                listOf(WheelItem("WAVE", "", "Моя волна", Card(Kind.WAVE, MY_WAVE, "Моя волна", seeds = listOf(MY_WAVE)), "#E5A823"),
                    WheelItem("SETTING", "", "Настроить Мою волну", null))
            } else emptyList()
            val state = items to key
            if (state == wheelState) return
            wheelState = state
            rail.removeAllViews()
            if (items.isEmpty()) return
            val wheelItems = items.take(20)
            val covers = arrayOfNulls<CoverView>(wheelItems.size)
            val tiles = wheelItems.mapIndexed { index, item ->
                val active = item.card?.kind == Kind.WAVE && item.card.id == key
                ui.column().apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(ui.dp(92), -2)
                    val circle = FrameLayout(this@MainActivity).apply {
                        val color = parseHexColor(item.color ?: item.card?.color) ?: ui.palette.lifted
                        background = ui.shape(color, 50).apply { if (active) setStroke(ui.dp(3), ui.palette.accent) }
                        val card = item.card
                        val size = if (active) 76 else 84
                        if (card?.cover != null) {
                            val coverView = ui.art(null, 200, circle = true, backdrop = false)
                            covers[index] = coverView
                            addView(coverView, FrameLayout.LayoutParams(ui.dp(size), ui.dp(size), Gravity.CENTER))
                        } else addView(IconView(this@MainActivity, if (card == null) Symbol.SETTINGS else Symbol.WAVE, ui.palette.text),
                            FrameLayout.LayoutParams(ui.dp(42), ui.dp(42), Gravity.CENTER))
                        if (active) addView(FrameLayout(this@MainActivity).apply {
                            background = ui.shape(ui.palette.accent, 17)
                            val bars = PlayingBars(this@MainActivity, Color.BLACK)
                            addView(bars, FrameLayout.LayoutParams(ui.dp(16), ui.dp(16), Gravity.CENTER))
                            observePlayback {
                                bars.running = controller?.isPlaying == true
                                contentDescription = if (bars.running) "Играет" else "Пауза"
                            }
                        }, FrameLayout.LayoutParams(ui.dp(34), ui.dp(34), Gravity.CENTER))
                    }
                    addView(circle, LinearLayout.LayoutParams(ui.dp(84), ui.dp(84)))
                    addView(ui.space(8))
                    addView(ui.text(item.title, 13, if (active) ui.palette.accentText else ui.palette.text, bold = true).apply {
                        maxLines = 2
                        gravity = Gravity.CENTER
                    }, ui.full())
                    pressFeedback(.92f)
                    setOnClickListener {
                        store.waveExtras.wheelEvent(item, "CLICK")
                        if (active) toggle() else item.card?.let(::openCard) ?: waveSettings()
                    }
                    isFocusable = true
                }
            }
            val lane = horizontal(tiles)
            rail.addView(lane)
            fun reportVisible() {
                val visible = android.graphics.Rect()
                for ((index, item) in wheelItems.withIndex()) {
                    val tile = tiles[index]
                    if (tile.getLocalVisibleRect(visible) && visible.width() > tile.width / 2) store.waveExtras.wheelEvent(item, "VIEW")
                }
            }
            lazyHorizontalCovers(lane, tiles, covers.asList(), wheelItems.map { it.card?.cover }, 200, onScrollExtra = ::reportVisible)
        }
    }

    private fun openShelf(shelf: Shelf) {
        hideKeyboard()
        stack.addLast(Route(Screen.SHELF, shelf = shelf))
        render()
    }

    private fun waveHero(ctx: PlayContext, active: Boolean, playing: Boolean, tuned: Boolean): View = ui.column().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(ui.dp(16), 0, ui.dp(16), 0)
        if (!active && ctx.name.isNotBlank()) {
            val now = when (ctx.kind) {
                "radio" -> if (ctx.name.startsWith("Волна")) ctx.name else "Волна «${ctx.name}»"
                "album" -> "Альбом «${ctx.name}»"
                "playlist" -> "Плейлист «${ctx.name}»"
                else -> ctx.name
            }
            addView(ui.row().apply {
                gravity = Gravity.CENTER
                addView(PlayingBars(this@MainActivity, ui.palette.accentText).apply {
                    running = controller?.isPlaying == true
                }, LinearLayout.LayoutParams(ui.dp(13), ui.dp(13)).apply { marginEnd = ui.dp(7) })
                addView(ui.line("Сейчас играет: $now", 14, ui.palette.text, bold = true))
            })
            addView(ui.space(18))
        }
        addView(ui.line("Моя волна", 36, bold = true).apply { gravity = Gravity.CENTER })
        addView(ui.space(18))
        addView(ui.row().apply {
            gravity = Gravity.CENTER
            addView(waveHeroAction(if (playing) "Пауза" else "Слушать",
                Symbol.PLAY, true, playing = playing) {
                if (active) toggle() else startWave(listOf(MY_WAVE), "Моя волна")
            }, LinearLayout.LayoutParams(-2, ui.dp(48)))
            addView(waveHeroAction(if (tuned) "Настроено" else "Настроить", Symbol.SETTINGS, false, click = ::waveSettings),
                LinearLayout.LayoutParams(-2, ui.dp(48)).apply { marginStart = ui.dp(12) })
        })
    }

    private fun waveHeroAction(title: String, symbol: Symbol, primary: Boolean, playing: Boolean = false, click: () -> Unit) = ui.row().apply {
        val color = if (primary) Color.BLACK else ui.palette.text
        val padding = ui.dp(when {
            resources.configuration.screenWidthDp < 350 -> 12
            resources.configuration.screenWidthDp <= 370 -> if (primary) 22 else 18
            primary -> 26
            else -> 20
        })
        setPadding(padding, 0, padding, 0)
        background = ui.touch(if (primary) ui.palette.accent else ui.palette.panel, 24)
        pressFeedback(.95f)
        val glyph = if (playing) PlayingBars(this@MainActivity, color).apply { running = true }
            else IconView(this@MainActivity, symbol, color).apply { iconSize = 18 }
        addView(glyph, LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)))
        addView(ui.line(title, 16, color, bold = true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(7) })
        setOnClickListener { click() }
        isFocusable = true
    }

    private fun homeSkeleton() = ui.row().apply {
        setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(12))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        repeat(3) {
            addView(View(this@MainActivity).apply { background = ui.shape(ui.palette.panel, 14) },
                LinearLayout.LayoutParams(0, ui.dp(105), 1f).apply { if (it > 0) marginStart = ui.dp(10) })
        }
        pulseWhileAttached()
    }

    private fun quickRows(): List<Row> {
        val tiles = listOf<() -> View>(
            { quickTile("Мне нравится", Symbol.HEART_FILLED) { push(Screen.LIKES) } },
            { quickTile("Скачанное", Symbol.DOWNLOAD) { push(Screen.DOWNLOADS) } },
            { quickTile("История", Symbol.HISTORY) { push(Screen.HISTORY) } },
        ) + store.ownPlaylists.value.take(3).map { card ->
            { quickTile(card.title, cover = card.cover) { openCard(card) } }
        }
        return tiles.chunked(2).map { pair ->
            Row.Layout(cache = true) {
                ui.row().apply {
                    setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(10))
                    pair.forEachIndexed { index, item ->
                        addView(item(), LinearLayout.LayoutParams(0, ui.dp(56), 1f).apply { if (index > 0) marginStart = ui.dp(10) })
                    }
                    if (pair.size == 1) addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = ui.dp(10) })
                }
            }
        }
    }

    private fun quickTile(title: String, symbol: Symbol? = null, cover: String? = null, click: () -> Unit) = ui.row().apply {
        background = ui.touch(ui.palette.panel, 14)
        clipToOutline = true
        val art = if (symbol == null) ui.art(cover, 120, corner = 0) else ui.symbolCover(symbol, 56, 0)
        addView(art, LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)))
        addView(ui.text(title, 14, bold = true).apply {
            maxLines = if (title.any(Char::isWhitespace)) 2 else 1
            setAutoSizeTextTypeUniformWithConfiguration(12, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPaddingRelative(ui.dp(10), 0, ui.dp(4), 0)
        }, ui.weight())
        pressFeedback(.95f)
        setOnClickListener { click() }
        isFocusable = true
    }

    private fun library() {
        val byName = store.preferences.getBoolean("playlistsByName", false)
        val sort = compareBy<Card, String>(java.text.Collator.getInstance(Locale.forLanguageTag("ru"))) { it.title }
        fun playlistCards(items: List<Card>, own: Boolean) = (if (byName) items.sortedWith(sort) else items).map { card ->
            val sub = if (own) {
                if (card.trackCount > 0) trackCount(card.trackCount) else card.subtitle
            } else {
                if (card.trackCount > 0) "Добавлен · ${trackCount(card.trackCount)}" else "Добавлен в коллекцию"
            }
            card.copy(subtitle = sub)
        }
        val displayed = playlistCards(store.ownPlaylists.value, own = true) + playlistCards(store.savedPlaylists.value, own = false)
        val count = store.likes.value.size
        val downloaded = store.files.downloaded().size
        rows(buildList {
            availableUpdate?.let { update ->
                add(libraryEntry(Symbol.DOWNLOAD, "Доступна версия ${update.version}", "Обновить") { push(Screen.SETTINGS) })
            }
            add(Row.Layout {
                ui.row().apply {
                    setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(20))
                    addView(ui.symbolCover(Symbol.HEART_FILLED, 92), LinearLayout.LayoutParams(ui.dp(92), ui.dp(92)))
                    addView(ui.column().apply {
                        setPadding(ui.dp(16), 0, 0, 0)
                        addView(ui.text("Мне нравится", 24, bold = true))
                        addView(ui.space(4))
                        addView(ui.line(trackCount(count), 15, ui.palette.muted))
                    }, ui.weight())
                    addView(IconView(this@MainActivity, Symbol.BACK, ui.palette.muted).apply { rotation = 180f; iconSize = 16 },
                        LinearLayout.LayoutParams(ui.dp(20), ui.dp(34)))
                    background = ui.touch()
                    setOnClickListener { push(Screen.LIKES) }
                    isFocusable = true
                }
            })
            val queued = store.files.status.value.values.count { it.error == null }
            libraryQueuedCount = queued
            val downloadsValue = when {
                queued > 0 -> "ещё $queued в очереди"
                downloaded > 0 -> downloaded.toString()
                else -> null
            }
            add(libraryEntry(Symbol.DOWNLOAD_CIRCLE, "Скачанные треки", downloadsValue) { push(Screen.DOWNLOADS) })
            add(libraryEntry(Symbol.LIBRARY, "Альбомы", savedCount(Kind.ALBUM)) { push(Screen.ALBUMS) })
            add(libraryEntry(Symbol.MIC_MUSIC, "Исполнители", savedCount(Kind.ARTIST)) { push(Screen.ARTISTS) })
            add(libraryEntry(Symbol.MIC, "Подкасты") { push(Screen.PODCASTS) })
            add(libraryEntry(Symbol.BOOK, "Аудиокниги") { push(Screen.BOOKS) })
            add(libraryEntry(Symbol.BALLOON, "Детям") { push(Screen.KIDS) })
            add(libraryEntry(Symbol.CONCERT, "Концерты") { push(Screen.CONCERTS) })
            add(libraryEntry(Symbol.HISTORY, "История") { push(Screen.HISTORY) })
            add(libraryEntry(Symbol.STATS, "Статистика") { push(Screen.STATS) })
            if (store.dislikes.value.isNotEmpty()) add(libraryEntry(Symbol.THUMB_DOWN, "Не нравится", store.dislikes.value.size.toString()) { push(Screen.DISLIKES) })
            add(libraryEntry(Symbol.PERSON_MINUS, "Скрытые исполнители") { push(Screen.HIDDEN_ARTISTS) })
            add(Row.Layout {
                ui.row().apply {
                    setPadding(ui.dp(16), ui.dp(26), ui.dp(16), ui.dp(10))
                    addView(ui.text("Плейлисты", 22, bold = true), ui.weight())
                    addView(ui.row().apply {
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(10))
                        background = ui.touch()
                        addView(IconView(this@MainActivity, Symbol.SORT, ui.palette.muted).apply { iconSize = 13 },
                            LinearLayout.LayoutParams(ui.dp(14), ui.dp(14)).apply { marginEnd = ui.dp(4) })
                        addView(ui.text(if (byName) "По названию" else "Недавние", 14, ui.palette.muted, bold = true))
                        setOnClickListener { store.preferences.edit().putBoolean("playlistsByName", !byName).apply(); library() }
                        isFocusable = true
                    })
                    addView(ui.icon(Symbol.ADD, "Создать или импортировать плейлист", click = ::playlistActions).apply {
                        iconSize = 18
                        background = ui.touch(ui.palette.panel, 18)
                    }, LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)))
                }
            })
            if (displayed.isEmpty()) add(note("Создайте плейлист или добавьте чужой в коллекцию."))
            else addAll(displayed.map(::entry))
        }, keepScroll = true)
        checkForUpdates()
    }

    private fun savedCount(kind: Kind) =
        store.savedCards.value.count { it.startsWith("${kind.name}:") }.takeIf { it > 0 }?.toString()

    private fun libraryEntry(symbol: Symbol, label: String, value: String? = null, click: () -> Unit) = Row.Layout {
        ui.row().apply {
            minimumHeight = ui.dp(58)
            setPadding(ui.dp(16), ui.dp(7), ui.dp(16), ui.dp(7))
            addView(IconView(this@MainActivity, symbol, ui.palette.text).apply {
                iconSize = 20
                background = ui.shape(ui.palette.lifted, 13)
            }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
            addView(ui.line(label, 17).apply { setPadding(ui.dp(14), 0, 0, 0) }, ui.weight())
            value?.let { addView(ui.line(it, 15, ui.palette.muted).apply { setPadding(0, 0, ui.dp(8), 0) }) }
            addView(IconView(this@MainActivity, Symbol.BACK, ui.palette.muted).apply { rotation = 180f; iconSize = 14 },
                LinearLayout.LayoutParams(ui.dp(18), ui.dp(36)))
            background = ui.touch()
            setOnClickListener { click() }
            isFocusable = true
        }
    }

    private fun loadTracks(screen: Screen) {
        val uid = userId ?: return
        val context = PlayContext(if (screen == Screen.LIKES) "playlist" else "various",
            if (screen == Screen.LIKES) "$uid:3" else null, screen.label)
        val symbol = when (screen) {
            Screen.LIKES -> Symbol.HEART_FILLED
            Screen.DISLIKES -> Symbol.THUMB_DOWN
            else -> Symbol.CLOCK
        }
        fun trackRowsForPage(tracks: List<Track>): List<Row> {
            val data = TrackList(tracks, context, symbol)
            trackList = data
            if (screen == Screen.LIKES) loadTrackChips("likes", tracks)
            return listRows(data)
        }
        loadRows(loading = {
            listOf(Row.Layout { listHeader(emptyList(), context, symbol, loading = true) }, Row.Layout { ui.loadingRows() })
        }) { showCached ->
            val onCached = showCached?.let { display -> { cached: List<Track> -> display(trackRowsForPage(cached)) } }
            val tracks = if (screen == Screen.LIKES) {
                store.likedTracks(onCached)
            } else {
                val key = if (screen == Screen.DISLIKES) "dislikes" else "history"
                store.cachedTracks(key, onCached) {
                    store.online {
                        if (screen == Screen.DISLIKES) tracks(libraryIds(uid, dislike = true)) else history()
                    }
                }
            }
            trackRowsForPage(tracks)
        }
    }

    private fun artistFamiliar(artist: Card, wave: Boolean) = loadRows {
        val account = store.requireUser()
        val catalog = store.versions.artistInsertions(artist.id).first.toSet()
        val local = if (wave) store.listening.recentWave(account.uid, artist, catalog) else {
            catalog.mapNotNull { store.versions.synthetic(it) }.filter { track ->
                track.id in store.likes.value || track.album?.key in store.savedCards.value
            }
        }
        val remote = try {
            store.cachedTracks("artist-familiar:${artist.id}:$wave") { store.online { artistFamiliar(artist.id, wave) } }
        } catch (error: java.io.IOException) {
            if (local.isEmpty()) throw error
            emptyList()
        }
        val selected = (remote + local).distinctBy { it.id }
        val context = PlayContext("artist", artist.id, artist.title)
        val data = TrackList(selected, context, if (wave) Symbol.WAVE else Symbol.HEART_FILLED)
        trackList = data
        listRows(data)
    }

    private fun collection(screen: Screen) = loadRows(
        loading = { listOf(Row.Layout { ui.loadingGrid(circle = screen == Screen.ARTISTS) }) }
    ) { showCached ->
        if (screen == Screen.ARTISTS) {
            var cachedTop = emptyList<Card>()
            var cachedLiked = emptyList<Card>()
            fun publishCached() {
                if (cachedTop.isNotEmpty() || cachedLiked.isNotEmpty()) {
                    showCached?.invoke(collectionRows(screen, cachedLiked, cachedTop))
                }
            }
            coroutineScope {
                val topDeferred = async {
                    try {
                        store.topArtists(onCached = showCached?.let {
                            { cached -> cachedTop = cached; publishCached() }
                        })
                    } catch (_: java.io.IOException) {
                        emptyList()
                    }
                }
                val likedDeferred = async {
                    store.collection(Kind.ARTIST, onCached = showCached?.let {
                        { cached -> cachedLiked = cached; publishCached() }
                    })
                }
                collectionRows(screen, likedDeferred.await(), topDeferred.await())
            }
        } else {
            val cards = store.collection(Kind.ALBUM, onCached = showCached?.let { display ->
                { cached -> if (cached.isNotEmpty()) display(collectionRows(screen, cached)) }
            })
            collectionRows(screen, cards)
        }
    }

    private fun collectionRows(screen: Screen, cards: List<Card>, topArtists: List<Card> = emptyList()): List<Row> {
        val filtered = cards.filter { card ->
            when (screen) {
                Screen.PODCASTS -> card.releaseType == "podcast"
                Screen.BOOKS -> card.releaseType == "audiobook"
                Screen.ALBUMS -> card.releaseType !in setOf("podcast", "audiobook")
                else -> true
            }
        }
        val collator = java.text.Collator.getInstance(Locale.forLanguageTag("ru"))
        val ordered = when (store.preferences.getInt("collectionOrder:${screen.name}", 0)) {
            1 -> filtered.sortedWith(compareBy(collator) { it.title })
            2 -> filtered.sortedWith(compareBy(collator) { it.subtitle })
            else -> filtered
        }
        if (screen == Screen.ARTISTS && topArtists.isNotEmpty()) {
            return buildList {
                add(section("Ваш топ месяца"))
                addAll(gridRows(topArtists))
                if (ordered.isNotEmpty()) {
                    add(section("Вам нравятся"))
                    addAll(gridRows(ordered))
                }
            }
        }
        return gridRows(ordered).ifEmpty { listOf(note("В коллекции пока пусто")) }
    }

    private fun hiddenArtists(): Unit = loadRows {
        val uid = store.requireUser().uid
        val cards = store.online { dislikedArtists(uid) }
        cards.map { card ->
            shortcut(Symbol.PERSON_MINUS, card.title, "Вернуть в рекомендации") {
                action { store.online { setArtistHidden(uid, card.id, false) }; if (route.screen == Screen.HIDDEN_ARTISTS) hiddenArtists() }
            }
        }.ifEmpty { listOf(note("Вы ещё не скрывали исполнителей")) }
    }

    private fun loadDetails(card: Card) = loadRows(
        loading = { listOf(Row.Layout { pageHeader(MusicPage(card, emptyList()), context = null) }, Row.Layout { ui.loadingRows() }) }
    ) { showCached ->
        reorderJob?.join()
        val page = store.details(card, onCached = showCached?.let { display ->
            { cached ->
                details = cached
                if (card.kind == Kind.PLAYLIST || (card.kind == Kind.ALBUM && !card.isPodcast) || card.kind == Kind.ARTIST) {
                    loadTrackChips("details:${card.key}", cached.tracks)
                }
                display(detailRows(cached))
            }
        })
        details = page
        if (card.kind == Kind.PLAYLIST || (card.kind == Kind.ALBUM && !card.isPodcast) || card.kind == Kind.ARTIST) {
            loadTrackChips("details:${card.key}", page.tracks)
        }
        detailRows(page)
    }

    private fun detailRows(page: MusicPage): List<Row> {
        val context = PlayContext(page.card.kind.name.lowercase(Locale.ROOT),
            if (page.card.kind == Kind.PLAYLIST) "${page.card.owner}:${page.card.id}" else page.card.id, page.card.title)
        val displayed = if (page.card.kind == Kind.ARTIST) page.tracks else visibleTracks(page.tracks)
        return buildList {
            add(Row.Layout(cache = true) { pageHeader(page, context) })
            if ((page.card.kind == Kind.PLAYLIST || (page.card.kind == Kind.ALBUM && !page.card.isPodcast)) &&
                trackChips.isNotEmpty() && !reordering) {
                add(trackChipsRow())
            }
            page.latestRelease?.let {
                add(heading("Последний релиз"))
                add(shelfRow(listOf(it)))
            }
            if (displayed.isNotEmpty()) {
                val owned = page.card.takeIf { it.kind == Kind.PLAYLIST && it.owner == userId }
                if (page.card.kind == Kind.ARTIST) {
                    add(section("Популярные треки", "Все") { push(Screen.ARTIST_TRACKS, page.card) })
                    if (trackChips.isNotEmpty()) add(trackChipsRow())
                    val chipIds = activeChipTracks()
                    if (chipIds != null) {
                        val filtered = page.tracks.filter { it.id in chipIds }
                        addAll(trackRows(filtered, context))
                    } else {
                        addAll(trackRows(page.tracks, context).take(5))
                    }
                } else if (page.card.isPodcast) {
                    add(heading("Выпуски"))
                    addAll(trackRows(displayed, context))
                } else if (page.card.kind == Kind.ALBUM && trackFilter.isBlank() && !onlyDownloaded && trackOrder == TrackOrder.ORIGINAL && selectedTrackChip == null) {
                    val disks = page.disks.takeIf { it.sum() == page.tracks.size } ?: listOf(page.tracks.size)
                    var start = 0
                    disks.forEachIndexed { disk, count ->
                        if (disks.size > 1) add(heading("Диск ${disk + 1}"))
                        repeat(count) { number ->
                            val index = start + number
                            val track = page.tracks[index]
                            add(Row.Music(track, { playTracks(page.tracks, index, context) }, { trackMenu(track, anchor = it) }, number + 1))
                        }
                        start += count
                    }
                    add(Row.Layout {
                        ui.column().apply {
                            setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(6))
                            page.description?.takeIf(String::isNotBlank)?.let { label ->
                                addView(ui.text(label, 13, ui.palette.muted))
                                addView(ui.space(4))
                            }
                            addView(ui.text("${trackCount(page.tracks.size)} · ${durationSummary(page.tracks.sumOf { it.duration })}",
                                13, ui.palette.muted))
                            if (page.tracks.any { it.alternate }) {
                                addView(ui.space(6))
                                addView(ui.row().apply {
                                    gravity = Gravity.CENTER_VERTICAL
                                    addView(IconView(this@MainActivity, Symbol.UNLOCK, ui.palette.muted).apply { iconSize = 13 },
                                        LinearLayout.LayoutParams(ui.dp(14), ui.dp(14)).apply { marginEnd = ui.dp(6) })
                                    addView(ui.text("Есть треки без цензуры", 13, ui.palette.muted))
                                })
                            }
                        }
                    })
                } else addAll(trackRows(displayed, context, owned))
            } else if (page.card.kind != Kind.ARTIST) add(Row.Layout {
                val ownPlaylist = page.card.kind == Kind.PLAYLIST && page.card.owner == userId
                when {
                    page.tracks.isNotEmpty() -> ui.pagePlaceholder(Symbol.SEARCH, "Ничего не найдено")
                    ownPlaylist -> ui.pagePlaceholder(Symbol.QUEUE, "В плейлисте пока пусто",
                        "Добавляйте треки через их меню: «Добавить в плейлист».")
                    else -> ui.pagePlaceholder(Symbol.QUEUE, "Здесь пока пусто")
                }
            })
            if (page.card.kind == Kind.ARTIST) {
                add(libraryEntry(Symbol.HEART, "У вас в коллекции") { push(Screen.ARTIST_COLLECTION, page.card) })
                add(libraryEntry(Symbol.WAVE, "Слушали в Волне") { push(Screen.ARTIST_WAVE, page.card) })
            }
            page.shelves.filter { it.cards.isNotEmpty() }.forEach { shelf ->
                if (shelf.cards.all { it.kind == Kind.CONCERT }) {
                    add(heading(shelf.title))
                    addAll(shelf.cards.map(::concertRow))
                } else {
                    add(if (shelf.cards.size > 3) section(shelf.title, "Все") { openShelf(shelf) } else heading(shelf.title))
                    add(shelfRow(shelf.cards))
                }
            }
            if (page.card.kind == Kind.ARTIST) page.description?.takeIf(String::isNotBlank)?.let { about ->
                add(heading("Об исполнителе"))
                add(Row.Layout {
                    var expanded = false
                    lateinit var bio: TextView
                    lateinit var more: TextView
                    ui.column().apply {
                        setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(14))
                        bio = ui.text(about, 15, ColorUtils.setAlphaComponent(ui.palette.text, 196)).apply {
                            maxLines = 3
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setLineSpacing(ui.dp(2).toFloat(), 1f)
                        }
                        more = ui.text("Читать дальше", 15, ui.palette.accentText, bold = true).apply {
                            setPadding(0, ui.dp(8), 0, 0)
                            visibility = View.GONE
                        }
                        addView(bio, ui.full())
                        addView(more)
                        bio.post {
                            val truncated = bio.layout?.let { layout ->
                                layout.lineCount > 0 && layout.getEllipsisCount(layout.lineCount - 1) > 0
                            } == true
                            if (truncated && !expanded) more.visibility = View.VISIBLE
                        }
                        background = ui.touch()
                        setOnClickListener {
                            if (more.visibility != View.VISIBLE && !expanded) return@setOnClickListener
                            expanded = !expanded
                            bio.maxLines = if (expanded) Int.MAX_VALUE else 3
                            bio.ellipsize = if (expanded) null else android.text.TextUtils.TruncateAt.END
                            more.text = if (expanded) "Свернуть" else "Читать дальше"
                            more.visibility = View.VISIBLE
                        }
                    }
                })
            }
        }
    }

    private fun pageHeader(page: MusicPage, context: PlayContext?): View = ui.column().apply {
        val card = page.card
        val artist = card.kind == Kind.ARTIST
        val gradient = SmoothGradientDrawable(ui.palette.panel, ui.palette.background)
        background = gradient
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(12))
        val size = minOf(if (artist) 190 else 220, resources.configuration.screenWidthDp - 60).coerceAtLeast(100)
        addView(ui.art(card.cover, 800, artist, 18) { tone ->
            val top = if (ui.palette.light) ColorUtils.blendARGB(tone, ui.palette.background, .70f) else tone
            gradient.setColors(top, ui.palette.background)
            if (route.card?.key == card.key) {
                detailTone = top
                updateDetailChrome()
            }
        }.apply {
            elevation = ui.dp(11).toFloat()
            if (context != null && card.kind == Kind.PLAYLIST && card.owner == userId) {
                contentDescription = "Сменить обложку"
                setOnClickListener { mediaPickerPlaylist = card; playlistCoverDocument.launch("image/*") }
                isFocusable = true
                pressFeedback(.96f)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }
        },
            LinearLayout.LayoutParams(ui.dp(size), ui.dp(size)))
        addView(ui.space(18))
        val kind = when (card.releaseType) {
            "single" -> "Сингл"
            "compilation" -> "Сборник"
            "podcast" -> "Подкаст"
            else -> card.kind.label
        }
        addView(ui.text(kind.uppercase(Locale.forLanguageTag("ru")), 11, ui.palette.muted, bold = true).apply {
            gravity = Gravity.CENTER
            letterSpacing = .09f
        })
        addView(ui.space(6))
        addView(ui.text(card.title, if (artist) 30 else 26, bold = true).apply {
            id = DETAIL_HEADER_TITLE
            gravity = Gravity.CENTER
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (card.kind != Kind.PLAYLIST && card.subtitle.isNotBlank()) {
            addView(ui.space(6))
            val mainArtist = if (card.kind == Kind.ALBUM) {
                page.tracks.firstOrNull()?.artists?.firstOrNull { it.id.isNotBlank() && card.subtitle.contains(it.title, ignoreCase = true) }
            } else null
            addView(ui.text(card.subtitle, 15, ui.palette.muted, bold = true).apply {
                gravity = Gravity.CENTER
                if (mainArtist != null) {
                    background = ui.touch()
                    pressFeedback(.96f)
                    setOnClickListener { openCard(mainArtist) }
                }
            })
        }
        if (!artist && context == null) {
            addView(ui.space(6))
            addView(ui.loadingShape(), LinearLayout.LayoutParams(ui.dp(102), ui.dp(13)))
        } else if (!artist && page.tracks.isNotEmpty()) {
            val parts = buildList {
                if (card.kind == Kind.PLAYLIST && card.owner != userId && card.subtitle.isNotBlank() && card.subtitle != "Плейлист") add(card.subtitle)
                add(trackCount(page.tracks.size, episodes = card.isPodcast))
                if (card.kind != Kind.ALBUM) add(durationSummary(page.tracks.sumOf { it.duration }))
            }
            addView(ui.space(6))
            addView(ui.text(parts.joinToString(" · "), 13, ui.palette.muted).apply { gravity = Gravity.CENTER })
        }
        page.description?.takeIf { !artist && it.isNotBlank() }?.let { addView(ui.text(it, 14, ui.palette.muted).apply {
            maxLines = 3
            gravity = Gravity.CENTER
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, ui.dp(4), 0, 0)
        }) }
        addView(ui.space(20))
        val readyContext = context ?: run {
            addView(ui.loadingTrackControls(), ui.full())
            return@apply
        }
        addView(ui.row().apply {
            gravity = Gravity.CENTER
            if (artist) {
                val liked = card.key in store.savedCards.value
                addView(roundIcon(if (liked) Symbol.HEART_FILLED else Symbol.HEART, if (liked) "Убрать из коллекции" else "В коллекцию",
                    if (liked) ui.palette.accent else ui.palette.text) { action { store.save(card) } })
                gap(this, 14)
                addView(playPill({ activeChipTracks()?.let { ids -> page.tracks.filter { it.id in ids } } ?: page.tracks }, readyContext))
                gap(this, 14)
                addView(roundIcon(Symbol.WAVE, "Волна исполнителя") { startWave(listOf("artist:${card.id}"), card.title) })
            } else addTrackControls(this, page.tracks, readyContext) { visibleTracks(page.tracks) }
        }, ui.full())
    }

    private fun updateDetailsTitle(current: Route, page: android.widget.AbsListView, first: Int) {
        val title = toolbarTitle ?: return
        val caption = details?.card?.title ?: current.card?.title.orEmpty()
        if (title.text != caption) title.text = caption
        val header = page.getChildAt(0)
        val heading = header?.findViewById<TextView>(DETAIL_HEADER_TITLE)
        val alpha = when {
            first > 0 -> 1f
            heading == null -> 0f
            else -> {
                val bottom = header.top + heading.bottom
                val range = ui.dp(34)
                if (playbackMotion.enabled) (1f - bottom.toFloat() / range).coerceIn(0f, 1f)
                else if (bottom <= 0) 1f else 0f
            }
        }
        title.alpha = alpha
        title.importantForAccessibility = if (alpha >= .5f) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun updateDetailChrome() {
        val bar = detailToolbar ?: return
        val list = list ?: return
        val offset = -(list.getChildAt(0)?.top ?: 0)
        val blend = if (list.firstVisiblePosition > 0) 1f else (offset.toFloat() / ui.dp(132)).coerceIn(0f, 1f)
        val background = ui.palette.background
        val color = androidx.core.graphics.ColorUtils.blendARGB(detailTone ?: background, background, blend)
        if (detailChrome == color) return
        detailChrome = color
        bar.setBackgroundColor(color)
        updateSystemBars()
    }

    private fun downloads() {
        val running = store.files.status.value.values.toList()
        downloadRows = running.map { it.track.id to it.error }
        val saved = store.files.downloaded()
        rows(buildList {
            add(Row.Layout { listHeader(saved, PlayContext(name = Screen.DOWNLOADS.label), Symbol.DOWNLOAD) })
            if (running.isNotEmpty()) {
                val paused = store.files.paused.value
                val active = running.any { it.error == null }
                val failed = running.filter { it.error != null }
                val label = when {
                    !active -> "Не удалось скачать"
                    paused -> "На паузе"
                    else -> store.files.networkWait.value?.label ?: "Скачивание"
                }
                add(when {
                    active -> section(label, if (paused) "Продолжить" else "Пауза") { store.files.setPaused(!paused) }
                    failed.isNotEmpty() -> section(label, "Повторить все") { action { store.files.enqueue(failed.map { it.track }) } }
                    else -> heading(label)
                })
                running.forEach { state ->
                    add(Row.Layout {
                        val latest = store.files.status.value[state.track.id] ?: state
                        shortcut(if (state.error == null) Symbol.DOWNLOAD else Symbol.REPEAT, state.track.title,
                            downloadLabel(latest), subtitleTag = DOWNLOAD_PROGRESS) {
                            if (state.error != null) action { store.files.enqueue(listOf(state.track)) }
                        }.make().apply { tag = state.track.id }
                    })
                }
                if (active && failed.isNotEmpty()) add(shortcut(Symbol.REPEAT, "Повторить ошибки") {
                    action { store.files.enqueue(failed.map { it.track }) }
                })
                if (active) add(shortcut(Symbol.CLOSE, "Отменить загрузки") { store.files.cancelDownloads() })
            }
            if (saved.isEmpty() && running.isEmpty()) add(Row.Layout {
                ui.pagePlaceholder(Symbol.DOWNLOAD_CIRCLE, "Пока ничего не скачано",
                    "Выберите «Скачать» в меню трека, чтобы слушать его без интернета.")
            })
            else {
                val displayed = visibleTracks(saved)
                if (displayed.isEmpty() && saved.isNotEmpty()) add(Row.Layout {
                    ui.pagePlaceholder(Symbol.SEARCH, "Ничего не найдено")
                })
                else addAll(trackRows(displayed, PlayContext(name = Screen.DOWNLOADS.label)))
            }
        }, keepScroll = true)
    }

    private fun downloadLabel(state: DownloadStatus) =
        state.error ?: state.percent?.let { "Скачивается · $it%" } ?: "В очереди"

    private fun updateDownloadRows() {
        val states = store.files.status.value
        if (downloadRows != states.values.map { it.track.id to it.error }) {
            downloads()
            return
        }
        val page = list ?: return
        for (index in 0 until page.childCount) {
            val row = page.getChildAt(index)
            val id = row.tag as? String ?: continue
            val state = states[id] ?: continue
            val label = row.findViewWithTag<TextView>(DOWNLOAD_PROGRESS) ?: continue
            val text = downloadLabel(state)
            if (label.text != text) label.text = text
        }
    }

    private fun listRows(data: TrackList): List<Row> = buildList {
        add(Row.Layout { listHeader(data.tracks, data.context, data.art) })
        if (trackChips.isNotEmpty() && route.screen in setOf(Screen.LIKES, Screen.ARTIST_TRACKS)) {
            add(trackChipsRow())
        }
        val visible = visibleTracks(data.tracks)
        if (visible.isEmpty()) add(Row.Layout {
            if (data.tracks.isEmpty()) ui.pagePlaceholder(Symbol.QUEUE, "Здесь пока пусто")
            else ui.pagePlaceholder(Symbol.SEARCH, "Ничего не найдено")
        })
        else {
            val rows = trackRows(visible, data.context)
            var lastDate: String? = null
            rows.forEach { row ->
                val date = row.track.historyDate
                if (route.screen == Screen.HISTORY && trackOrder == TrackOrder.ORIGINAL && date != null && date != lastDate) {
                    add(heading(historyDate(date)))
                    lastDate = date
                }
                add(row)
            }
        }
    }

    private fun historyDate(value: String): String {
        val date = optional { java.time.LocalDate.parse(value) } ?: return value
        val today = java.time.LocalDate.now()
        if (date == today) return "Сегодня"
        if (date == today.minusDays(1)) return "Вчера"
        val format = java.time.format.DateTimeFormatter.ofPattern(if (date.year == today.year) "d MMMM" else "d MMMM yyyy",
            Locale.forLanguageTag("ru"))
        return date.format(format)
    }

    private fun activeChipTracks(): Set<String>? =
        selectedTrackChip?.let { id -> trackChips.firstOrNull { it.id == id }?.trackIds }

    private fun loadTrackChips(key: String, tracks: List<Track>) {
        val ids = tracks.asSequence().map { it.id }.filter { it.isNotBlank() && it.all(Char::isDigit) }.distinct().toList()
        val cacheKey = "$key:${ids.size}:${ids.firstOrNull().orEmpty()}:${ids.lastOrNull().orEmpty()}"
        if (trackChipsKey == cacheKey) return
        trackChipsKey = cacheKey
        trackChipsJob?.cancel()
        if (ids.size < 4) {
            trackChips = emptyList()
            selectedTrackChip = null
            return
        }
        trackChipsCache[cacheKey]?.let { cached ->
            trackChips = cached
            if (selectedTrackChip != null && cached.none { it.id == selectedTrackChip }) selectedTrackChip = null
            return
        }
        if (store.offline || !hasNetwork()) return
        trackChipsJob = lifecycleScope.launch {
            val loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                optional { store.online { trackFilters(ids) } }
            } ?: return@launch
            if (trackChipsKey != cacheKey) return@launch
            trackChipsCache[cacheKey] = loaded
            trackChips = loaded
            if (selectedTrackChip != null && loaded.none { it.id == selectedTrackChip }) selectedTrackChip = null
            if (loaded.isNotEmpty() || selectedTrackChip != null) updateTrackFilter()
        }
    }

    private fun trackChipsRow(): Row.Layout = Row.Layout {
        val current = selectedTrackChip
        val buttons = mutableListOf<View>()
        buttons += ui.chip("Всё", active = current == null) {
            if (selectedTrackChip != null) {
                selectedTrackChip = null
                updateTrackFilter()
            }
        }
        trackChips.forEach { filter ->
            buttons += ui.chip(filter.name, active = current == filter.id) {
                selectedTrackChip = if (selectedTrackChip == filter.id) null else filter.id
                updateTrackFilter()
            }
        }
        val activeIndex = if (current == null) 0 else (trackChips.indexOfFirst { it.id == current } + 1).coerceAtLeast(0)
        horizontal(buttons).apply {
            setPadding(0, ui.dp(2), 0, ui.dp(4))
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val chip = buttons.getOrNull(activeIndex) ?: return@addOnLayoutChangeListener
                if (chip.right > scrollX + width || chip.left < scrollX) {
                    scrollTo((chip.left - ui.dp(16)).coerceAtLeast(0), 0)
                }
            }
        }
    }

    private fun visibleTracks(tracks: List<Track>): List<Track> {
        val query = trackFilter.trim()
        val chipTracks = activeChipTracks()
        val filtered = tracks.filter { track ->
            (!onlyDownloaded || store.files.has(track)) &&
                (chipTracks == null || track.id in chipTracks) &&
                (query.isEmpty() || track.title.contains(query, ignoreCase = true) || track.artistText.contains(query, ignoreCase = true))
        }
        val order = java.text.Collator.getInstance(Locale.forLanguageTag("ru"))
        return when (trackOrder) {
            TrackOrder.ORIGINAL -> filtered
            TrackOrder.TITLE -> filtered.sortedWith(compareBy(order) { it.title })
            TrackOrder.ARTIST -> filtered.sortedWith(compareBy(order) { it.artistText })
            TrackOrder.RECENT -> filtered.sortedWith(compareBy<Track> { it.addedAt == null }.thenByDescending { it.addedAt ?: Long.MIN_VALUE })
            TrackOrder.OLDEST -> filtered.sortedWith(compareBy<Track> { it.addedAt == null }.thenBy { it.addedAt ?: Long.MAX_VALUE })
        }
    }

    private fun updateTrackFilter() {
        when (route.screen) {
            Screen.DOWNLOADS -> downloads()
            Screen.DETAILS -> details?.let { rows(detailRows(it), keepScroll = true) }
            else -> trackList?.let { rows(listRows(it), keepScroll = true) }
        }
    }

    private fun showTrackFilter(focus: Boolean = true) {
        if (filterBar != null) return
        val field = EditText(this).apply {
            setSingleLine()
            hint = "Поиск в списке"
            setText(trackFilter)
            setTextColor(ui.palette.text)
            setHintTextColor(ui.palette.muted)
            setPadding(ui.dp(13), 0, ui.dp(8), 0)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = ui.shape(ui.palette.panel, 22)
            doAfterTextChanged {
                trackFilter = it.toString()
                updateTrackFilter()
            }
            setOnEditorActionListener { _, _, _ -> hideKeyboard(); true }
        }
        val bar = ui.row().apply {
            setPadding(ui.dp(16), 0, ui.dp(10), ui.dp(6))
            addView(field, LinearLayout.LayoutParams(0, ui.dp(42), 1f))
            addView(ui.button("Отмена", click = ::hideTrackFilter).apply { background = ui.touch() })
        }
        filterBar = bar
        filterField = field
        content.addView(bar, 1, ui.full(52))
        if (focus) {
            field.requestFocus()
            field.post { getSystemService(InputMethodManager::class.java).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT) }
        }
    }

    private fun hideTrackFilter() {
        hideKeyboard()
        filterBar?.let(content::removeView)
        filterBar = null
        filterField = null
        trackFilter = ""
        updateTrackFilter()
    }

    private fun trackListMenu(page: MusicPage? = null, anchor: View) {
        val all = page?.tracks ?: trackList?.tracks ?: store.files.downloaded().takeIf { route.screen == Screen.DOWNLOADS }.orEmpty()
        val options = buildList {
            if (all.isNotEmpty() && route.screen != Screen.DOWNLOADS) add(MenuAction("Скачать всё", Symbol.DOWNLOAD) {
                action { store.files.enqueue(all); toast("Загрузки добавлены") }
            })
            if (store.files.status.value.values.any { it.error == null }) add(MenuAction("Отменить загрузки", Symbol.CLOSE) {
                store.files.cancelDownloads()
                toast("Загрузки отменены")
            })
            if (all.isNotEmpty()) add(MenuAction("Экспортировать списком", Symbol.SHARE) {
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText(route.card?.title ?: route.screen.label,
                        all.joinToString("\n") { "${it.artistText} — ${it.title}" }))
                toast("Список скопирован")
            })
            if (route.screen == Screen.LIKES) add(MenuAction(if (store.autoDownload()) "Выключить автоскачивание" else "Скачивать новые лайки", Symbol.REPEAT) {
                store.setAutoDownload(enabled = !store.autoDownload())
            })
            if (route.screen == Screen.LIKES) add(MenuAction("Загрузить свои аудиофайлы", Symbol.ARROW_UP) { uploadTo(null) })
            if (all.isNotEmpty()) add(MenuAction(if (selecting) "Закончить выбор" else "Выбрать треки", Symbol.CHECK_CIRCLE) {
                if (selecting) endSelection() else {
                    selecting = true
                    refreshRows()
                    updateSelectionBar()
                }
            })
            add(MenuAction("Сортировка · ${trackOrder.label}", Symbol.FILTER) {
                val hasDates = all.any { item -> item.addedAt != null }
                val choices = TrackOrder.entries.filter { it !in setOf(TrackOrder.RECENT, TrackOrder.OLDEST) || hasDates }
                choose("Сортировка", choices.map { it.label }, choices.indexOf(trackOrder)) { trackOrder = choices[it]; updateTrackFilter() }
            })
            if (route.screen != Screen.DOWNLOADS) add(MenuAction(if (onlyDownloaded) "Показать все треки" else "Только скачанные", Symbol.DOWNLOAD) {
                onlyDownloaded = !onlyDownloaded
                updateTrackFilter()
            })
        }
        actionMenu(anchor, options)
    }

    private fun selectedSource(): List<Track> = visibleTracks(
        details?.tracks ?: trackList?.tracks ?: store.files.downloaded().takeIf { route.screen == Screen.DOWNLOADS }.orEmpty()
    )

    private fun endSelection() {
        selecting = false
        selectedTracks.clear()
        updateSelectionBar()
        refreshRows()
    }

    private fun toggleSelected(track: Track) {
        if (!selectedTracks.add(track.id)) selectedTracks.remove(track.id)
        refreshRows()
        updateSelectionBar()
    }

    private fun updateSelectionBar() {
        val bar = selectionBar ?: return
        bar.visibility = if (selecting) View.VISIBLE else View.GONE
        bar.removeAllViews()
        if (!selecting) return
        val visible = selectedSource()
        val selected = visible.filter { it.id in selectedTracks }.distinctBy { it.id }
        fun button(symbol: Symbol, text: String, enabled: Boolean = true, click: () -> Unit) {
            val item = ui.column().apply {
                gravity = Gravity.CENTER
                background = ui.touch()
                addView(IconView(this@MainActivity, symbol, ui.palette.text).apply { iconSize = 21 },
                    LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)))
                addView(ui.space(4))
                addView(ui.line(text, 10, bold = true))
                setOnClickListener { click() }
                isEnabled = enabled
                alpha = if (enabled) 1f else .4f
                isFocusable = true
                contentDescription = text
            }
            bar.addView(item, LinearLayout.LayoutParams(0, -1, 1f))
        }
        button(Symbol.CHECK, "${selected.size} · Все", visible.isNotEmpty()) {
            if (visible.all { it.id in selectedTracks }) selectedTracks.clear()
            else selectedTracks.addAll(visible.map { it.id })
            refreshRows()
            updateSelectionBar()
        }
        val removingDownloads = route.screen == Screen.DOWNLOADS
        button(if (removingDownloads) Symbol.TRASH else Symbol.DOWNLOAD, if (removingDownloads) "Удалить" else "Скачать", selected.isNotEmpty()) {
            if (removingDownloads) confirm("Удалить выбранные загрузки?", "Удалить") {
                action { selected.forEach { store.files.remove(it) }; endSelection() }
            } else action { store.files.enqueue(selected); endSelection() }
        }
        button(Symbol.PLAYLIST_ADD, "В плейлист", selected.isNotEmpty()) { pickPlaylist(selected, ::endSelection) }
        button(Symbol.QUEUE_NEXT, "Далее", selected.isNotEmpty()) { enqueue(selected, next = true); endSelection() }
        val own = details?.card?.takeIf { it.kind == Kind.PLAYLIST && it.owner == userId }
        if (own != null) button(Symbol.MINUS_CIRCLE, "Убрать", selected.isNotEmpty()) {
            action {
                store.online { editPlaylist(own, selected, remove = true) }
                endSelection()
                if (route.card?.key == own.key) loadDetails(own)
            }
        } else button(Symbol.CLOSE, "Готово", click = ::endSelection)
    }

    private fun listHeader(tracks: List<Track>, context: PlayContext, symbol: Symbol?, loading: Boolean = false) = ui.column().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(18))
        if (symbol != null) {
            addView(ui.symbolCover(symbol, 200, 22), LinearLayout.LayoutParams(ui.dp(200), ui.dp(200)))
            addView(ui.space(16))
            addView(ui.text(context.name, 26, bold = true).apply { gravity = Gravity.CENTER })
        }
        addView(ui.space(6))
        if (loading) addView(ui.loadingShape(), LinearLayout.LayoutParams(ui.dp(76), ui.dp(13)))
        else addView(ui.text(trackCount(tracks.size), 13, ui.palette.muted).apply { gravity = Gravity.CENTER })
        addView(ui.space(14))
        addView(if (loading) ui.loadingTrackControls() else ui.row().apply {
            gravity = Gravity.CENTER
            addTrackControls(this, tracks, context, downloaded = symbol == Symbol.DOWNLOAD) { visibleTracks(tracks) }
        }, ui.full())
    }

    private fun addTrackControls(
        row: LinearLayout,
        tracks: List<Track>,
        context: PlayContext,
        downloaded: Boolean = false,
        playTracks: () -> List<Track> = { tracks }
    ) {
        row.addView(roundIcon(if (downloaded) Symbol.TRASH else Symbol.DOWNLOAD, if (downloaded) "Удалить загрузки" else "Скачать треки") {
            if (downloaded) confirm("Удалить скачанные треки?", "Удалить") { action { tracks.forEach { store.files.remove(it) } } }
            else action { store.files.enqueue(tracks); toast("Загрузки добавлены") }
        }.apply { isEnabled = tracks.isNotEmpty(); alpha = if (isEnabled) 1f else .4f })
        gap(row, 14)
        row.addView(playPill(playTracks, context))
        gap(row, 14)
        row.addView(roundIcon(Symbol.SHUFFLE, "Перемешать") {
            val current = playTracks().ifEmpty { tracks }
            val playable = current.indices.filter { !current[it].unavailable || store.files.hasReplacement(current[it]) || store.files.has(current[it]) }
            playable.randomOrNull()?.let { playTracks(current, it, context, shuffle = true) } ?: toast("Нет доступных треков")
        }.apply { isEnabled = tracks.isNotEmpty(); alpha = if (isEnabled) 1f else .4f })
    }

    private fun View.observePlayback(update: () -> Unit) {
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { playbackBindings.add(update); update() }
            override fun onViewDetachedFromWindow(view: View) { playbackBindings.remove(update) }
        })
        update()
    }

    private fun playPill(tracks: () -> List<Track>, context: PlayContext): View = ui.row().apply {
            fun isActive(): Boolean {
                val now = QueueItems.context(controller?.currentMediaItem)
                return currentTrack() != null && now.kind == context.kind && now.item == context.item &&
                    (context.item != null || now.name == context.name)
            }
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ui.dp(50), 1f)
            background = ui.touch(ui.palette.accent, 25)
            pressFeedback(.95f)
            val bars = PlayingBars(this@MainActivity, Color.BLACK)
            val play = IconView(this@MainActivity, Symbol.PLAY, Color.BLACK).apply { iconSize = 17 }
            addView(FrameLayout(this@MainActivity).apply {
                addView(play, FrameLayout.LayoutParams(-1, -1))
                addView(bars, FrameLayout.LayoutParams(-1, -1))
            }, LinearLayout.LayoutParams(ui.dp(17), ui.dp(17)))
            val label = ui.line("", 17, Color.BLACK, bold = true)
            addView(label, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(8) })
            var previous: Boolean? = null
            observePlayback {
                val active = isActive()
                val playing = active && controller?.isPlaying == true
                val text = if (playing) "Пауза" else if (active) "Продолжить" else "Слушать"
                if (label.text != text) label.text = text
                if (previous != playing) {
                    val shown = if (playing) bars else play
                    val hidden = if (playing) play else bars
                    shown.visibility = View.VISIBLE
                    hidden.visibility = View.INVISIBLE
                    bars.running = playing
                    if (previous != null && android.animation.ValueAnimator.areAnimatorsEnabled()) {
                        shown.animate().cancel()
                        shown.alpha = .45f
                        shown.scaleX = .7f
                        shown.scaleY = .7f
                        shown.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start()
                    }
                    previous = playing
                }
            }
            setOnClickListener {
                if (isActive()) toggle() else {
                    val current = tracks()
                    val start = current.indexOfFirst { !it.unavailable || store.files.hasReplacement(it) || store.files.has(it) }
                    if (start >= 0) playTracks(current, start, context, shuffle = false) else toast("Нет доступных треков")
                }
            }
            isFocusable = true
            isEnabled = tracks().isNotEmpty()
            alpha = if (isEnabled) 1f else .5f
    }

    private fun roundIcon(symbol: Symbol, label: String, color: Int = ui.palette.text, action: () -> Unit) =
        ui.icon(symbol, label, color, action).apply {
            layoutParams = LinearLayout.LayoutParams(ui.dp(50), ui.dp(50))
            iconSize = 19
            background = ui.touch(ui.palette.lifted, 25)
        }

    private fun gap(row: LinearLayout, width: Int) {
        row.addView(View(this), LinearLayout.LayoutParams(ui.dp(width), 1))
    }

    private fun trackRows(tracks: List<Track>, context: PlayContext, playlist: Card? = null, numbered: Boolean = false) =
        tracks.mapIndexed { index, track ->
            Row.Music(track, { playTracks(tracks, index, context) }, { anchor -> trackMenu(track, playlist, anchor = anchor) },
                number = (index + 1).takeIf { numbered },
                reorderIndex = index.takeIf { reordering && playlist != null && tracks.size > 1 })
        }

    private fun setReordering(enabled: Boolean) {
        val page = details ?: return
        if (reordering == enabled) return
        reordering = enabled
        if (enabled) {
            if (selecting) endSelection()
            trackOrder = TrackOrder.ORIGINAL
            onlyDownloaded = false
            selectedTrackChip = null
            if (filterBar != null) hideTrackFilter()
            trackFilter = ""
        } else {
            (list as? PlaylistTrackList)?.clearDrag()
        }
        detailMenu?.visibility = if (enabled) View.GONE else View.VISIBLE
        reorderDone?.visibility = if (enabled) View.VISIBLE else View.GONE
        rows(detailRows(page), keepScroll = true)
    }

    private fun reorderActions(anchor: View, index: Int) {
        val last = details?.tracks?.lastIndex ?: return
        val options = buildList {
            if (index > 0) add(MenuAction("Переместить выше", Symbol.ARROW_UP) { movePlaylistTrack(index, index - 1) })
            if (index < last) add(MenuAction("Переместить ниже", Symbol.ARROW_DOWN) { movePlaylistTrack(index, index + 1) })
        }
        if (options.isNotEmpty()) actionMenu(anchor, options)
    }

    private fun movePlaylistTrack(from: Int, to: Int) {
        val page = details ?: return
        if (!reordering || from == to || from !in page.tracks.indices || to !in page.tracks.indices) return
        if (reorderJob?.isActive == true) { toast("Сохраняется порядок треков"); return }
        if (store.offline) { toast("Включён офлайн-режим"); return }
        val owner = userId ?: return
        if (page.card.owner != owner || page.card.kind != Kind.PLAYLIST) return
        val changed = page.copy(tracks = page.tracks.toMutableList().apply { add(to, removeAt(from)) })
        pageJob?.cancel()
        details = changed
        rows(detailRows(changed), keepScroll = true)
        reorderJob = action {
            try {
                store.movePlaylistTrack(page, changed, from, to)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (details === changed && userId == owner) {
                    val actual = optional { store.details(page.card) } ?: page
                    if (details === changed && userId == owner) {
                        details = actual
                        rows(detailRows(actual), keepScroll = true)
                    }
                }
                throw error
            }
        }
    }

    private fun entry(card: Card) = Row.Entry(card) { openCard(card) }
    private fun note(label: String) = Row.Layout { ui.note(label) }
    private fun heading(label: String) = Row.Layout { ui.heading(label) }

    private fun section(label: String, button: String? = null, click: () -> Unit = {}) = Row.Layout {
        ui.row().apply {
            setPadding(ui.dp(16), ui.dp(17), ui.dp(16), ui.dp(6))
            addView(ui.line(label, 22, bold = true), ui.weight())
            if (button != null) addView(ui.text(button, 14, ui.palette.accentText, bold = true).apply {
                setPadding(ui.dp(10), ui.dp(10), 0, ui.dp(10))
                background = ui.touch()
                setOnClickListener { click() }
                isFocusable = true
            })
        }
    }

    private fun plural(count: Int, one: String, few: String, many: String): String {
        val noun = if (count % 100 in 11..14) many else when (count % 10) {
            1 -> one; in 2..4 -> few; else -> many
        }
        return "$count $noun"
    }

    private fun durationSummary(durationMs: Long): String {
        val minutes = (durationMs / 60_000L).coerceAtLeast(0L)
        return if (minutes >= 60) "${minutes / 60} ч ${minutes % 60} мин" else "$minutes мин"
    }

    private fun trackCount(count: Int, episodes: Boolean = false): String =
        if (episodes) plural(count, "выпуск", "выпуска", "выпусков") else plural(count, "трек", "трека", "треков")

    private fun shortcut(symbol: Symbol, label: String, sub: String? = null, subtitleTag: String? = null, action: () -> Unit) = Row.Layout {
        ui.row().apply {
            background = ui.touch()
            minimumHeight = ui.dp(60)
            setPadding(ui.dp(16), ui.dp(5), ui.dp(16), ui.dp(5))
            val glyph = IconView(this@MainActivity, symbol, if (symbol == Symbol.HEART_FILLED) ui.palette.accent else ui.palette.text)
            glyph.background = ui.shape(ui.palette.lifted)
            glyph.iconSize = 22
            addView(glyph, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
            addView(ui.column().apply {
                setPadding(ui.dp(12), 0, 0, 0)
                addView(ui.line(label, 15, bold = true))
                if (sub != null) {
                    addView(ui.space(4))
                    addView(ui.line(sub, 12, ui.palette.muted).apply { tag = subtitleTag })
                }
            }, ui.weight())
            addView(IconView(this@MainActivity, Symbol.BACK, ui.palette.muted).apply { rotation = 180f; iconSize = 17 },
                LinearLayout.LayoutParams(ui.dp(22), ui.dp(34)))
            setOnClickListener { action() }
            isFocusable = true
        }
    }

    private fun waveDot(
        card: Card,
        size: Int = 24,
        lazy: Boolean = false,
        onCover: ((CoverView) -> Unit)? = null,
    ): FrameLayout {
        val color = parseHexColor(card.color) ?: ui.palette.lifted
        return FrameLayout(this).apply {
            background = ui.shape(color, size)
            if (card.cover != null) {
                val art = ui.art(if (lazy) null else card.cover, 100, circle = true, backdrop = false)
                onCover?.invoke(art)
                addView(art, FrameLayout.LayoutParams(-1, -1))
            } else {
                clipToOutline = true
                val glyph = (size * .6f).toInt()
                addView(IconView(this@MainActivity, Symbol.WAVE, Color.WHITE).apply {
                    iconSize = (size * .48f).toInt()
                }, FrameLayout.LayoutParams(ui.dp(glyph), ui.dp(glyph), Gravity.CENTER))
            }
        }
    }

    private fun stationChip(
        station: Card,
        lazy: Boolean = false,
        onCover: ((CoverView) -> Unit)? = null,
    ): View {
        val bars = PlayingBars(this, Color.BLACK)
        val dot = waveDot(station, 24, lazy = lazy, onCover = onCover)
        val label = ui.line(station.title, 15, ui.palette.text, bold = true)
        val chip = ui.row().apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-2, ui.dp(40))
            setPadding(ui.dp(10), 0, ui.dp(15), 0)
            pressFeedback(.95f)
            isFocusable = true
            addView(FrameLayout(this@MainActivity).apply {
                addView(dot, FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER))
                addView(bars, FrameLayout.LayoutParams(ui.dp(13), ui.dp(13), Gravity.CENTER))
            }, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { marginEnd = ui.dp(7) })
            addView(label)
            setOnClickListener {
                val now = QueueItems.context(controller?.currentMediaItem)
                if (now.kind == "radio" && now.item == station.id) toggle() else openCard(station)
            }
        }
        var lastActive: Boolean? = null
        chip.observePlayback {
            val now = QueueItems.context(controller?.currentMediaItem)
            val active = currentTrack() != null && now.kind == "radio" && now.item == station.id
            if (lastActive != active) {
                lastActive = active
                chip.background = ui.touch(if (active) ui.palette.accent else ui.palette.lifted, 20)
                label.setTextColor(if (active) Color.BLACK else ui.palette.text)
                dot.visibility = if (active) View.INVISIBLE else View.VISIBLE
                bars.visibility = if (active) View.VISIBLE else View.INVISIBLE
            }
            bars.running = active && controller?.isPlaying == true
        }
        return chip
    }

    private fun waveGridRows(cards: List<Card>) = cards.chunked(2).map { pair ->
        Row.Layout {
            ui.row().apply {
                setPadding(ui.dp(16), ui.dp(5), ui.dp(16), ui.dp(5))
                pair.forEachIndexed { index, card ->
                    val bars = PlayingBars(this@MainActivity, Color.BLACK)
                    val dot = if (card.cover != null || card.color != null) waveDot(card, 26) else null
                    val label = ui.line(card.title, 15, ui.palette.text, bold = true)
                    val lead = FrameLayout(this@MainActivity).apply {
                        dot?.let { addView(it, FrameLayout.LayoutParams(ui.dp(26), ui.dp(26), Gravity.CENTER)) }
                        addView(bars, FrameLayout.LayoutParams(ui.dp(14), ui.dp(14), Gravity.CENTER))
                    }
                    val tile = ui.row().apply {
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(ui.dp(14), 0, ui.dp(14), 0)
                        pressFeedback(.95f)
                        isFocusable = true
                        addView(lead, LinearLayout.LayoutParams(ui.dp(if (dot != null) 26 else 18), ui.dp(26)).apply {
                            marginEnd = ui.dp(8)
                        })
                        addView(label, ui.weight())
                        setOnClickListener {
                            val now = QueueItems.context(controller?.currentMediaItem)
                            if (now.kind == "radio" && now.item == card.id) toggle() else openCard(card)
                        }
                    }
                    var lastActive: Boolean? = null
                    tile.observePlayback {
                        val now = QueueItems.context(controller?.currentMediaItem)
                        val active = currentTrack() != null && now.kind == "radio" && now.item == card.id
                        if (lastActive != active) {
                            lastActive = active
                            tile.background = ui.touch(if (active) ui.palette.accent else ui.palette.panel, 14)
                            label.setTextColor(if (active) Color.BLACK else ui.palette.text)
                            dot?.visibility = if (active) View.INVISIBLE else View.VISIBLE
                            bars.visibility = if (active) View.VISIBLE else View.INVISIBLE
                            lead.visibility = if (active || dot != null) View.VISIBLE else View.GONE
                        }
                        bars.running = active && controller?.isPlaying == true
                    }
                    addView(tile, LinearLayout.LayoutParams(0, ui.dp(48), 1f).apply { if (index > 0) marginStart = ui.dp(10) })
                }
                if (pair.size == 1) addView(View(this@MainActivity), LinearLayout.LayoutParams(0, ui.dp(48), 1f).apply { marginStart = ui.dp(10) })
            }
        }
    }

    private fun shelfRow(cards: List<Card>) = Row.Layout(cache = true) {
        val shelf = cards.take(20)
        val covers = shelf.map { card -> ui.art(null, 300, card.kind == Kind.ARTIST, corner = 14) }
        val tiles = shelf.mapIndexed { index, card ->
            val title = ui.line(card.title, 15, bold = true)
            val bars = PlayingBars(this@MainActivity, Color.BLACK)
            val badge = FrameLayout(this@MainActivity).apply {
                background = ui.shape(ui.palette.accent, 17)
                elevation = ui.dp(4).toFloat()
                visibility = View.GONE
                addView(bars, FrameLayout.LayoutParams(ui.dp(16), ui.dp(16), Gravity.CENTER))
            }
            val tile = ui.column().apply {
                background = ui.touch()
                if (card.kind == Kind.ARTIST) gravity = Gravity.CENTER_HORIZONTAL
                addView(FrameLayout(this@MainActivity).apply {
                    addView(covers[index], FrameLayout.LayoutParams(-1, -1))
                    addView(badge, FrameLayout.LayoutParams(ui.dp(34), ui.dp(34),
                        if (card.kind == Kind.ARTIST) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                        else Gravity.BOTTOM or Gravity.END).apply {
                        val margin = ui.dp(8)
                        setMargins(margin, margin, margin, margin)
                    })
                }, LinearLayout.LayoutParams(ui.dp(148), ui.dp(148)))
                addView(ui.space(8))
                addView(title.apply {
                    if (card.kind == Kind.ARTIST) gravity = Gravity.CENTER_HORIZONTAL
                }, ui.full())
                if (card.subtitle.isNotBlank() && (card.kind != Kind.ARTIST || card.subtitle != "Исполнитель")) {
                    addView(ui.space(4))
                    addView(ui.line(card.subtitle, 13, ui.palette.muted).apply {
                        if (card.kind == Kind.ARTIST) gravity = Gravity.CENTER_HORIZONTAL
                    }, ui.full())
                }
                layoutParams = LinearLayout.LayoutParams(ui.dp(148), -2)
                setOnClickListener { openCard(card) }
                setOnLongClickListener { cardMenu(card, it); true }
                isFocusable = true
            }
            tile.observePlayback {
                val now = QueueItems.context(controller?.currentMediaItem)
                val itemKind = if (card.kind == Kind.WAVE) "radio" else card.kind.name.lowercase(Locale.ROOT)
                val itemId = if (card.kind == Kind.PLAYLIST && !card.owner.isNullOrBlank()) "${card.owner}:${card.id}" else card.id
                val active = currentTrack() != null && now.kind == itemKind &&
                    (now.item == itemId || (card.kind == Kind.PLAYLIST && now.item == card.id))
                badge.visibility = if (active) View.VISIBLE else View.GONE
                bars.running = active && controller?.isPlaying == true
                title.setTextColor(if (active) ui.palette.accentText else ui.palette.text)
            }
            tile
        }
        horizontal(tiles).also { scroll ->
            lazyHorizontalCovers(scroll, tiles, covers, shelf.map { it.cover }, 300)
        }
    }

    private fun lazyHorizontalCovers(
        scroll: HorizontalScrollView,
        tiles: List<View>,
        covers: List<CoverView?>,
        uris: List<String?>,
        size: Int,
        prefetch: Int = 4,
        onScrollExtra: (() -> Unit)? = null,
    ) {
        val requested = BooleanArray(tiles.size)
        fun update() {
            if (scroll.width == 0 || !scroll.isAttachedToWindow) return
            val start = scroll.scrollX
            val end = start + scroll.width
            val firstVisible = tiles.indexOfFirst { it.right > start && it.left < end }
            if (firstVisible >= 0) {
                val lastVisible = tiles.indexOfLast { it.right > start && it.left < end }
                val lastPrefetched = minOf(lastVisible + prefetch, tiles.lastIndex)
                for (index in firstVisible..lastPrefetched) {
                    if (requested[index]) continue
                    requested[index] = true
                    covers[index]?.bind(uris[index], size)
                }
            }
            onScrollExtra?.invoke()
        }
        scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> update() }
        scroll.post { update() }
    }

    private fun gridRows(cards: List<Card>) = cards.chunked(2).map { pair ->
        Row.Layout {
            ui.row().apply {
                gravity = Gravity.TOP
                setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(10))
                val width = ((resources.configuration.screenWidthDp - 44) / 2).coerceIn(96, 320)
                pair.forEachIndexed { index, card ->
                    val artist = card.kind == Kind.ARTIST
                    addView(ui.column().apply {
                        background = ui.touch()
                        if (artist) gravity = Gravity.CENTER_HORIZONTAL
                        addView(ui.art(card.cover, 500, artist, 14), LinearLayout.LayoutParams(ui.dp(width), ui.dp(width)))
                        addView(ui.space(8))
                        addView(ui.line(card.title, 15, bold = true).apply {
                            if (artist) gravity = Gravity.CENTER_HORIZONTAL
                        }, ui.full())
                        if (card.subtitle.isNotBlank() && (!artist || card.subtitle != "Исполнитель")) {
                            addView(ui.space(4))
                            addView(ui.line(card.subtitle, 12, ui.palette.muted).apply {
                                if (artist) gravity = Gravity.CENTER_HORIZONTAL
                            }, ui.full())
                        }
                        setOnClickListener { openCard(card) }
                        setOnLongClickListener { cardMenu(card, it); true }
                        isFocusable = true
                    }, LinearLayout.LayoutParams(ui.dp(width), -2).apply { if (index > 0) marginStart = ui.dp(12) })
                }
            }
        }
    }

    private fun horizontal(items: List<View>) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        addView(ui.row().apply {
            gravity = Gravity.TOP
            setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8))
            items.forEachIndexed { index, child ->
                if (index > 0) addView(View(this@MainActivity), LinearLayout.LayoutParams(ui.dp(10), 1))
                addView(child)
            }
        }, ViewGroup.LayoutParams(-2, -2))
    }

    private fun listPosition(): TrackListPosition? {
        settingsScroll?.let { return TrackListPosition(0, it.scrollY, null) }
        val list = list ?: return null
        val child = list.getChildAt(0) ?: return null
        val position = list.firstVisiblePosition
        val currentAdapter = adapter ?: return null
        val id = if (position in 0 until currentAdapter.count) (currentAdapter.getItem(position) as? Row.Music)?.track?.id else null
        return TrackListPosition(position, child.top, id)
    }

    private fun saveTrackPage() {
        val displayed = displayedRoute ?: return
        if (displayed.screen == Screen.SEARCH) return
        displayed.filter = trackFilter
        displayed.order = trackOrder
        displayed.downloadedOnly = onlyDownloaded
        displayed.filterVisible = filterBar != null
        displayed.chipId = selectedTrackChip
        if (pageHasRows) displayed.position = listPosition()
    }

    private fun rows(newRows: List<Row>, keepScroll: Boolean = false, transient: Boolean = false) {
        val list = list ?: return
        val currentAdapter = adapter ?: return
        val position = if (transient) null else restorePosition ?: if (keepScroll) listPosition() else null
        currentAdapter.replace(newRows)
        if (!transient && newRows.isNotEmpty()) {
            restorePosition = null
            pageHasRows = true
        }
        if (position != null && newRows.isNotEmpty()) {
            val anchor = position.index.coerceIn(newRows.indices)
            val id = position.trackId
            val target = if (id == null || (newRows[anchor] as? Row.Music)?.track?.id == id) anchor
            else newRows.indices.asSequence().filter { (newRows[it] as? Row.Music)?.track?.id == id }
                .minByOrNull { kotlin.math.abs(it - anchor) } ?: anchor
            list.setSelectionFromTop(target, position.offset)
        } else list.setSelection(0)
        if (selecting) updateSelectionBar()
    }

    private fun loadRows(
        loading: () -> List<Row> = { listOf(Row.Layout { ui.loadingRows() }) },
        block: suspend (showCached: ((List<Row>) -> Unit)?) -> List<Row>
    ) {
        pageJob?.cancel()
        fun display(items: List<Row>) {
            rows(items, keepScroll = listHasContent)
            listHasContent = true
        }
        val showCached: ((List<Row>) -> Unit)? = if (listHasContent) null else ::display
        pageJob = action(onError = { error ->
            refreshLayout?.setRefreshing(false)
            if (listHasContent) toast(error)
            else rows(listOf(Row.Layout {
                ui.pagePlaceholder(Symbol.WIFI_OFF, "Не удалось загрузить", error) { load() }
            }), transient = true)
        }) {
            coroutineScope {
                val skeleton = if (listHasContent) null else launch {
                    delay(200)
                    if (!listHasContent) rows(loading(), transient = true)
                }
                try {
                    display(block(showCached))
                } finally {
                    skeleton?.cancel()
                    refreshLayout?.setRefreshing(false)
                }
            }
        }
    }

    private fun refreshCurrentPage() {
        val current = route
        when {
            current.screen == Screen.HOME -> homePage(force = true)
            current.screen == Screen.LIBRARY -> {
                pageJob?.cancel()
                pageJob = action(onError = {
                    refreshLayout?.setRefreshing(false)
                    toast(it)
                }) {
                    try {
                        store.refreshAccount()
                        library()
                    } finally {
                        refreshLayout?.setRefreshing(false)
                    }
                }
            }
            current.screen in REFRESHABLE_SCREENS -> load(current)
            else -> refreshLayout?.setRefreshing(false)
        }
    }

    private fun refreshRows() {
        adapter?.notifyDataSetChanged()
    }

    private fun refreshCollection() {
        if (userId == null) return
        if (route.screen == Screen.HOME) return
        if (route.screen == Screen.LIBRARY) library() else refreshRows()
    }

    internal fun action(onError: (String) -> Unit = ::toast, block: suspend () -> Unit): Job = lifecycleScope.launch {
        val original = store.profile.value?.uid
        try { block() } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is MusicError && error.status == 401 && original != null && original == store.profile.value?.uid) store.logout()
            val message = if (error is java.io.IOException) error.message else null
            if (errorLog.size >= 50) errorLog.removeLast()
            errorLog.addFirst("${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())} · ${message ?: "Не удалось выполнить действие"}")
            onError(message ?: "Не удалось выполнить действие")
        }
    }

    private fun toastLayoutParams(inPlayer: Boolean) = FrameLayout.LayoutParams(
        -2, -2, (if (inPlayer) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
    ).apply {
        leftMargin = ui.dp(24)
        rightMargin = ui.dp(24)
        if (inPlayer) topMargin = systemInsets.top + ui.dp(58)
        else {
            val barOffset = if (::mini.isInitialized && mini.visibility == View.VISIBLE) 132 else 68
            bottomMargin = maxOf(systemInsets.bottom, imeBottom) + ui.dp(barOffset)
        }
    }

    private fun reattachToast() {
        val pill = toastPill ?: return
        if (toastHide == null) return
        (pill.parent as? ViewGroup)?.removeView(pill)
        val inPlayer = playerPanel != null
        val darkPill = inPlayer || !ui.palette.light
        pill.setTextColor(if (darkPill) Color.WHITE else ui.palette.text)
        (pill.background as? GradientDrawable)?.apply {
            setColor(if (darkPill) 0xeb262626.toInt() else 0xf2ffffff.toInt())
            setStroke(ui.dp(1), if (darkPill) 0x24ffffff else 0x1c000000)
        }
        root.addView(pill, toastLayoutParams(inPlayer))
    }

    internal fun toast(text: String) {
        if (isFinishing || !::root.isInitialized) return
        toastHide?.let { root.removeCallbacks(it) }
        val old = toastPill
        old?.animate()?.cancel()
        val inPlayer = playerPanel != null
        val darkPill = inPlayer || !ui.palette.light
        val pill = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(if (darkPill) Color.WHITE else ui.palette.text)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            gravity = Gravity.CENTER
            maxLines = 3
            setPadding(ui.dp(18), ui.dp(11), ui.dp(18), ui.dp(11))
            background = GradientDrawable().apply {
                setColor(if (darkPill) 0xeb262626.toInt() else 0xf2ffffff.toInt())
                cornerRadius = ui.dp(24).toFloat()
                setStroke(ui.dp(1), if (darkPill) 0x24ffffff else 0x1c000000)
            }
            elevation = ui.dp(10).toFloat()
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        old?.let { (it.parent as? ViewGroup)?.removeView(it) }
        toastPill = pill
        root.addView(pill, toastLayoutParams(inPlayer))
        if (ValueAnimator.areAnimatorsEnabled()) {
            pill.alpha = 0f
            pill.translationY = ui.dp(if (inPlayer) -14 else 14).toFloat()
            pill.animate().alpha(1f).translationY(0f).setDuration(220)
                .setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
        val hide = Runnable {
            if (toastPill !== pill) return@Runnable
            if (ValueAnimator.areAnimatorsEnabled() && pill.isAttachedToWindow) {
                pill.animate().alpha(0f).translationY(ui.dp(if (playerPanel != null) -10 else 10).toFloat())
                    .setDuration(200).withEndAction {
                        (pill.parent as? ViewGroup)?.removeView(pill)
                        if (toastPill === pill) toastPill = null
                    }.start()
            } else {
                (pill.parent as? ViewGroup)?.removeView(pill)
                toastPill = null
            }
        }
        toastHide = hide
        root.postDelayed(hide, 2200)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
        searchField?.clearFocus()
        filterField?.clearFocus()
        lyricsMirrorField?.clearFocus()
    }

    private fun openCard(card: Card) {
        when (card.kind) {
            Kind.WAVE -> startWave(card.seeds, card.title)
            Kind.CLIP -> card.link?.let(::browser) ?: showVideo(card)
            Kind.CONCERT -> card.url?.let(::browser)
            else -> if (card.id.isNotBlank()) push(Screen.DETAILS, card)
        }
    }

    internal fun openTrackAlbum(track: Track) { track.album?.let(::openCard) }
    internal fun openTrackArtist(track: Track) {
        val artists = track.artists.filter { it.id.isNotBlank() }
        if (artists.size == 1) openCard(artists.single())
        else if (artists.size > 1) choose("Исполнители", artists.map { it.title }) { openCard(artists[it]) }
    }

    internal fun input(title: String, value: String, hint: String, confirm: (String) -> Unit) {
        tuneInput(title, value, hint, dark = playerPanel != null, confirm = confirm)
    }

    internal fun choose(title: String, labels: List<String>, selected: Int = -1, action: (Int) -> Unit) =
        tuneChoices(title, labels, selected, dark = playerPanel != null, action = action)

    private fun confirm(title: String, label: String, message: String = "", action: () -> Unit) {
        tuneConfirm(title, label, message, destructive = label in setOf("Удалить", "Очистить", "Выйти", "Сбросить"), action = action)
    }

    private fun createPlaylist() = input("Новый плейлист", "", "Название") { title ->
        action {
            val uid = store.requireUser().uid
            val card = store.online { createPlaylist(uid, title) }
            store.changeLocalPlaylist(card)
            openCard(card)
        }
    }

    private fun playlistActions() {
        choose("Плейлисты", listOf("Создать плейлист", "Импортировать ссылки", "Импортировать TXT или M3U")) {
            when (it) {
                0 -> createPlaylist()
                1 -> tuneInput("Импорт плейлиста", "", "Вставьте ссылки на треки Яндекс Музыки",
                    multiline = true, positive = "Продолжить", confirm = ::importPlaylist)
                else -> importPlaylistDocument.launch(arrayOf("text/*", "audio/x-mpegurl", "application/vnd.apple.mpegurl", "application/octet-stream"))
            }
        }
    }

    private fun importPlaylist(text: String) {
        val ids = playlistTrackIds(text)
        if (ids.isEmpty()) {
            toast("Ссылки на треки Яндекс Музыки не найдены")
            return
        }
        input("Импорт плейлиста", "Импортированные треки", "Название") { title ->
            action {
                val user = store.requireUser()
                val tracks = store.online { tracks(ids) }.filterNot { it.unavailable }
                if (tracks.isEmpty()) throw MusicError("Треки из списка недоступны")
                val playlist = store.online { createPlaylist(user.uid, title) }
                store.changeLocalPlaylist(playlist)
                var inserted = 0
                try {
                    for (portion in tracks.chunked(100).asReversed()) {
                        store.online { editPlaylist(playlist, portion) }
                        inserted += portion.size
                    }
                } catch (error: java.io.IOException) {
                    toast("Добавлено $inserted из ${tracks.size}. ${error.message.orEmpty()}")
                    openCard(playlist)
                    return@action
                }
                toast("Импортировано: ${trackCount(inserted)}")
                openCard(playlist)
            }
        }
    }

    private fun loadKids() = loadRows(loading = { listOf(Row.Layout { ui.loadingGrid(circle = false) }) }) { showCached ->
        fun kidsRows(shelves: List<Shelf>) = shelfRows(shelves).ifEmpty {
            listOf(Row.Layout { ui.pagePlaceholder(Symbol.NOTE, "Подборок пока нет") })
        }
        kidsRows(store.kids(showCached?.let { display -> { shelves -> display(kidsRows(shelves)) } }))
    }

    private fun loadConcerts() = loadRows { showCached ->
        val requestedCity = city
        fun concertRows(cards: List<Card>) = cards.map(::concertRow).ifEmpty {
            listOf(Row.Layout { ui.pagePlaceholder(Symbol.CONCERT, "Нет предстоящих концертов") })
        }
        concertRows(store.concerts(requestedCity, showCached?.let { display -> { cards -> display(concertRows(cards)) } }))
    }

    private fun concertRow(card: Card) = Row.Layout {
        ui.row().apply {
            setPadding(ui.dp(16), ui.dp(7), ui.dp(16), ui.dp(7))
            addView(ui.art(card.cover, 200, corner = 8), LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)))
            addView(ui.column().apply {
                setPaddingRelative(ui.dp(12), 0, ui.dp(8), 0)
                addView(ui.text(card.title, 16, bold = true))
                listOfNotNull(card.subtitle.takeIf(String::isNotBlank), card.eventDate?.let(::formatConcertDate)).forEach { detail ->
                    addView(ui.space(4))
                    addView(ui.text(detail, 14, ui.palette.muted))
                }
            }, ui.weight())
            card.link?.let { link ->
                addView(ui.button("Билеты") { browser(link) }.apply { setTextColor(ui.palette.accentText) })
            }
        }
    }

    private fun concertCityLabel() = cityName.ifBlank { "Рядом" }

    private fun chooseCity() {
        val page = route
        action {
            val cities = store.concertCities()
            if (route !== page) return@action
            choose("Город", listOf("Рядом") + cities.map { it.second }, cities.indexOfFirst { it.first == city } + 1) { index ->
                val chosen = cities.getOrNull(index - 1)
                if (city == chosen?.first) return@choose
                city = chosen?.first
                cityName = chosen?.second.orEmpty()
                store.preferences.edit().putString("city", city).putString("cityName", cityName).apply()
                if (route === page) {
                    findViewById<TextView>(CONCERT_CITY_BUTTON)?.text = concertCityLabel()
                    restorePosition = null
                    listHasContent = false
                    pageHasRows = false
                    rows(emptyList(), transient = true)
                    loadConcerts()
                }
            }
        }
    }

    private fun loadRooms(): Unit = loadRows {
        val rooms = store.online { waveRooms() }
        buildList {
            add(shortcut(Symbol.ADD, "Создать приглашение") {
                action {
                    val room = store.online { createWaveRoom() }
                    if (route.screen == Screen.WAVE_ROOMS) loadRooms()
                    showRoom(room)
                }
            })
            if (rooms.isEmpty()) add(note("Пригласите друга, чтобы слушать волну по вашим общим вкусам."))
            else rooms.forEach { room ->
                val detail = when {
                    room.canListen -> room.people.joinToString(" · ")
                    room.status == "DRAFT" -> "Ожидает участника"
                    else -> "Волна недоступна"
                }
                add(shortcut(Symbol.WAVE, room.name, detail) { showRoom(room) })
            }
        }
    }

    private fun showRoom(room: WaveRoom, invite: Boolean = false) {
        val actions = buildList<Pair<String, () -> Unit>> {
            if (room.canListen) add("Слушать" to { startWave(room.seeds, room.name) })
            if (invite && room.status != "DISABLED") add("Принять приглашение" to {
                action {
                    val joined = store.online { enterWaveRoom(room.id) }
                    if (route.screen == Screen.WAVE_ROOMS) loadRooms()
                    showRoom(joined)
                }
            })
            if (room.status != "DISABLED") add("Поделиться ссылкой" to { share(room.link) })
            if (!invite) add("Покинуть волну" to {
                confirm("Покинуть общую волну?", "Покинуть") {
                    action {
                        store.online { exitWaveRoom(room.id) }
                        if (route.screen == Screen.WAVE_ROOMS) loadRooms()
                    }
                }
            })
        }
        if (actions.isEmpty()) toast("Приглашение больше не действует")
        else choose(room.name, actions.map { it.first }) { actions[it].second() }
    }

    private fun pageMenu(page: MusicPage, anchor: View) {
        val card = page.card
        val options = mutableListOf<MenuAction>()
        fun item(title: String, icon: Symbol, destructive: Boolean = false, run: () -> Unit) {
            options += MenuAction(title, icon, destructive, run)
        }
        if (card.kind != Kind.ARTIST) item("Сортировка и фильтры", Symbol.SORT) { trackListMenu(page, anchor) }
        if (card.kind in setOf(Kind.ARTIST, Kind.ALBUM)) item("Подменить ${if (card.kind == Kind.ARTIST) "исполнителя" else "альбом"}", Symbol.EDIT) {
            openReplacement(ReplacementTarget(card.kind.name.lowercase(Locale.ROOT), card.id, card.title))
        }
        card.url?.let { url -> item("Поделиться", Symbol.SHARE) { share(url) } }
        if (card.kind == Kind.ALBUM || card.kind == Kind.PLAYLIST && card.owner != userId) {
            val liked = card.key in store.savedCards.value
            item(if (liked) "Убрать из коллекции" else "Добавить в коллекцию", if (liked) Symbol.HEART_SLASH else Symbol.HEART) {
                action { store.save(card) }
            }
        }
        if (page.tracks.isNotEmpty()) item("Скачать треки", Symbol.DOWNLOAD) {
            action { store.files.enqueue(page.tracks); toast("Загрузки добавлены") }
        }
        if (card.kind == Kind.PLAYLIST && page.tracks.isNotEmpty()) {
            item("Экспортировать ссылки", Symbol.LINK) {
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText(card.title, page.tracks.joinToString("\n") { it.url }))
                toast("Ссылки скопированы")
            }
            item("Экспортировать плейлист M3U", Symbol.FILE) {
                exportPlaylistText = playlistM3u(page.tracks)
                exportPlaylistDocument.launch(card.title.replace(Regex("""[\\/:*?"<>|]"""), "_").take(140) + ".m3u8")
            }
        }
        if (card.kind == Kind.PLAYLIST && (card.owner == userId || card.key in store.savedCards.value)) {
            item(if (store.autoDownload(card)) "Выключить автоскачивание" else "Скачивать новые треки", Symbol.REPEAT) {
                store.setAutoDownload(card, enabled = !store.autoDownload(card))
            }
        }
        if (card.kind == Kind.PLAYLIST && card.owner == userId) {
            item("Изменить порядок", Symbol.REORDER) { setReordering(true) }
            item("Загрузить свои аудиофайлы", Symbol.ARROW_UP) { uploadTo(card) }
            item("Изменить описание", Symbol.FILE) {
                input("Описание плейлиста", page.description.orEmpty(), "Описание") { value ->
                    action {
                        store.online { editPlaylistDetails(card, value) }
                        if (route.card?.key == card.key) loadDetails(card)
                    }
                }
            }
            item("Сменить обложку", Symbol.PHOTO) {
                mediaPickerPlaylist = card
                playlistCoverDocument.launch("image/*")
            }
            item(if (page.publicPlaylist == true) "Сделать приватным" else "Открыть доступ по ссылке", Symbol.LINK) {
                action {
                    store.online { setPlaylistPublic(card, page.publicPlaylist != true) }
                    if (route.card?.key == card.key) loadDetails(card)
                }
            }
            item("Переименовать", Symbol.EDIT) {
                input("Название плейлиста", card.title, "Название") { name ->
                    action {
                        store.online { renamePlaylist(card, name) }
                        val changed = card.copy(title = name)
                        store.changeLocalPlaylist(changed)
                        if (route.card?.key == card.key) {
                            route.card = changed
                            loadDetails(changed)
                        }
                    }
                }
            }
            item("Удалить плейлист", Symbol.TRASH, destructive = true) {
                confirm("Удалить «${card.title}»?", "Удалить") {
                    action {
                        store.online { deletePlaylist(card) }
                        store.changeLocalPlaylist(card, remove = true)
                        if (route.card?.key == card.key) {
                            stack.removeLast()
                            render()
                        }
                    }
                }
            }
        }
        actionMenu(anchor, options)
    }

    private fun uploadTo(card: Card?) {
        mediaPickerPlaylist = card
        uploadTrackDocuments.launch(arrayOf("audio/*"))
    }

    private fun cardMenu(card: Card, anchor: View) {
        val items = buildList {
            add(MenuAction("Открыть", Symbol.LIBRARY) { openCard(card) })
            card.url?.let { url -> add(MenuAction("Поделиться", Symbol.SHARE) { share(url) }) }
            if (card.kind in setOf(Kind.ALBUM, Kind.ARTIST, Kind.PLAYLIST) && card.owner != userId) {
                val saved = card.key in store.savedCards.value
                add(MenuAction(if (saved) "Убрать из коллекции" else "В коллекцию", if (saved) Symbol.HEART_SLASH else Symbol.HEART) {
                    action { store.save(card) }
                })
            }
        }
        actionMenu(anchor, items)
    }

    internal fun trackMenu(track: Track, playlist: Card? = null, anchor: View? = null) {
        val options = mutableListOf<MenuAction>()
        fun item(title: String, icon: Symbol, destructive: Boolean = false, run: () -> Unit) {
            options += MenuAction(title, icon, destructive, run)
        }
        val liked = track.id in store.likes.value
        val disliked = track.id in store.dislikes.value
        if (anchor == null) item("Устройства Яндекс Музыки", Symbol.SPEAKER, run = ::openDevices)
        item(if (liked) "Убрать из «Мне нравится»" else "Нравится", if (liked) Symbol.HEART_SLASH else Symbol.HEART) { rate(track) }
        item(if (disliked) "Снять дизлайк" else "Не нравится", Symbol.THUMB_DOWN) { rate(track, true) }
        if (currentTrack()?.id == track.id && (userId?.let { store.allowsAlternate(it, track.id) && store.versions.hasAudio(track.id) } == true ||
                store.files.hasReplacement(track))) {
            item("Версия трека", Symbol.WAVE) { chooseVersion(track) }
        }
        item("Добавить в плейлист", Symbol.PLAYLIST_ADD) { pickPlaylist(listOf(track)) }
        if (track.uploaded) item("Редактировать трек", Symbol.EDIT) { editUploadedTrack(track) }
        item("Подменить трек", Symbol.EDIT) { openReplacement(ReplacementTarget("track", track.id, track.title, track)) }
        val downloadStatus = store.files.status.value[track.id]
        if (store.files.has(track)) item("Удалить с устройства", Symbol.TRASH, true) { action { store.files.remove(track) } }
        else if (!track.unavailable && (downloadStatus == null || downloadStatus.error != null)) {
            item(if (downloadStatus == null) "Скачать" else "Повторить загрузку", Symbol.DOWNLOAD) {
                action { store.files.enqueue(listOf(track)); toast("Трек добавлен в загрузки") }
            }
        }
        if (anchor != null && (controller?.mediaItemCount ?: 0) > 0) {
            item("Играть следующим", Symbol.QUEUE_NEXT) { enqueue(track, next = true) }
            item("В конец очереди", Symbol.QUEUE_LAST) { enqueue(track, next = false) }
        }
        if (track.album != null) item("Перейти к альбому", Symbol.LIBRARY) { openTrackAlbum(track) }
        track.artists.filter { it.id.isNotBlank() }.take(3).forEach { artist ->
            item(artist.title, Symbol.MIC_MUSIC) { openCard(artist) }
        }
        if (QueueItems.context(controller?.currentMediaItem).kind == "radio") track.artists.firstOrNull { it.id.isNotBlank() }?.let { artist ->
            item("Не рекомендовать исполнителя", Symbol.PERSON_MINUS) {
                action {
                    val uid = store.requireUser().uid
                    store.online { setArtistHidden(uid, artist.id, true) }
                    toast("Исполнитель скрыт из рекомендаций")
                    if (currentTrack()?.id == track.id) next()
                }
            }
        }
        item("Волна по треку", Symbol.WAVE) {
            closePlayer()
            startWave(listOf("track:${track.id}"), "Волна по треку «${track.title}»")
        }
        item("Поделиться", Symbol.SHARE) { share(track.url) }
        store.files.extension(track)?.let { ext ->
            item("Сохранить аудиофайл", Symbol.FILE) { export(track, ext) }
        }
        item("Подменить аудиофайл", Symbol.NOTE) { documentTrack = track; importAudio.launch(arrayOf("audio/*")) }
        if (store.files.hasReplacement(track)) item("Удалить подмену", Symbol.TRASH, true) {
            action { store.files.removeReplacement(track); toast("Подмена удалена") }
        }
        if (playlist != null) item("Убрать из плейлиста", Symbol.MINUS_CIRCLE, true) {
            action { store.online { editPlaylist(playlist, listOf(track), remove = true) }; loadDetails(playlist) }
        }
        if (anchor == null) trackActionsSheet(track, options) else actionMenu(anchor, options)
    }

    private fun editUploadedTrack(track: Track) {
        val uid = userId ?: return
        tuneTrackEditor(track) { title, artist ->
            action {
                if (store.requireUser().uid != uid) return@action
                store.online { changeUploadedTrack(track.id, title, artist) }
                if (userId != uid) return@action
                fun update(item: Track) = if (item.id == track.id) item.withUploadedMetadata(title, artist) else item
                store.files.updateUploadedMetadata(uid, track.id, title, artist)
                controller?.let { player ->
                    for (index in 0 until player.mediaItemCount) {
                        val item = player.getMediaItemAt(index)
                        val queued = QueueItems.track(item) ?: continue
                        if (queued.id == track.id) player.replaceMediaItem(index, QueueItems.make(update(queued), QueueItems.context(item)))
                    }
                }
                home = home?.let { it.copy(recent = it.recent.map(::update)) }
                trackList = trackList?.let { it.copy(tracks = it.tracks.map(::update)) }
                details = details?.let { it.copy(tracks = it.tracks.map(::update)) }
                searchPages.replaceAll { _, page ->
                    val result = page.results
                    page.copy(results = result.copy(tracks = result.tracks.map(::update),
                        best = (result.best as? SearchBest.Song)?.let { SearchBest.Song(update(it.track)) } ?: result.best))
                }
                when (route.screen) {
                    Screen.SEARCH -> searchRows(keepScroll = true)
                    Screen.DOWNLOADS -> downloads()
                    Screen.DETAILS -> details?.let { rows(detailRows(it), keepScroll = true) }
                    Screen.HOME -> showHome(keepScroll = true)
                    else -> trackList?.let { rows(listRows(it), keepScroll = true) }
                }
                toast("Трек обновлён в Яндекс Музыке")
            }
        }
    }

    private fun chooseVersion(track: Track) {
        val uid = store.requireUser().uid
        val current = store.files.selectedVersion(uid, track.id)
        val options = buildList {
            add("Авто" to "auto")
            if (track.originalAvailable != false && !track.unavailable) add("Яндекс Музыка" to "original")
            if (store.allowsAlternate(uid, track.id) && store.versions.hasAudio(track.id)) add("Без цензуры" to "alternate")
            if (store.files.hasReplacement(track)) add("Моя подмена" to "local")
        }
        val selected = options.indexOfFirst { it.second == current }.takeIf { it >= 0 } ?: 0
        choose("Версия трека", options.map { it.first }, selected) { index ->
            store.files.selectVersion(track, options[index].second)
            reloadVersion(track.id)
        }
    }

    private fun reloadVersion(trackId: String) {
        val args = Bundle().apply { putString("track", trackId) }
        controller?.sendCustomCommand(androidx.media3.session.SessionCommand(QueueItems.RELOAD_VERSION, Bundle.EMPTY), args)
    }

    private fun setAlternateEnabled(enabled: Boolean) {
        store.alternateEnabled = enabled
        home = null
        homeLoaded = false
        searchPages.clear()
        action {
            val ready = !enabled || store.refreshVersions()
            if (store.alternateEnabled != enabled) return@action
            if (!ready) {
                if (!store.offline) toast("Не удалось загрузить список треков без цензуры")
                return@action
            }
            currentTrack()?.let { reloadVersion(it.id) }
        }
    }

    private fun pickPlaylist(tracks: List<Track>, done: () -> Unit = {}) {
        action {
            val uid = store.requireUser().uid
            val cards = store.online { playlists(uid) }
            val dark = playerPanel != null
            val style = if (dark) TuneUi(this) else ui
            val body = style.column()
            val sheet = bottomSheet(body, dark = dark, scrollContent = false)
            body.addView(FrameLayout(this).apply {
                val heading = if (tracks.size == 1) "Добавить в плейлист" else "Добавить в плейлист: ${tracks.size}"
                addView(style.line(heading, 17, bold = true).apply { gravity = Gravity.CENTER },
                    FrameLayout.LayoutParams(-1, -1).apply { marginStart = style.dp(80); marginEnd = style.dp(80) })
                addView(style.line("Отмена", 16, style.palette.accentText, bold = true).apply {
                    gravity = Gravity.CENTER
                    setPadding(style.dp(14), 0, style.dp(14), 0)
                    background = style.touch()
                    isFocusable = true
                    pressFeedback(.94f)
                    setOnClickListener { sheet.dismiss() }
                }, FrameLayout.LayoutParams(-2, -1, Gravity.END).apply { marginEnd = style.dp(4) })
            }, style.full(52))
            val rows = style.column().apply { setPadding(0, style.dp(4), 0, style.dp(12)) }
            rows.addView(style.row().apply {
                setPadding(style.dp(16), style.dp(8), style.dp(16), style.dp(8))
                background = style.touch()
                isFocusable = true
                pressFeedback(.96f)
                addView(IconView(this@MainActivity, Symbol.ADD, Color.BLACK).apply {
                    iconSize = 20
                    background = style.shape(style.palette.accent, 10)
                }, LinearLayout.LayoutParams(style.dp(50), style.dp(50)))
                addView(style.line("Новый плейлист", 16, bold = true).apply {
                    setPadding(style.dp(12), 0, 0, 0)
                }, style.weight())
                setOnClickListener {
                    sheet.dismiss()
                    input("Новый плейлист", "", "Название") { title ->
                        action {
                            val card = store.online { createPlaylist(uid, title) }
                            store.changeLocalPlaylist(card)
                            store.online { editPlaylist(card, tracks) }
                            toast("Добавлено в «${card.title}»")
                            done()
                        }
                    }
                }
            }, style.full())
            cards.forEach { card ->
                val sub = if (card.trackCount > 0) trackCount(card.trackCount) else card.subtitle.ifBlank { "Плейлист" }
                rows.addView(style.row().apply {
                    setPadding(style.dp(16), style.dp(8), style.dp(16), style.dp(8))
                    background = style.touch()
                    isFocusable = true
                    pressFeedback(.96f)
                    addView(style.art(card.cover, 120, corner = 10), LinearLayout.LayoutParams(style.dp(50), style.dp(50)))
                    addView(style.column().apply {
                        setPadding(style.dp(12), 0, 0, 0)
                        addView(style.line(card.title, 16, bold = true))
                        addView(style.space(3))
                        addView(style.line(sub, 14, style.palette.muted))
                    }, style.weight())
                    setOnClickListener {
                        sheet.dismiss()
                        action {
                            store.online { editPlaylist(card, tracks) }
                            toast("Добавлено в «${card.title}»")
                            done()
                        }
                    }
                }, style.full())
            }
            val scroller = ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(rows, FrameLayout.LayoutParams(-1, -2))
            }
            body.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
            val usable = android.graphics.Rect().also(window.decorView::getWindowVisibleDisplayFrame)
            rows.measure(
                View.MeasureSpec.makeMeasureSpec(usable.width().coerceAtLeast(style.dp(320)), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val sheetHeight = minOf(style.dp(52) + rows.measuredHeight, (usable.height() * .74f).toInt()).coerceAtLeast(style.dp(160))
            sheet.window?.setWindowAnimations(R.style.TuneSheetAnimation)
            sheet.show()
            sheet.window?.setLayout(-1, sheetHeight)
        }
    }

    private fun export(track: Track, ext: String) {
        documentTrack = track
        val title = listOf(track.artistText, track.title).filter(String::isNotBlank).joinToString(" — ")
            .replace(Regex("""[\\/:*?"<>|]"""), "_").take(150)
        exportAudio.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(when (ext) { "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; else -> "audio/$ext" })
            .putExtra(Intent.EXTRA_TITLE, "$title.$ext"))
    }

    internal fun share(url: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null))
    }

    internal fun browser(url: String) {
        if (Uri.parse(url).scheme != "https") return
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(url)), null)
                .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, MainActivity::class.java))))
        } catch (_: android.content.ActivityNotFoundException) { toast("Браузер не найден") }
    }

    internal fun rate(track: Track, dislike: Boolean = false) {
        root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        action {
            val add = store.rate(track, dislike)
            if (dislike && add && currentTrack()?.id == track.id && QueueItems.context(controller?.currentMediaItem).wave != null) next()
            if (route.screen == if (dislike) Screen.DISLIKES else Screen.LIKES) load()
            if (route.screen == Screen.LIBRARY) library()
        }
    }

    private fun settings(loadAi: Boolean = true, force: Boolean = false) {
        val account = store.profile.value ?: return
        if (!force && lyricsMirrorField?.hasFocus() == true) {
            pendingSettingsRefresh = true
            return
        }
        if (force) lyricsMirrorField?.clearFocus()
        pendingSettingsRefresh = false
        val scroll = settingsScroll ?: return
        val body = settingsBody ?: return
        val prefs = store.preferences
        val qualities = listOf("lossless" to "Высокое", "nq" to "Обычное", "lq" to "Экономное")
        val appearanceOptions = listOf("system" to "Как в системе", "dark" to "Тёмная", "light" to "Светлая")
        val transitionOptions = listOf(0 to "Выключен", -1 to "Авто", 3 to "3 секунды", 5 to "5 секунд", 8 to "8 секунд")
        val destructive = if (ui.palette.light) 0xffba2828.toInt() else 0xffff6861.toInt()
        val downloaded = store.files.downloaded()
        val savedScrollY = restorePosition?.offset ?: scroll.scrollY
        restorePosition = null
        lyricsMirrorField = null
        body.removeAllViews()
        buildList {
            availableUpdate?.let { update ->
                add(settingsGroupView("Доступна версия ${update.version}", update.notes) {
                    line("Скачать APK", color = ui.palette.accentText) { showUpdate(update) }
                })
            }
            add(settingsGroupView("Аккаунт", "Без Плюса Яндекс отдаёт только отрывки треков.") {
                add(lineView().apply {
                    addView(ui.text(account.name.take(1).uppercase(), 20, Color.BLACK, bold = true).apply {
                        gravity = Gravity.CENTER
                        background = ui.shape(ui.palette.accent, 24)
                    }, LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)))
                    addView(ui.column().apply {
                        setPadding(ui.dp(14), 0, ui.dp(8), 0)
                        addView(ui.text(account.name, 17, bold = true))
                        if (account.login.isNotBlank()) {
                            addView(ui.space(3))
                            addView(ui.text(account.login, 14, ui.palette.muted))
                        }
                    }, ui.weight())
                    addView(ui.text(if (account.plus) "Плюс активен" else "Без Плюса", 13,
                        if (account.plus) Color.BLACK else ui.palette.text, bold = true).apply {
                        background = ui.shape(if (account.plus) ui.palette.accent else ui.palette.lifted, 16)
                        setPadding(ui.dp(10), ui.dp(5), ui.dp(10), ui.dp(5))
                    })
                })
                store.accounts().filterNot { it.uid == account.uid }.forEach { saved ->
                    line(saved.name.ifBlank { saved.login }, "Переключиться", Symbol.ACCOUNT) { action { store.switchAccount(saved) } }
                }
                line("Добавить аккаунт", color = ui.palette.accentText) {
                    login.launch(Intent(this@MainActivity, LoginActivity::class.java).putExtra(LoginActivity.ADD_ACCOUNT, true))
                }
            })
            add(settingsGroupView(footer = "Вместо версий с вырезанными словами играют оригинальные из списка FckCensor. Треки, удалённые из Яндекса, но оставшиеся в списке, снова доступны.") {
                toggle("Без цензуры", store.alternateEnabled, change = ::setAlternateEnabled)
            })
            add(settingsGroupView("Оформление") {
                settingsPicker("Тема", appearance, appearanceOptions) {
                    prefs.edit().putString("appearance", it).apply()
                    reopenSettings = true
                    recreate()
                }
            })
            add(settingsGroupView("Воспроизведение",
                "«Авто» использует точки перехода Яндекса и сведение в волнах-сетах. Для треков без разметки переход занимает до 6 секунд.") {
                val stored = prefs.getInt("crossfade", -1)
                settingsPicker("Плавный переход", stored.takeIf { value -> transitionOptions.any { it.first == value } } ?: -1,
                    transitionOptions) { prefs.edit().putInt("crossfade", it).apply(); settings(loadAi = false, force = true) }
                toggle("Выравнивать громкость", prefs.getBoolean("normalize", true)) {
                    prefs.edit().putBoolean("normalize", it).apply()
                }
            })
            add(settingsGroupView {
                line("Устройства Яндекс Музыки", icon = Symbol.OUTPUT, chevron = true, click = ::openDevices)
            })
            add(settingsGroupView("Моя волна") {
                toggle("Колесо волны", prefs.getBoolean("waveWheel", true), "Подсказки волн по настроению, жанру и артистам под Моей волной") {
                    prefs.edit().putBoolean("waveWheel", it).apply()
                }
                toggle("Слова в волне", prefs.getBoolean("waveWords", true), "Короткие факты о треках от ИИ под Моей волной") {
                    prefs.edit().putBoolean("waveWords", it).apply()
                }
                toggle("Свои треки в «Моей волне»", prefs.getBoolean("uploadsInWave", false),
                    "Загруженные вами треки из «Мне нравится» иногда попадаются в волне") {
                    prefs.edit().putBoolean("uploadsInWave", it).apply()
                }
                toggle("Удалённые треки в «Моей волне»", prefs.getBoolean("restoredInWave", false),
                    "Треки, которые Яндекс удалил, а «Без цензуры» вернул, попадаются в волне по вашему вкусу") {
                    prefs.edit().putBoolean("restoredInWave", it).apply()
                }
            })
            add(settingsGroupView("Рекомендации", "В рекомендациях будет реже встречаться музыка, созданная с помощью ИИ") {
                toggle("Меньше ИИ", reduceAi == true, enabled = reduceAi != null && !store.offline) { enabled ->
                    action(onError = { toast(it); settings(loadAi = false, force = true) }) {
                        store.online { setReduceAi(enabled) }
                        reduceAi = enabled
                        if (route.screen == Screen.SETTINGS) settings(loadAi = false, force = true)
                    }
                }
            })
            add(settingsGroupView("Текст песен", "Когда у Яндекса нет текста со временем строк, Tune ищет его в открытых базах. Настроенное зеркало LRCLIB проверяется первым.") {
                lyricsPreferences()
            })
            add(settingsGroupView("Качество звука") {
                settingsPicker("При прослушивании", store.streamQuality, qualities) { store.streamQuality = it; settings(loadAi = false, force = true) }
                settingsPicker("При скачивании", store.downloadQuality, qualities) { store.downloadQuality = it; settings(loadAi = false, force = true) }
            })
            add(settingsGroupView("Загрузки",
                "В сотовой сети загрузки ждут Wi-Fi, если включён этот режим. Отдельный плейлист можно скачивать автоматически из его меню.") {
                toggle("Только по Wi-Fi", store.wifiOnly) { store.wifiOnly = it }
                toggle("Скачивать «Мне нравится»", store.autoDownload()) { store.setAutoDownload(enabled = it) }
                toggle("Офлайн-режим", store.offline) {
                    store.offline = it
                    homeLoaded = false
                    settings(loadAi = !it, force = true)
                    if (!it) action { store.refreshAccount(); store.refreshVersions() }
                }
            })
            add(settingsGroupView("Память", "Скачанная музыка хранится в памяти приложения. Android может освободить место в кэше при нехватке памяти.") {
                line("Скачанная музыка", settingsStorage?.first?.let(::fileSize).orEmpty(), chevron = true) { push(Screen.STORAGE) }
                line("Кэш", settingsStorage?.second?.let(::fileSize).orEmpty())
                line("Очистить кэш", color = ui.palette.accentText) {
                    action {
                        store.files.clearStreamCache()
                        clearImageCache()
                        refreshSettingsStorage()
                    }
                }
                line("Удалить всю скачанную музыку", color = destructive, enabled = downloaded.isNotEmpty()) {
                    confirm("Удалить всю скачанную музыку?", "Удалить") {
                        action { downloaded.forEach { store.files.remove(it) }; refreshSettingsStorage() }
                    }
                }
            })
            add(settingsGroupView {
                line("Что нового", chevron = true) { push(Screen.CHANGES) }
                if (AppUpdates.configured) line("Проверить обновления") { checkForUpdates(manual = true) }
                line("Журнал ошибок", chevron = true) { push(Screen.ERROR_LOG) }
                line("Версия", BuildConfig.VERSION_NAME)
            })
            add(settingsGroupView {
                line("Выйти из аккаунта", color = destructive) {
                    confirm("Выйти из аккаунта?", "Выйти") {
                        store.logout()
                        CookieManager.getInstance().removeAllCookies(null)
                    }
                }
            })
        }.forEach { group -> body.addView(group, ui.full()) }
        pageHasRows = true
        if (savedScrollY > 0) scroll.post { scroll.scrollTo(0, savedScrollY) }
        checkForUpdates()
        if (settingsStorage == null && settingsStorageJob?.isActive != true) refreshSettingsStorage()
        if (loadAi && reduceAi == null && !store.offline) {
            val uid = account.uid
            action(onError = {}) {
                val loaded = store.online { reduceAi() }
                if (userId == uid && route.screen == Screen.SETTINGS) {
                    reduceAi = loaded
                    settings(loadAi = false)
                }
            }
        }
    }

    private fun checkForUpdates(manual: Boolean = false) {
        if (!AppUpdates.configured || updateCheck?.isActive == true) return
        if (store.offline) {
            if (manual) toast("Включён офлайн-режим")
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (!manual && lastUpdateCheck > 0 && now - lastUpdateCheck < 60 * 60 * 1000L) return
        lastUpdateCheck = now
        updateCheck = action(onError = { if (manual) toast(it) }) {
            val update = AppUpdates.check()
            if (update != null && update != availableUpdate && route.screen !in setOf(Screen.SETTINGS, Screen.LIBRARY)) {
                toast("Доступна версия ${update.version}")
            }
            availableUpdate = update
            if (manual && update == null) toast("Установлена последняя версия Tune")
            when (route.screen) {
                Screen.SETTINGS -> settings(loadAi = false)
                Screen.LIBRARY -> library()
                else -> Unit
            }
        }
    }

    private fun showUpdate(update: AppUpdate) {
        val instructions = "После загрузки откройте APK, чтобы обновить Tune."
        tuneConfirm("Доступна версия ${update.version}", "Скачать APK",
            listOf(update.notes, instructions).filter(String::isNotBlank).joinToString("\n\n"), negative = "Позже") {
            browser(update.downloadUrl)
        }
    }

    private fun changes() {
        rows(listOf(
            heading("Tune ${BuildConfig.VERSION_NAME}"),
            note("На страницах альбомов появились подпись лейбла и общая длительность, биография исполнителя сворачивается в 3 строки с кнопкой «Читать дальше», имя артиста в шапке альбома кликабельно, а в «Лучшем результате» поиска видны живые столбцы воспроизведения."),
            heading("В прошлых версиях"),
            note("В «Очереди» плеера добавлен режим «Изменить» для перестановки и удаления треков, а окно добавления в плейлист теперь показывает обложки и число треков."),
            note("Добавлены сортировка «Сначала старые», галочки текущего выбора в списках, автооткрытие ссылок из поиска и подписи числа треков у плейлистов в коллекции."),
            note("Обновлены экраны «Статистика», «Занятое место», «Устройства» и «Подмена» — карточки, сводные плитки, нумерация любимых треков и очистка статистики в панели сверху."),
            note("Убраны подвисания на главной при прокрутке и переключении вкладок, сглажен градиент без полос в плеере и шапках страниц."),
            note("В плеере появились кольца буферизации и прогресса загрузки, активная подсветка таймера сна, компактные уведомления вместо системных тостов, исправлен скачок клавиатуры в поле зеркала LRCLIB и выровнены мелочи в коллекции, поиске и мини-плеере."),
            note("Полноэкранный плеер и клипы растянуты на весь экран без чёрных полос. Обновлены значки разделов, плавный скролл текста песни, перелистывание обложек свайпом и кроссфейд между треками."),
            note("Исправлены цвета волн на главной, разделены подкасты и выпуски в поиске, сохраняются фильтры в списках и продолжается очередь загрузок после потери сети."),
            note("Tune работает в портретной ориентации. Верхняя панель плеера остаётся видимой при прокрутке, и свайп вниз с неё сворачивает плеер."),
            note("Лайки восстановленных треков сохраняются на устройстве и синхронизируются с Яндекс Музыкой, когда сервер их принимает."),
            note("Поле поиска перемещено в верхнюю часть экрана. Скачанные обложки и тексты доступны в офлайн-режиме."),
            note("Исправлены загрузка обложек, выравнивание в плеере и на главной и свечение «Моей волны»."),
            note("Настройки, коллекция и главная приведены к порядку Tune для iOS."),
            note("Добавлены ссылки Tune и проверка обновлений в настройках для сборок с подключённым источником релизов."),
            heading("Главная и поиск"),
            note("«Слова в волне» с рассказами и оценками, колесо волны, тренды, подкасты и поиск по тексту песни."),
            heading("Плеер и устройства"),
            note("Плавный переход, выравнивание громкости, эквалайзер и реверб. Перенос воспроизведения через Ynison, управление со Станции и библиотека Android Auto."),
            heading("Коллекция"),
            note("Несколько аккаунтов, импорт и экспорт плейлистов, загрузка своих аудиофайлов, подмены метаданных и статистика."),
            heading("Android"),
            note("Светлая тема, виджет, быстрые действия на значке и фоновая очередь загрузок."),
        ))
    }

    internal fun openDevices() {
        if (controller == null && controllerFuture == null) connectPlayer()
        store.ynison.ensureConnected()
        push(Screen.DEVICES)
    }

    private fun settingsGroupView(title: String = "", footer: String = "", content: SettingsGroup.() -> Unit): View =
        SettingsGroup(ui, title, footer).apply(content).view

    private fun <T> SettingsGroup.settingsPicker(title: String, selected: T, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
        val index = options.indexOfFirst { it.first == selected }
        line(title, options.getOrNull(index)?.second.orEmpty(), chevron = true) {
            choose(title, options.map { it.second }, index) { onSelect(options[it].first) }
        }
    }

    private fun saveLyricsMirror(field: EditText, quiet: Boolean = false) {
        val prefs = store.preferences
        val address = field.text.toString().trim()
        val normalized = if (address.isBlank()) "" else lyricsMirror(address)
        if (normalized != null) {
            if (prefs.getString("lyricsMirror", "").orEmpty() != normalized) {
                prefs.edit().putString("lyricsMirror", normalized).apply()
            }
            if (!quiet && field.text.toString() != normalized) {
                field.setText(normalized)
                field.setSelection(normalized.length)
            }
        } else if (!quiet) {
            val saved = prefs.getString("lyricsMirror", "").orEmpty()
            field.setText(saved)
            field.setSelection(saved.length)
            toast("Введите адрес HTTPS")
        }
    }

    private fun SettingsGroup.lyricsPreferences() {
        val prefs = store.preferences
        val enabled = prefs.getBoolean("lyricsExternal", true)
        toggle("Синхронный текст из LRCLIB и NetEase", enabled) {
            prefs.edit().putBoolean("lyricsExternal", it).apply()
            settings(loadAi = false, force = true)
        }
        if (!enabled) return
        val lrclib = prefs.getBoolean("lyricsLRCLIB", true)
        toggle("LRCLIB", lrclib) {
            prefs.edit().putBoolean("lyricsLRCLIB", it).apply()
            settings(loadAi = false, force = true)
        }
        toggle("NetEase Cloud Music", prefs.getBoolean("lyricsNetEase", true)) {
            prefs.edit().putBoolean("lyricsNetEase", it).apply()
        }
        toggle("Сначала NetEase", prefs.getBoolean("lyricsNetEaseFirst", false)) {
            prefs.edit().putBoolean("lyricsNetEaseFirst", it).apply()
        }
        toggle("Искать и там, где у Яндекса есть свой текст", prefs.getBoolean("lyricsAlways", false)) {
            prefs.edit().putBoolean("lyricsAlways", it).apply()
        }
        if (lrclib) {
            val mirror = prefs.getString("lyricsMirror", "").orEmpty()
            val field = EditText(this@MainActivity).apply {
                setSingleLine()
                textSize = 16f
                setTextColor(ui.palette.text)
                setHintTextColor(ui.palette.muted)
                highlightColor = 0x66facc15
                if (Build.VERSION.SDK_INT >= 29) {
                    textCursorDrawable = ui.shape(ui.palette.accentText, 1).apply { setSize(ui.dp(2), ui.dp(18)) }
                }
                hint = "Зеркало, например lrclib.example.com"
                contentDescription = "Зеркало LRCLIB"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = EditorInfo.IME_ACTION_DONE
                minimumHeight = ui.dp(28)
                setPadding(0, ui.dp(2), 0, ui.dp(2))
                setText(mirror)
                setSelection(mirror.length)
                background = null
                doAfterTextChanged { editable ->
                    val address = editable?.toString().orEmpty().trim()
                    val normalized = if (address.isBlank()) "" else lyricsMirror(address)
                    if (normalized != null && prefs.getString("lyricsMirror", "").orEmpty() != normalized) {
                        prefs.edit().putString("lyricsMirror", normalized).apply()
                    }
                }
                setOnEditorActionListener { _, action, event ->
                    if (action != EditorInfo.IME_ACTION_DONE && !(event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                        return@setOnEditorActionListener false
                    }
                    clearFocus()
                    hideKeyboard()
                    true
                }
                setOnFocusChangeListener { _, focused ->
                    if (!focused) {
                        saveLyricsMirror(this)
                        if (pendingSettingsRefresh && route.screen == Screen.SETTINGS) {
                            post {
                                if (route.screen == Screen.SETTINGS && lyricsMirrorField?.hasFocus() != true) {
                                    settings(loadAi = false)
                                }
                            }
                        }
                    }
                }
            }
            lyricsMirrorField = field
            add(lineView().apply {
                addView(field, ui.full())
                setOnClickListener {
                    if (field.requestFocus()) {
                        getSystemService(InputMethodManager::class.java).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            })
        }
    }

    private fun fileSize(bytes: Long) = android.text.format.Formatter.formatFileSize(this, bytes)

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    private suspend fun clearImageCache() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        imageLoader.diskCache?.clear()
        imageLoader.memoryCache?.clear()
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    private fun refreshSettingsStorage() {
        settingsStorageJob?.cancel()
        val uid = userId
        settingsStorageJob = action(onError = {}) {
            val total = store.files.diskUsage().sumOf { it.second }
            val cache = store.files.streamCacheSize() + (imageLoader.diskCache?.size ?: 0L)
            if (uid == userId) {
                settingsStorage = total to cache
                if (route.screen == Screen.SETTINGS) settings(loadAi = false)
            }
        }
    }

    private fun errorLogRows() {
        rows(if (errorLog.isEmpty()) listOf(note("Ошибок пока нет")) else listOf(
            shortcut(Symbol.SHARE, "Скопировать журнал") {
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Tune", errorLog.joinToString("\n")))
            },
        ) + errorLog.map(::note))
    }

    private fun openReplacement(target: ReplacementTarget) {
        hideKeyboard()
        closePlayer()
        stack.addLast(Route(Screen.REPLACEMENT, replacement = target))
        render()
    }

    private fun saveReplacement(target: ReplacementTarget, value: MetadataOverride?) {
        val uid = store.requireUser().uid
        store.overrides.write(uid, target.kind, target.id, value)
        home = null
        homeLoaded = false
        if (route.replacement == target) replacement(target)
        toast("Подмена сохранена")
    }

    private fun replacement(target: ReplacementTarget) {
        val uid = store.requireUser().uid
        val value = store.overrides.read(uid, target.kind, target.id)
        val hasAudio = target.track?.let(store.files::hasReplacement) == true
        val modified = value != MetadataOverride() || hasAudio
        val destructive = if (ui.palette.light) 0xffba2828.toInt() else 0xffff6861.toInt()
        rows(buildList {
            add(Row.Layout {
                settingsGroupView(
                    "Название и исполнители",
                    "Пустые поля сохраняют данные каталога. Подмены действуют в Tune на этом устройстве."
                ) {
                    line("Название", value.title.ifBlank { target.title }, chevron = true) {
                        input("Название", value.title, target.title) { saveReplacement(target, value.copy(title = it)) }
                    }
                    if (target.kind == "track") {
                        line("Исполнитель", value.artist.ifBlank { target.track?.artistText.orEmpty() }, chevron = true) {
                            input("Исполнитель", value.artist, target.track?.artistText.orEmpty()) { saveReplacement(target, value.copy(artist = it)) }
                        }
                    }
                }
            })
            add(Row.Layout {
                settingsGroupView("Обложка") {
                    line("Ссылка на обложку", value.cover.takeIf { it.startsWith("https://") }.orEmpty(), Symbol.LINK, chevron = true) {
                        input("Ссылка на обложку", value.cover.takeIf { it.startsWith("https://") }.orEmpty(), "https://") { link ->
                            val uri = Uri.parse(link)
                            if (uri.scheme == "https" && !uri.host.isNullOrBlank()) saveReplacement(target, value.copy(cover = link))
                            else toast("Нужна ссылка HTTPS")
                        }
                    }
                    line("Выбрать изображение", icon = Symbol.PHOTO, chevron = true) {
                        coverPickerTarget = target
                        localCoverDocument.launch("image/*")
                    }
                }
            })
            target.track?.let { track ->
                add(Row.Layout {
                    settingsGroupView("Аудио") {
                        line(if (hasAudio) "Заменить аудиофайл" else "Выбрать аудиофайл", if (hasAudio) "Файл выбран" else "",
                            Symbol.WAVE, chevron = true) {
                            documentTrack = track
                            importAudio.launch(arrayOf("audio/*"))
                        }
                    }
                })
            }
            if (target.kind != "track") {
                add(Row.Layout {
                    settingsGroupView(
                        if (target.kind == "album") "Порядок треков" else "Добавить на страницу исполнителя",
                        if (target.kind == "album") "Ссылки или номера треков в нужном порядке."
                        else "Добавленные треки и альбомы поднимутся в начало списков."
                    ) {
                        line(if (target.kind == "album") "Порядок треков" else "Добавить треки",
                            value.tracks.size.takeIf { it > 0 }?.toString().orEmpty(), Symbol.QUEUE, chevron = true) {
                            input("Треки", value.tracks.joinToString(", "), "Ссылки или номера треков") {
                                val ids = MetadataOverrides.ids(it, "track")
                                if (ids.isEmpty()) toast("Треки не распознаны") else saveReplacement(target, value.copy(tracks = ids))
                            }
                        }
                        if (target.kind == "artist") {
                            line("Добавить альбомы", value.albums.size.takeIf { it > 0 }?.toString().orEmpty(),
                                Symbol.LIBRARY, chevron = true) {
                                input("Альбомы", value.albums.joinToString(", "), "Ссылки или номера альбомов") {
                                    val ids = MetadataOverrides.ids(it, "album")
                                    if (ids.isEmpty()) toast("Альбомы не распознаны") else saveReplacement(target, value.copy(albums = ids))
                                }
                            }
                        }
                    }
                })
            }
            if (target.kind != "artist" || modified) {
                add(Row.Layout {
                    settingsGroupView {
                        if (target.kind != "artist") toggle("Применять данные FckCensor", value.usePlugin) {
                            saveReplacement(target, value.copy(usePlugin = it))
                        }
                        if (modified) line("Сбросить подмену", color = destructive) {
                            confirm("Сбросить подмену?", "Сбросить") {
                                action {
                                    target.track?.takeIf(store.files::hasReplacement)?.let { store.files.removeReplacement(it) }
                                    saveReplacement(target, null)
                                }
                            }
                        }
                    }
                })
            }
            add(Row.Layout { ui.space(20) })
        }, keepScroll = true)
    }

    private suspend fun imageJpeg(uri: Uri): ByteArray = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) throw MusicError("Не удалось прочитать изображение")
        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / sample > 1440) sample *= 2
        val decoded = contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw MusicError("Не удалось прочитать изображение")
        try {
            java.io.ByteArrayOutputStream().use { output ->
                if (!decoded.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, output)) throw MusicError("Не удалось подготовить обложку")
                output.toByteArray()
            }
        } finally {
            decoded.recycle()
        }
    }

    private fun storage(): Unit = loadRows {
        val usage = store.files.diskUsage()
        if (usage.isEmpty()) {
            return@loadRows listOf(Row.Layout {
                ui.pagePlaceholder(Symbol.DOWNLOAD_CIRCLE, "Пока ничего не скачано",
                    "Здесь будет видно, чья музыка занимает больше всего места.")
            })
        }
        val totals = usage.groupBy { (track, _) ->
            track.artists.firstOrNull()?.title?.takeIf(String::isNotBlank) ?: track.artistText.ifBlank { "Без исполнителя" }
        }.entries.map { (artist, entries) ->
            Triple(artist, entries, entries.sumOf { it.second })
        }.sortedWith(compareByDescending<Triple<String, List<Pair<Track, Long>>, Long>> { it.third }.thenBy { it.first })
        val totalBytes = usage.sumOf { it.second }
        buildList {
            add(Row.Layout {
                ui.column().apply {
                    setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(14))
                    addView(ui.text(fileSize(totalBytes), 30, bold = true).apply { fontFeatureSettings = "tnum" })
                    addView(ui.space(4))
                    addView(ui.line("${trackCount(usage.size)} · ${plural(totals.size, "исполнитель", "исполнителя", "исполнителей")}",
                        14, ui.palette.muted))
                }
            })
            totals.forEach { (artist, entries, bytes) ->
                val sizeLabel = fileSize(bytes)
                val removeArtist = {
                    confirm("Удалить скачанное?", "Удалить", "$artist: ${trackCount(entries.size)}, $sizeLabel") {
                        action { entries.forEach { store.files.remove(it.first) }; storage() }
                    }
                }
                add(Row.Layout {
                    ui.row().apply {
                        minimumHeight = ui.dp(60)
                        setPadding(ui.dp(16), ui.dp(6), ui.dp(10), ui.dp(6))
                        background = ui.touch()
                        isFocusable = true
                        addView(ui.art(entries.firstOrNull()?.first?.cover, 120, corner = 8),
                            LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)))
                        addView(ui.column().apply {
                            setPadding(ui.dp(12), 0, ui.dp(8), 0)
                            addView(ui.line(artist, 16, bold = true))
                            addView(ui.space(3))
                            addView(ui.line(trackCount(entries.size), 14, ui.palette.muted))
                        }, ui.weight())
                        addView(ui.line(sizeLabel, 15, ui.palette.muted).apply {
                            fontFeatureSettings = "tnum"
                            setPadding(ui.dp(4), 0, ui.dp(4), 0)
                        })
                        addView(ui.icon(Symbol.TRASH, "Удалить скачанное: $artist", ui.palette.muted, click = removeArtist).apply {
                            iconSize = 17
                        }, LinearLayout.LayoutParams(ui.dp(36), ui.dp(40)))
                        setOnClickListener { removeArtist() }
                    }
                })
            }
        }
    }

    private fun devices() {
        val sync = store.ynison
        val state = sync.state.value
        val connected = sync.connection.value == Ynison.CONNECTED
        val here = sync.deviceId
        val remotes = state.devices.filterNot { it.id == here }
        val otherDevices = remotes.filterNot { it.kind.contains("SPEAKER") || it.kind.contains("STATION") }
        val speakers = remotes.filter { it.kind.contains("SPEAKER") || it.kind.contains("STATION") }
        rows(buildList {
            add(Row.Layout {
                settingsGroupView(footer = "Переносите очередь, текущий трек и позицию между устройствами одного аккаунта Яндекса.") {
                    toggle("Синхронизация устройств", sync.enabled) {
                        sync.enable(it)
                        devices()
                    }
                }
            })
            if (sync.enabled) {
                if (!connected) {
                    add(Row.Layout {
                        settingsGroupView {
                            line(sync.connection.value, color = ui.palette.muted)
                            sync.detail.value?.takeIf(String::isNotBlank)?.let {
                                line(it, color = ui.palette.muted)
                            }
                            if (store.offline) line("Для подключения устройств нужен интернет", icon = Symbol.WIFI_OFF, color = ui.palette.muted)
                            else line("Повторить подключение", icon = Symbol.REPEAT, color = ui.palette.accentText) { sync.reconnect() }
                        }
                    })
                } else {
                    if (remotes.isEmpty()) {
                        add(Row.Layout {
                            settingsGroupView(footer = "Включите музыку на другом устройстве или Станции, подключённой к тому же аккаунту.") {
                                line("Других устройств в сети пока нет", color = ui.palette.muted)
                            }
                        })
                    }
                    add(Row.Layout {
                        settingsGroupView("Воспроизведение") {
                            val thisDevice = YnisonDevice(here, "Это устройство", "PHONE")
                            (listOf(thisDevice) + otherDevices).forEach { device ->
                                val active = state.active == device.id
                                line(device.name, icon = Symbol.OUTPUT, checked = active, enabled = !active) {
                                    action { sync.transfer(device.id) }
                                }
                            }
                        }
                    })
                    if (speakers.isNotEmpty()) {
                        add(Row.Layout {
                            settingsGroupView("Колонки") {
                                speakers.forEach { device ->
                                    val active = state.active == device.id
                                    line(device.name, icon = Symbol.SPEAKER, checked = active, enabled = !active) {
                                        action { sync.transfer(device.id) }
                                    }
                                }
                            }
                        })
                    }
                    val activeRemote = state.active?.takeIf { it != here }?.let { active -> remotes.firstOrNull { it.id == active } }
                    val song = state.playback.current
                    if (activeRemote != null && song != null) {
                        add(Row.Layout {
                            settingsGroupView(activeRemote.name) {
                                line(song.title.ifBlank { "Текущий трек" })
                                line(if (state.playback.paused) "Продолжить" else "Пауза",
                                    icon = if (state.playback.paused) Symbol.PLAY else Symbol.PAUSE) {
                                    sync.control(paused = !state.playback.paused)
                                }
                                line("Предыдущий трек", icon = Symbol.PREVIOUS) { sync.control(offset = -1) }
                                line("Следующий трек", icon = Symbol.NEXT) { sync.control(offset = 1) }
                            }
                        })
                    }
                }
            }
            add(Row.Layout { ui.space(20) })
        }, keepScroll = true)
    }

    private fun stats(): Unit = loadRows {
        val uid = store.requireUser().uid
        val period = statsDays
        val data = store.listening.summary(uid, period)
        val cachedCovers = store.cachedArtistCovers(data.artists.map { it.first.id }.filter(String::isNotBlank))
        val hasAny = data.count > 0 || (statsDays != null && store.listening.summary(uid, null).count > 0)
        statsClearButton?.apply {
            isEnabled = hasAny
            alpha = if (hasAny) 1f else .38f
        }
        val periods = listOf(7 to "7 дней", 30 to "30 дней", null to "Всё время")
        fun statTile(value: String, caption: String) = ui.column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = ui.shape(ui.palette.panel, 16)
            setPadding(ui.dp(6), ui.dp(16), ui.dp(6), ui.dp(16))
            addView(ui.line(value, 22, bold = true).apply {
                gravity = Gravity.CENTER
                fontFeatureSettings = "tnum"
                setAutoSizeTextTypeUniformWithConfiguration(13, 22, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            }, ui.full())
            addView(ui.space(4))
            addView(ui.line(caption, 13, ui.palette.muted).apply {
                gravity = Gravity.CENTER
                setAutoSizeTextTypeUniformWithConfiguration(10, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            }, ui.full())
        }
        buildList {
            add(Row.Layout { horizontal(periods.map { (days, label) -> ui.chip(label, statsDays == days) {
                statsDays = days
                stats()
            } }) })
            if (data.count == 0) {
                add(Row.Layout {
                    ui.pagePlaceholder(Symbol.STATS, "Пока нечего показать",
                        "Статистика копится на этом устройстве: послушайте что-нибудь, и здесь появятся любимые треки и исполнители.")
                })
            } else {
                val listenedText = durationSummary(data.duration)
                val playsWord = plural(data.count, "прослушивание", "прослушивания", "прослушиваний").substringAfter(' ')
                val artistsText = "${data.artists.size}${if (data.artists.size >= 12) "+" else ""}"
                add(Row.Layout {
                    ui.row().apply {
                        setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(12))
                        addView(statTile(listenedText, "слушали"), LinearLayout.LayoutParams(0, -2, 1f))
                        gap(this, 10)
                        addView(statTile(data.count.toString(), playsWord), LinearLayout.LayoutParams(0, -2, 1f))
                        gap(this, 10)
                        addView(statTile(artistsText, "исполнителей"), LinearLayout.LayoutParams(0, -2, 1f))
                    }
                })
                add(heading("Любимые треки"))
                addAll(trackRows(data.songs.map { it.first }, PlayContext(name = "Любимые треки"), numbered = true))
                if (data.artists.isNotEmpty()) {
                    add(heading("Любимые исполнители"))
                    val missing = data.artists.map { it.first.id }.filter { it.isNotBlank() && cachedCovers[it] == null }
                    if (missing.isNotEmpty() && !store.offline) {
                        action(onError = {}) {
                            val added = store.loadMissingArtistCovers(missing)
                            if (added.isNotEmpty() && userId == uid && route.screen == Screen.STATS && statsDays == period) {
                                stats()
                            }
                        }
                    }
                    add(shelfRow(data.artists.map { (artist, count) ->
                        artist.copy(cover = cachedCovers[artist.id] ?: artist.cover, subtitle = plural(count, "раз", "раза", "раз"))
                    }))
                }
            }
        }
    }

    private fun waveSettings() {
        val groups = listOf(
            "По характеру" to listOf("Любимое" to "settingDiversity:favorite", "Незнакомое" to "settingDiversity:discover", "Популярное" to "settingDiversity:popular"),
            "Под настроение" to listOf("Бодрое" to "settingMoodEnergy:active", "Весёлое" to "settingMoodEnergy:fun", "Спокойное" to "settingMoodEnergy:calm", "Грустное" to "settingMoodEnergy:sad"),
            "По языку" to listOf("Русский" to "settingLanguage:russian", "Иностранный" to "settingLanguage:not-russian", "Без слов" to "settingLanguage:without-words"),
        )
        val selected = store.preferences.getStringSet("waveSettings", emptySet()).orEmpty().toMutableSet()
        val body = ui.column()
        val sheet = bottomSheet(body)
        fun populate() {
            body.removeAllViews()
            body.addView(ui.row().apply {
                setPadding(ui.dp(12), 0, ui.dp(6), 0)
                addView(ui.button("Сбросить") { selected.clear(); populate() }.apply {
                    background = ui.touch()
                    alpha = if (selected.isEmpty()) .4f else 1f
                    isEnabled = selected.isNotEmpty()
                })
                addView(ui.line("Моя волна", 18, bold = true).apply { gravity = Gravity.CENTER }, ui.weight())
                addView(ui.icon(Symbol.CLOSE, "Закрыть") { sheet.dismiss() })
            }, ui.full(56))
            groups.forEach { (title, options) ->
                body.addView(ui.text(title, 17, bold = true).apply { setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(4)) })
                body.addView(horizontal(options.map { (label, seed) ->
                    ui.chip(label, seed in selected) {
                        if (seed in selected) selected.remove(seed) else {
                            selected.removeAll(options.map { it.second }.toSet())
                            selected.add(seed)
                        }
                        populate()
                    }
                }))
            }
            body.addView(ui.button("Включить волну", true) {
                store.preferences.edit().putStringSet("waveSettings", selected.toSet()).apply()
                sheet.dismiss()
                startWave(listOf(MY_WAVE), "Моя волна")
                refreshRows()
            }.apply { background = ui.touch(ui.palette.accent, 27) }, ui.full(50).apply {
                setMargins(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(16))
            })
        }
        populate()
        sheet.show()
        sheet.window?.setLayout(-1, -2)
    }

    internal fun bottomSheet(body: View, dark: Boolean = false, scrollContent: Boolean = true): android.app.Dialog {
        val dialog = if (dark) android.app.Dialog(this, android.R.style.Theme_Material_Dialog) else android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val container: FrameLayout = if (scrollContent) android.widget.ScrollView(this).apply { isFillViewport = true }
            else FrameLayout(this)
        container.apply {
            background = ui.shape(if (dark) Palette.panel else ui.palette.panel, 24)
            clipToOutline = true
            addView(body, FrameLayout.LayoutParams(-1, if (scrollContent) -2 else -1))
        }
        dialog.setContentView(container)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            setDimAmount(.45f)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        return dialog
    }

    private fun startWave(seeds: List<String>, name: String) {
        waveJob?.cancel()
        waveJob = action {
            val settings = if (seeds == listOf(MY_WAVE)) store.preferences.getStringSet("waveSettings", emptySet()).orEmpty() else emptySet()
            val wave = if (store.offline) null else try {
                store.online { startWave(seeds + settings) }
            } catch (error: MusicError) {
                if (error.cause !is java.io.IOException) throw error
                null
            }
            if (wave == null) {
                val saved = store.files.downloaded().shuffled()
                if (saved.isEmpty()) throw MusicError("Скачайте музыку, чтобы включать волну без сети")
                playTracks(saved, 0, PlayContext("radio", seeds.joinToString(","), name), shuffle = false)
                return@action
            }
            if (wave.tracks.isEmpty()) throw MusicError("Волна пока не предложила треков")
            store.scope.launch { optional { store.online { waveFeedback(wave.session, "radioStarted") } } }
            playTracks(wave.tracks, 0, PlayContext("radio", seeds.joinToString(","), name, wave.session), shuffle = false)
        }
    }

    internal fun currentTrack() = QueueItems.track(controller?.currentMediaItem)

    internal fun playTracks(tracks: List<Track>, index: Int, source: PlayContext, shuffle: Boolean? = null) {
        val track = tracks.getOrNull(index) ?: return
        if (track.unavailable && !store.files.hasReplacement(track) && !store.files.has(track)) {
            toast("Трек недоступен")
            return
        }
        val selected = tracks.withIndex().filter { !it.value.unavailable || store.files.hasReplacement(it.value) || store.files.has(it.value) }
        val start = selected.indexOfFirst { it.index == index }.coerceAtLeast(0)
        val items = selected.map { QueueItems.make(it.value, source) }
        withPlayer {
            if (shuffle != null) shuffleModeEnabled = shuffle
            setMediaItems(items, start, 0)
            prepare()
            play()
        }
    }

    private fun withPlayer(block: MediaController.() -> Unit) {
        controller?.let { it.block(); return }
        pendingPlayback = { controller?.block() }
        if (controllerFuture == null) connectPlayer()
    }

    private fun enqueue(track: Track, next: Boolean) = enqueue(listOf(track), next)

    private fun enqueue(tracks: List<Track>, next: Boolean) {
        val available = tracks.filter { !it.unavailable || store.files.hasReplacement(it) || store.files.has(it) }
        if (available.isEmpty()) { toast("Нет доступных треков"); return }
        withPlayer {
            if (mediaItemCount == 0) playTracks(available, 0, PlayContext(name = "Очередь"))
            else {
                val items = available.map { QueueItems.make(it, PlayContext(name = "Очередь")) }
                if (next) addMediaItems(currentMediaItemIndex + 1, items) else addMediaItems(items)
                toast("В очередь добавлено: ${trackCount(available.size)}")
            }
        }
    }

    internal fun toggle() {
        controller?.let {
            if (it.isPlaying || it.playWhenReady && it.playbackState == Player.STATE_BUFFERING) it.pause()
            else {
                if (it.playbackState == Player.STATE_ENDED) it.seekTo(0)
                if (it.playbackState == Player.STATE_IDLE) it.prepare()
                it.play()
            }
        }
    }

    internal fun previous() {
        controller?.let {
            root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            it.seekToPrevious()
        }
    }

    internal fun previousTrack() {
        controller?.let { player ->
            root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
            else player.seekToPrevious()
        }
    }
    internal fun next() {
        controller?.let { player ->
            root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            if (player.hasNextMediaItem()) player.seekToNextMediaItem()
            else if (QueueItems.context(player.currentMediaItem).wave != null) {
                player.sendCustomCommand(androidx.media3.session.SessionCommand(QueueItems.EXTEND_WAVE, Bundle.EMPTY), Bundle.EMPTY)
            }
        }
    }

    private fun showPlayer() {
        if (currentTrack() == null || userId == null || playerPanel != null || videoPanel != null) return
        hideKeyboard()
        openPlayerOnConnect = false
        closingPlayer?.let { view -> view.animate().cancel(); root.removeView(view) }
        closingPlayer = null
        playerPanel = PlayerPanel(this).also {
            content.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            root.addView(it.view, FrameLayout.LayoutParams(-1, -1))
            it.applyInsets(systemInsets)
            it.view.translationY = root.height.toFloat()
            it.view.animate().translationY(0f).setDuration(250).start()
            it.update()
        }
        reattachToast()
        updateSystemBars()
    }

    internal fun closePlayer(animate: Boolean = false) {
        val panel = playerPanel ?: return
        panel.dismiss()
        playerPanel = null
        content.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        if (animate) {
            closingPlayer = panel.view
            panel.view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            panel.view.animate().translationY(root.height.toFloat()).setDuration(220).withEndAction {
                root.removeView(panel.view)
                if (closingPlayer === panel.view) closingPlayer = null
            }.start()
        } else root.removeView(panel.view)
        if (route.screen == Screen.HOME) waveHead?.resumeGlow()
        reattachToast()
        updateSystemBars()
    }

    private fun showVideo(card: Card) {
        closePlayer()
        closeVideo()
        controller?.pause()
        videoPanel = ClipPanel(this, card).also {
            content.visibility = View.INVISIBLE
            root.addView(it.view, FrameLayout.LayoutParams(-1, -1))
            it.applyInsets(systemInsets)
            it.load()
        }
        updateSystemBars()
    }

    internal fun closeVideo() {
        val panel = videoPanel ?: return
        panel.release()
        root.removeView(panel.view)
        videoPanel = null
        if (::content.isInitialized) content.visibility = View.VISIBLE
        if (route.screen == Screen.HOME) waveHead?.resumeGlow()
        updateSystemBars()
    }

    override fun onStop() {
        playbackMotion.stop()
        videoPanel?.pause()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        playbackMotion.start()
        checkForUpdates()
    }

    override fun onResume() {
        super.onResume()
        store.files.resumeForegroundQueue()
    }

    private fun updateSystemBars() {
        val light = ui.palette.light && userId != null && playerPanel == null && videoPanel == null
        @Suppress("DEPRECATION")
        window.statusBarColor = if (playerPanel == null && videoPanel == null) detailChrome else Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    internal fun setIcon(view: IconView, icon: Symbol, label: String, color: Int = Palette.text) {
        val animate = view.symbol != icon && (view.symbol == Symbol.PLAY || view.symbol == Symbol.PAUSE) &&
            (icon == Symbol.PLAY || icon == Symbol.PAUSE)
        val dirty = view.symbol != icon || view.tint != color
        view.symbol = icon
        view.tint = color
        if (view.contentDescription != label) {
            view.contentDescription = label
            view.tooltipText = label
        }
        if (animate) view.animateGlyph()
        if (dirty) view.invalidate()
    }

    override fun onEvents(player: Player, events: Player.Events) {
        updatePlaying()
        if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED)) {
            if (route.screen == Screen.HOME) waveHead?.bindHero()
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                if (route.screen != Screen.HOME) refreshRows()
            } else {
                val visible = list
                val currentId = currentTrack()?.id
                if (visible != null) for (index in 0 until visible.childCount) {
                    (visible.getChildAt(index).tag as? ItemRow)?.updatePlayback(currentId, player.isPlaying)
                }
            }
            playbackBindings.toList().forEach { it() }
        }
        if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED)) updateWaveContent()
    }

    private fun updatePlaying() {
        if (!::mini.isInitialized || userId == null) return
        val track = currentTrack()
        mini.visibility = if (track == null) View.GONE else View.VISIBLE
        if (track != null) {
            val player = controller
            fun adjacent(index: Int?): Track? =
                if (player == null || index == null || index !in 0 until player.mediaItemCount || index == player.currentMediaItemIndex) null
                else QueueItems.track(player.getMediaItemAt(index))
            miniPager.bind(adjacent(player?.previousMediaItemIndex), track, adjacent(player?.nextMediaItemIndex))
            val playing = controller?.let { it.isPlaying || it.playWhenReady && it.playbackState == Player.STATE_BUFFERING } == true
            setIcon(miniToggle, if (playing) Symbol.PAUSE else Symbol.PLAY, if (playing) "Пауза" else "Воспроизвести")
            miniToggle.alpha = if (controller?.playbackState == Player.STATE_BUFFERING) .55f else 1f
            val liked = track.id in store.likes.value
            setIcon(miniLike, if (liked) Symbol.HEART_FILLED else Symbol.HEART, if (liked) "Убрать из «Мне нравится»" else "Мне нравится",
                if (liked) Palette.accent else Palette.text)
            if (openPlayerOnConnect && playerPanel == null) showPlayer()
        } else {
            miniPager.bind(null, null, null)
            closePlayer()
        }
        playerPanel?.update()
        updateProgress()
    }

    private fun updateProgress() {
        if (!::miniProgress.isInitialized) return
        val player = controller ?: return
        val duration = player.duration
        miniProgress.progress = if (duration > 0) (1000 * player.currentPosition / duration).toInt().coerceIn(0, 1000) else 0
        playerPanel?.progress()
    }

    override fun onDestroy() {
        toastHide?.let { if (::root.isInitialized) root.removeCallbacks(it) }
        toastHide = null
        toastPill?.animate()?.cancel()
        toastPill = null
        videoPanel?.release()
        playerPanel?.dismiss()
        controller?.removeListener(this)
        controllerFuture?.let(MediaController::releaseFuture)
        super.onDestroy()
    }

    private inner class PageAdapter : BaseAdapter() {
        private var data = emptyList<Row>()
        fun replace(rows: List<Row>) { data = rows; notifyDataSetChanged() }
        override fun getCount() = data.size
        override fun getItem(position: Int) = data[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 4
        override fun getItemViewType(position: Int) = when (val row = data[position]) {
            is Row.Layout -> if (row.cache) android.widget.Adapter.IGNORE_ITEM_VIEW_TYPE else 0
            is Row.Music -> 1
            is Row.Entry -> 2
            Row.WaveHead -> 3
        }
        override fun getView(position: Int, convert: View?, parent: ViewGroup): View = when (val row = data[position]) {
            is Row.Layout -> row.obtain(parent)
            is Row.Music -> ((convert?.tag as? ItemRow) ?: ItemRow(track = true)).apply { bind(row) }.view
            is Row.Entry -> ((convert?.tag as? ItemRow) ?: ItemRow(track = false)).apply { bind(row) }.view
            Row.WaveHead -> (
                (convert?.tag as? HomeWaveHead)
                    ?: cachedHomeWaveHead?.takeIf { it.view.parent !== parent }?.also {
                        (it.view.parent as? ViewGroup)?.removeView(it.view)
                    }
                    ?: HomeWaveHead().also {
                        it.view.tag = it
                        cachedHomeWaveHead = it
                    }
            ).also { head ->
                waveHead = head
                head.bindHero()
                head.bindWheel()
                updateWordCard()
            }.view
        }
    }

    private inner class ItemRow(track: Boolean) {
        private val art = ui.art(null, 200, corner = if (track) 8 else 10)
        private val number = ui.text("", 16, ui.palette.muted).apply {
            gravity = Gravity.CENTER
            fontFeatureSettings = "tnum"
        }
        private val selection = IconView(this@MainActivity, Symbol.CIRCLE, ui.palette.muted).apply { iconSize = 23; visibility = View.GONE }
        private val playback = PlayingBars(this@MainActivity, ui.palette.accentText)
        private val overlay = FrameLayout(this@MainActivity).apply {
            addView(playback, FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.CENTER))
            visibility = View.GONE
        }
        private val leading = FrameLayout(this@MainActivity).apply {
            addView(art, FrameLayout.LayoutParams(-1, -1))
            addView(number, FrameLayout.LayoutParams(-1, -1))
            addView(selection, FrameLayout.LayoutParams(-1, -1))
            addView(this@ItemRow.overlay, FrameLayout.LayoutParams(-1, -1))
            number.visibility = View.GONE
        }
        private val title = TrackTitle(ui, 16)
        private val sub = ui.line("", 14, ui.palette.muted)
        private val historySource = ui.line("", 12, ui.palette.muted).apply { visibility = View.GONE }
        private val state = ui.line("", 13, ui.palette.muted).apply { fontFeatureSettings = "tnum" }
        private val favorite = IconView(this@MainActivity, Symbol.HEART_FILLED, ui.palette.accentText).apply { iconSize = 13; visibility = View.GONE }
        private val downloadRing = ProgressRingView(this@MainActivity, ui.palette.lifted, ui.palette.accent).apply { visibility = View.GONE }
        private val downloaded = IconView(this@MainActivity, Symbol.DOWNLOAD_CIRCLE, ui.palette.accentText).apply { iconSize = 12; visibility = View.GONE }
        private val more = ui.icon(Symbol.MORE, "Ещё", ui.palette.muted) {}.apply {
            iconSize = 18
            setOnClickListener { menu?.invoke(it) }
        }
        private var menu: ((View) -> Unit)? = null
        private var boundId: String? = null
        val view = ui.row().apply {
            tag = this@ItemRow
            minimumHeight = ui.dp(if (track) 60 else 64)
            setPadding(ui.dp(16), ui.dp(5), if (track) ui.dp(6) else ui.dp(16), ui.dp(5))
            background = ui.touch(showSelection = track)
            val size = if (track) 50 else 56
            addView(leading, LinearLayout.LayoutParams(ui.dp(size), ui.dp(size)))
            addView(ui.column().apply {
                setPadding(ui.dp(12), 0, ui.dp(4), 0)
                addView(title, ui.full())
                addView(ui.space(3))
                addView(ui.row().apply {
                    if (track) addView(downloaded, LinearLayout.LayoutParams(ui.dp(13), ui.dp(13)).apply { marginEnd = ui.dp(5) })
                    addView(sub, ui.weight())
                }, ui.full())
                if (track) addView(historySource, ui.full())
            }, ui.weight())
            if (track) addView(favorite, LinearLayout.LayoutParams(ui.dp(22), ui.dp(27)))
            if (track) addView(downloadRing, LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)).apply {
                marginStart = ui.dp(4)
                marginEnd = ui.dp(4)
            })
            addView(state)
            if (track) addView(more, LinearLayout.LayoutParams(ui.dp(36), ui.dp(44)))
            isFocusable = true
            if (track) observePlayback { playback.running = boundId == currentTrack()?.id && controller?.isPlaying == true }
        }
        @android.annotation.SuppressLint("ClickableViewAccessibility")
        private fun bindReorderHandle(index: Int?) {
            setIcon(more, if (index == null) Symbol.MORE else Symbol.REORDER,
                if (index == null) "Ещё" else "Переместить «${title.label.text}»", ui.palette.muted)
            if (index == null) {
                more.setOnTouchListener(null)
                return
            }
            val slop = ViewConfiguration.get(this@MainActivity).scaledTouchSlop
            var start = 0f
            var moved = false
            more.setOnTouchListener { handle, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        start = event.rawY
                        moved = false
                        handle.parent.requestDisallowInterceptTouchEvent(true)
                        handle.isPressed = true
                    }
                    MotionEvent.ACTION_MOVE -> if (!moved && kotlin.math.abs(event.rawY - start) > slop) {
                        moved = true
                        handle.isPressed = false
                        val trackList = list as? PlaylistTrackList
                        val last = details?.tracks?.lastIndex ?: index
                        if (reorderJob?.isActive == true) toast("Сохраняется порядок треков")
                        else if (trackList?.lift(view, event.rawX, index, last, ::movePlaylistTrack) == true) {
                            handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }
                        handle.parent.requestDisallowInterceptTouchEvent(false)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        handle.isPressed = false
                        handle.parent.requestDisallowInterceptTouchEvent(false)
                        if (!moved && event.actionMasked == MotionEvent.ACTION_UP) handle.performClick()
                    }
                }
                true
            }
        }

        fun bind(row: Row.Music) {
            val track = row.track
            boundId = track.id
            art.bind(track.cover, 200)
            title.bind(track)
            val playing = currentTrack()?.id == track.id
            val checked = selecting && track.id in selectedTracks
            number.text = row.number?.toString().orEmpty()
            number.visibility = if (row.number != null && !selecting && !playing) View.VISIBLE else View.GONE
            selection.visibility = if (selecting) View.VISIBLE else View.GONE
            setIcon(selection, if (checked) Symbol.CHECK_CIRCLE else Symbol.CIRCLE, if (checked) "Выбрано" else "Не выбрано",
                if (checked) ui.palette.accentText else ui.palette.muted)
            art.visibility = if (row.number != null || selecting) View.GONE else View.VISIBLE
            overlay.visibility = if (playing && !selecting) View.VISIBLE else View.GONE
            overlay.background = if (row.number == null) ui.shape(0x99000000.toInt(), 8) else null
            playback.running = playing && controller?.isPlaying == true
            leading.layoutParams = LinearLayout.LayoutParams(ui.dp(if (row.number == null || selecting) 50 else 30), ui.dp(50))
            val saved = store.files.has(track)
            val unavailable = track.unavailable && !store.files.hasReplacement(track) && !saved
            val disliked = route.screen != Screen.DISLIKES && track.id in store.dislikes.value
            view.alpha = if (unavailable || disliked) .4f else 1f
            title.label.setTextColor(when {
                playing -> ui.palette.accentText; unavailable -> ui.palette.muted; else -> ui.palette.text
            })
            sub.text = track.artistText.ifBlank { track.album?.title.orEmpty() }
            historySource.text = track.historySource.orEmpty()
            historySource.visibility = if (route.screen == Screen.HISTORY && historySource.text.isNotBlank()) View.VISIBLE else View.GONE
            downloaded.visibility = if (saved) View.VISIBLE else View.GONE
            updateDownloadStatus(store.files.status.value)
            state.text = if (row.number != null && track.duration > 0) timeLabel(track.duration) else ""
            more.visibility = if (selecting) View.INVISIBLE else View.VISIBLE
            val index = row.reorderIndex
            menu = if (index == null) row.menu else { anchor -> reorderActions(anchor, index) }
            bindReorderHandle(index)
            view.isSelected = checked
            if (index == null) {
                view.setOnClickListener { if (selecting) toggleSelected(track) else row.play() }
                view.setOnLongClickListener { if (selecting) toggleSelected(track) else row.menu(it); true }
            } else {
                view.setOnClickListener(null)
                view.setOnLongClickListener(null)
                view.isClickable = false
                view.isLongClickable = false
            }
        }

        fun updatePlayback(currentId: String?, isPlaying: Boolean) {
            playback.running = boundId != null && boundId == currentId && isPlaying
        }

        fun updateDownloadStatus(states: Map<String, DownloadStatus>) {
            val id = boundId ?: return
            val percent = states[id]?.percent
            if (percent != null) {
                favorite.visibility = View.GONE
                downloadRing.visibility = View.VISIBLE
                downloadRing.progress = percent / 100f
            } else {
                downloadRing.visibility = View.GONE
                favorite.visibility = if (id in store.likes.value) View.VISIBLE else View.GONE
            }
        }
        fun bind(row: Row.Entry) {
            art.bind(row.card.cover, 100)
            title.label.text = row.card.title
            sub.text = row.card.subtitle.ifBlank { row.card.kind.label }
            state.text = ""
            view.setOnClickListener { row.open() }
            view.setOnLongClickListener { cardMenu(row.card, it); true }
        }
    }

    companion object {
        private const val DOWNLOAD_PROGRESS = "download-progress"
        internal const val OPEN_PLAYER = "music.tune.android.OPEN_PLAYER"
        internal const val OPEN_DOWNLOADS = "music.tune.android.OPEN_DOWNLOADS"
        private val SHORTCUTS = setOf("music.tune.android.SHORTCUT_WAVE", "music.tune.android.SHORTCUT_SEARCH", "music.tune.android.SHORTCUT_LIKES", OPEN_DOWNLOADS)
        private const val MY_WAVE = "user:onyourwave"
        private val ID = Regex("[0-9]+")
        private val COLLECTION_REFRESH_PAGES = setOf(Screen.DETAILS, Screen.LIKES, Screen.DISLIKES,
            Screen.HISTORY, Screen.ALBUMS, Screen.ARTISTS, Screen.PODCASTS, Screen.BOOKS)
        private val REFRESHABLE_SCREENS = setOf(
            Screen.HOME, Screen.LIBRARY, Screen.CONCERTS, Screen.HIDDEN_ARTISTS,
            Screen.LIKES, Screen.DISLIKES, Screen.HISTORY,
            Screen.DETAILS, Screen.ARTIST_TRACKS, Screen.ARTIST_COLLECTION, Screen.ARTIST_WAVE,
            Screen.STATIONS, Screen.WAVE_ROOMS, Screen.CLIPS, Screen.KIDS,
            Screen.ALBUMS, Screen.ARTISTS, Screen.PODCASTS, Screen.BOOKS
        )
        private val FAVORITE = View.generateViewId()
        private val DETAIL_HEADER_TITLE = View.generateViewId()
        private val CONCERT_CITY_BUTTON = View.generateViewId()
        private val ROOM_ID = Regex("[a-zA-Z0-9_-]{1,150}")
        private val SHARED_LINK = Regex("""https://music\.yandex\.(?:ru|com|kz)/[^\s<>"»]+""")
        private val TUNE_LINK = Regex("""tune://[^\s<>"»]+""")
        private val FILTERS = listOf("Всё" to "track,album,artist,playlist,podcast,podcast_episode", "Треки" to "track",
            "Исполнители" to "artist", "Альбомы" to "album", "Плейлисты" to "playlist",
            "Подкасты" to "podcast", "По тексту" to "lyrics")
    }
}
