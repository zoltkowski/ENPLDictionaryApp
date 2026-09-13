package app.enpl.dictionary

import android.os.Build
import android.os.Bundle

/**
 * The actual dictionary UI for an external lookup.
 *
 * This Activity can be launched into a system freeform task when the device
 * advertises standard Android freeform-window support.
 */
class FloatingLookupActivity : DictionaryActivity() {
    companion object {
        const val EXTRA_EXTERNAL_LOOKUP =
            "app.enpl.dictionary.extra.EXTERNAL_LOOKUP"
    }

    private var externalLookup = false

    override fun isPopup() = false

    override fun onCreate(state: Bundle?) {
        externalLookup = intent.getBooleanExtra(EXTRA_EXTERNAL_LOOKUP, false)
        super.onCreate(state)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        externalLookup = intent.getBooleanExtra(EXTRA_EXTERNAL_LOOKUP, false)
        super.onNewIntent(intent)
    }

    @Deprecated("Legacy back callback retained for minSdk 23")
    override fun onBackPressed() {
        if (externalLookup) {
            // This Activity uses a dedicated task affinity. Removing its task
            // therefore reveals the app that invoked EN-PL instead of an older
            // EN-PL screen that may already exist in the main app task.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask()
            } else {
                finish()
            }
            return
        }
        super.onBackPressed()
    }
}
