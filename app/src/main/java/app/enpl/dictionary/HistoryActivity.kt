package app.enpl.dictionary

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

class HistoryActivity : Activity() {
    private lateinit var store: HistoryStore
    private lateinit var listHost: LinearLayout
    private var sort = "time"
    private val selected = LinkedHashSet<String>()
    private val exportLauncherCode = 7102

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        store = HistoryStore(this)
        buildUi()
        refresh()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(10))
        }
        val title = TextView(this).apply {
            text = "Search history"
            textSize = 22f
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(title)

        val sorts = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val rTime = RadioButton(this).apply { text = "Recent"; isChecked = true }
        val rAlpha = RadioButton(this).apply { text = "A–Z" }
        val rCount = RadioButton(this).apply { text = "Count" }
        sorts.addView(rTime); sorts.addView(rAlpha); sorts.addView(rCount)
        root.addView(sorts)
        sorts.setOnCheckedChangeListener { _, id ->
            sort = when (id) {
                rAlpha.id -> "alpha"
                rCount.id -> "count"
                else -> "time"
            }
            refresh()
        }

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val delete = Button(this).apply { text = "Delete selected"; isAllCaps = false }
        val clear = Button(this).apply { text = "Clear all"; isAllCaps = false }
        val export = Button(this).apply { text = "Export TXT"; isAllCaps = false }
        actions.addView(delete, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(clear, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(export, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions)

        val scroll = ScrollView(this)
        listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listHost)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        delete.setOnClickListener {
            if (selected.isNotEmpty()) {
                store.delete(selected)
                selected.clear()
                refresh()
            }
        }
        clear.setOnClickListener {
            store.clear()
            selected.clear()
            refresh()
        }
        export.setOnClickListener {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "enpl-search-history.txt")
            }
            startActivityForResult(i, exportLauncherCode)
        }
    }

    private fun refresh() {
        listHost.removeAllViews()
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        for (item in store.list(sort)) {
            val cb = CheckBox(this).apply {
                text = "${item.word}    ×${item.count}\n${fmt.format(Date(item.lastUsed))}"
                textSize = 17f
                isChecked = item.word in selected
                setPadding(dp(2), dp(8), dp(2), dp(8))
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected += item.word else selected -= item.word
                }
                setOnClickListener {
                    // Keep checkbox semantics; open lookup via long press instead.
                }
                setOnLongClickListener {
                    startActivity(Intent(this@HistoryActivity, MainActivity::class.java)
                        .putExtra("keyword", item.word))
                    true
                }
            }
            listHost.addView(cb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    @Deprecated("Retained for minSdk 23")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != exportLauncherCode || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            contentResolver.openOutputStream(uri, "w")!!.bufferedWriter().use { out ->
                out.appendLine("word\tcount\tlast_search")
                val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                for (item in store.list("time")) {
                    out.append(item.word).append('\t')
                        .append(item.count.toString()).append('\t')
                        .appendLine(fmt.format(Date(item.lastUsed)))
                }
            }
        }.onSuccess {
            Toast.makeText(this, "History exported", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "Export failed: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        store.close()
    }
}
