package app.winters.octo.desktop.motion

import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// A shortened copy of the playlists Apple Music serves an animated cover
// through: H.264 and HEVC copies at several sizes, the I-frame copies for
// scrubbing, and each copy one file in byte ranges.
class HlsTest {
    private val masterUrl = "https://mvod.itunes.apple.com/assets/a/P1_default.m3u8".toHttpUrl()

    private val master = """
        #EXTM3U
        #EXT-X-VERSION:7
        #EXT-X-INDEPENDENT-SEGMENTS

        #EXT-X-I-FRAME-STREAM-INF:AVERAGE-BANDWIDTH=515068,BANDWIDTH=548783,VIDEO-RANGE=SDR,CODECS="avc1.64001f",RESOLUTION=486x486,URI="https://mvod.itunes.apple.com/assets/i/P1_iframes.m3u8"

        #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=2131276,BANDWIDTH=2319795,VIDEO-RANGE=SDR,CLOSED-CAPTIONS=NONE,CODECS="avc1.64001f",FRAME-RATE=24.000,RESOLUTION=768x768
        https://mvod.itunes.apple.com/assets/b/P1_video_gr240_sdr_768x768.m3u8
        #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=1526948,BANDWIDTH=1791820,VIDEO-RANGE=SDR,CLOSED-CAPTIONS=NONE,CODECS="hvc1.2.20000000.L123.B0",FRAME-RATE=24.000,RESOLUTION=768x768
        https://mvod.itunes.apple.com/assets/c/P1_video_gr540_sdr_768x768.m3u8
        #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=761716,BANDWIDTH=845140,VIDEO-RANGE=SDR,CODECS="avc1.64001f",FRAME-RATE=24.000,RESOLUTION=486x486
        https://mvod.itunes.apple.com/assets/d/P1_video_gr210_sdr_486x486.m3u8
        #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=1114381,BANDWIDTH=1307159,VIDEO-RANGE=SDR,CODECS="avc1.64001f",FRAME-RATE=24.000,RESOLUTION=486x486
        https://mvod.itunes.apple.com/assets/e/P1_video_gr220_sdr_486x486.m3u8
        #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=3906377,BANDWIDTH=4242783,VIDEO-RANGE=SDR,CODECS="avc1.640020",FRAME-RATE=24.000,RESOLUTION=1080x1080
        https://mvod.itunes.apple.com/assets/f/P1_video_gr265_sdr_1080x1080.m3u8
        #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=9000000,BANDWIDTH=9900000,VIDEO-RANGE=PQ,CODECS="avc1.640020",FRAME-RATE=24.000,RESOLUTION=2048x2048
        relative/P1_video_hdr_2048.m3u8
    """.trimIndent()

    private val variants = hlsVariants(master, masterUrl)

    @Test
    fun readsEveryCopyButTheScrubbingOnes() {
        assertEquals(6, variants.size)
        val first = variants.first()
        assertEquals("https://mvod.itunes.apple.com/assets/b/P1_video_gr240_sdr_768x768.m3u8", first.url.toString())
        assertEquals(768, first.width)
        assertEquals(2131276, first.bandwidth)
        assertTrue(first.h264)
        // Relative addresses are made whole against the master's.
        assertEquals("https://mvod.itunes.apple.com/assets/a/relative/P1_video_hdr_2048.m3u8", variants.last().url.toString())
        assertTrue(!variants.last().sdr)
    }

    @Test
    fun picksTheSmallestH264CopyThatCoversTheBox() {
        assertEquals("P1_video_gr240_sdr_768x768.m3u8", pickVariant(variants, 600)!!.url.pathSegments.last())
        assertEquals("P1_video_gr240_sdr_768x768.m3u8", pickVariant(variants, 768)!!.url.pathSegments.last())
        assertEquals("P1_video_gr265_sdr_1080x1080.m3u8", pickVariant(variants, 900)!!.url.pathSegments.last())
    }

    @Test
    fun ofTwoAtOneSizeTakesTheOneWithMoreDetail() {
        assertEquals("P1_video_gr220_sdr_486x486.m3u8", pickVariant(variants, 300)!!.url.pathSegments.last())
    }

    @Test
    fun aBoxBiggerThanEveryCopyGetsTheLargestStandardRangeOne() {
        // Not the 2048 HDR copy.
        assertEquals(1080, pickVariant(variants, 3000)!!.side)
    }

    @Test
    fun noH264CopyMeansNothingToPick() {
        assertNull(pickVariant(variants.filterNot { it.h264 }, 600))
    }

    private val mediaUrl = "https://mvod.itunes.apple.com/assets/b/P1_video_gr240_sdr_768x768.m3u8".toHttpUrl()

    private val media = """
        #EXTM3U
        #EXT-X-TARGETDURATION:3
        #EXT-X-VERSION:7
        #EXT-X-MEDIA-SEQUENCE:0
        #EXT-X-PLAYLIST-TYPE:VOD
        #EXT-X-INDEPENDENT-SEGMENTS
        #EXT-X-MAP:URI="P1_video_gr240_sdr_768x768-.mp4",BYTERANGE="896@0"
        #EXTINF:2.83333,
        #EXT-X-BYTERANGE:662307@896
        P1_video_gr240_sdr_768x768-.mp4
        #EXTINF:2.83333,
        #EXT-X-BYTERANGE:764684@663203
        P1_video_gr240_sdr_768x768-.mp4
        #EXTINF:2.83333,
        #EXT-X-BYTERANGE:778094
        P1_video_gr240_sdr_768x768-.mp4
        #EXT-X-ENDLIST
    """.trimIndent()

    @Test
    fun readsTheSetupThenEachSegment() {
        val pieces = hlsPieces(media, mediaUrl)
        assertEquals(4, pieces.size)
        val file = "https://mvod.itunes.apple.com/assets/b/P1_video_gr240_sdr_768x768-.mp4".toHttpUrl()
        assertEquals(HlsPiece(file, 0, 896), pieces[0])
        assertEquals(HlsPiece(file, 896, 662307), pieces[1])
        // A range without an offset follows on from the one before.
        assertEquals(HlsPiece(file, 663203 + 764684, 778094), pieces[3])
    }

    @Test
    fun oneFileInRangesBecomesOneRequest() {
        val joined = joinedPieces(hlsPieces(media, mediaUrl))
        assertEquals(1, joined.size)
        assertEquals(0L, joined[0].offset)
        assertEquals(663203L + 764684 + 778094, joined[0].end)
    }

    @Test
    fun separateSegmentFilesStaySeparate() {
        val pieces = hlsPieces("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:3,\nseg1.m4s\n#EXTINF:3,\nseg2.m4s\n", mediaUrl)
        assertEquals(listOf("init.mp4", "seg1.m4s", "seg2.m4s"), joinedPieces(pieces).map { it.url.pathSegments.last() })
        assertTrue(pieces.all { it.offset == null })
    }

    @Test
    fun anEncryptedLoopIsNotSaved() {
        val encrypted = "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\"\n#EXTINF:3,\nseg1.m4s\n"
        assertTrue(hlsPieces(encrypted, mediaUrl).isEmpty())
    }

    @Test
    fun quotedValuesKeepTheirCommas() {
        val attributes = hlsAttributes("BANDWIDTH=1,CODECS=\"avc1.64001f,mp4a.40.2\",RESOLUTION=10x20")
        assertEquals("avc1.64001f,mp4a.40.2", attributes["CODECS"])
        assertEquals("10x20", attributes["RESOLUTION"])
        assertEquals("1", attributes["BANDWIDTH"])
    }
}
