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
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal class StarDictDictionary(
    context: Context,
    private val resolver: ContentResolver,
    val id: String,
    val title: String,
    private val idxFile: DocumentFile,
    private val dictFile: DocumentFile
) : Closeable {

    data class Hit(val word: String, val offset: Long, val size: Int)

    internal class PrefixCursor internal constructor(
        internal val prefixLower: String,
        internal var index: Int
    )

    private val hits = ArrayList<Hit>()
    private val pfd = resolver.openFileDescriptor(dictFile.uri, "r")
        ?: throw IOException("Cannot open ${dictFile.name}")
    private val channel: FileChannel = FileInputStream(pfd.fileDescriptor).channel

    init {
        val cacheDir = File(context.cacheDir, "stardict-index").apply { mkdirs() }
        val keyMaterial = buildString {
            append(id)
            append('|').append(idxFile.length())
            append('|').append(idxFile.lastModified())
            append('|').append(quickFingerprint(idxFile))
        }
        val cacheName = sha1(keyMaterial) + ".idxcache"
        val cache = File(cacheDir, cacheName)

        val inMemory = memoryIndexCache[cacheName]
        if (inMemory != null) {
            hits.addAll(inMemory)
        } else {
            if (!loadCache(cache)) {
                parseIndex(idxFile.uri)
                sortHitsForLookup()
                saveCache(cache)
            }
            memoryIndexCache[cacheName] = ArrayList(hits)
            cacheDir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(24)
                ?.forEach { runCatching { it.delete() } }
        }
    }

    companion object {
        private val memoryIndexCache = ConcurrentHashMap<String, List<Hit>>()

        fun invalidateAllIndexCaches(context: Context) {
            memoryIndexCache.clear()
            val dir = File(context.cacheDir, "stardict-index")
            dir.listFiles()?.forEach { runCatching { it.delete() } }
        }
    }

    private fun quickFingerprint(file: DocumentFile): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val length = file.length().coerceAtLeast(0L)
        digest.update(length.toString().toByteArray(StandardCharsets.UTF_8))

        val window = 64 * 1024
        val positions = linkedSetOf(
            0L,
            (length / 2L - window / 2L).coerceAtLeast(0L),
            (length - window).coerceAtLeast(0L)
        )

        val sampled = runCatching {
            resolver.openFileDescriptor(file.uri, "r")?.use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use { ch ->
                    val buf = ByteBuffer.allocate(window)
                    for (position in positions) {
                        if (position >= length) continue
                        buf.clear()
                        ch.position(position)
                        val wanted = minOf(window.toLong(), length - position).toInt()
                        buf.limit(wanted)
                        while (buf.hasRemaining()) {
                            if (ch.read(buf) < 0) break
                        }
                        digest.update(buf.array(), 0, buf.position())
                    }
                }
            }
            true
        }.getOrDefault(false)

        if (!sampled) {
            runCatching {
                resolver.openInputStream(file.uri)?.use { input ->
                    val buf = ByteArray(window)
                    var remaining = window * 2
                    while (remaining > 0) {
                        val n = input.read(buf, 0, minOf(buf.size, remaining))
                        if (n <= 0) break
                        digest.update(buf, 0, n)
                        remaining -= n
                    }
                }
            }
        }
        return digest.digest().take(12).joinToString("") { "%02x".format(it) }
    }

    private fun sha1(s: String): String {
        val d = MessageDigest.getInstance("SHA-1")
            .digest(s.toByteArray(StandardCharsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun loadCache(file: File): Boolean = runCatching {
        if (!file.isFile) return@runCatching false
        DataInputStream(BufferedInputStream(file.inputStream(), 256 * 1024)).use { input ->
            if (input.readInt() != 0x454E504C) return@runCatching false
            if (input.readInt() != 2) return@runCatching false
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
                out.writeInt(2)
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

    private fun sortHitsForLookup() {
        hits.sortWith(
            compareBy<Hit> { it.word.lowercase(Locale.ROOT) }
                .thenBy { it.word }
        )
    }

    private fun ciCompare(a: String, b: String): Int =
        a.lowercase(Locale.ROOT).compareTo(b.lowercase(Locale.ROOT))

    private fun lowerBound(prefixLower: String): Int {
        var lo = 0
        var hi = hits.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val w = hits[mid].word.lowercase(Locale.ROOT)
            if (w < prefixLower) lo = mid + 1 else hi = mid
        }
        return lo
    }

    internal fun openPrefixCursor(prefix: String): PrefixCursor {
        val p = prefix.trim().lowercase(Locale.ROOT)
        return PrefixCursor(p, lowerBound(p))
    }

    internal fun nextPrefix(cursor: PrefixCursor): String? {
        if (cursor.prefixLower.isEmpty()) return null
        if (cursor.index >= hits.size) return null
        val word = hits[cursor.index].word
        if (!word.lowercase(Locale.ROOT).startsWith(cursor.prefixLower)) return null
        cursor.index++
        return word
    }

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
        val cursor = openPrefixCursor(prefix)
        val out = ArrayList<String>(limit)
        while (out.size < limit) {
            val next = nextPrefix(cursor) ?: break
            out += next
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
