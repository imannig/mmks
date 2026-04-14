package app.morphe.patches.music.misc.lyrics

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.literal
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Fingerprint: MusicLyricsEntityModel.getClientLyricsData()
 *
 * Targets: bual.getClientLyricsData()
 * The toString() method in the same class contains "MusicLyricsEntityModel{".
 */
internal object MusicLyricsEntityModelFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = emptyList(),
    strings = listOf("MusicLyricsEntityModel{"),
)

/**
 * Fingerprint: DownloadsLyricsPageProvider check
 *
 * Targets: lte.a(String) — checks if browse ID starts with "FEmusic_offline_lyrics_"
 */
internal object OfflineLyricsDetectionFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        string("FEmusic_offline_lyrics_")
    ),
)

/**
 * Fingerprint: LyricsTabBuilder.e(String)
 *
 * Targets: njk.e(String) — builds the lyrics tab protobuf payload.
 * Identified by literals 95102 (icon resource) and 3832 (service endpoint).
 */
internal object LyricsTabBuilderFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        literal(95102L),
        literal(3832L),
    ),
)

/**
 * Fingerprint: PlayerBottomSheetTabsPresenter lyrics renderer type check
 *
 * Targets: rev — renders the lyrics tab.
 * Identified by literal 153515154 (protobuf field number for lyrics renderer bohp).
 * Additionally requires "FEmusic_offline_lyrics_" string to disambiguate from
 * other classes that reference 153515154.
 */
internal object LyricsRendererTypeCheckFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        literal(153515154L),
    ),
    strings = listOf(
        "FEmusic_offline_lyrics_",
    ),
)

/**
 * Fingerprint: VideoActionBarPresenter — the BrowseModel toString() in kmv
 * reveals "BrowseModel" identity. The rev class references this via the lyrics
 * loading path.
 */
internal object BrowseModelFingerprint : Fingerprint(
    strings = listOf(
        "BrowseModel",
        "navigationCommand",
        "deeplinkUrl",
    ),
)

/**
 * Fingerprint: Cronet request URL builder (airm.e)
 *
 * This is the central method that builds Cronet UrlRequest objects.
 * Identified by: invokes CronetEngine.newUrlRequestBuilder and sets
 * Content-Type, If-None-Match, If-Modified-Since headers.
 *
 * Note: The shared SpoofVideoStreamsPatch's BuildRequestFingerprint
 * may also target this same method. Our patch must be compatible.
 */
internal object CronetBrowseRequestFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Lorg/chromium/net/UrlRequest\$Builder;",
    custom = { methodDef, _ ->
        val parameterTypes = methodDef.parameterTypes
        parameterTypes.size >= 5 &&
                parameterTypes[1] == "Ljava/util/Map;" // URL headers
    }
)
