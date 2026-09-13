package app.enpl.dictionary

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.Closeable
import java.io.IOException
import java.util.LinkedHashMap
import java.util.Locale

internal class DictionaryCatalog private constructor(
    val treeUri: Uri,
    private val dictionaries: List<StarDictDictionary>,
    val allDictionaries: List<DictionaryInfo>
) : Closeable {

    data class Result(val dictionary: String, val word: String, val html: String)

    val totalEntries: Int
        get() = dictionaries.sumOf { it.size }

    private data class FileSet(
        val id: String,
        val base: String,
        val ifo: DocumentFile,
        val idx: DocumentFile,
        val dict: DocumentFile,
        val title: String
    )

    companion object {
        fun open(context: Context, treeUri: Uri): DictionaryCatalog {
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw IOException("Cannot access selected folder")
            if (!root.canRead()) throw IOException("Selected folder is not readable")

            val groups = LinkedHashMap<String, MutableMap<String, DocumentFile>>()
            scan(root, groups, depth = 0)

            val discovered = ArrayList<FileSet>()
            for ((base, files) in groups) {
                val ifo = files["ifo"] ?: continue
                val idx = files["idx"] ?: continue
                val dict = files["dict"] ?: continue
                val title = readBookName(context, ifo) ?: base.substringAfterLast('/')
                discovered += FileSet(base, base, ifo, idx, dict, title)
            }
            if (discovered.isEmpty()) {
                throw IOException("No StarDict dictionaries found (.ifo + .idx + .dict)")
            }

            val config = DictionaryPreferences.mergeDiscovered(
                context,
                discovered.map { it.id to it.title }
            )
            val byId = discovered.associateBy { it.id }
            val opened = ArrayList<StarDictDictionary>()
            try {
                for (cfg in config) {
                    if (!cfg.enabled) continue
                    val fs = byId[cfg.id] ?: continue
                    opened += StarDictDictionary(
                        context = context.applicationContext,
                        resolver = context.contentResolver,
                        id = fs.id,
                        title = fs.title,
                        idxFile = fs.idx,
                        dictFile = fs.dict
                    )
                }
            } catch (e: Exception) {
                opened.forEach { runCatching { it.close() } }
                throw e
            }
            return DictionaryCatalog(treeUri, opened, config)
        }

        private fun scan(
            dir: DocumentFile,
            groups: MutableMap<String, MutableMap<String, DocumentFile>>,
            depth: Int
        ) {
            if (depth > 6) return
            for (file in dir.listFiles()) {
                if (file.isDirectory) {
                    scan(file, groups, depth + 1)
                    continue
                }
                val name = file.name ?: continue
                val lower = name.lowercase(Locale.ROOT)
                val ext = when {
                    lower.endsWith(".ifo") -> "ifo"
                    lower.endsWith(".idx") -> "idx"
                    lower.endsWith(".dict") -> "dict"
                    else -> null
                } ?: continue

                val stem = name.substring(0, name.length - ext.length - 1)
                val key = "${dir.uri}/$stem"
                groups.getOrPut(key) { LinkedHashMap() }[ext] = file
            }
        }

        private fun readBookName(context: Context, ifo: DocumentFile): String? =
            runCatching {
                context.contentResolver.openInputStream(ifo.uri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.useLines { lines ->
                        lines.firstOrNull { it.startsWith("bookname=") }
                            ?.substringAfter("bookname=")?.trim()
                    }
            }.getOrNull()
    }

    fun lookup(query: String): List<Result> {
        val out = ArrayList<Result>()
        for (dict in dictionaries) {
            val hit = dict.findExact(query) ?: continue
            out += Result(dict.title, hit.word, dict.read(hit))
        }
        return out
    }

    fun suggest(prefix: String, limit: Int): List<String> {
        val seen = LinkedHashSet<String>()
        for (dict in dictionaries) {
            for (word in dict.suggest(prefix, limit)) {
                seen += word
                if (seen.size >= limit) return seen.toList()
            }
        }
        return seen.toList()
    }

    val dictionaryCount: Int get() = dictionaries.size
    val entryCount: Int get() = dictionaries.sumOf { it.size }

    override fun close() {
        dictionaries.forEach { runCatching { it.close() } }
    }
}
