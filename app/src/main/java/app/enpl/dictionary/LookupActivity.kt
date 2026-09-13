package app.enpl.dictionary

import android.app.Activity
import android.app.ActivityOptions
import android.app.SearchManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle

class LookupActivity : Activity() {
    companion object {
        private const val PREFS = "settings"
        private const val PREF_EXTERNAL_FLOATING = "external_lookup_floating"
    }


    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        routeLookup(queryFromIntent(intent))
    }


    private fun routeLookup(rawQuery: String?) {
        val q = rawQuery?.trim().orEmpty()
        if (q.isBlank()) {
            finish()
            return
        }

        val floating = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(PREF_EXTERNAL_FLOATING, false)

        if (floating && supportsStandardFreeform()) {
            openStandardFreeform(q)
        } else {
            openFullScreen(q)
        }

        // This Activity is only an invisible external-intent trampoline.
        // Never leave it in the task/back stack.
        finish()
    }

    private fun supportsStandardFreeform(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            packageManager.hasSystemFeature(
                PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT
            )

    private fun openStandardFreeform(q: String) {
        val dm = resources.displayMetrics
        val portrait =
            resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        val width = if (portrait) {
            (dm.widthPixels * 0.74f).toInt()
        } else {
            (dm.widthPixels * 0.55f).toInt()
        }
        val height = if (portrait) {
            (dm.heightPixels * 0.62f).toInt()
        } else {
            (dm.heightPixels * 0.82f).toInt()
        }

        val left = ((dm.widthPixels - width) / 2).coerceAtLeast(0)
        val top = ((dm.heightPixels - height) / 2).coerceAtLeast(0)
        val bounds = Rect(left, top, left + width, top + height)

        val target = Intent(this, FloatingLookupActivity::class.java).apply {
            putExtra("keyword", q)
            putExtra(FloatingLookupActivity.EXTRA_EXTERNAL_LOOKUP, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }

        val options = ActivityOptions.makeBasic().apply {
            launchBounds = bounds
        }

        val launched = runCatching {
            startActivity(target, options.toBundle())
        }.isSuccess

        if (!launched) {
            openFullScreen(q)
            return
        }

        finish()
        overridePendingTransition(0, 0)
    }

    private fun openFullScreen(q: String) {
        startActivity(
            Intent(this, ExternalLookupActivity::class.java)
                .putExtra("keyword", q)
        )
        finish()
        overridePendingTransition(0, 0)
    }

    private fun queryFromIntent(i: Intent?): String? {
        if (i == null) return null

        i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it.toString() }

        for (key in arrayOf("keyword", SearchManager.QUERY, "EXTRA_QUERY")) {
            i.getStringExtra(key)?.takeIf { it.isNotBlank() }?.let { return it }
        }

        i.getCharSequenceExtra(Intent.EXTRA_TEXT)
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it.toString() }

        return i.data?.lastPathSegment
    }
}
