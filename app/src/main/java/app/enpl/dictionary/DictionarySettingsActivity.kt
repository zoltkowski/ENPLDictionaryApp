package app.enpl.dictionary

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.Locale

class DictionarySettingsActivity : Activity() {
    companion object {
        private const val PREFS = "settings"
        private const val PREF_DARK = "dark"
        private const val PREF_THEME_SET = "theme_set"
        private const val PREF_TREE_URI = "dictionary_tree_uri"
        private const val PREF_EXTERNAL_FLOATING = "external_lookup_floating"
        private const val REQ_FOLDER = 5107
    }

    private val rows = ArrayList<Row>()
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var root: LinearLayout
    private lateinit var listHost: LinearLayout
    private lateinit var folderText: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar

    private var dark = false
    private var bg = Color.WHITE
    private var fg = Color.BLACK
    private var secondary = Color.DKGRAY

    private data class Row(
        var info: DictionaryInfo,
        val host: LinearLayout,
        val check: CheckBox
    )

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        dark = resolveDarkMode()
        bg = if (dark) Color.rgb(16, 18, 20) else Color.rgb(250, 250, 250)
        fg = if (dark) Color.rgb(235, 235, 235) else Color.rgb(25, 25, 25)
        secondary = if (dark) Color.rgb(165, 165, 165) else Color.rgb(95, 95, 95)
        window.setBackgroundDrawable(ColorDrawable(bg))

        buildUi()
        refreshFolderLabel()
        renderDictionaryRows()
    }

    private fun resolveDarkMode(): Boolean {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (p.getBoolean(PREF_THEME_SET, false)) return p.getBoolean(PREF_DARK, true)
        val night = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return night == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(10))
            setBackgroundColor(bg)
        }
        applySystemBarInsets(root)

        root.addView(TextView(this).apply {
            text = "Dictionaries"
            textSize = 22f
            setTextColor(fg)
            setPadding(0, 0, 0, dp(8))
        })

        folderText = TextView(this).apply {
            textSize = 13f
            setTextColor(secondary)
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(folderText)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val choose = Button(this).apply {
            text = "Choose folder"
            setOnClickListener { chooseDictionaryFolder() }
        }
        val reindex = Button(this).apply {
            text = "Reindex"
            setOnClickListener { reindexDictionaries() }
        }

        controls.addView(
            choose,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        controls.addView(
            reindex,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(controls)

        root.addView(TextView(this).apply {
            text = "External lookup"
            textSize = 17f
            setTextColor(fg)
            setPadding(0, dp(14), 0, dp(4))
        })

        val externalMode = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }

        val fullScreen = android.widget.RadioButton(this).apply {
            id = View.generateViewId()
            text = "Full screen"
            textSize = 15f
            setTextColor(fg)
        }

        val floating = android.widget.RadioButton(this).apply {
            id = View.generateViewId()
            text = "Floating window when supported"
            textSize = 15f
            setTextColor(fg)
        }

        externalMode.addView(fullScreen)
        externalMode.addView(floating)

        val floatingEnabled = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(PREF_EXTERNAL_FLOATING, false)
        if (floatingEnabled) floating.isChecked = true else fullScreen.isChecked = true

        externalMode.setOnCheckedChangeListener { _, checkedId ->
            val enabled = checkedId == floating.id
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_EXTERNAL_FLOATING, enabled)
                .apply()
        }

        root.addView(externalMode)

        root.addView(TextView(this).apply {
            text = "If the device supports native freeform windows, external lookups open in a floating window. Otherwise EN-PL opens full screen."
            textSize = 13f
            setTextColor(secondary)
            setPadding(0, dp(2), 0, dp(10))
        })

        progress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(
            progress,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
        )

        statusText = TextView(this).apply {
            textSize = 13f
            setTextColor(secondary)
            setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(statusText)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bg)
        }
        listHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        scroll.addView(
            listHost,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        root.addView(TextView(this).apply {
            text = "Order controls result order and suggestions. Unchecked dictionaries are disabled."
            textSize = 14f
            setTextColor(secondary)
            setPadding(0, dp(10), 0, 0)
        })

        setContentView(root)
    }

    private fun refreshFolderLabel() {
        val raw = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(PREF_TREE_URI, null)

        folderText.text = if (raw.isNullOrBlank()) {
            "Folder: not selected"
        } else {
            "Folder: ${Uri.parse(raw).lastPathSegment ?: raw}"
        }
    }

    private fun renderDictionaryRows() {
        rows.clear()
        listHost.removeAllViews()

        val saved = DictionaryPreferences.load(this).toMutableList()
        if (saved.isEmpty()) {
            listHost.addView(TextView(this).apply {
                text = "No dictionaries found yet. Choose a folder above."
                textSize = 15f
                setTextColor(secondary)
                setPadding(0, dp(10), 0, dp(10))
            })
            return
        }

        saved.forEach { info ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(2))
                setBackgroundColor(bg)
            }

            val check = CheckBox(this).apply {
                text = info.title
                isChecked = info.enabled
                textSize = 17f
                setTextColor(fg)
                buttonTintList = ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf()
                    ),
                    intArrayOf(
                        if (dark) Color.rgb(120, 160, 255) else Color.rgb(60, 90, 190),
                        if (dark) Color.rgb(150, 150, 150) else Color.rgb(110, 110, 110)
                    )
                )
            }

            val up = arrowButton(android.R.drawable.arrow_up_float, "Move up")
            val down = arrowButton(android.R.drawable.arrow_down_float, "Move down")

            row.addView(
                check,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(up, LinearLayout.LayoutParams(dp(44), dp(44)))
            row.addView(down, LinearLayout.LayoutParams(dp(44), dp(44)))
            listHost.addView(row)

            val r = Row(info, row, check)
            rows += r

            check.setOnCheckedChangeListener { _, checked ->
                r.info = r.info.copy(enabled = checked)
                persist()
            }
            up.setOnClickListener { move(r, -1) }
            down.setOnClickListener { move(r, +1) }
        }
    }

    private fun arrowButton(drawable: Int, description: String): ImageButton =
        ImageButton(this).apply {
            setImageResource(drawable)
            contentDescription = description
            background = ColorDrawable(Color.TRANSPARENT)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            alpha = if (dark) 0.92f else 0.78f
            minimumWidth = 0
            minimumHeight = 0
        }

    private fun move(row: Row, delta: Int) {
        val from = rows.indexOf(row)
        val to = from + delta
        if (from < 0 || to !in rows.indices) return

        rows.removeAt(from)
        rows.add(to, row)
        listHost.removeView(row.host)
        listHost.addView(row.host, to)
        persist()
    }

    private fun persist() {
        DictionaryPreferences.save(this, rows.map { it.info })
        setResult(RESULT_OK)
    }

    private fun chooseDictionaryFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(i, REQ_FOLDER)
    }

    @Deprecated("Deprecated Android API; retained for minSdk 23")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FOLDER || resultCode != RESULT_OK) return

        val uri = data?.data ?: return
        val takeFlags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        runCatching {
            contentResolver.takePersistableUriPermission(uri, takeFlags)
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(PREF_TREE_URI, uri.toString())
            .apply()

        refreshFolderLabel()
        scanFolder(forceReindex = false)
    }

    private fun reindexDictionaries() {
        val raw = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(PREF_TREE_URI, null)

        if (raw.isNullOrBlank()) {
            statusText.text = "Choose a dictionary folder first."
            return
        }

        scanFolder(forceReindex = true)
    }

    private fun scanFolder(forceReindex: Boolean) {
        val raw = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(PREF_TREE_URI, null) ?: return
        val uri = Uri.parse(raw)

        progress.visibility = View.VISIBLE
        statusText.text =
            if (forceReindex) "Reindexing dictionaries…" else "Scanning folder…"

        executor.execute {
            val result = runCatching {
                if (forceReindex) {
                    StarDictDictionary.invalidateAllIndexCaches(applicationContext)
                }

                DictionaryCatalog.open(applicationContext, uri).use { catalog ->
                    catalog.allDictionaries.size to catalog.totalEntries
                }
            }

            runOnUiThread {
                progress.visibility = View.GONE

                result.onSuccess { (count, entryCount) ->
                    val entries = String.format(Locale.getDefault(), "%,d", entryCount)
                    statusText.text =
                        "Found $count ${if (count == 1) "dictionary" else "dictionaries"}, " +
                            "$entries ${if (entryCount == 1) "entry" else "entries"}."
                    renderDictionaryRows()
                    setResult(RESULT_OK)
                }.onFailure { e ->
                    statusText.text =
                        e.message ?: "Could not scan dictionary folder."
                }
            }
        }
    }

    private fun applySystemBarInsets(view: View) {
        val left = dp(12)
        val top = dp(12)
        val right = dp(12)
        val bottom = dp(10)

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

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }
}
