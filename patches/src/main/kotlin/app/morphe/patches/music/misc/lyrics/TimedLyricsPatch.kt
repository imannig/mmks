package app.morphe.patches.music.misc.lyrics

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstLiteralInstructionOrThrow
import app.morphe.util.indexOfFirstStringInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

// Extension class descriptor for the hook receiver.
private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/music/lyrics/TimedLyricsHookPatch;"

/**
 * ReVanced Patch: Custom Lyrics API Interception & Injection
 *
 * Intercepts YouTube Music's lyrics pipeline at multiple levels to:
 * 1. Redirect lyrics fetching to a custom API
 * 2. Convert custom API JSON responses → YouTube's internal protobuf format (bohp/bohr/bohx)
 * 3. Inject the converted data back into the rendering pipeline
 * 4. Fall back gracefully to YouTube's lyrics if the custom API fails
 *
 * ## Architecture:
 *
 * YouTube Music uses **Chromium Cronet** for all networking (not OkHttp/Retrofit).
 * Lyrics are fetched via the Innertube Browse API endpoint:
 *   POST https://music.youtube.com/youtubei/v1/browse
 *   Body: protobuf with browseId = "FEmusic_offline_lyrics_{trackId}"
 *
 * Rather than manipulating Cronet internals (which conflicts with SpoofVideoStreamsPatch),
 * we intercept at the **DownloadsLyricsPageProvider** level (rev.java → lte check).
 * Our extension uses its own HttpURLConnection to call the custom API,
 * bypassing Cronet entirely — avoiding SSL pinning and conflicts.
 *
 * ## Protobuf Data Structure (server → UI):
 *
 * ```
 * bohp (LyricsPage)
 *  └── bohr (LyricsSectionRenderer)
 *       ├── bohx.b: String   [LRC-format timed lyrics text]
 *       ├── bohr.c: boolean  [hasSyncedLyrics]
 *       ├── bohr.d: String   [source attribution]
 *       ├── bohr.e: String   [track name]
 *       ├── bohr.f: boolean  [isTimeSynced]
 *       └── bohr.i: String   [copyright text]
 * ```
 *
 * The timed_lyrics_controller (EML bundle) parses LRC timestamps from bohx.b
 * and syncs highlight/scroll with player currentPosition.
 */
val timedLyricsPatch = bytecodePatch(
    name = "Custom lyrics API",
    description = "Intercepts lyrics requests and redirects them to a custom API for timed/synced lyrics.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        // ──────────────────────────────────────────────────────────────
        // Hook 1: Lyrics Renderer Loading Interception (rev.java)
        //
        // This is the PRIMARY INJECTION POINT. When the player bottom
        // sheet loads lyrics content and detects the browse ID starts
        // with "FEmusic_offline_lyrics_", our extension intercepts
        // the track ID and can provide a fully constructed bohp
        // protobuf response from the custom API.
        //
        // The hook is placed inside the method that resolves lyrics
        // content for the bottom sheet tab. We inject BEFORE the
        // offline lyrics provider is called, giving us the chance
        // to return custom data or let the original flow continue.
        //
        // Original flow (rev.java:544-577):
        //   if (lte.a(str)) {
        //       String trackId = str.replace("FEmusic_offline_lyrics_", "");
        //       // fetch via DownloadsLyricsPageProvider
        //   } else {
        //       // online fetch via InnerTube browse
        //   }
        //
        // After patch:
        //   Extension.onLyricsRequest(browseId) is called first.
        //   If extension returns custom bohp bytes, we short-circuit.
        //   If extension returns null, the original flow continues.
        // ──────────────────────────────────────────────────────────────
        LyricsRendererTypeCheckFingerprint.let { fingerprint ->
            val method = fingerprint.method

            // Find the string "FEmusic_offline_lyrics_" usage — this is where
            // the lyrics browse ID is being extracted for processing.
            val lyricsStringIndex = method.indexOfFirstStringInstructionOrThrow(
                "FEmusic_offline_lyrics_"
            )

            // Walk backwards to find the register holding the browse ID string.
            // The browse ID is the `str` variable used in lte.a(str) check.
            val checkIndex = method.indexOfFirstInstructionOrThrow(lyricsStringIndex) {
                opcode == Opcode.INVOKE_VIRTUAL &&
                        getReference<MethodReference>()?.name == "replace"
            }

            // The register with the browse ID is the instance register of String.replace()
            val browseIdRegister = method.getInstruction<FiveRegisterInstruction>(checkIndex).registerC

            // Inject our hook at the point where we have the raw browse ID string,
            // BEFORE the lte.a() check. This lets us intercept ALL lyrics requests.
            // We pass the full browse ID to the extension which:
            //   1. Extracts trackId from "FEmusic_offline_lyrics_{trackId}"
            //   2. Calls custom API with trackId
            //   3. Returns custom bohp protobuf bytes, or null to pass through
            method.addInstructions(
                lyricsStringIndex,
                """
                    invoke-static { v$browseIdRegister }, $EXTENSION_CLASS_DESCRIPTOR->onLyricsRequest(Ljava/lang/String;)[B
                """
            )
        }

        // ──────────────────────────────────────────────────────────────
        // Hook 2: MusicLyricsEntityModel.getClientLyricsData()
        //
        // Secondary hook: intercepts entity data access from the
        // entity store. Useful for logging/debugging the original
        // lyrics data structure when developing the converter.
        // ──────────────────────────────────────────────────────────────
        val lyricsEntityClass = MusicLyricsEntityModelFingerprint.classDef
        val getClientLyricsMethod = lyricsEntityClass.methods.first {
            it.name == "getClientLyricsData"
        }

        val returnInstructions = getClientLyricsMethod.instructions.toList()
        val lastReturn = returnInstructions.indexOfLast {
            it.opcode == Opcode.RETURN_OBJECT
        }

        if (lastReturn >= 0) {
            val returnRegister = getClientLyricsMethod
                .getInstruction<OneRegisterInstruction>(lastReturn).registerA

            getClientLyricsMethod.addInstructions(
                lastReturn,
                """
                    invoke-static { v$returnRegister }, $EXTENSION_CLASS_DESCRIPTOR->onLyricsDataAccessed(Ljava/lang/Object;)V
                """
            )
        }

        // ──────────────────────────────────────────────────────────────
        // Hook 3: Offline lyrics detection (lte.a)
        //
        // Intercepts the boolean check to log every browse ID that
        // passes through the lyrics detection gate.
        // ──────────────────────────────────────────────────────────────
        OfflineLyricsDetectionFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->onLyricsBrowseIdChecked(Ljava/lang/String;)V
            """
        )

        // ──────────────────────────────────────────────────────────────
        // Hook 4: Lyrics tab construction (njk.e)
        //
        // Intercepts lyrics tab builder to capture track ID when
        // a lyrics tab is being constructed. Useful for pre-fetching.
        // ──────────────────────────────────────────────────────────────
        LyricsTabBuilderFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS_DESCRIPTOR->onLyricsTabBuilt(Ljava/lang/String;)V
            """
        )
    }
}
