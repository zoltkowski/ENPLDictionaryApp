package app.enpl.dictionary

/** Full-screen fallback for external lookup. */
class ExternalLookupActivity : DictionaryActivity() {
    override fun isPopup() = false

    @Deprecated("Legacy back callback retained for minSdk 23")
    override fun onBackPressed() {
        // Close the whole EN-PL task/affinity so Back returns directly
        // to the app that invoked the external lookup, instead of exposing
        // an older EN-PL activity underneath.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        } else {
            finish()
        }
    }
}
