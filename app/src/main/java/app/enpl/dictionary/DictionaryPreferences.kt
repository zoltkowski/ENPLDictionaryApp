package app.enpl.dictionary

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class DictionaryInfo(
    val id: String,
    val title: String,
    val enabled: Boolean = true
)

internal object DictionaryPreferences {
    private const val PREFS = "settings"
    private const val KEY_DICTIONARIES = "dictionary_config"

    fun mergeDiscovered(
        context: Context,
        discovered: List<Pair<String, String>>
    ): List<DictionaryInfo> {
        val saved = load(context)
        val discoveredMap = discovered.toMap()
        val savedIds = saved.map { it.id }.toHashSet()

        val merged = ArrayList<DictionaryInfo>()
        // Preserve saved order/settings for dictionaries that still exist.
        for (item in saved) {
            val currentTitle = discoveredMap[item.id] ?: continue
            merged += item.copy(title = currentTitle)
        }
        // Append new dictionaries.
        for ((id, title) in discovered) {
            if (id !in savedIds) merged += DictionaryInfo(id, title, true)
        }
        save(context, merged)
        return merged
    }

    fun load(context: Context): List<DictionaryInfo> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DICTIONARIES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        DictionaryInfo(
                            id = o.getString("id"),
                            title = o.optString("title", o.getString("id")),
                            enabled = o.optBoolean("enabled", true)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, list: List<DictionaryInfo>) {
        val arr = JSONArray()
        for (item in list) {
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("enabled", item.enabled)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DICTIONARIES, arr.toString()).apply()
    }
}
