package app.enpl.dictionary

import android.app.Activity
import android.app.SearchManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

abstract class DictionaryActivity : Activity(), TextToSpeech.OnInitListener {
    companion object {
        private const val PREFS = "settings"
        private const val PREF_DARK = "dark"
        private const val PREF_THEME_SET = "theme_set"
        private const val PREF_TREE_URI = "dictionary_tree_uri"
        private const val REQ_FOLDER = 4107
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var queryBox: EditText
    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private var catalog: DictionaryCatalog? = null
    private var tts: TextToSpeech? = null
    private var currentWord = ""
    private var dark = false

    protected abstract fun isPopup(): Boolean

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        dark = resolveDarkMode()
        if (isPopup()) {
            setFinishOnTouchOutside(true)
            val w: Window = window
            w.setDimAmount(0.35f)
        }
        buildUi()
        tts = TextToSpeech(this, this)
        loadStoredFolderThenIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun resolveDarkMode(): Boolean {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (p.getBoolean(PREF_THEME_SET, false)) return p.getBoolean(PREF_DARK, true)
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return night == Configuration.UI_MODE_NIGHT_YES
    }

    private fun dp(n: Int) = Math.round(n * resources.displayMetrics.density)

    private fun button(text: String) = Button(this).apply {
        this.text = text
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(8), 0, dp(8), 0)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(6))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        queryBox = EditText(this).apply {
            setSingleLine(true)
            hint = "English word or phrase"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        bar.addView(queryBox, LinearLayout.LayoutParams(0, dp(48), 1f))

        val search = button("Search")
        val folder = button("Folder")
        val uk = button("UK")
        val us = button("US")
        val theme = button("◐")
        listOf(search, folder, uk, us, theme).forEach {
            bar.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        progress = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        web = WebView(this).apply {
            setBackgroundColor(if (dark) Color.rgb(16, 18, 20) else Color.WHITE)
            settings.javaScriptEnabled = false
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.defaultFontSize = 18
            settings.textZoom = 100
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                    handleUrl(request?.url)

                @Deprecated("Deprecated in Android API")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                    handleUrl(url?.let(Uri::parse))
            }
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        search.setOnClickListener { lookup(queryBox.text.toString()) }
        folder.setOnClickListener { chooseDictionaryFolder() }
        uk.setOnClickListener { speak(Locale.UK) }
        us.setOnClickListener { speak(Locale.US) }
        theme.setOnClickListener { toggleTheme() }
        queryBox.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                lookup(queryBox.text.toString())
                true
            } else false
        }
        applyChromeTheme(root)
    }

    private fun applyChromeTheme(root: View) {
        val bg = if (dark) Color.rgb(16, 18, 20) else Color.rgb(250, 250, 250)
        val fg = if (dark) Color.rgb(235, 235, 235) else Color.rgb(25, 25, 25)
        root.setBackgroundColor(bg)
        queryBox.setTextColor(fg)
        queryBox.setHintTextColor(if (dark) Color.rgb(150, 150, 150) else Color.rgb(105, 105, 105))
        web.setBackgroundColor(bg)
    }

    private fun toggleTheme() {
        dark = !dark
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(PREF_THEME_SET, true)
            .putBoolean(PREF_DARK, dark)
            .apply()
        recreate()
    }

    private fun chooseDictionaryFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(i, REQ_FOLDER)
    }

    @Deprecated("Deprecated in Android API; retained for minSdk 23 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FOLDER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val takeFlags = (data.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        try {
            contentResolver.takePersistableUriPermission(uri, takeFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some providers grant access without persistable permissions.
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_TREE_URI, uri.toString()).apply()
        openFolder(uri, intent)
    }

    private fun loadStoredFolderThenIntent(intent: Intent?) {
        val raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_TREE_URI, null)
        if (raw.isNullOrBlank()) {
            progress.visibility = View.GONE
            showMessage(
                "No dictionary folder selected.<br><br>Tap <B>Folder</B> and choose a folder containing " +
                    "StarDict files: <code>.ifo</code> + <code>.idx</code> + <code>.dict</code>. " +
                    "The folder may contain several dictionaries."
            )
            return
        }
        openFolder(Uri.parse(raw), intent)
    }

    private fun openFolder(uri: Uri, pendingIntent: Intent?) {
        progress.visibility = View.VISIBLE
        showMessage("Scanning dictionary folder…")
        executor.execute {
            try {
                val newCatalog = DictionaryCatalog.open(applicationContext, uri)
                val old = catalog
                catalog = newCatalog
                old?.close()
                runOnUiThread {
                    progress.visibility = View.GONE
                    Toast.makeText(
                        this,
                        "${newCatalog.dictionaryCount} dictionaries, ${newCatalog.entryCount} entries",
                        Toast.LENGTH_SHORT
                    ).show()
                    handleIntent(pendingIntent)
                    if (queryFromIntent(pendingIntent).isNullOrBlank() && currentWord.isBlank()) {
                        showMessage(
                            "Ready: <B>${newCatalog.dictionaryCount}</B> dictionaries, " +
                                "<B>${newCatalog.entryCount}</B> indexed entries.<br>Type a word or select one in your reader."
                        )
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    showMessage("Could not open dictionary folder: ${esc(e.message ?: e.javaClass.simpleName)}")
                }
            }
        }
    }

    private fun handleUrl(uri: Uri?): Boolean {
        if (uri == null) return false
        if (uri.scheme.equals("bword", ignoreCase = true)) {
            var q = uri.schemeSpecificPart
            if (q.startsWith("//")) q = q.substring(2)
            q = runCatching { URLDecoder.decode(q, StandardCharsets.UTF_8.name()) }.getOrDefault(q)
            lookup(q)
            return true
        }
        return true
    }

    private fun queryFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.takeIf { it.isNotEmpty() }?.let { return it.toString() }
        for (key in arrayOf("keyword", SearchManager.QUERY, "EXTRA_QUERY")) {
            intent.getStringExtra(key)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotEmpty() }?.let { return it.toString() }
        return intent.data?.lastPathSegment
    }

    private fun handleIntent(intent: Intent?) {
        if (catalog == null) return
        val q = queryFromIntent(intent)
        if (!q.isNullOrBlank()) lookup(q)
    }

    private fun cleanQuery(raw: String?): String {
        if (raw == null) return ""
        return raw.replace('\n', ' ').replace('\r', ' ').trim()
            .replace(Regex("^[\\p{Punct}“”‘’]+|[\\p{Punct}“”‘’]+$"), "")
            .replace(Regex("\\s+"), " ")
    }

    private fun lookup(raw: String) {
        val active = catalog ?: run {
            showMessage("Select a dictionary folder first.")
            return
        }
        val q = cleanQuery(raw)
        if (q.isEmpty()) return
        queryBox.setText(q)
        queryBox.setSelection(q.length)
        progress.visibility = View.VISIBLE
        executor.execute {
            try {
                val results = active.lookup(q)
                if (results.isNotEmpty()) {
                    runOnUiThread {
                        progress.visibility = View.GONE
                        currentWord = results.first().word
                        queryBox.setText(currentWord)
                        renderResults(results)
                    }
                } else {
                    val suggestions = active.suggest(q, 24)
                    runOnUiThread {
                        progress.visibility = View.GONE
                        currentWord = q
                        renderSuggestions(q, suggestions)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    showMessage("Read error: ${esc(e.message ?: e.javaClass.simpleName)}")
                }
            }
        }
    }

    private fun css(): String = if (dark) {
        "html,body{background:#101214!important;color:#e9e9e9!important;}" +
            "body{font-family:sans-serif;line-height:1.38;margin:10px;font-size:18px;}" +
            ".dict{margin:0 0 16px 0}.dict-title{font-weight:700;font-size:14px;opacity:.72;margin:0 0 6px 0;}" +
            "a{color:#8ab4ff!important;text-decoration:none;}" +
            "font[color=\"#0000E0\"],font[color=\"#0000e0\"]{color:#86a8ff!important;}" +
            "font[color=\"#E00000\"],font[color=\"#e00000\"]{color:#ff8585!important;}" +
            "font[color=\"#008000\"]{color:#65d47b!important;}" +
            "font[color=\"#736324\"]{color:#d7c77b!important;}"
    } else {
        "html,body{background:#fafafa!important;color:#171717!important;}" +
            "body{font-family:sans-serif;line-height:1.38;margin:10px;font-size:18px;}" +
            ".dict{margin:0 0 16px 0}.dict-title{font-weight:700;font-size:14px;color:#666;margin:0 0 6px 0;}" +
            "a{text-decoration:none;}"
    }

    private fun renderResults(results: List<DictionaryCatalog.Result>) {
        val body = buildString {
            for (result in results) {
                append("<div class='dict'><div class='dict-title'>")
                append(esc(result.dictionary))
                append("</div>")
                append(result.html)
                append("</div>")
            }
        }
        renderEntry(body)
    }

    private fun renderEntry(body: String) {
        val page = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<style>${css()}</style></head><body>$body</body></html>"
        web.loadDataWithBaseURL("https://local.dictionary/", page, "text/html", "UTF-8", null)
    }

    private fun renderSuggestions(q: String, suggestions: List<String>) {
        val body = buildString {
            append("<B>No exact entry for ‘${esc(q)}’.</B><br><br>")
            if (suggestions.isEmpty()) append("No prefix matches.")
            else {
                append("Nearby entries:<br>")
                suggestions.forEach { s ->
                    append("<a href='bword://${Uri.encode(s)}'>${esc(s)}</a><br>")
                }
            }
        }
        renderEntry(body)
    }

    private fun showMessage(msg: String) = renderEntry("<div>$msg</div>")

    private fun esc(s: String): String = s.replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun speak(locale: Locale) {
        if (currentWord.isBlank()) return
        val engine = tts ?: return
        val status = engine.setLanguage(locale)
        if (status == TextToSpeech.LANG_MISSING_DATA || status == TextToSpeech.LANG_NOT_SUPPORTED) {
            Toast.makeText(this, "Offline voice not installed for $locale", Toast.LENGTH_SHORT).show()
            return
        }
        engine.speak(currentWord, TextToSpeech.QUEUE_FLUSH, null, "word")
    }

    override fun onInit(status: Int) = Unit

    override fun onDestroy() {
        super.onDestroy()
        catalog?.close()
        catalog = null
        tts?.stop()
        tts?.shutdown()
        executor.shutdownNow()
    }
}
