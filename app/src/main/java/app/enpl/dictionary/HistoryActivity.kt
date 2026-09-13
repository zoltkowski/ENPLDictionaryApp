package app.enpl.dictionary

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

class HistoryActivity : Activity() {
    companion object {
        private const val PREFS = "settings"
        private const val PREF_DARK = "dark"
        private const val PREF_THEME_SET = "theme_set"
    }

    private lateinit var store: HistoryStore
    private lateinit var listHost: LinearLayout
    private lateinit var root: LinearLayout
    private lateinit var title: TextView
    private lateinit var selectButton: ImageButton
    private lateinit var deleteButton: ImageButton
    private lateinit var exportButton: ImageButton
    private lateinit var actionBar: LinearLayout

    private var sort = "time"
    private val selected = LinkedHashSet<String>()
    private var currentItems: List<HistoryItem> = emptyList()
    private var selectionMode = false
    private val exportLauncherCode = 7102

    private var dark = false
    private var bg = Color.WHITE
    private var fg = Color.BLACK
    private var secondary = Color.DKGRAY
    private var accent = Color.rgb(70, 100, 200)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        dark = resolveDarkMode()
        applyPalette()
        window.setBackgroundDrawable(ColorDrawable(bg))
        store = HistoryStore(this)
        buildUi()
        refresh()
    }

    private fun resolveDarkMode(): Boolean {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (p.getBoolean(PREF_THEME_SET, false)) return p.getBoolean(PREF_DARK, true)
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return night == Configuration.UI_MODE_NIGHT_YES
    }

    private fun applyPalette() {
        bg = if (dark) Color.rgb(16, 18, 20) else Color.rgb(250, 250, 250)
        fg = if (dark) Color.rgb(235, 235, 235) else Color.rgb(25, 25, 25)
        secondary = if (dark) Color.rgb(165, 165, 165) else Color.rgb(95, 95, 95)
        accent = if (dark) Color.rgb(130, 165, 255) else Color.rgb(70, 95, 185)
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun iconButton(drawableId: Int, description: String): ImageButton =
        ImageButton(this).apply {
            setImageResource(drawableId)
            contentDescription = description
            background = ColorDrawable(Color.TRANSPARENT)
            setColorFilter(fg)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            minimumWidth = 0
            minimumHeight = 0
        }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(10))
            setBackgroundColor(bg)
        }

        title = TextView(this).apply {
            text = "Search history"
            textSize = 22f
            setTextColor(fg)
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(title)

        val sorts = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            setBackgroundColor(bg)
        }

        val rTime = themedRadio("Recent", checked = true)
        val rAlpha = themedRadio("A–Z")
        val rCount = themedRadio("Count")

        sorts.addView(rTime)
        sorts.addView(rAlpha)
        sorts.addView(rCount)
        root.addView(sorts)

        sorts.setOnCheckedChangeListener { _, id ->
            sort = when (id) {
                rAlpha.id -> "alpha"
                rCount.id -> "count"
                else -> "time"
            }
            refresh()
        }

        actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(2), 0, dp(6))
        }

        selectButton = iconButton(R.drawable.ic_select_all, "Select all / unselect all")
        deleteButton = iconButton(R.drawable.ic_delete, "Delete selected")
        exportButton = iconButton(R.drawable.ic_export, "Export history")

        actionBar.addView(selectButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        actionBar.addView(deleteButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        actionBar.addView(exportButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(actionBar)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
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

        setContentView(root)

        selectButton.setOnClickListener {
            if (!selectionMode || currentItems.isEmpty()) return@setOnClickListener

            val allSelected = currentItems.all { it.word in selected }
            selected.clear()
            if (!allSelected) selected.addAll(currentItems.map { it.word })
            refresh()
        }

        deleteButton.setOnClickListener {
            if (!selectionMode) return@setOnClickListener

            if (selected.isEmpty()) {
                Toast.makeText(this, "Select entries to delete", Toast.LENGTH_SHORT).show()
            } else {
                store.delete(selected)
                selected.clear()
                exitSelectionMode()
                refresh()
            }
        }

        exportButton.setOnClickListener {
            if (selectionMode && selected.isEmpty()) {
                Toast.makeText(this, "Select entries to export", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "enpl-search-history.txt")
            }
            startActivityForResult(i, exportLauncherCode)
        }

        updateActionBar()
    }

    private fun themedRadio(label: String, checked: Boolean = false): RadioButton =
        RadioButton(this).apply {
            id = View.generateViewId()
            text = label
            isChecked = checked
            textSize = 15f
            setTextColor(fg)
            buttonTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf()
                ),
                intArrayOf(accent, secondary)
            )
        }

    private fun enterSelectionMode(initialWord: String) {
        selectionMode = true
        selected.clear()
        selected += initialWord
        refresh()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selected.clear()
        updateActionBar()
    }

    private fun updateActionBar() {
        // Export is always available.
        exportButton.visibility = View.VISIBLE

        // Select-all and delete only appear after long-press enters selection mode.
        selectButton.visibility = if (selectionMode) View.VISIBLE else View.GONE
        deleteButton.visibility = if (selectionMode) View.VISIBLE else View.GONE

        if (selectionMode) {
            val allSelected = currentItems.isNotEmpty() &&
                currentItems.all { it.word in selected }

            selectButton.setImageResource(
                if (allSelected) R.drawable.ic_unselect_all else R.drawable.ic_select_all
            )
            selectButton.contentDescription =
                if (allSelected) "Unselect all" else "Select all"

            deleteButton.alpha = if (selected.isEmpty()) 0.38f else 1f
            title.text = if (selected.isEmpty()) {
                "Search history"
            } else {
                "Search history  (${selected.size})"
            }
        } else {
            title.text = "Search history"
        }
    }

    private fun refresh() {
        currentItems = store.list(sort)
        listHost.removeAllViews()

        // Drop selections that no longer exist after deletion/filter changes.
        val existingWords = currentItems.mapTo(HashSet()) { it.word }
        selected.retainAll(existingWords)

        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val divider = if (dark) Color.rgb(48, 50, 54) else Color.rgb(225, 225, 225)

        for (item in currentItems) {
            if (selectionMode) {
                val cb = CheckBox(this).apply {
                    text = buildHistoryLabel(item, fmt)
                    textSize = 17f
                    setTextColor(fg)
                    buttonTintList = ColorStateList(
                        arrayOf(
                            intArrayOf(android.R.attr.state_checked),
                            intArrayOf()
                        ),
                        intArrayOf(accent, secondary)
                    )
                    isChecked = item.word in selected
                    setPadding(dp(2), dp(8), dp(2), dp(8))
                    setBackgroundColor(bg)

                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selected += item.word else selected -= item.word
                        updateActionBar()
                    }

                    // While selecting, tapping the row toggles selection only.
                    setOnClickListener {
                        isChecked = !isChecked
                    }
                }

                listHost.addView(
                    cb,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            } else {
                val row = TextView(this).apply {
                    text = buildHistoryLabel(item, fmt)
                    textSize = 17f
                    setTextColor(fg)
                    setPadding(dp(10), dp(9), dp(10), dp(9))
                    setBackgroundColor(bg)
                    isClickable = true
                    isFocusable = true

                    // Normal tap opens the definition.
                    setOnClickListener {
                        startActivity(
                            Intent(this@HistoryActivity, MainActivity::class.java)
                                .putExtra("keyword", item.word)
                        )
                    }

                    // Long press enters multi-select mode and preselects this word.
                    setOnLongClickListener {
                        enterSelectionMode(item.word)
                        true
                    }
                }

                listHost.addView(
                    row,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }

            listHost.addView(
                View(this).apply { setBackgroundColor(divider) },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1
                )
            )
        }

        updateActionBar()
    }

    private fun buildHistoryLabel(
        item: HistoryItem,
        fmt: DateFormat
    ): CharSequence {
        val out = SpannableStringBuilder()
        out.append(item.word)

        if (item.count > 1) {
            val start = out.length
            out.append("  [${item.count}]")
            out.setSpan(
                RelativeSizeSpan(0.72f),
                start,
                out.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        out.append("\n")
        val dateStart = out.length
        out.append(fmt.format(Date(item.lastUsed)))
        out.setSpan(
            RelativeSizeSpan(0.78f),
            dateStart,
            out.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return out
    }

    override fun onBackPressed() {
        if (selectionMode) {
            exitSelectionMode()
            refresh()
        } else {
            super.onBackPressed()
        }
    }

    @Deprecated("Retained for minSdk 23")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != exportLauncherCode || resultCode != RESULT_OK) return

        val uri = data?.data ?: return

        val itemsToExport = if (selectionMode) {
            currentItems.filter { it.word in selected }
        } else {
            // Export all history, independent of current sort choice.
            store.list("time")
        }

        runCatching {
            contentResolver.openOutputStream(uri, "w")!!.bufferedWriter().use { out ->
                out.appendLine("word\tcount\tlast_search")
                val fmt = DateFormat.getDateTimeInstance(
                    DateFormat.SHORT,
                    DateFormat.SHORT
                )

                for (item in itemsToExport) {
                    out.append(item.word).append('\t')
                        .append(item.count.toString()).append('\t')
                        .appendLine(fmt.format(Date(item.lastUsed)))
                }
            }
        }.onSuccess {
            Toast.makeText(
                this,
                if (selectionMode) "Selected history exported" else "History exported",
                Toast.LENGTH_SHORT
            ).show()
        }.onFailure {
            Toast.makeText(
                this,
                "Export failed: ${it.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        store.close()
    }
}
