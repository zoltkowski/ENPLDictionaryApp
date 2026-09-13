package app.enpl.dictionary

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle

/**
 * Exported lookup activity used by BOOX/NeoReader/ColorDict-compatible intents.
 *
 * If the Activity was opened with a word supplied by another app, Back should
 * leave the dictionary task immediately instead of walking through any older
 * EN-PL Dictionary activities that may already be in our task.
 */
class LookupActivity : DictionaryActivity() {
    private var externalLookup = false

    override fun isPopup() = false

    override fun onCreate(state: Bundle?) {
        externalLookup = hasLookupWord(intent)
        super.onCreate(state)
    }

    override fun onNewIntent(intent: Intent) {
        externalLookup = hasLookupWord(intent)
        super.onNewIntent(intent)
    }

    @Deprecated("Android's legacy back callback is intentional here for minSdk 23")
    override fun onBackPressed() {
        if (externalLookup) {
            /*
             * finishAffinity() closes this Activity plus any older activities
             * from our own affinity underneath it. If the caller belongs to a
             * different app/affinity, Android leaves that caller in place and
             * returns to it immediately.
             */
            finishAffinity()
        } else {
            super.onBackPressed()
        }
    }

    private fun hasLookupWord(i: Intent?): Boolean {
        if (i == null) return false

        if (!i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT).isNullOrBlank()) return true
        if (!i.getCharSequenceExtra(Intent.EXTRA_TEXT).isNullOrBlank()) return true

        for (key in arrayOf("keyword", SearchManager.QUERY, "EXTRA_QUERY")) {
            if (!i.getStringExtra(key).isNullOrBlank()) return true
        }

        // Some reader integrations pass the selected word in the data URI.
        if (!i.data?.lastPathSegment.isNullOrBlank()) return true

        return false
    }
}
