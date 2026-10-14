package app.winters.octo.subsonic

import kotlinx.serialization.Serializable

// The OpenSubsonic extension an Octo server lists while it can find Apple
// Music's animated album covers (its Metadata:AnimatedArtworkUrl is set).
const val OCTO_ANIMATED_ARTWORK = "octoAnimatedArtwork"

// An album's animated cover as Octo gives it: HLS playlists of the square
// loop and the tall one, either of which may be missing.
@Serializable
data class AnimatedArtwork(val url: String? = null, val urlTall: String? = null)

// The animated cover of an album, by its id (in the library or found
// online), or by its artist and name when there is no id. Null when the
// album has none, or the server does not know the album. Throws when the
// server could not say just now (its artwork server did not answer).
suspend fun SubsonicClient.animatedArtwork(albumId: String?, artist: String?, album: String?): AnimatedArtwork? {
    val params = if (!albumId.isNullOrBlank()) {
        mapOf("id" to albumId)
    } else {
        mapOf("artist" to artist.orEmpty(), "album" to album.orEmpty())
    }
    return try {
        getOptional("getAnimatedArtwork", params, "animatedArtwork", AnimatedArtwork.serializer())
    } catch (e: SubsonicException.NotFound) {
        null
    }
}
