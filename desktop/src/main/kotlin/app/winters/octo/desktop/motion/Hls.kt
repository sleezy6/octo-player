package app.winters.octo.desktop.motion

import okhttp3.HttpUrl

// Reading the HLS playlists Apple Music serves animated covers through.
// Only what is needed to save one copy of the loop whole: the master
// playlist's video copies, and the pieces of the chosen one's file.

// One copy of the video the master playlist offers.
data class HlsVariant(
    val url: HttpUrl,
    val codecs: String,
    val width: Int,
    val height: Int,
    val bandwidth: Long,
    // Standard range: HDR copies are left alone, which this app could not
    // show as meant.
    val sdr: Boolean,
) {
    // H.264, the one codec the engine decodes. Apple offers each loop in
    // H.264 and HEVC.
    val h264: Boolean get() = codecs.split(',').any { it.trim().startsWith("avc1") || it.trim().startsWith("avc3") }

    // The smaller side, which is what has to cover the box the loop fills.
    val side: Int get() = minOf(width, height)
}

// The copies a master playlist offers, each address made whole against
// the playlist's own. The I-frame-only copies (for scrubbing) are left out.
fun hlsVariants(master: String, base: HttpUrl): List<HlsVariant> {
    val lines = master.lines().map(String::trim)
    val found = mutableListOf<HlsVariant>()
    for ((i, line) in lines.withIndex()) {
        if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
        val attributes = hlsAttributes(line.substringAfter(':'))
        val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
        val url = base.resolve(uri) ?: continue
        val (width, height) = attributes["RESOLUTION"]?.split('x')?.mapNotNull(String::toIntOrNull)?.takeIf { it.size == 2 }
            ?.let { it[0] to it[1] } ?: (0 to 0)
        found += HlsVariant(
            url = url,
            codecs = attributes["CODECS"].orEmpty(),
            width = width,
            height = height,
            bandwidth = attributes["AVERAGE-BANDWIDTH"]?.toLongOrNull() ?: attributes["BANDWIDTH"]?.toLongOrNull() ?: 0,
            sdr = attributes["VIDEO-RANGE"]?.equals("SDR", ignoreCase = true) ?: true,
        )
    }
    return found
}

// The copy to save for a box `px` pixels on its smaller side: H.264 in
// standard range, the smallest that still covers the box (or the largest
// when none does), and of those the one with the most detail. Null when
// there is no H.264 copy at all.
fun pickVariant(variants: List<HlsVariant>, px: Int): HlsVariant? {
    val usable = variants.filter { it.h264 && it.sdr && it.side > 0 }
    if (usable.isEmpty()) return null
    val covering = usable.filter { it.side >= px }
    val side = if (covering.isNotEmpty()) covering.minOf { it.side } else usable.maxOf { it.side }
    return usable.filter { it.side == side }.maxBy { it.bandwidth }
}

// A part of a file to fetch: all of it, or `length` bytes from `offset`.
data class HlsPiece(val url: HttpUrl, val offset: Long? = null, val length: Long? = null) {
    val end: Long? get() = if (offset != null && length != null) offset + length else null
}

// The pieces of a media playlist in the order they join into one file: the
// setup (EXT-X-MAP) first, then each segment. A byte range with no offset
// follows on from the last range of the same file, as HLS has it.
fun hlsPieces(media: String, base: HttpUrl): List<HlsPiece> {
    val pieces = mutableListOf<HlsPiece>()
    val ends = mutableMapOf<HttpUrl, Long>()
    fun piece(url: HttpUrl, range: String?): HlsPiece {
        if (range == null) return HlsPiece(url)
        val length = range.substringBefore('@').trim().toLong()
        val offset = range.substringAfter('@', "").trim().toLongOrNull() ?: ends[url] ?: 0
        ends[url] = offset + length
        return HlsPiece(url, offset, length)
    }
    var range: String? = null
    for (raw in media.lines()) {
        val line = raw.trim()
        when {
            line.startsWith("#EXT-X-MAP:") -> {
                val attributes = hlsAttributes(line.substringAfter(':'))
                val url = attributes["URI"]?.let(base::resolve) ?: continue
                pieces += piece(url, attributes["BYTERANGE"])
            }
            line.startsWith("#EXT-X-BYTERANGE:") -> range = line.substringAfter(':')
            line.startsWith("#EXT-X-KEY:") -> {
                // An encrypted loop would need its keys; Apple's covers have
                // none, and one that does is not saved as if it could play.
                val method = hlsAttributes(line.substringAfter(':'))["METHOD"]
                if (method != null && !method.equals("NONE", ignoreCase = true)) return emptyList()
            }
            line.isEmpty() || line.startsWith("#") -> Unit
            else -> {
                val url = base.resolve(line) ?: continue
                pieces += piece(url, range)
                range = null
            }
        }
    }
    return pieces
}

// The same pieces with neighbours in one file joined: Apple keeps each
// loop as one file named over and over, so this comes to a single request.
fun joinedPieces(pieces: List<HlsPiece>): List<HlsPiece> {
    val joined = mutableListOf<HlsPiece>()
    for (piece in pieces) {
        val last = joined.lastOrNull()
        if (last != null && last.url == piece.url && last.end != null && piece.offset == last.end && piece.length != null) {
            joined[joined.lastIndex] = last.copy(length = last.length!! + piece.length)
        } else {
            joined += piece
        }
    }
    return joined
}

// A tag's attribute list: NAME=value pairs split on commas, where a quoted
// value may hold commas of its own (CODECS="avc1.64001f,mp4a.40.2").
internal fun hlsAttributes(list: String): Map<String, String> {
    val found = mutableMapOf<String, String>()
    var i = 0
    while (i < list.length) {
        val equals = list.indexOf('=', i)
        if (equals < 0) break
        val name = list.substring(i, equals).trim()
        var j = equals + 1
        val value: String
        if (j < list.length && list[j] == '"') {
            val close = list.indexOf('"', j + 1).let { if (it < 0) list.length else it }
            value = list.substring(j + 1, close)
            j = close + 1
        } else {
            val comma = list.indexOf(',', j).let { if (it < 0) list.length else it }
            value = list.substring(j, comma).trim()
            j = comma
        }
        found[name] = value
        val next = list.indexOf(',', j)
        i = if (next < 0) list.length else next + 1
    }
    return found
}
