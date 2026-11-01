/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.derive;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.romanizers.JapaneseDictStore;
import app.morphe.extension.music.patches.lyrics.romanizers.KanaRomanizer;
import app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector;
import app.morphe.extension.music.patches.lyrics.romanizers.WordReadingTable;
import app.morphe.extension.music.patches.lyrics.storage.LyricsCache;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

public final class LyricsFuriganaGenerator {

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "lyrics-furigana-generator");
        thread.setDaemon(true);
        return thread;
    });

    private static final Pattern BRACKET_RUBY_PATTERN = Pattern.compile("([^\\s\\[\\]]+)\\[([^\\[\\]]+)\\]");

    public interface Callback {
        void onGenerated(@Nullable Lyrics lyrics, boolean fromAI, @Nullable String model);
    }

    private LyricsFuriganaGenerator() {
    }

    public static void generate(TrackInfo track, Lyrics lyrics, Callback callback) {
        if (lyrics == null || lyrics.lines() == null || lyrics.lines().isEmpty()) {
            callback.onGenerated(lyrics, false, null);
            return;
        }

        boolean hasJapanese = false;
        for (LyricsLine l : lyrics.lines()) {
            String text = l.text();
            if (text != null && (ScriptDetector.containsKana(text)
                    || ScriptDetector.contains(text, Character.UnicodeScript.HAN))) {
                hasJapanese = true;
                break;
            }
        }
        if (!hasJapanese) {
            callback.onGenerated(lyrics, false, null);
            return;
        }

        final List<String> providers = AiProviders.enabledProviders(
                Settings.LYRICS_FURIGANA_PROVIDERS.get(),
                AiProviders.FURIGANA_ORDER);

        if (providers.isEmpty()) {
            callback.onGenerated(lyrics, false, null);
            return;
        }

        final String targetStyle = Settings.LYRICS_FURIGANA_STYLE.get();

        if (providers.size() == 1 && AiProviders.LOCAL.equals(providers.get(0)) && JapaneseDictStore.isReady()) {
            Lyrics enriched = enrichFromLocalDict(lyrics, targetStyle);
            callback.onGenerated(enriched, false, null);
            return;
        }

        WORKER.execute(() -> {
            try {
                Lyrics currentLyrics = lyrics;
                boolean fromAi = false;
                String aiModel = null;

                for (String provider : providers) {
                    if (AiProviders.EMBEDDED.equals(provider)) {
                        currentLyrics = formatExistingRuby(currentLyrics, targetStyle);
                    } else if (AiProviders.LOCAL.equals(provider)) {
                        if (!JapaneseDictStore.isReady()) {
                            JapaneseDictStore.ensure(null);
                        }
                        currentLyrics = enrichFromLocalDict(currentLyrics, targetStyle);
                    } else if (AiProviders.isAi(provider)) {
                        List<String> rawLines = new ArrayList<>(currentLyrics.lines().size());
                        for (LyricsLine l : currentLyrics.lines()) {
                            rawLines.add(l.text() != null ? l.text().trim() : "");
                        }

                        List<String> aiLines = LyricsCache.getFuriganaAI(track, currentLyrics.providerName(), rawLines, provider);
                        if (aiLines == null || aiLines.size() != rawLines.size()) {
                            String prompt = OpenAIClient.renderPrompt(
                                    Settings.LYRICS_AI_FURIGANA_PROMPT.get(),
                                    "furigana",
                                    "Japanese",
                                    track.title(),
                                    track.artist(),
                                    rawLines);
                            AiProviders.Result ai = AiProviders.mapLines(provider, prompt, rawLines);
                            if (ai != null && ai.lines() != null && ai.lines().size() == rawLines.size()) {
                                aiLines = ai.lines();
                                aiModel = ai.model();
                                fromAi = true;
                                LyricsCache.putFuriganaAI(track, currentLyrics.providerName(), rawLines, aiLines, provider);
                            }
                        } else {
                            fromAi = true;
                            aiModel = Settings.LYRICS_AI_MODEL.get();
                        }

                        if (aiLines != null && aiLines.size() == currentLyrics.lines().size()) {
                            currentLyrics = enrichFromAiLines(currentLyrics, aiLines, targetStyle);
                        }
                    }
                }

                final Lyrics result = currentLyrics;
                final boolean finalFromAi = fromAi;
                final String finalModel = aiModel;
                Utils.runOnMainThread(() -> callback.onGenerated(result, finalFromAi, finalModel));
            } catch (Exception ex) {
                Logger.printInfo(() -> "Furigana generation failed", ex);
                Utils.runOnMainThread(() -> callback.onGenerated(lyrics, false, null));
            }
        });
    }

    private static Lyrics formatExistingRuby(Lyrics lyrics, String targetStyle) {
        List<LyricsLine> origLines = lyrics.lines();
        List<LyricsLine> newLines = new ArrayList<>(origLines.size());

        for (LyricsLine line : origLines) {
            if (!line.hasWords()) {
                newLines.add(line);
                continue;
            }

            List<Word> words = line.words();
            List<Word> newWords = new ArrayList<>(words.size());
            boolean changed = false;

            for (Word w : words) {
                if (w.ruby() != null && !w.ruby().isEmpty()) {
                    String formatted = formatRuby(w.ruby(), targetStyle);
                    newWords.add(new Word(w.startMs(), w.endMs(), w.text(), w.romaji(), w.endsWithSpace(), formatted));
                    changed = true;
                } else {
                    newWords.add(w);
                }
            }

            newLines.add(changed
                    ? new LyricsLine(line.startTimeMs(), line.endTimeMs(), line.text(), newWords,
                    line.agentId(), line.isDuet(), line.isBG(), line.songPart())
                    : line);
        }

        return lyrics.withLines(newLines);
    }

    private static Lyrics enrichFromLocalDict(Lyrics lyrics, String targetStyle) {
        WordReadingTable table = JapaneseDictStore.table();

        List<LyricsLine> origLines = lyrics.lines();
        List<LyricsLine> newLines = new ArrayList<>(origLines.size());

        for (LyricsLine line : origLines) {
            if (!line.hasWords()) {
                newLines.add(line);
                continue;
            }

            List<Word> words = line.words();
            List<Word> newWords = new ArrayList<>(words.size());
            boolean changed = false;

            for (Word w : words) {
                if (w.ruby() != null && !w.ruby().isEmpty()) {
                    String formatted = formatRuby(w.ruby(), targetStyle);
                    newWords.add(new Word(w.startMs(), w.endMs(), w.text(), w.romaji(), w.endsWithSpace(), formatted));
                } else if (w.text() != null && ScriptDetector.contains(w.text(), Character.UnicodeScript.HAN)) {
                    String reading = null;
                    if (w.romaji() != null && !w.romaji().trim().isEmpty() && LyricsRomanizer.isPurelyLatinScript(w.romaji())) {
                        reading = KanaRomanizer.romajiToHiragana(w.romaji());
                    }
                    if (reading == null || reading.isEmpty()) {
                        reading = lookupReading(w.text(), table);
                    }
                    if (reading != null && !reading.isEmpty()) {
                        String formatted = formatRuby(reading, targetStyle);
                        newWords.add(new Word(w.startMs(), w.endMs(), w.text(), w.romaji(), w.endsWithSpace(), formatted));
                        changed = true;
                    } else {
                        newWords.add(w);
                    }
                } else {
                    newWords.add(w);
                }
            }

            newLines.add(changed
                    ? new LyricsLine(line.startTimeMs(), line.endTimeMs(), line.text(), newWords,
                    line.agentId(), line.isDuet(), line.isBG(), line.songPart())
                    : line);
        }

        return lyrics.withLines(newLines);
    }

    @Nullable
    private static String lookupReading(String wordText, @Nullable WordReadingTable table) {
        if (table == null) return null;
        String clean = wordText.trim();
        if (clean.isEmpty()) return null;
        String reading = table.reading(clean);
        if (reading != null && !reading.isEmpty()) {
            return reading;
        }
        int limit = Math.min(WordReadingTable.MAX_WORD_LENGTH, clean.length());
        for (int candidate = limit; candidate >= 1; candidate--) {
            String found = table.reading(clean.substring(0, candidate));
            if (found != null && !found.isEmpty()) {
                return found;
            }
        }
        return null;
    }

    private static Lyrics enrichFromAiLines(Lyrics lyrics, List<String> aiLines, String targetStyle) {
        List<LyricsLine> origLines = lyrics.lines();
        List<LyricsLine> newLines = new ArrayList<>(origLines.size());

        for (int li = 0; li < origLines.size(); li++) {
            LyricsLine line = origLines.get(li);
            if (!line.hasWords() || li >= aiLines.size()) {
                newLines.add(line);
                continue;
            }

            String aiLine = aiLines.get(li);
            if (aiLine == null || aiLine.trim().isEmpty()) {
                newLines.add(line);
                continue;
            }

            Map<String, String> aiRubyMap = parseAiBrackets(aiLine);
            if (aiRubyMap.isEmpty()) {
                newLines.add(line);
                continue;
            }

            List<Word> words = line.words();
            List<Word> newWords = new ArrayList<>(words.size());
            boolean changed = false;

            for (Word w : words) {
                if (w.ruby() != null && !w.ruby().isEmpty()) {
                    newWords.add(w);
                } else if (w.text() != null && ScriptDetector.contains(w.text(), Character.UnicodeScript.HAN)) {
                    String clean = w.text().trim();
                    String reading = aiRubyMap.get(clean);
                    if (reading == null) {
                        for (Map.Entry<String, String> e : aiRubyMap.entrySet()) {
                            if (clean.contains(e.getKey()) || e.getKey().contains(clean)) {
                                reading = e.getValue();
                                break;
                            }
                        }
                    }
                    if (reading != null && !reading.isEmpty()) {
                        String formatted = formatRuby(reading, targetStyle);
                        newWords.add(new Word(w.startMs(), w.endMs(), w.text(), w.romaji(), w.endsWithSpace(), formatted));
                        changed = true;
                    } else {
                        newWords.add(w);
                    }
                } else {
                    newWords.add(w);
                }
            }

            newLines.add(changed
                    ? new LyricsLine(line.startTimeMs(), line.endTimeMs(), line.text(), newWords,
                    line.agentId(), line.isDuet(), line.isBG(), line.songPart())
                    : line);
        }

        return lyrics.withLines(newLines);
    }

    private static Map<String, String> parseAiBrackets(String aiLine) {
        Map<String, String> map = new HashMap<>();
        Matcher m = BRACKET_RUBY_PATTERN.matcher(aiLine);
        while (m.find()) {
            String base = m.group(1).trim();
            String ruby = m.group(2).trim();
            if (!base.isEmpty() && !ruby.isEmpty()) {
                map.put(base, ruby);
            }
        }
        return map;
    }

    private static String formatRuby(String ruby, String targetStyle) {
        if ("KATAKANA".equalsIgnoreCase(targetStyle)) {
            return KanaRomanizer.toKatakana(ruby);
        }
        return KanaRomanizer.toHiragana(ruby);
    }
}
