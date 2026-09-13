package app.enpl.dictionary

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class OverlayLookupService : Service() {
    companion object {
        const val EXTRA_QUERY = "query"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var wm: WindowManager
    private var windowView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var catalog: DictionaryCatalog? = null
    private lateinit var queryBox: EditText
    private lateinit var web: WebView

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val q = intent?.getStringExtra(EXTRA_QUERY)?.trim().orEmpty()

        if (
            q.isBlank() ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !Settings.canDrawOverlays(this))
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (windowView == null) createWindow()
        openCatalogAndLookup(q)
        return START_NOT_STICKY
    }

    private fun createWindow() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()

        val darkBg = Color.rgb(16, 18, 20)
        val fg = Color.rgb(235, 235, 235)

        val frame = BackAwareFrameLayout(this) {
            stopSelf()
        }.apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(darkBg)
            elevation = dp(8).toFloat()
        }
        frame.addView(
            root,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(6), dp(4))
            setBackgroundColor(Color.rgb(34, 36, 40))
        }

        val drag = TextView(this).apply {
            text = "⋮⋮"
            textSize = 18f
            setTextColor(Color.rgb(150, 150, 150))
            gravity = Gravity.CENTER
        }
        top.addView(drag, LinearLayout.LayoutParams(dp(38), dp(42)))

        queryBox = EditText(this).apply {
            setSingleLine(true)
            textSize = 18f
            setTextColor(fg)
            setHintTextColor(Color.GRAY)
            hint = "English word or phrase"
        }
        top.addView(queryBox, LinearLayout.LayoutParams(0, dp(46), 1f))

        val search = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_search)
            background = ColorDrawable(Color.TRANSPARENT)
            contentDescription = "Search"
        }
        top.addView(search, LinearLayout.LayoutParams(dp(46), dp(46)))

        val fullScreen = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_view)
            background = ColorDrawable(Color.TRANSPARENT)
            contentDescription = "Open full screen"
        }
        top.addView(fullScreen, LinearLayout.LayoutParams(dp(46), dp(46)))

        val close = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = ColorDrawable(Color.TRANSPARENT)
            contentDescription = "Close"
        }
        top.addView(close, LinearLayout.LayoutParams(dp(46), dp(46)))
        root.addView(top)

        web = WebView(this).apply {
            setBackgroundColor(darkBg)
            settings.javaScriptEnabled = false
            settings.defaultFontSize = 18
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean = handleUrl(request?.url)

                @Deprecated("Deprecated Android API")
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    url: String?
                ): Boolean = handleUrl(url?.let(Uri::parse))
            }
        }

        root.addView(
            web,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val dm = resources.displayMetrics
        val width = (dm.widthPixels * 0.86f).toInt()
        val height = (dm.heightPixels * 0.62f).toInt()

        params = WindowManager.LayoutParams(
            width,
            height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((dm.widthPixels - width) / 2).coerceAtLeast(0)
            y = ((dm.heightPixels - height) / 4).coerceAtLeast(0)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        installMoveHandler(drag)

        val sideEdge = dp(12)
        val bottomEdge = dp(20)
        val corner = dp(36)
        addResizeHandle(frame, Gravity.LEFT, sideEdge, ViewGroup.LayoutParams.MATCH_PARENT, ResizeMode.LEFT)
        addResizeHandle(frame, Gravity.RIGHT, sideEdge, ViewGroup.LayoutParams.MATCH_PARENT, ResizeMode.RIGHT)
        addResizeHandle(frame, Gravity.TOP, ViewGroup.LayoutParams.MATCH_PARENT, sideEdge, ResizeMode.TOP)
        addResizeHandle(frame, Gravity.BOTTOM, ViewGroup.LayoutParams.MATCH_PARENT, bottomEdge, ResizeMode.BOTTOM)
        addResizeHandle(frame, Gravity.TOP or Gravity.LEFT, corner, corner, ResizeMode.TOP_LEFT)
        addResizeHandle(frame, Gravity.TOP or Gravity.RIGHT, corner, corner, ResizeMode.TOP_RIGHT)
        addResizeHandle(frame, Gravity.BOTTOM or Gravity.LEFT, corner, corner, ResizeMode.BOTTOM_LEFT)
        addResizeHandle(frame, Gravity.BOTTOM or Gravity.RIGHT, corner, corner, ResizeMode.BOTTOM_RIGHT)

        close.setOnClickListener { stopSelf() }
        search.setOnClickListener { lookup(queryBox.text.toString()) }

        fullScreen.setOnClickListener {
            val q = queryBox.text.toString().trim()
            if (q.isNotBlank()) {
                startActivity(
                    Intent(this, ExternalLookupActivity::class.java)
                        .putExtra("keyword", q)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            stopSelf()
        }

        queryBox.setOnEditorActionListener { _, _, _ ->
            lookup(queryBox.text.toString())
            true
        }

        windowView = frame
        wm.addView(frame, params)
        frame.requestFocus()
    }

    private class BackAwareFrameLayout(
        context: android.content.Context,
        private val onBack: () -> Unit
    ) : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (
                event.keyCode == KeyEvent.KEYCODE_BACK &&
                event.action == KeyEvent.ACTION_UP
            ) {
                onBack()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    private enum class ResizeMode {
        LEFT, RIGHT, TOP, BOTTOM,
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }

    private fun addResizeHandle(
        parent: FrameLayout,
        gravity: Int,
        width: Int,
        height: Int,
        mode: ResizeMode
    ) {
        val handle = View(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        parent.addView(handle, FrameLayout.LayoutParams(width, height, gravity))
        installResizeHandler(handle, mode)
    }

    private fun installMoveHandler(view: View) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        view.setOnTouchListener { _, event ->
            val p = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x
                    startY = p.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = startX + (event.rawX - touchX).toInt()
                    p.y = startY + (event.rawY - touchY).toInt()
                    clampToScreen(p)
                    windowView?.let { wm.updateViewLayout(it, p) }
                    true
                }
                else -> false
            }
        }
    }

    private fun installResizeHandler(view: View, mode: ResizeMode) {
        var startX = 0
        var startY = 0
        var startW = 0
        var startH = 0
        var touchX = 0f
        var touchY = 0f

        view.setOnTouchListener { _, event ->
            val p = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x
                    startY = p.y
                    startW = p.width
                    startH = p.height
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    val minW = (resources.displayMetrics.widthPixels * 0.46f).toInt()
                    val minH = (resources.displayMetrics.heightPixels * 0.30f).toInt()

                    var newX = startX
                    var newY = startY
                    var newW = startW
                    var newH = startH

                    if (mode == ResizeMode.LEFT || mode == ResizeMode.TOP_LEFT || mode == ResizeMode.BOTTOM_LEFT) {
                        val proposed = max(minW, startW - dx)
                        newX = startX + (startW - proposed)
                        newW = proposed
                    }
                    if (mode == ResizeMode.RIGHT || mode == ResizeMode.TOP_RIGHT || mode == ResizeMode.BOTTOM_RIGHT) {
                        newW = max(minW, startW + dx)
                    }
                    if (mode == ResizeMode.TOP || mode == ResizeMode.TOP_LEFT || mode == ResizeMode.TOP_RIGHT) {
                        val proposed = max(minH, startH - dy)
                        newY = startY + (startH - proposed)
                        newH = proposed
                    }
                    if (mode == ResizeMode.BOTTOM || mode == ResizeMode.BOTTOM_LEFT || mode == ResizeMode.BOTTOM_RIGHT) {
                        newH = max(minH, startH + dy)
                    }

                    val screenW = resources.displayMetrics.widthPixels
                    val screenH = resources.displayMetrics.heightPixels
                    newX = newX.coerceIn(0, screenW - minW)
                    newY = newY.coerceIn(0, screenH - minH)
                    newW = min(newW, screenW - newX)
                    newH = min(newH, screenH - newY)

                    p.x = newX
                    p.y = newY
                    p.width = newW
                    p.height = newH
                    windowView?.let { wm.updateViewLayout(it, p) }
                    true
                }
                else -> false
            }
        }
    }

    private fun clampToScreen(p: WindowManager.LayoutParams) {
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        p.x = p.x.coerceIn(0, max(0, screenW - p.width))
        p.y = p.y.coerceIn(0, max(0, screenH - p.height))
    }

    private fun openCatalogAndLookup(q: String) {
        queryBox.setText(q)
        queryBox.setSelection(q.length)

        executor.execute {
            val raw = getSharedPreferences("settings", MODE_PRIVATE)
                .getString("dictionary_tree_uri", null)

            if (raw.isNullOrBlank()) {
                runOnUiThread {
                    renderMessage("No dictionaries configured. Open EN-PL → Dictionaries.")
                }
                return@execute
            }

            val result = runCatching {
                if (catalog == null) {
                    catalog = DictionaryCatalog.open(
                        applicationContext,
                        Uri.parse(raw)
                    )
                }
                catalog!!.lookup(q)
            }

            runOnUiThread {
                result.onSuccess { results ->
                    if (results.isEmpty()) {
                        renderMessage("No exact entry for <b>${esc(q)}</b>.")
                    } else {
                        renderResults(results)
                    }
                }.onFailure {
                    renderMessage(
                        esc(it.message ?: "Could not open dictionary.")
                    )
                }
            }
        }
    }

    private fun lookup(q: String) {
        val clean = q.trim()
        if (clean.isNotBlank()) openCatalogAndLookup(clean)
    }

    private fun handleUrl(uri: Uri?): Boolean {
        if (uri == null) return false

        if (uri.scheme.equals("bword", true)) {
            var q = uri.schemeSpecificPart
            if (q.startsWith("//")) q = q.substring(2)

            q = runCatching {
                URLDecoder.decode(q, StandardCharsets.UTF_8.name())
            }.getOrDefault(q)

            lookup(q)
        }

        return true
    }

    private fun renderResults(results: List<DictionaryCatalog.Result>) {
        val body = buildString {
            for (result in results) {
                append("<div class='dict'><div class='title'>")
                append(esc(result.dictionary))
                append("</div>")
                append(result.html)
                append("</div>")
            }
        }
        renderHtml(body)
    }

    private fun renderMessage(msg: String) =
        renderHtml("<div>$msg</div>")

    private fun renderHtml(body: String) {
        val css =
            "html,body{background:#101214;color:#e9e9e9;font-family:sans-serif;" +
                "font-size:18px;line-height:1.38;margin:10px}" +
                ".dict{margin:0 0 16px 0}" +
                ".title{font-weight:700;font-size:14px;opacity:.72;margin-bottom:6px}" +
                "a{color:#8ab4ff;text-decoration:none}" +
                "font[color=\"#0000E0\"],font[color=\"#0000e0\"]{color:#86a8ff!important}" +
                "font[color=\"#E00000\"],font[color=\"#e00000\"]{color:#ff8585!important}" +
                "font[color=\"#008000\"]{color:#65d47b!important}" +
                "font[color=\"#736324\"]{color:#d7c77b!important}"

        web.loadDataWithBaseURL(
            "https://local.dictionary/",
            "<!doctype html><html><head>" +
                "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<style>$css</style></head><body>$body</body></html>",
            "text/html",
            "UTF-8",
            null
        )
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun runOnUiThread(block: () -> Unit) {
        android.os.Handler(mainLooper).post(block)
    }

    override fun onDestroy() {
        super.onDestroy()

        runCatching {
            windowView?.let { wm.removeView(it) }
        }

        windowView = null
        catalog?.close()
        catalog = null
        executor.shutdownNow()

        if (::web.isInitialized) web.destroy()
    }
}
