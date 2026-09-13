package app.enpl.dictionary

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class DictionarySettingsActivity : Activity() {
    companion object {
        private const val PREFS = "settings"
        private const val PREF_DARK = "dark"
        private const val PREF_THEME_SET = "theme_set"
    }

    private val rows = ArrayList<Row>()
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
    }

    private fun resolveDarkMode(): Boolean {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (p.getBoolean(PREF_THEME_SET, false)) return p.getBoolean(PREF_DARK, true)
        val night = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return night == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

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

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(10))
            setBackgroundColor(bg)
        }

        val title = TextView(this).apply {
            text = "Dictionaries"
            textSize = 22f
            setTextColor(fg)
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(title)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bg)
        }
        val listHost = LinearLayout(this).apply {
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

        val saved = DictionaryPreferences.load(this).toMutableList()
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
                // Explicit tint keeps the checkbox readable in both themes.
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
            listHost.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            val r = Row(info, row, check)
            rows += r

            check.setOnCheckedChangeListener { _, checked ->
                r.info = r.info.copy(enabled = checked)
                persist()
            }
            up.setOnClickListener { move(r, -1, listHost) }
            down.setOnClickListener { move(r, +1, listHost) }
        }

        val note = TextView(this).apply {
            text = "Order controls result order and suggestions. Unchecked dictionaries are disabled."
            textSize = 14f
            setTextColor(secondary)
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(note)

        setContentView(root)
    }

    private fun move(row: Row, delta: Int, listHost: LinearLayout) {
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
}
