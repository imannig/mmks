/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.Word;
import app.morphe.extension.shared.Logger;

/**
 * Parser for the LRC format used by both LRCLIB and KuGou.
 */
public final class LrcParser {

    /** Tags such as {@code [ar:Artist]} that are not timestamps. */
    private static final String METADATA_TAG_CHARACTERS = "abcdefghijklmnopqrstuvwxyz";

    static final Set<String> CREDIT_META_KEYS = Set.of("ti", "ar", "al", "au");

    static final Pattern LRC_META = Pattern.compile("^\\[(\\w+):([^]]*)]$");

    public static final class LrcParseResult {
        public final List<LyricsLine> lines;
        public final List<String> creditLines;

        LrcParseResult(List<LyricsLine> lines, List<String> creditLines) {
            this.lines = lines;
            this.creditLines = creditLines;
        }
    }

    private LrcParser() {
    }

    public static LrcParseResult parseSyncedWithCreditLines(@Nullable String lrc) {
        List<String> creditLines = extractCreditMetadata(lrc);
        List<LyricsLine> lines = parseSynced(lrc);
        return new LrcParseResult(lines, creditLines);
    }

    public static List<String> extractCreditMetadata(@Nullable String lrc) {
        List<String> creditLines = new ArrayList<>();
        if (lrc == null || lrc.isEmpty()) {
            return creditLines;
        }
        for (String rawLine : lrc.split("\\r?\\n")) {
            String line = rawLine.trim();
            Matcher meta = LRC_META.matcher(line);
            if (meta.matches()) {
                String tag = meta.group(1);
                String rawValue = meta.group(2);
                if (tag == null || rawValue == null) {
                    continue;
                }
                String key = tag.toLowerCase(Locale.ROOT);
                if (!key.equals("offset") && CREDIT_META_KEYS.contains(key)) {
                    String value = rawValue.trim();
                    if (!value.isEmpty()) {
                        creditLines.add(tag + ":" + value);
                    }
                }
            }
        }
        return creditLines;
    }

    public static boolean hasOuterParentheses(@Nullable String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            return (first == '(' && last == ')')
                    || (first == '（' && last == '）')
                    || (first == '[' && last == ']');
        }
        return false;
    }

    public static String cleanTextForComparison(@Nullable String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\"'`.,!?:;~()（）\\[\\]{}<>«»“”‘’]+", "").trim().toLowerCase(Locale.ROOT);
    }

    private static final Pattern SPEAKER_TAG_PATTERN =
            Pattern.compile("^(bg|v\\d+|[FMD]|duet|male|female)$", Pattern.CASE_INSENSITIVE);

    /**
     * Parses synced LRC content.
     *
     * @return Lines sorted by time, or an empty list if nothing could be parsed.
     */
    public static List<LyricsLine> parseSynced(@Nullable String lrc) {
        if (lrc == null || lrc.isEmpty()) {
            return Collections.emptyList();
        }

        final class RawEntry {
            final long timeMs;
            final String text;
            final List<Word> words;
            final String agentId;
            final boolean isDuet;
            final boolean isBg;

            RawEntry(long timeMs, String text, List<Word> words, String agentId, boolean isDuet, boolean isBg) {
                this.timeMs = timeMs;
                this.text = text;
                this.words = words;
                this.agentId = agentId;
                this.isDuet = isDuet;
                this.isBg = isBg;
            }
        }

        List<RawEntry> rawEntries = new ArrayList<>();
        // The LRC "offset" tag shifts every timestamp, and is applied while parsing
        // because it belongs to the file rather than to the user configured offset.
        long fileOffsetMs = 0;

        for (String rawLine : lrc.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            List<Long> timestamps = new ArrayList<>(1);
            String currentSpeaker = null;
            boolean lineIsBg = false;
            boolean lineIsDuet = false;
            int index = 0;

            while (index < line.length() && line.charAt(index) == '[') {
                int end = line.indexOf(']', index);
                if (end < 0) {
                    break;
                }

                String tag = line.substring(index + 1, end).trim();
                if (isMetadataTag(tag)) {
                    Long offset = parseOffsetTag(tag);
                    if (offset != null) {
                        fileOffsetMs = offset;
                    }
                } else if ("bg".equalsIgnoreCase(tag)) {
                    lineIsBg = true;
                } else if ("duet".equalsIgnoreCase(tag)) {
                    lineIsDuet = true;
                } else if (SPEAKER_TAG_PATTERN.matcher(tag).matches()) {
                    currentSpeaker = tag.toLowerCase(Locale.ROOT);
                } else {
                    long time = parseTimestamp(tag);
                    if (time != LyricsLine.NO_TIME) {
                        timestamps.add(time);
                    }
                }

                index = end + 1;
            }

            if (timestamps.isEmpty()) {
                continue;
            }

            final long lineStartMs = Math.max(0, timestamps.get(0) + fileOffsetMs);
            BodyParse body = parseBody(line.substring(index), lineStartMs);
            String text = body.text.trim();
            List<Word> words = body.words;
            String agentMarker = agentMarker(text);
            String agentId = currentSpeaker;
            boolean isDuet = lineIsDuet;
            if (agentMarker != null) {
                text = text.substring(agentMarker.length()).trim();
                words = stripAgentMarker(words, agentMarker);
                agentId = agentMarker.substring(0, agentMarker.length() - 1);
                isDuet = isDuetAgent(agentId);
            } else if (agentId != null && agentId.matches(".*\\d+$")) {
                isDuet = isDuetAgent(agentId);
            }
            final boolean isBg = lineIsBg || "bg".equalsIgnoreCase(agentId)
                    || "bg".equalsIgnoreCase(currentSpeaker) || hasOuterParentheses(text);
            if (!words.isEmpty()) {
                for (long time : timestamps) {
                    rawEntries.add(new RawEntry(Math.max(0, time + fileOffsetMs),
                            text, words, agentId, isDuet, isBg));
                }
            } else {
                for (long time : timestamps) {
                    rawEntries.add(new RawEntry(Math.max(0, time + fileOffsetMs),
                            text, Collections.emptyList(), agentId, isDuet, isBg));
                }
            }
        }

        if (rawEntries.isEmpty()) {
            return Collections.emptyList();
        }

        rawEntries.sort(Comparator.comparingLong(e -> e.timeMs));

        List<LyricsLine> lines = new ArrayList<>();
        final int entryCount = rawEntries.size();

        for (int i = 0; i < entryCount; i++) {
            RawEntry current = rawEntries.get(i);
            String text = current.text.trim();
            if (text.isEmpty() || "♪".equals(text) || "♪♪".equals(text)) {
                continue;
            }

            long startMs = current.timeMs;
            long endMs = LyricsLine.NO_TIME;

            if (!current.words.isEmpty()) {
                endMs = current.words.get(current.words.size() - 1).endMs();
            }

            if (endMs == LyricsLine.NO_TIME) {
                RawEntry next = null;
                for (int j = i + 1; j < entryCount; j++) {
                    RawEntry candidate = rawEntries.get(j);
                    if (candidate.timeMs > startMs) {
                        if (!current.isBg && candidate.isBg) {
                            continue;
                        }
                        next = candidate;
                        break;
                    }
                }

                if (next != null) {
                    String nextText = next.text.trim();
                    boolean nextIsBreakMarker = nextText.isEmpty() || "♪".equals(nextText) || "♪♪".equals(nextText);
                    if (nextIsBreakMarker) {
                        endMs = next.timeMs;
                    } else {
                        long gap = next.timeMs - startMs;
                        if (gap < 11000L) {
                            endMs = next.timeMs;
                        } else {
                            long vocalHold = Math.max(5000L, text.length() * 180L);
                            endMs = Math.min(startMs + vocalHold, next.timeMs - 7000L);
                        }
                    }
                } else {
                    endMs = startMs + Math.max(5000L, text.length() * 180L);
                }
            }

            lines.add(new LyricsLine(startMs, endMs, text, current.words,
                    current.agentId, current.isDuet, current.isBg, null));
        }

        if (lines.isEmpty()) {
            return Collections.emptyList();
        }

        for (int i = 1; i < lines.size(); i++) {
            LyricsLine prev = lines.get(i - 1);
            LyricsLine curr = lines.get(i);
            if (!curr.isBG()) {
                long gap = curr.startTimeMs() - prev.startTimeMs();
                if (gap >= 0 && gap <= 3500L) {
                    String cleanCurr = cleanTextForComparison(curr.text());
                    String cleanPrev = cleanTextForComparison(prev.text());
                    if (!cleanCurr.isEmpty() && cleanCurr.equals(cleanPrev)) {
                        lines.set(i, new LyricsLine(curr.startTimeMs(), curr.endTimeMs(), curr.text(),
                                curr.words(), curr.agentId(), curr.isDuet(), true, curr.songPart()));
                    }
                }
            }
        }

        resolveDuet(lines);
        return lines;
    }

    private static void resolveDuet(List<LyricsLine> lines) {
        String lastPersonSpeaker = null;
        boolean lastPersonIsDuet = false;
        boolean hasMultipleSpeakers = false;
        String firstSpeaker = null;

        for (LyricsLine line : lines) {
            String sp = line.agentId();
            if (sp != null && !sp.isEmpty() && !"bg".equalsIgnoreCase(sp) && !"duet".equalsIgnoreCase(sp)) {
                if (firstSpeaker == null) {
                    firstSpeaker = sp;
                } else if (!firstSpeaker.equalsIgnoreCase(sp)) {
                    hasMultipleSpeakers = true;
                    break;
                }
            }
        }

        if (!hasMultipleSpeakers) {
            return;
        }

        for (int i = 0; i < lines.size(); i++) {
            LyricsLine orig = lines.get(i);
            String sp = orig.agentId();
            if (sp == null || sp.isEmpty() || "bg".equalsIgnoreCase(sp) || "duet".equalsIgnoreCase(sp)) {
                continue;
            }

            boolean isDuet;
            if ("v1".equalsIgnoreCase(sp) || "m".equalsIgnoreCase(sp) || "male".equalsIgnoreCase(sp)) {
                isDuet = false;
                lastPersonSpeaker = sp;
                lastPersonIsDuet = false;
            } else if ("v2".equalsIgnoreCase(sp) || "f".equalsIgnoreCase(sp) || "female".equalsIgnoreCase(sp)) {
                isDuet = true;
                lastPersonSpeaker = sp;
                lastPersonIsDuet = true;
            } else if (lastPersonSpeaker == null) {
                isDuet = false;
                lastPersonSpeaker = sp;
                lastPersonIsDuet = false;
            } else if (sp.equalsIgnoreCase(lastPersonSpeaker)) {
                isDuet = lastPersonIsDuet;
            } else {
                isDuet = !lastPersonIsDuet;
                lastPersonSpeaker = sp;
                lastPersonIsDuet = isDuet;
            }

            lines.set(i, new LyricsLine(
                    orig.startTimeMs(), orig.endTimeMs(), orig.text(),
                    orig.words(), sp, isDuet, orig.isBG(), orig.songPart()));
        }
    }

    private record BodyParse(String text, List<Word> words) {
    }

    private static BodyParse parseBody(String body, long lineStartMs) {
        if (body.indexOf('<') < 0 && body.indexOf('[') < 0) {
            // No word-level tags, so this is a plain line.
            return new BodyParse(body, List.of());
        }

        List<Word> words = new ArrayList<>();
        StringBuilder full = new StringBuilder();

        long pendingStart = LyricsLine.NO_TIME;
        StringBuilder pending = new StringBuilder();
        String prefix = "";
        boolean hasToken = false;

        int index = 0;
        final int length = body.length();
        while (index < length) {
            final char c = body.charAt(index);
            if (c == '<' || c == '[') {
                final char close = (c == '<') ? '>' : ']';
                final int end = body.indexOf(close, index);
                if (end < 0) {
                    // Unterminated tag: keep the remainder as literal text.
                    full.append(body, index, length);
                    pending.append(body, index, length);
                    break;
                }
                final long time = parseTimestamp(body.substring(index + 1, end));
                if (time != LyricsLine.NO_TIME) {
                    if (pendingStart == LyricsLine.NO_TIME) {
                        // First word tag: text accumulated so far precedes any timed word.
                        prefix = pending.toString();
                    } else {
                        String word = pending.toString();
                        if (!word.trim().isEmpty()) {
                            boolean endsWithSpace = word.endsWith(" ") || word.endsWith("\t");
                            words.add(new Word(pendingStart, LyricsLine.NO_TIME, word.trim(), null, endsWithSpace));
                        }
                    }
                    pendingStart = time;
                    hasToken = true;
                    pending.setLength(0);
                } else {
                    // Not a timestamp (e.g. [chorus]): keep as literal text.
                    full.append(body, index, end + 1);
                    pending.append(body, index, end + 1);
                }
                index = end + 1;
            } else {
                full.append(c);
                pending.append(c);
                index++;
            }
        }

        if (!hasToken) {
            // No valid word tag was found; treat the whole body as a plain line.
            return new BodyParse(body, List.of());
        }

        String word = pending.toString();
        if (!word.trim().isEmpty()) {
            boolean endsWithSpace = word.endsWith(" ") || word.endsWith("\t");
            words.add(new Word(pendingStart, LyricsLine.NO_TIME, word.trim(), null, endsWithSpace));
        } else if (pendingStart != LyricsLine.NO_TIME && !words.isEmpty()) {
            int lastIdx = words.size() - 1;
            Word last = words.get(lastIdx);
            if (pendingStart > last.startMs()) {
                words.set(lastIdx, new Word(last.startMs(), pendingStart, last.text(), last.romaji(), last.endsWithSpace()));
            }
        }

        if (!prefix.trim().isEmpty()) {
            words.add(0, new Word(lineStartMs, LyricsLine.NO_TIME, prefix.trim()));
        }

        if (words.isEmpty()) {
            return new BodyParse(full.toString().trim(), List.of());
        }

        inferMissingWordEnds(words, 800);
        return new BodyParse(full.toString().trim(), words);
    }

    @Nullable
    private static String agentMarker(@Nullable String text) {
        if (text == null || text.isEmpty() || text.charAt(0) != 'v') {
            return null;
        }
        int index = 1;
        while (index < text.length() && Character.isDigit(text.charAt(index))) {
            index++;
        }
        if (index == 1 || index >= text.length() || text.charAt(index) != ':') {
            return null;
        }
        return text.substring(0, index + 1);
    }

    private static List<Word> stripAgentMarker(List<Word> words, String agentMarker) {
        if (words.isEmpty()) {
            return words;
        }
        Word first = words.get(0);
        if (!first.text().startsWith(agentMarker)) {
            return words;
        }
        String remainder = first.text().substring(agentMarker.length()).stripLeading();
        List<Word> stripped = new ArrayList<>(words);
        if (remainder.isEmpty()) {
            stripped.remove(0);
        } else {
            stripped.set(0, new Word(first.startMs(), first.endMs(), remainder));
        }
        return stripped;
    }

    private static boolean isDuetAgent(String agentId) {
        return (agentId.charAt(agentId.length() - 1) - '0') % 2 == 0;
    }

    public static String formatLine(LyricsLine line) {
        StringBuilder builder = new StringBuilder(formatTimestamp(line.startTimeMs()));
        if (line.isBG()) {
            builder.append("[bg]");
        }
        String agentMarker = agentMarker(line.agentId() == null ? null : line.agentId() + ":");
        if (agentMarker != null) {
            builder.append(agentMarker);
        } else if (line.isDuet()) {
            builder.append("[duet]");
        }
        if (line.hasWords()) {
            Word lastWord = null;
            for (Word word : line.words()) {
                builder.append('<')
                        .append(formatWordTimestamp(word.startMs()))
                        .append('>')
                        .append(word.text());
                if (word.endsWithSpace()) {
                    builder.append(' ');
                }
                lastWord = word;
            }
            if (lastWord != null && lastWord.endMs() > lastWord.startMs()) {
                builder.append('<')
                        .append(formatWordTimestamp(lastWord.endMs()))
                        .append('>');
            }
        } else {
            builder.append(line.text());
        }
        return builder.toString();
    }

    /**
     * Parses plain (unsynced) lyrics, one line per text line.
     */
    public static List<LyricsLine> parsePlain(@Nullable String plain) {
        if (plain == null || plain.isEmpty()) {
            return Collections.emptyList();
        }

        List<LyricsLine> lines = new ArrayList<>();
        for (String rawLine : plain.split("\\r?\\n")) {
            lines.add(new LyricsLine(LyricsLine.NO_TIME, rawLine.trim()));
        }

        // Trailing blank lines add nothing but scroll space.
        while (!lines.isEmpty() && lines.get(lines.size() - 1).text().isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    /**
     * @return {@code true} if the tag is metadata such as {@code ti} or {@code offset}.
     */
    private static boolean isMetadataTag(String tag) {
        int colon = tag.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        String name = tag.substring(0, colon).toLowerCase(Locale.ROOT);
        for (int i = 0; i < name.length(); i++) {
            if (METADATA_TAG_CHARACTERS.indexOf(name.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param tag Tag that {@link #isMetadataTag} already accepted, so it holds a colon.
     */
    @Nullable
    private static Long parseOffsetTag(String tag) {
        final int colon = tag.indexOf(':');
        if (!tag.substring(0, colon).equalsIgnoreCase("offset")) {
            return null;
        }
        try {
            String value = tag.substring(colon + 1).trim();
            if (value.startsWith("+")) {
                value = value.substring(1);
            }
            // A positive LRC offset means the lyrics are shown earlier.
            return -Long.parseLong(value);
        } catch (NumberFormatException ex) {
            Logger.printDebug(() -> "Failed to parse LRC offset tag", ex);
            return null;
        }
    }

    /**
     * Parses {@code mm:ss.xx}, {@code mm:ss.xxx} or {@code mm:ss}.
     *
     * @return Time in milliseconds, or {@link LyricsLine#NO_TIME} if the tag is not a timestamp.
     */
    private static long parseTimestamp(String tag) {
        try {
            int colon = tag.indexOf(':');
            if (colon <= 0) {
                return LyricsLine.NO_TIME;
            }

            long minutes = Long.parseLong(tag.substring(0, colon).trim());

            String rest = tag.substring(colon + 1).trim();
            int dot = rest.indexOf('.');
            if (dot < 0) {
                dot = rest.indexOf(':');
            }

            long seconds;
            long fractionMs = 0;
            if (dot < 0) {
                seconds = Long.parseLong(rest);
            } else {
                seconds = Long.parseLong(rest.substring(0, dot));
                fractionMs = LyricsRequests.fractionToMs(rest.substring(dot + 1));
            }

            return (minutes * 60 + seconds) * 1000 + fractionMs;
        } catch (NumberFormatException | IndexOutOfBoundsException ex) {
            Logger.printDebug(() -> "Failed to parse LRC timestamp: " + tag, ex);
            return LyricsLine.NO_TIME;
        }
    }

    /**
     * Formats a line level timestamp as {@code [mm:ss.xx]} for the LRC cache.
     */
    private static String formatTimestamp(long timeMs) {
        return "[" + formatCentiseconds(timeMs) + "]";
    }

    private static String formatWordTimestamp(long timeMs) {
        return formatCentiseconds(timeMs);
    }

    /** Formats {@code mm:ss.xx} (hundredths) in US locale. */
    public static String formatCentiseconds(long timeMs) {
        final long minutes = timeMs / 60_000;
        final long seconds = (timeMs / 1000) % 60;
        final long hundredths = (timeMs % 1000) / 10;
        return String.format(Locale.US, "%02d:%02d.%02d", minutes, seconds, hundredths);
    }

    /** Fills missing word end times: end = next start, last word = start + {@code lastFallbackMs}. */
    public static void inferMissingWordEnds(List<Word> words, long lastFallbackMs) {
        for (int i = 0; i < words.size() - 1; i++) {
            Word word = words.get(i);
            if (word.endMs() == LyricsLine.NO_TIME) {
                words.set(i, new Word(word.startMs(), words.get(i + 1).startMs(), word.text()));
            }
        }
        if (!words.isEmpty()) {
            int last = words.size() - 1;
            Word lastWord = words.get(last);
            if (lastWord.endMs() == LyricsLine.NO_TIME) {
                words.set(last, new Word(lastWord.startMs(),
                        lastWord.startMs() + lastFallbackMs, lastWord.text()));
            }
        }
    }
}
