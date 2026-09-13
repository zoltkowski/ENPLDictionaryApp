package app.enpl.dictionary

import android.app.Activity
import android.app.SearchManager
import android.content.Intent
import android.app.UiModeManager
import android.content.res.Configuration
import android.os.Build
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

abstract class DictionaryActivity : Activity(), TextToSpeech.OnInitListener {
    companion object {
        private const val PREFS = "settings"
        private const val PREF_DARK = "dark"
        private const val PREF_THEME_SET = "theme_set"
        private const val PREF_TREE_URI = "dictionary_tree_uri"
        private const val REQ_FOLDER = 4107
        private const val REQ_DICT_SETTINGS = 4108
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var root: LinearLayout
    private lateinit var queryBox: EditText
    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private lateinit var suggestionHost: LinearLayout
    private lateinit var ukLabel: TextView
    private lateinit var usLabel: TextView

    private var catalog: DictionaryCatalog? = null
    private var tts: TextToSpeech? = null
    private var currentWord = ""
    private var dark = false
    private var suppressWatcher = false
    private val suggestGeneration = AtomicInteger(0)
    private lateinit var history: HistoryStore

    protected abstract fun isPopup(): Boolean

    override fun onCreate(state: Bundle?) {
        // Keep Android's per-app day/night state synchronized with our saved setting.
        // On Android 12+ this controls the system launch/splash background on the NEXT cold start.
        syncPlatformNightMode()
        super.onCreate(state)
        dark = resolveDarkMode()
        val bg = if (dark) Color.rgb(16, 18, 20) else Color.rgb(250, 250, 250)
        window.setBackgroundDrawable(ColorDrawable(bg))
        history = HistoryStore(this)

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

    private fun syncPlatformNightMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!p.getBoolean(PREF_THEME_SET, false)) return
        val manager = getSystemService(UiModeManager::class.java) ?: return
        val mode = if (p.getBoolean(PREF_DARK, true)) {
            UiModeManager.MODE_NIGHT_YES
        } else {
            UiModeManager.MODE_NIGHT_NO
        }
        if (manager.nightMode != mode) manager.setApplicationNightMode(mode)
    }

    private fun dp(n: Int) = Math.round(n * resources.displayMetrics.density)

    private fun iconButton(drawableId: Int, description: String) = ImageButton(this).apply {
        setImageResource(drawableId)
        contentDescription = description
        background = ColorDrawable(Color.TRANSPARENT)
        setPadding(dp(9), dp(7), dp(9), dp(7))
        minimumWidth = 0
        minimumHeight = 0
    }

    private fun speakerControl(
        button: ImageButton,
        caption: String,
        assignLabel: (TextView) -> Unit
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(button, LinearLayout.LayoutParams(dp(44), dp(34)))
        val label = TextView(this@DictionaryActivity).apply {
            text = caption
            textSize = 9f
            gravity = Gravity.CENTER
            includeFontPadding = false
            alpha = 0.72f
        }
        assignLabel(label)
        addView(label, LinearLayout.LayoutParams(dp(44), dp(13)))
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(6))
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val menu = iconButton(android.R.drawable.ic_menu_more, "Menu")
        bar.addView(menu, LinearLayout.LayoutParams(dp(48), dp(48)))

        queryBox = EditText(this).apply {
            setSingleLine(true)
            hint = "English word or phrase"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        bar.addView(queryBox, LinearLayout.LayoutParams(0, dp(48), 1f))

        val search = iconButton(android.R.drawable.ic_menu_search, "Search")
        val uk = iconButton(android.R.drawable.ic_btn_speak_now, "British pronunciation")
        val us = iconButton(android.R.drawable.ic_btn_speak_now, "American pronunciation")

        bar.addView(search, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(
            speakerControl(uk, "UK") { ukLabel = it },
            LinearLayout.LayoutParams(dp(44), dp(48))
        )
        bar.addView(
            speakerControl(us, "US") { usLabel = it },
            LinearLayout.LayoutParams(dp(44), dp(48))
        )
        root.addView(bar)

        // Explicit suggestion panel: easier to see/control than a ListView and
        // guaranteed to remain above the WebView.
        suggestionHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            elevation = dp(3).toFloat()
        }
        root.addView(
            suggestionHost,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        progress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(
            progress,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
        )

        web = WebView(this).apply {
            setBackgroundColor(if (dark) Color.rgb(16, 18, 20) else Color.rgb(250, 250, 250))
            settings.javaScriptEnabled = false
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.defaultFontSize = 18
            settings.textZoom = 100
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean = handleUrl(request?.url)

                @Deprecated("Deprecated in Android API")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                    handleUrl(url?.let(Uri::parse))
            }
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        menu.setOnClickListener { showMenu(menu) }
        search.setOnClickListener { lookup(queryBox.text.toString(), recordHistory = true) }
        uk.setOnClickListener { speak(Locale.UK) }
        us.setOnClickListener { speak(Locale.US) }

        queryBox.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER
            ) {
                lookup(queryBox.text.toString(), recordHistory = true)
                true
            } else false
        }

        queryBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!suppressWatcher) requestSuggestions(s?.toString().orEmpty())
            }
        })

        applyChromeTheme()
    }

    private fun applyChromeTheme() {
        val bg = if (dark) Color.rgb(16, 18, 20) else Color.rgb(250, 250, 250)
        val fg = if (dark) Color.rgb(235, 235, 235) else Color.rgb(25, 25, 25)
        val secondary = if (dark) Color.rgb(175, 175, 175) else Color.rgb(90, 90, 90)

        root.setBackgroundColor(bg)
        queryBox.setTextColor(fg)
        queryBox.setHintTextColor(if (dark) Color.rgb(145, 145, 145) else Color.rgb(110, 110, 110))
        ukLabel.setTextColor(secondary)
        usLabel.setTextColor(secondary)
        suggestionHost.setBackgroundColor(if (dark) Color.rgb(28, 30, 33) else Color.WHITE)
        web.setBackgroundColor(bg)
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("History")
            menu.add("Dictionaries")
            menu.add("Choose dictionary folder")
            menu.add(if (dark) "Light mode" else "Dark mode")
            setOnMenuItemClickListener {
                when (it.title.toString()) {
                    "History" -> startActivity(
                        Intent(this@DictionaryActivity, HistoryActivity::class.java)
                    )
                    "Dictionaries" -> startActivityForResult(
                        Intent(this@DictionaryActivity, DictionarySettingsActivity::class.java),
                        REQ_DICT_SETTINGS
                    )
                    "Choose dictionary folder" -> chooseDictionaryFolder()
                    "Light mode", "Dark mode" -> toggleTheme()
                }
                true
            }
        }.show()
    }

    private fun toggleTheme() {
        dark = !dark
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(PREF_THEME_SET, true)
            .putBoolean(PREF_DARK, dark)
            .apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(UiModeManager::class.java)
            manager?.setApplicationNightMode(
                if (dark) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
            )
        }
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

        if (requestCode == REQ_DICT_SETTINGS) {
            val raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_TREE_URI, null)
            if (!raw.isNullOrBlank()) openFolder(Uri.parse(raw), intent)
            return
        }

        if (requestCode != REQ_FOLDER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val takeFlags = data.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                takeFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit().putString(PREF_TREE_URI, uri.toString()).apply()
        openFolder(uri, intent)
    }

    private fun loadStoredFolderThenIntent(intent: Intent?) {
        val raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_TREE_URI, null)
        if (raw.isNullOrBlank()) {
            showMessage(
                "No dictionary folder selected.<br><br>" +
                    "Open <B>☰ → Choose dictionary folder</B>."
            )
            return
        }
        openFolder(Uri.parse(raw), intent)
    }

    private fun openFolder(uri: Uri, pendingIntent: Intent?) {
        progress.visibility = View.VISIBLE

        executor.execute {
            try {
                val newCatalog = DictionaryCatalog.open(applicationContext, uri)
                val old = catalog
                catalog = newCatalog
                old?.close()

                runOnUiThread {
                    progress.visibility = View.GONE
                    // Deliberately no toast/status message here.
                    handleIntent(pendingIntent)

                    // If opened manually with no query, keep the current page as-is.
                    // On first app start with no query, leave the neutral blank surface.
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    showMessage(
                        "Could not open dictionary folder: " +
                            esc(e.message ?: e.javaClass.simpleName)
                    )
                }
            }
        }
    }

    private fun requestSuggestions(raw: String) {
        val active = catalog ?: return
        val q = cleanQuery(raw)
        if (q.isBlank()) {
            hideSuggestions()
            return
        }

        val generation = suggestGeneration.incrementAndGet()
        executor.execute {
            val list = runCatching { active.suggest(q, 24) }.getOrDefault(emptyList())
            runOnUiThread {
                if (generation != suggestGeneration.get()) return@runOnUiThread
                renderLiveSuggestions(list)
            }
        }
    }

    private fun renderLiveSuggestions(list: List<String>) {
        suggestionHost.removeAllViews()

        if (list.isEmpty()) {
            suggestionHost.visibility = View.GONE
            return
        }

        val fg = if (dark) Color.rgb(235, 235, 235) else Color.rgb(25, 25, 25)
        val divider = if (dark) Color.rgb(55, 57, 60) else Color.rgb(225, 225, 225)

        for (word in list.take(8)) {
            val row = TextView(this).apply {
                text = word
                textSize = 17f
                setTextColor(fg)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
                setBackgroundColor(if (dark) Color.rgb(28, 30, 33) else Color.WHITE)
                setOnClickListener {
                    hideSuggestions()
                    lookup(word, recordHistory = true)
                }
            }
            suggestionHost.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(42)
                )
            )
            suggestionHost.addView(View(this).apply {
                setBackgroundColor(divider)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        }

        suggestionHost.visibility = View.VISIBLE
        suggestionHost.bringToFront()
    }

    private fun hideSuggestions() {
        suggestGeneration.incrementAndGet()
        suggestionHost.removeAllViews()
        suggestionHost.visibility = View.GONE
    }

    private fun handleUrl(uri: Uri?): Boolean {
        if (uri == null) return false
        if (uri.scheme.equals("bword", ignoreCase = true)) {
            var q = uri.schemeSpecificPart
            if (q.startsWith("//")) q = q.substring(2)
            q = runCatching {
                URLDecoder.decode(q, StandardCharsets.UTF_8.name())
            }.getOrDefault(q)
            lookup(q, recordHistory = true)
            return true
        }
        return true
    }

    private fun queryFromIntent(intent: Intent?): String? {
        if (intent == null) return null

        intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it.toString() }

        for (key in arrayOf("keyword", SearchManager.QUERY, "EXTRA_QUERY")) {
            intent.getStringExtra(key)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it.toString() }

        return intent.data?.lastPathSegment
    }

    private fun handleIntent(intent: Intent?) {
        if (catalog == null) return
        val q = queryFromIntent(intent)
        if (!q.isNullOrBlank()) lookup(q, recordHistory = true)
    }

    private fun cleanQuery(raw: String?): String {
        if (raw == null) return ""
        return raw.replace('\n', ' ').replace('\r', ' ').trim()
            .replace(Regex("^[\\p{Punct}“”‘’]+|[\\p{Punct}“”‘’]+$"), "")
            .replace(Regex("\\s+"), " ")
    }

    private fun setQueryText(text: String) {
        suppressWatcher = true
        try {
            queryBox.setText(text)
            queryBox.setSelection(text.length)
        } finally {
            suppressWatcher = false
        }
    }

    private fun lookup(raw: String, recordHistory: Boolean) {
        val active = catalog ?: return
        val q = cleanQuery(raw)
        if (q.isEmpty()) return

        hideSuggestions()
        setQueryText(q)
        progress.visibility = View.VISIBLE

        executor.execute {
            try {
                val results = active.lookup(q)
                if (recordHistory) history.record(q)

                if (results.isNotEmpty()) {
                    runOnUiThread {
                        progress.visibility = View.GONE
                        currentWord = results.first().word
                        setQueryText(currentWord)
                        renderResults(results)
                    }
                } else {
                    val nearby = active.suggest(q, 24)
                    runOnUiThread {
                        progress.visibility = View.GONE
                        currentWord = q
                        renderSuggestions(q, nearby)
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
        val page =
            "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<style>${css()}</style></head><body>$body</body></html>"
        web.loadDataWithBaseURL("https://local.dictionary/", page, "text/html", "UTF-8", null)
    }

    private fun renderSuggestions(q: String, list: List<String>) {
        val body = buildString {
            append("<B>No exact entry for ‘${esc(q)}’.</B><br><br>")
            if (list.isEmpty()) append("No prefix matches.")
            else {
                append("Nearby entries:<br>")
                for (s in list) {
                    append("<a href='bword://${Uri.encode(s)}'>${esc(s)}</a><br>")
                }
            }
        }
        renderEntry(body)
    }

    private fun showMessage(msg: String) = renderEntry("<div>$msg</div>")

    private fun esc(s: String): String = s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun speak(locale: Locale) {
        if (currentWord.isBlank()) return
        val engine = tts ?: return
        val status = engine.setLanguage(locale)
        if (status == TextToSpeech.LANG_MISSING_DATA ||
            status == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            Toast.makeText(
                this,
                "Offline voice not installed for $locale",
                Toast.LENGTH_SHORT
            ).show()
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
        history.close()
        executor.shutdownNow()
    }
}
