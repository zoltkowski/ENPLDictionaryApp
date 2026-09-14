package app.enpl.dictionary

import android.app.Activity
import android.app.SearchManager
import android.app.UiModeManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.AbsListView
import android.view.ActionMode
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
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

        private const val SUGGEST_PAGE_SIZE = 80
        private const val SUGGEST_VISIBLE_ROWS = 8
        private const val SUGGEST_PREFETCH_ROWS = 18
        private const val SUGGEST_DEBOUNCE_MS = 65L
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var root: LinearLayout
    private lateinit var queryBox: EditText
    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private lateinit var ukLabel: TextView
    private lateinit var usLabel: TextView

    private var suggestionPopup: PopupWindow? = null
    private var suggestionList: ListView? = null
    private var suggestionAdapter: BaseAdapter? = null
    private val suggestionItems = ArrayList<String>()
    private var suggestionPager: DictionaryCatalog.SuggestionPager? = null
    private var suggestionLoading = false
    private var suggestionHasMore = false
    private var suggestionPrefix = ""
    private val uiHandler = Handler(Looper.getMainLooper())
    private var suggestionRunnable: Runnable? = null
    private val suggestGeneration = AtomicInteger(0)

    private var catalog: DictionaryCatalog? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeakLocale: Locale? = null
    private var currentWord = ""
    private var dark = false
    private var suppressWatcher = false
    private lateinit var history: HistoryStore
    private var showingDictionarySetupMessage = false

    // External lookups hide the intermediate "empty UI -> query -> definition"
    // sequence. The complete UI is revealed only when the final WebView page is ready.
    private var deferInitialExternalPresentation = false
    private var revealAfterNextPage = false

    protected abstract fun isPopup(): Boolean

    override fun onCreate(state: Bundle?) {
        syncPlatformNightMode()
        super.onCreate(state)

        dark = resolveDarkMode()
        val bg = pageBackground()
        window.setBackgroundDrawable(ColorDrawable(bg))
        history = HistoryStore(this)

        val initialQuery = cleanQuery(queryFromIntent(intent))
        deferInitialExternalPresentation =
            initialQuery.isNotBlank() &&
                (this is ExternalLookupActivity || this is FloatingLookupActivity)

        buildUi()

        if (deferInitialExternalPresentation) {
            root.visibility = View.INVISIBLE
            queryBox.clearFocus()
            window.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            )
        }

        // TTS is intentionally lazy: binding the speech service is unnecessary work
        // on the latency-critical lookup path.
        loadStoredFolderThenIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deferInitialExternalPresentation = false
        root.visibility = View.VISIBLE
        handleIntent(intent)
    }

    private fun pageBackground(): Int =
        if (dark) Color.rgb(16, 18, 20) else Color.rgb(250, 250, 250)

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
        applySystemBarInsets(root)

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

        progress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(
            progress,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
        )

        web = WebView(this).apply {
            setBackgroundColor(pageBackground())
            settings.javaScriptEnabled = true
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

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (revealAfterNextPage) {
                        revealAfterNextPage = false
                        this@DictionaryActivity.progress.visibility = View.GONE
                        root.visibility = View.VISIBLE
                        queryBox.clearFocus()
                        web.requestFocus()
                    }
                }
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
        val bg = pageBackground()
        val fg = if (dark) Color.rgb(235, 235, 235) else Color.rgb(25, 25, 25)
        val secondary = if (dark) Color.rgb(175, 175, 175) else Color.rgb(90, 90, 90)

        root.setBackgroundColor(bg)
        queryBox.setTextColor(fg)
        queryBox.setHintTextColor(
            if (dark) Color.rgb(145, 145, 145) else Color.rgb(110, 110, 110)
        )
        ukLabel.setTextColor(secondary)
        usLabel.setTextColor(secondary)
        web.setBackgroundColor(bg)
    }

    private fun showMenu(anchor: View) {
        val menuBg = if (dark) Color.rgb(12, 12, 12) else Color.WHITE
        val menuFg = if (dark) Color.WHITE else Color.rgb(20, 20, 20)
        val divider = if (dark) Color.rgb(52, 52, 52) else Color.rgb(225, 225, 225)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(menuBg)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        lateinit var popup: PopupWindow

        fun addItem(label: String, action: () -> Unit) {
            val row = TextView(this).apply {
                text = label
                textSize = 17f
                setTextColor(menuFg)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(20), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    popup.dismiss()
                    action()
                }
            }
            content.addView(row, LinearLayout.LayoutParams(dp(250), dp(48)))
            content.addView(
                View(this).apply { setBackgroundColor(divider) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            )
        }

        addItem("History") {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        addItem("Dictionaries") {
            startActivityForResult(
                Intent(this, DictionarySettingsActivity::class.java),
                REQ_DICT_SETTINGS
            )
        }
        addItem(if (dark) "Light mode" else "Dark mode") {
            toggleTheme()
        }

        if (content.childCount > 0) content.removeViewAt(content.childCount - 1)

        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        }.getOrDefault("?")

        content.addView(
            TextView(this).apply {
                text = "v$versionName"
                textSize = 11f
                alpha = 0.55f
                gravity = Gravity.CENTER_HORIZONTAL
                setTextColor(menuFg)
                setPadding(dp(12), dp(8), dp(12), dp(6))
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        popup = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
            setBackgroundDrawable(ColorDrawable(menuBg))
        }
        popup.showAsDropDown(anchor, 0, -dp(4))
    }

    private fun toggleTheme() {
        dark = !dark
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(PREF_THEME_SET, true)
            .putBoolean(PREF_DARK, dark)
            .apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(UiModeManager::class.java)?.setApplicationNightMode(
                if (dark) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
            )
        }
        recreate()
    }

    private fun applySystemBarInsets(view: View) {
        val left = dp(8)
        val top = dp(7)
        val right = dp(8)
        val bottom = dp(6)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            view.setOnApplyWindowInsetsListener { v, insets ->
                val statusTop = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(WindowInsets.Type.statusBars()).top
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetTop
                }
                val navBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetBottom
                }
                v.setPadding(left, top + statusTop, right, bottom + navBottom)
                insets
            }
            view.requestApplyInsets()
        }
    }

    @Deprecated("Deprecated in Android API; retained for minSdk 23 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_DICT_SETTINGS) {
            val raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_TREE_URI, null)
            if (!raw.isNullOrBlank()) openFolder(Uri.parse(raw), null)
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
            showingDictionarySetupMessage = true
            showMessage(
                "<B>No dictionaries configured.</B><br><br>" +
                    "Open <B>☰ → Dictionaries</B>, choose a folder containing " +
                    "StarDict files (<B>.ifo + .idx + .dict</B>), then return here.",
                revealWhenReady = deferInitialExternalPresentation
            )
            return
        }
        openFolder(Uri.parse(raw), intent)
    }

    private fun openFolder(uri: Uri, pendingIntent: Intent?) {
        if (!deferInitialExternalPresentation) {
            progress.visibility = View.VISIBLE
        }

        executor.execute {
            try {
                val newCatalog = DictionaryCatalog.open(applicationContext, uri)
                val old = catalog
                catalog = newCatalog
                old?.close()

                runOnUiThread {
                    if (!deferInitialExternalPresentation) {
                        progress.visibility = View.GONE
                    }

                    if (showingDictionarySetupMessage) {
                        showingDictionarySetupMessage = false
                        renderEntry("")
                    }
                    handleIntent(pendingIntent)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    showMessage(
                        "Could not open dictionary folder: " +
                            esc(e.message ?: e.javaClass.simpleName),
                        revealWhenReady = deferInitialExternalPresentation
                    )
                }
            }
        }
    }

    // ---------- paged / infinite-scroll suggestions ----------

    private fun requestSuggestions(raw: String) {
        val active = catalog ?: return
        val q = cleanQuery(raw)

        suggestionRunnable?.let(uiHandler::removeCallbacks)
        suggestionRunnable = null

        if (q.isBlank()) {
            hideSuggestions()
            return
        }

        val generation = suggestGeneration.incrementAndGet()
        suggestionLoading = true

        val runnable = Runnable {
            executor.execute {
                val pager = runCatching { active.newSuggestionPager(q) }.getOrNull()
                val first = runCatching {
                    pager?.next(SUGGEST_PAGE_SIZE).orEmpty()
                }.getOrDefault(emptyList())
                val more = pager?.hasMore() == true

                runOnUiThread {
                    if (generation != suggestGeneration.get()) return@runOnUiThread
                    suggestionPager = pager
                    suggestionPrefix = q
                    suggestionLoading = false
                    suggestionHasMore = more
                    suggestionItems.clear()
                    suggestionItems.addAll(first)
                    suggestionAdapter?.notifyDataSetChanged()

                    if (first.isEmpty()) hideSuggestions()
                    else renderSuggestionPopup()
                }
            }
        }

        suggestionRunnable = runnable
        uiHandler.postDelayed(runnable, SUGGEST_DEBOUNCE_MS)
    }

    private fun loadNextSuggestionPage() {
        if (suggestionLoading || !suggestionHasMore) return
        val pager = suggestionPager ?: return
        val generation = suggestGeneration.get()

        suggestionLoading = true
        executor.execute {
            val page = runCatching { pager.next(SUGGEST_PAGE_SIZE) }.getOrDefault(emptyList())
            val more = pager.hasMore()

            runOnUiThread {
                if (generation != suggestGeneration.get()) return@runOnUiThread
                suggestionLoading = false
                suggestionHasMore = more
                if (page.isNotEmpty()) {
                    suggestionItems.addAll(page)
                    suggestionAdapter?.notifyDataSetChanged()
                }
            }
        }
    }

    private fun ensureSuggestionPopup() {
        if (suggestionPopup != null && suggestionList != null && suggestionAdapter != null) return

        val panelBg = if (dark) Color.rgb(28, 30, 33) else Color.WHITE
        val fg = if (dark) Color.rgb(235, 235, 235) else Color.rgb(25, 25, 25)
        val divider = if (dark) Color.rgb(55, 57, 60) else Color.rgb(225, 225, 225)

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = suggestionItems.size
            override fun getItem(position: Int): String = suggestionItems[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val row = (convertView as? TextView) ?: TextView(this@DictionaryActivity).apply {
                    textSize = 17f
                    setTextColor(fg)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), 0, dp(14), 0)
                    minHeight = dp(42)
                    setBackgroundColor(panelBg)
                }
                row.text = getItem(position)
                return row
            }
        }

        val list = ListView(this).apply {
            this.adapter = adapter
            setBackgroundColor(panelBg)
            dividerHeight = 1
            setDivider(ColorDrawable(divider))
            isVerticalScrollBarEnabled = true

            setOnItemClickListener { _, _, position, _ ->
                val word = suggestionItems.getOrNull(position).orEmpty()
                if (word.isNotBlank()) {
                    hideSuggestions()
                    lookup(word, recordHistory = true)
                }
            }

            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit

                override fun onScroll(
                    view: AbsListView?,
                    firstVisibleItem: Int,
                    visibleItemCount: Int,
                    totalItemCount: Int
                ) {
                    if (totalItemCount == 0) return
                    val lastVisible = firstVisibleItem + visibleItemCount
                    if (lastVisible >= totalItemCount - SUGGEST_PREFETCH_ROWS) {
                        loadNextSuggestionPage()
                    }
                }
            })
        }

        suggestionAdapter = adapter
        suggestionList = list
        suggestionPopup = PopupWindow(
            list,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(42 * SUGGEST_VISIBLE_ROWS),
            false
        ).apply {
            isOutsideTouchable = true
            isClippingEnabled = true
            elevation = dp(5).toFloat()
            setBackgroundDrawable(ColorDrawable(panelBg))
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
        }
    }

    private fun renderSuggestionPopup() {
        ensureSuggestionPopup()
        val width = (root.width - root.paddingLeft - root.paddingRight).coerceAtLeast(dp(220))
        val height = dp(42 * SUGGEST_VISIBLE_ROWS)
        val popup = suggestionPopup ?: return
        popup.width = width
        popup.height = height

        if (!popup.isShowing) {
            popup.showAsDropDown(queryBox, -dp(48), -dp(1))
        } else {
            popup.update(width, height)
        }
    }

    private fun hideSuggestions() {
        suggestionRunnable?.let(uiHandler::removeCallbacks)
        suggestionRunnable = null
        suggestGeneration.incrementAndGet()
        suggestionLoading = false
        suggestionHasMore = false
        suggestionPrefix = ""
        suggestionPager = null
        suggestionItems.clear()
        suggestionAdapter?.notifyDataSetChanged()
        suggestionPopup?.dismiss()
    }

    // ---------- selection / links ----------

    override fun onActionModeStarted(mode: ActionMode) {
        super.onActionModeStarted(mode)
        addDictionarySelectionAction(mode.menu)

        mode.menu.findItem(0x454E504C)?.setOnMenuItemClickListener {
            web.evaluateJavascript(
                "(function(){return window.getSelection ? window.getSelection().toString() : '';})()"
            ) { raw ->
                val selectedText = decodeJavascriptString(raw)
                mode.finish()
                if (selectedText.isNotBlank()) lookup(selectedText, recordHistory = true)
            }
            true
        }
    }

    private fun addDictionarySelectionAction(menu: Menu) {
        if (menu.findItem(0x454E504C) != null) return
        menu.add(Menu.NONE, 0x454E504C, 0, "EN-PL")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
    }

    private fun decodeJavascriptString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching {
            val body = if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"') {
                raw.substring(1, raw.length - 1)
            } else raw
            body.replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .trim()
        }.getOrDefault("")
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

    // ---------- lookup ----------

    private fun lookup(raw: String, recordHistory: Boolean) {
        val active = catalog ?: return
        val q = cleanQuery(raw)
        if (q.isEmpty()) return

        val deferred = deferInitialExternalPresentation && root.visibility != View.VISIBLE

        hideSuggestions()
        if (!deferred) {
            setQueryText(q)
            progress.visibility = View.VISIBLE
        }

        executor.execute {
            try {
                val results = active.lookup(q)

                if (results.isNotEmpty()) {
                    runOnUiThread {
                        currentWord = results.first().word
                        setQueryText(currentWord)
                        if (!deferred) progress.visibility = View.GONE
                        renderResults(results, revealWhenReady = deferred)
                    }
                } else {
                    val nearby = active.suggest(q, 24)
                    runOnUiThread {
                        currentWord = q
                        setQueryText(q)
                        if (!deferred) progress.visibility = View.GONE
                        renderSuggestions(q, nearby, revealWhenReady = deferred)
                    }
                }

                // History persistence must never delay the first visible definition frame.
                if (recordHistory) history.record(q)
            } catch (e: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    showMessage(
                        "Read error: ${esc(e.message ?: e.javaClass.simpleName)}",
                        revealWhenReady = deferred
                    )
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

    private fun renderResults(
        results: List<DictionaryCatalog.Result>,
        revealWhenReady: Boolean = false
    ) {
        val body = buildString {
            for (result in results) {
                append("<div class='dict'><div class='dict-title'>")
                append(esc(result.dictionary))
                append("</div>")
                append(result.html)
                append("</div>")
            }
        }
        renderEntry(body, revealWhenReady)
    }

    private fun renderEntry(body: String, revealWhenReady: Boolean = false) {
        if (revealWhenReady) revealAfterNextPage = true
        val page =
            "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<style>${css()}</style></head><body>$body</body></html>"
        web.loadDataWithBaseURL("https://local.dictionary/", page, "text/html", "UTF-8", null)
    }

    private fun renderSuggestions(
        q: String,
        list: List<String>,
        revealWhenReady: Boolean = false
    ) {
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
        renderEntry(body, revealWhenReady)
    }

    private fun showMessage(msg: String, revealWhenReady: Boolean = false) =
        renderEntry("<div>$msg</div>", revealWhenReady)

    private fun esc(s: String): String = s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    // ---------- lazy TTS ----------

    private fun speak(locale: Locale) {
        if (currentWord.isBlank()) return
        if (tts == null) {
            pendingSpeakLocale = locale
            tts = TextToSpeech(this, this)
            return
        }
        if (!ttsReady) {
            pendingSpeakLocale = locale
            return
        }
        speakReady(locale)
    }

    private fun speakReady(locale: Locale) {
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

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (!ttsReady) return
        val locale = pendingSpeakLocale ?: return
        pendingSpeakLocale = null
        speakReady(locale)
    }

    override fun onDestroy() {
        super.onDestroy()
        suggestionRunnable?.let(uiHandler::removeCallbacks)
        suggestionRunnable = null
        suggestionPopup?.dismiss()
        suggestionPopup = null
        suggestionList = null
        suggestionAdapter = null
        suggestionPager = null
        suggestionItems.clear()

        catalog?.close()
        catalog = null

        tts?.stop()
        tts?.shutdown()
        tts = null

        history.close()
        executor.shutdownNow()
    }
}
