package app.enpl.dictionary

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.Locale

internal class StarDictDictionary(
    context: Context,
    private val resolver: ContentResolver,
    val id: String,
    val title: String,
    private val idxFile: DocumentFile,
    private val dictFile: DocumentFile
) : Closeable {

    data class Hit(val word: String, val offset: Long, val size: Int)

    private val hits = ArrayList<Hit>()
    private val pfd = resolver.openFileDescriptor(dictFile.uri, "r")
        ?: throw IOException("Cannot open ${dictFile.name}")
    private val channel: FileChannel = java.io.FileInputStream(pfd.fileDescriptor).channel

    init {
        val cacheDir = File(context.cacheDir, "stardict-index").apply { mkdirs() }
        val keyMaterial = buildString {
            append(id)
            append('|').append(idxFile.length())
            append('|').append(idxFile.lastModified())
        }
        val cacheName = sha1(keyMaterial) + ".idxcache"
        val cache = File(cacheDir, cacheName)

        if (!loadCache(cache)) {
            parseIndex(idxFile.uri)
            saveCache(cache)
            // Keep only a bounded number of stale caches.
            cacheDir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(24)
                ?.forEach { runCatching { it.delete() } }
        }
    }

    private fun sha1(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(StandardCharsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun loadCache(file: File): Boolean = runCatching {
        if (!file.isFile) return@runCatching false
        DataInputStream(BufferedInputStream(file.inputStream(), 256 * 1024)).use { input ->
            if (input.readInt() != 0x454E504C) return@runCatching false // ENPL
            if (input.readInt() != 1) return@runCatching false
            val count = input.readInt()
            if (count < 0 || count > 5_000_000) return@runCatching false
            repeat(count) {
                hits += Hit(input.readUTF(), input.readLong(), input.readInt())
            }
        }
        file.setLastModified(System.currentTimeMillis())
        true
    }.getOrElse {
        hits.clear()
        false
    }

    private fun saveCache(file: File) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            DataOutputStream(BufferedOutputStream(tmp.outputStream(), 256 * 1024)).use { out ->
                out.writeInt(0x454E504C)
                out.writeInt(1)
                out.writeInt(hits.size)
                for (h in hits) {
                    out.writeUTF(h.word)
                    out.writeLong(h.offset)
                    out.writeInt(h.size)
                }
            }
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }

    private fun parseIndex(uri: Uri) {
        resolver.openInputStream(uri)?.use { raw ->
            BufferedInputStream(raw, 256 * 1024).use { input ->
                val word = ByteArrayOutputStream(64)
                while (true) {
                    word.reset()
                    var b: Int
                    while (true) {
                        b = input.read()
                        if (b == -1 || b == 0) break
                        word.write(b)
                    }
                    if (b == -1) break

                    val meta = ByteArray(8)
                    var got = 0
                    while (got < meta.size) {
                        val n = input.read(meta, got, meta.size - got)
                        if (n < 0) throw IOException("Truncated StarDict index: ${idxFile.name}")
                        got += n
                    }
                    val off = ((meta[0].toLong() and 0xff) shl 24) or
                        ((meta[1].toLong() and 0xff) shl 16) or
                        ((meta[2].toLong() and 0xff) shl 8) or
                        (meta[3].toLong() and 0xff)
                    val size = ((meta[4].toInt() and 0xff) shl 24) or
                        ((meta[5].toInt() and 0xff) shl 16) or
                        ((meta[6].toInt() and 0xff) shl 8) or
                        (meta[7].toInt() and 0xff)

                    hits += Hit(word.toString(StandardCharsets.UTF_8.name()), off, size)
                }
            }
        } ?: throw IOException("Cannot read ${idxFile.name}")
    }

    private fun ciCompare(a: String, b: String): Int =
        a.lowercase(Locale.ROOT).compareTo(b.lowercase(Locale.ROOT))

    fun findExact(query: String): Hit? {
        var lo = 0
        var hi = hits.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val h = hits[mid]
            val c = ciCompare(h.word, query)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> {
                    var first = mid
                    while (first > 0 && ciCompare(hits[first - 1].word, query) == 0) first--
                    var i = first
                    while (i < hits.size && ciCompare(hits[i].word, query) == 0) {
                        if (hits[i].word == query) return hits[i]
                        i++
                    }
                    return hits[first]
                }
            }
        }
        return null
    }

    fun suggest(prefix: String, limit: Int): List<String> {
        val p = prefix.trim().lowercase(Locale.ROOT)
        if (p.isEmpty()) return emptyList()

        var lo = 0
        var hi = hits.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val w = hits[mid].word.lowercase(Locale.ROOT)
            if (w < p) lo = mid + 1 else hi = mid
        }

        val out = ArrayList<String>(limit)
        var i = lo
        while (i < hits.size && out.size < limit) {
            val w = hits[i].word
            if (!w.lowercase(Locale.ROOT).startsWith(p)) break
            out += w
            i++
        }
        return out
    }

    @Synchronized
    fun read(hit: Hit): String {
        val buffer = ByteBuffer.allocate(hit.size)
        channel.position(hit.offset)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw IOException("Unexpected end of ${dictFile.name}")
        }
        return String(buffer.array(), StandardCharsets.UTF_8)
    }

    val size: Int get() = hits.size

    override fun close() {
        runCatching { channel.close() }
        runCatching { pfd.close() }
    }
}
