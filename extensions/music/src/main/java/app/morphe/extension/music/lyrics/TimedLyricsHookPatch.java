package app.morphe.extension.music.lyrics;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Extension hook receiver for the Custom Lyrics API patch.
 *
 * <h2>Architecture</h2>
 * <p>
 * YouTube Music uses <b>Chromium Cronet</b> for all networking.
 * Lyrics are fetched via the InnerTube Browse API:
 * <pre>POST https://music.youtube.com/youtubei/v1/browse</pre>
 * With a protobuf body containing:
 * <pre>browseId: "FEmusic_offline_lyrics_{trackId}"</pre>
 *
 * <p>
 * This extension bypasses Cronet entirely by using {@link HttpURLConnection}
 * to fetch lyrics from a custom API. This avoids:
 * <ul>
 *   <li>SSL pinning issues (Cronet pins YouTube certificates)</li>
 *   <li>Conflicts with {@code SpoofVideoStreamsPatch}</li>
 *   <li>Complex Cronet URL rewriting</li>
 * </ul>
 *
 * <h2>Protobuf Data Structure</h2>
 * <p>The lyrics UI expects a {@code bohp} protobuf message structured as:
 * <pre>
 * bohp (LyricsPage, field 153515154 on bxel)
 * ├── b: int           [bitmask flags]
 * ├── c: bohr          [LyricsSectionRenderer]
 * │   ├── b: int       [bitmask]
 * │   ├── c: boolean   [hasSyncedLyrics]
 * │   ├── d: String    [lyrics source / provider]
 * │   ├── e: String    [track name]
 * │   ├── f: boolean   [isTimeSynced flag]
 * │   ├── g: bohx      [LyricsBody]
 * │   │   ├── b: String [LRC-format timed lyrics text]
 * │   │   └── d: int    [format type]
 * │   ├── h: boolean   [has footer buttons]
 * │   └── i: String    [copyright / attribution]
 * └── d: bjvp          [serialized extension data]
 * </pre>
 *
 * <h2>Timed Lyrics Format</h2>
 * <p>The timed_lyrics_controller EML bundle expects <b>LRC format</b> in bohx.b:
 * <pre>
 * [00:04.50]First line of the song
 * [00:08.20]Second line of the song
 * [00:12.75]Third line with longer duration
 * </pre>
 *
 * <h2>Custom API Expected JSON Response</h2>
 * <pre>
 * {
 *   "synced": true,
 *   "source": "CustomLyricsProvider",
 *   "copyright": "Lyrics provided by CustomProvider",
 *   "track_name": "Song Title",
 *   "lines": [
 *     { "start_ms": 4500,  "text": "First line of the song" },
 *     { "start_ms": 8200,  "text": "Second line of the song" },
 *     { "start_ms": 12750, "text": "Third line with longer duration" }
 *   ],
 *   "plain_lyrics": "Full text fallback if not synced"
 * }
 * </pre>
 *
 * <h2>Field Mapping Table</h2>
 * <table>
 * <tr><th>Custom API JSON</th><th>Protobuf Class</th><th>Field</th><th>Type</th></tr>
 * <tr><td>lines[].text (LRC)</td><td>bohx</td><td>b</td><td>String</td></tr>
 * <tr><td>synced</td><td>bohr</td><td>f</td><td>boolean</td></tr>
 * <tr><td>synced</td><td>bohr</td><td>c</td><td>boolean</td></tr>
 * <tr><td>source</td><td>bohr</td><td>d</td><td>String</td></tr>
 * <tr><td>track_name</td><td>bohr</td><td>e</td><td>String</td></tr>
 * <tr><td>copyright</td><td>bohr</td><td>i</td><td>String</td></tr>
 * </table>
 */
@SuppressWarnings("unused")
public final class TimedLyricsHookPatch {

    private static final String TAG = "TimedLyricsHook";

    /**
     * ★ REPLACE THIS with your custom lyrics API URL.
     * The extension will append the track ID as a query parameter:
     *   {CUSTOM_API_URL}?trackId={trackId}
     */
    private static final String CUSTOM_API_URL = "INSERT_YOUR_API_URL_HERE";

    /**
     * User-Agent sent with custom API requests.
     * Uses a generic Android browser UA to avoid blocks.
     * YouTube Music's actual UA is:
     *   com.google.android.apps.youtube.music/VERSION (Linux; U; Android SDK; DEVICE) gzip
     * but using that for a non-Google API would be suspicious.
     */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    /** Connection timeout for custom API calls (ms). */
    private static final int CONNECT_TIMEOUT = 5000;

    /** Read timeout for custom API calls (ms). */
    private static final int READ_TIMEOUT = 8000;

    private TimedLyricsHookPatch() {
    }

    // ════════════════════════════════════════════════════════════════
    // HOOK 1: Primary Injection — Lyrics Request Interception
    // Called from rev.java at the point where the browse ID is resolved
    // ════════════════════════════════════════════════════════════════

    /**
     * Called when the player bottom sheet resolves a lyrics browse ID.
     *
     * <p>This is the <b>primary injection point</b>. The browse ID follows
     * the pattern {@code "FEmusic_offline_lyrics_{trackId}"}.
     *
     * <p>If this method returns non-null bytes, they are used to construct
     * a {@code bohp} protobuf response, bypassing YouTube's lyrics server.
     * If it returns null, the original YouTube pipeline is used.
     *
     * @param browseId The full browse ID (e.g., "FEmusic_offline_lyrics_dQw4w9WgXcQ")
     * @return protobuf bytes for a {@code bohp} message, or null to use YouTube's lyrics
     */
    public static byte[] onLyricsRequest(String browseId) {
        if (browseId == null || !browseId.startsWith("FEmusic_offline_lyrics_")) {
            return null; // Not a lyrics request; pass through.
        }

        String trackId = browseId.replace("FEmusic_offline_lyrics_", "");
        if (trackId.isEmpty()) {
            Log.w(TAG, "Empty track ID in browse ID: " + browseId);
            return null;
        }

        Log.d(TAG, "Intercepting lyrics request for trackId: " + trackId);

        try {
            // Fetch lyrics from custom API.
            String jsonResponse = fetchLyricsFromApi(trackId);
            if (jsonResponse == null) {
                Log.w(TAG, "Custom API returned null for trackId: " + trackId);
                return null; // Fallback to YouTube.
            }

            // Convert JSON response → bohp protobuf bytes.
            byte[] protobufBytes = convertJsonToProtobuf(jsonResponse);
            if (protobufBytes == null || protobufBytes.length == 0) {
                Log.w(TAG, "Failed to convert API response to protobuf for trackId: " + trackId);
                return null; // Fallback to YouTube.
            }

            Log.d(TAG, "Successfully injected custom lyrics for trackId: " + trackId
                    + " (" + protobufBytes.length + " bytes)");
            return protobufBytes;

        } catch (Exception e) {
            // Graceful fallback: if ANYTHING fails, let YouTube handle it.
            Log.e(TAG, "Error fetching custom lyrics for trackId: " + trackId, e);
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // CUSTOM API FETCHER
    // Uses HttpURLConnection (NOT Cronet) to bypass SSL pinning
    // ════════════════════════════════════════════════════════════════

    /**
     * Fetches lyrics from the custom API.
     *
     * <p>Uses {@link HttpURLConnection} instead of Cronet to:
     * <ul>
     *   <li>Bypass YouTube's SSL certificate pinning</li>
     *   <li>Avoid conflicts with {@code SpoofVideoStreamsPatch}</li>
     *   <li>Use a separate networking stack entirely</li>
     * </ul>
     *
     * @param trackId The YouTube video/track ID
     * @return JSON response string, or null if the request fails
     */
    private static String fetchLyricsFromApi(String trackId) {
        HttpURLConnection connection = null;
        try {
            String apiUrl = CUSTOM_API_URL + "?trackId=" + trackId;
            URL url = new URL(apiUrl);

            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);

            // Set headers to avoid being blocked.
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");

            // Optional: Add API key or auth token.
            // connection.setRequestProperty("Authorization", "Bearer YOUR_API_KEY");
            // connection.setRequestProperty("X-API-Key", "YOUR_API_KEY");

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Custom API returned HTTP " + responseCode + " for trackId: " + trackId);
                return null;
            }

            // Read the response body.
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }

            return sb.toString();

        } catch (java.net.SocketTimeoutException e) {
            Log.w(TAG, "Custom API timed out for trackId: " + trackId);
            return null;
        } catch (java.io.IOException e) {
            Log.w(TAG, "Network error fetching from custom API for trackId: " + trackId, e);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error in fetchLyricsFromApi", e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // JSON → PROTOBUF CONVERTER
    // Converts custom API JSON into bohp protobuf wire format
    // ════════════════════════════════════════════════════════════════

    /**
     * Converts a custom API JSON response into a {@code bohp} protobuf byte array.
     *
     * <p>Instead of using the obfuscated protobuf builder classes (which would break
     * across APK versions), we construct the <b>raw protobuf wire format</b> manually.
     * This is version-independent as long as the proto field numbers don't change.
     *
     * <h3>Target Protobuf Structure</h3>
     * <pre>
     * bohp {
     *   field 3 (bohr, MESSAGE) {          // LyricsSectionRenderer
     *     field 6  (bool)   = true          // hasSyncedLyrics
     *     field 10 (string) = source        // lyrics source/provider
     *     field 13 (string) = track_name    // track name
     *     field 15 (bool)   = true          // isTimeSynced
     *     field 18 (string) = copyright     // copyright text
     *     field 19 (bool)   = false         // hasFooterButtons
     *     field 20 (bohx, MESSAGE) {        // LyricsBody
     *       field 1 (string) = lrc_text     // the actual lyrics in LRC format
     *     }
     *   }
     * }
     * </pre>
     *
     * <h3>Protobuf Wire Format Reference</h3>
     * <ul>
     *   <li>Varint: wire type 0 (bool, int32, int64, enum)</li>
     *   <li>Length-delimited: wire type 2 (string, bytes, embedded messages)</li>
     *   <li>Field key = (field_number &lt;&lt; 3) | wire_type</li>
     * </ul>
     *
     * @param jsonResponse The JSON response from the custom API
     * @return Raw protobuf bytes representing a {@code bohp} message, or null on error
     */
    private static byte[] convertJsonToProtobuf(String jsonResponse) {
        try {
            JSONObject json = new JSONObject(jsonResponse);

            boolean isSynced = json.optBoolean("synced", false);
            String source = json.optString("source", "Custom Lyrics Provider");
            String copyright = json.optString("copyright", "");
            String trackName = json.optString("track_name", "");

            // Build the lyrics text in LRC format.
            String lyricsText;
            if (isSynced && json.has("lines")) {
                lyricsText = buildLrcText(json.getJSONArray("lines"));
            } else {
                lyricsText = json.optString("plain_lyrics", "");
            }

            if (lyricsText.isEmpty()) {
                Log.w(TAG, "No lyrics text found in API response");
                return null;
            }

            // ─── Build bohx (LyricsBody) ───
            // Field 1 (string): lrc_text
            byte[] lrcBytes = lyricsText.getBytes(StandardCharsets.UTF_8);
            byte[] bohxPayload = encodeString(1, lrcBytes);

            // ─── Build bohr (LyricsSectionRenderer) ───
            byte[][] bohrFields = new byte[][] {
                    encodeBool(6, isSynced),                                     // hasSyncedLyrics
                    encodeString(10, source.getBytes(StandardCharsets.UTF_8)),    // source
                    encodeString(13, trackName.getBytes(StandardCharsets.UTF_8)), // trackName
                    encodeBool(15, isSynced),                                    // isTimeSynced
                    encodeString(18, copyright.getBytes(StandardCharsets.UTF_8)), // copyright
                    encodeBool(19, false),                                       // hasFooterButtons
                    encodeMessage(20, bohxPayload),                              // LyricsBody
            };
            byte[] bohrPayload = concatBytes(bohrFields);

            // ─── Build bohp (LyricsPage) ───
            // Field 3 (message): bohr (LyricsSectionRenderer)
            byte[] bohpPayload = encodeMessage(3, bohrPayload);

            Log.d(TAG, "Built protobuf: " + bohpPayload.length + " bytes, "
                    + "synced=" + isSynced + ", lines=" + lyricsText.split("\n").length);

            return bohpPayload;

        } catch (Exception e) {
            Log.e(TAG, "Error converting JSON to protobuf", e);
            return null;
        }
    }

    /**
     * Converts an array of timed lyric lines into LRC format.
     *
     * <p>Expected JSON format:
     * <pre>
     * [
     *   { "start_ms": 4500,  "text": "First line" },
     *   { "start_ms": 8200,  "text": "Second line" }
     * ]
     * </pre>
     *
     * <p>Output LRC format:
     * <pre>
     * [00:04.50]First line
     * [00:08.20]Second line
     * </pre>
     *
     * @param lines JSONArray of lyric line objects
     * @return LRC-formatted string
     */
    private static String buildLrcText(JSONArray lines) throws Exception {
        StringBuilder lrc = new StringBuilder();

        for (int i = 0; i < lines.length(); i++) {
            JSONObject line = lines.getJSONObject(i);
            long startMs = line.getLong("start_ms");
            String text = line.getString("text");

            // Convert milliseconds to [mm:ss.cs] format.
            long totalSeconds = startMs / 1000;
            long minutes = totalSeconds / 60;
            long seconds = totalSeconds % 60;
            long centiseconds = (startMs % 1000) / 10;

            lrc.append(String.format("[%02d:%02d.%02d]%s\n", minutes, seconds, centiseconds, text));
        }

        return lrc.toString().trim();
    }

    // ════════════════════════════════════════════════════════════════
    // PROTOBUF WIRE FORMAT ENCODER
    // Manual protobuf serialization (no dependency on obfuscated classes)
    // ════════════════════════════════════════════════════════════════

    /**
     * Encode a varint (variable-length integer) into bytes.
     */
    private static byte[] encodeVarint(long value) {
        if (value == 0) return new byte[]{0};

        // Max 10 bytes for a 64-bit varint.
        byte[] buf = new byte[10];
        int pos = 0;
        while (value != 0) {
            byte b = (byte) (value & 0x7F);
            value >>>= 7;
            if (value != 0) b |= 0x80;
            buf[pos++] = b;
        }
        byte[] result = new byte[pos];
        System.arraycopy(buf, 0, result, 0, pos);
        return result;
    }

    /**
     * Encode a field key (field_number << 3 | wire_type).
     */
    private static byte[] encodeFieldKey(int fieldNumber, int wireType) {
        return encodeVarint(((long) fieldNumber << 3) | wireType);
    }

    /**
     * Encode a boolean value as a varint field.
     * Wire type 0 (varint).
     */
    private static byte[] encodeBool(int fieldNumber, boolean value) {
        byte[] key = encodeFieldKey(fieldNumber, 0); // wire type 0 = varint
        byte[] val = encodeVarint(value ? 1 : 0);
        return concatTwo(key, val);
    }

    /**
     * Encode a string value as a length-delimited field.
     * Wire type 2 (length-delimited).
     */
    private static byte[] encodeString(int fieldNumber, byte[] stringBytes) {
        byte[] key = encodeFieldKey(fieldNumber, 2); // wire type 2 = length-delimited
        byte[] len = encodeVarint(stringBytes.length);
        byte[] result = new byte[key.length + len.length + stringBytes.length];
        System.arraycopy(key, 0, result, 0, key.length);
        System.arraycopy(len, 0, result, key.length, len.length);
        System.arraycopy(stringBytes, 0, result, key.length + len.length, stringBytes.length);
        return result;
    }

    /**
     * Encode an embedded message as a length-delimited field.
     * Wire type 2 (length-delimited).
     */
    private static byte[] encodeMessage(int fieldNumber, byte[] messageBytes) {
        return encodeString(fieldNumber, messageBytes); // Same encoding as string.
    }

    /**
     * Concatenate two byte arrays.
     */
    private static byte[] concatTwo(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    /**
     * Concatenate multiple byte arrays.
     */
    private static byte[] concatBytes(byte[][] arrays) {
        int totalLen = 0;
        for (byte[] arr : arrays) totalLen += arr.length;
        byte[] result = new byte[totalLen];
        int pos = 0;
        for (byte[] arr : arrays) {
            System.arraycopy(arr, 0, result, pos, arr.length);
            pos += arr.length;
        }
        return result;
    }

    // ════════════════════════════════════════════════════════════════
    // SECONDARY HOOKS (Logging / Debugging)
    // ════════════════════════════════════════════════════════════════

    /**
     * Called when {@code MusicLyricsEntityModel.getClientLyricsData()} is invoked.
     * The parameter is the ClientLyricsData protobuf object (obfuscated: bmrf).
     *
     * @param clientLyricsData The ClientLyricsData protobuf instance.
     */
    public static void onLyricsDataAccessed(Object clientLyricsData) {
        try {
            Log.d(TAG, "Lyrics entity data accessed: " + clientLyricsData.getClass().getName());

            // Debug: serialize to bytes to inspect the protobuf structure.
            // Method toByteArray = clientLyricsData.getClass().getMethod("toByteArray");
            // byte[] rawProto = (byte[]) toByteArray.invoke(clientLyricsData);
            // Log.d(TAG, "ClientLyricsData proto size: " + rawProto.length + " bytes");

        } catch (Exception e) {
            Log.e(TAG, "Error in onLyricsDataAccessed", e);
        }
    }

    /**
     * Called when the offline lyrics detection checks a browse ID.
     *
     * @param browseId The browse ID (pattern: "FEmusic_offline_lyrics_{trackId}")
     */
    public static void onLyricsBrowseIdChecked(String browseId) {
        Log.d(TAG, "Lyrics browse ID checked: " + browseId);
    }

    /**
     * Called when the lyrics tab protobuf is constructed.
     *
     * @param trackId The video/track ID.
     */
    public static void onLyricsTabBuilt(String trackId) {
        Log.d(TAG, "Lyrics tab built for track: " + trackId);
    }
}
