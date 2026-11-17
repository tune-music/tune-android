package music.tune.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.UUID

class LoginActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private var web: WebView? = null
    private val addingAccount by lazy { intent.getBooleanExtra(ADD_ACCOUNT, false) }
    private var state = ""
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = FrameLayout(this).apply {
            setBackgroundColor(Palette.background)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
                WindowInsetsCompat.Type.mandatorySystemGestures())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (addingAccount && web != null) showLanding() else finish()
            }
        })
        if (addingAccount && savedInstanceState?.getBoolean("web") != true) showLanding() else showWeb()
    }

    private fun showLanding(failed: Boolean = false) {
        closeWeb()
        root.removeAllViews()
        root.addView(loginLanding(this, failed = failed, cancel = { finish() }) {
            if (web == null) {
                CookieManager.getInstance().removeAllCookies { if (!isFinishing && !isDestroyed && web == null) showWeb() }
            }
        }, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showWeb() {
        root.removeAllViews()
        state = UUID.randomUUID().toString()
        finished = false
        val ui = TuneUi(this)
        val page = ui.column().apply {
            addView(ui.row().apply {
                minimumHeight = ui.dp(48)
                addView(ui.text("Закрыть", 16).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    minHeight = ui.dp(48)
                    setPadding(ui.dp(16), ui.dp(8), 0, ui.dp(8))
                    background = ui.touch()
                    isFocusable = true
                    setOnClickListener { if (addingAccount) showLanding() else finish() }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.text("Вход в Яндекс", 17, bold = true).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(0, -2, 2f))
                addView(android.view.View(this@LoginActivity), LinearLayout.LayoutParams(0, 1, 1f))
            }, ui.full())
        }
        val browser = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true // Required by Yandex Passport.
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (request.isForMainFrame && uri.scheme == "music-application") {
                        if (finished) return true
                        finished = true
                        val token = oauthToken(uri, state)
                        if (token == null) {
                            view.post {
                                if (addingAccount) showLanding(failed = true)
                                else {
                                    setResult(Activity.RESULT_FIRST_USER)
                                    finish()
                                }
                            }
                        } else {
                            setResult(Activity.RESULT_OK, Intent().putExtra(TOKEN, token))
                            finish()
                        }
                        return true
                    }
                    if (!request.isForMainFrame) return uri.scheme !in setOf("https", "about")
                    val host = uri.host.orEmpty().lowercase(java.util.Locale.ROOT)
                    return uri.scheme != "https" || DOMAINS.none { host == it || host.endsWith(".$it") }
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame && !finished) {
                        Toast.makeText(this@LoginActivity, "Не удалось открыть Яндекс", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        web = browser
        page.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        val authorization = Uri.parse("https://oauth.yandex.ru/authorize").buildUpon()
            .appendQueryParameter("response_type", "token")
            .appendQueryParameter("display", "popup")
            .appendQueryParameter("scope", "music:content")
            .appendQueryParameter("scope", "music:read")
            .appendQueryParameter("scope", "music:write")
            .appendQueryParameter("client_id", "97fe03033fa34407ac9bcf91d5afed5b")
            .appendQueryParameter("redirect_uri", "music-application://desktop/oauth")
            .appendQueryParameter("state", state)
            .appendQueryParameter("origin", "music_desktop")
            .appendQueryParameter("language", "ru")
            .build()
        browser.loadUrl(authorization.toString())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("web", web != null)
        super.onSaveInstanceState(outState)
    }

    private fun closeWeb() {
        web?.let { browser ->
            (browser.parent as? LinearLayout)?.removeView(browser)
            browser.destroy()
        }
        web = null
    }

    override fun onDestroy() {
        closeWeb()
        super.onDestroy()
    }

    companion object {
        internal const val TOKEN = "music.tune.android.token"
        internal const val ADD_ACCOUNT = "music.tune.android.add_account"
        private val DOMAINS = listOf("yandex.ru", "yandex.com", "yandex.kz", "ya.ru")
    }
}

internal fun oauthToken(uri: Uri, expectedState: String): String? {
    if (uri.scheme != "music-application" || uri.host != "desktop" || uri.path != "/oauth") return null
    val fragment = uri.encodedFragment ?: return null
    val fields = Uri.parse("https://localhost/?$fragment")
    if (fields.getQueryParameters("state").singleOrNull() != expectedState) return null
    return fields.getQueryParameters("access_token").singleOrNull()?.takeIf { it.isNotBlank() }
}
