/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.text.BoringLayout;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristic;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;

public final class WordAnimationUtils {

    private WordAnimationUtils() {
    }

    public static final int WIPE_PAST = 0;
    public static final int WIPE_UNLIT = 1;
    public static final int WIPE_BAND = 2;

    public static final float[] GRADIENT_STOPS = new float[]{0.0f, 0.1f, 0.9f, 1.0f};

    private static volatile Boolean cachedIsMiuiOrHyperOs = null;
    private static volatile Typeface cachedLyricsBoldTypeface = null;

    // MIUI and HyperOS sometimes render CJK / Latin fonts way too thin compared to clean AOSP.
    // so we detect OS and apply fake bold & font variation settings so thickness stay chunky and equal.
    public static boolean isMiuiOrHyperOs() {
        Boolean cached = cachedIsMiuiOrHyperOs;
        if (cached != null) {
            return cached;
        }
        boolean result = false;
        try {
            String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER.toLowerCase(java.util.Locale.ROOT) : "";
            String brand = Build.BRAND != null ? Build.BRAND.toLowerCase(java.util.Locale.ROOT) : "";
            if (manufacturer.contains("xiaomi") || brand.contains("xiaomi")
                    || brand.contains("redmi") || brand.contains("poco")) {
                result = true;
            } else {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
                String miuiVer = (String) get.invoke(null, "ro.miui.ui.version.name", "");
                String hyperVer = (String) get.invoke(null, "ro.mi.os.version.name", "");
                if ((miuiVer != null && !miuiVer.isEmpty()) || (hyperVer != null && !hyperVer.isEmpty())) {
                    result = true;
                }
            }
        } catch (Throwable ignored) {
        }
        cachedIsMiuiOrHyperOs = result;
        return result;
    }

    @NonNull
    public static Typeface getLyricsBoldTypeface() {
        Typeface tf = cachedLyricsBoldTypeface;
        if (tf != null) {
            return tf;
        }
        Typeface base = Typeface.create("sans-serif", Typeface.BOLD);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            tf = Typeface.create(base, 700, false);
        } else {
            tf = base;
        }
        cachedLyricsBoldTypeface = tf;
        return tf;
    }

    public static void applyLyricsBoldPaint(@NonNull TextPaint paint) {
        applyLyricsBoldPaint(paint, false);
    }

    public static void applyLyricsBoldPaint(@NonNull TextPaint paint, boolean isSubText) {
        paint.setTypeface(getLyricsBoldTypeface());
        paint.setFlags(paint.getFlags() | Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                paint.setFontVariationSettings(isMiuiOrHyperOs() ? "'wght' 750" : "'wght' 700");
            } catch (Throwable ignored) {
            }
        }
        paint.setFakeBoldText(isMiuiOrHyperOs());
    }

    public static final class WordTimestamp {
        public final String text;
        public final double startTime;
        public final double endTime;
        public final boolean hasTrailingSpace;
        public final boolean isBackground;
        @Nullable
        public final String romanized;

        public WordTimestamp(
                @NonNull String text,
                double startTime,
                double endTime,
                boolean hasTrailingSpace,
                boolean isBackground,
                @Nullable String romanized) {
            this.text = text;
            this.startTime = startTime;
            this.endTime = endTime;
            this.hasTrailingSpace = hasTrailingSpace;
            this.isBackground = isBackground;
            this.romanized = romanized;
        }

        public long startTimeMs() {
            return Math.round(startTime * 1000.0);
        }

        public long endTimeMs() {
            return Math.round(endTime * 1000.0);
        }

        public WordTimestamp copy(
                @NonNull String newText,
                double newStartTime,
                double newEndTime,
                boolean newHasTrailingSpace) {
            return new WordTimestamp(newText, newStartTime, newEndTime, newHasTrailingSpace, isBackground, romanized);
        }

        @NonNull
        public static List<WordTimestamp> fromLyricsLine(@Nullable LyricsLine line, boolean isBg) {
            if (line == null || !line.hasWords()) {
                return Collections.emptyList();
            }
            List<Word> words = line.words();
            List<WordTimestamp> list = new ArrayList<>(words.size());
            long fallbackStart = line.startTimeMs() != LyricsLine.NO_TIME ? line.startTimeMs() : 0L;
            for (int i = 0; i < words.size(); i++) {
                Word w = words.get(i);
                long sMs = (w.startMs() != LyricsLine.NO_TIME) ? w.startMs() : fallbackStart;
                long eMs = (w.endMs() != LyricsLine.NO_TIME) ? w.endMs() : sMs;
                String rawText = w.text() != null ? w.text() : "";
                if (isBg) {
                    rawText = rawText.replace("(", "").replace(")", "")
                            .replace("（", "").replace("）", "");
                }
                String rom = (w.romaji() != null && !w.romaji().isEmpty()) ? w.romaji() : null;
                list.add(new WordTimestamp(
                        rawText,
                        sMs / 1000.0,
                        eMs / 1000.0,
                        w.endsWithSpace(),
                        isBg,
                        rom
                ));
                fallbackStart = eMs;
            }
            return list;
        }

        @Nullable
        public static List<WordTimestamp> buildRomanizedWordsFromLines(
                @Nullable LyricsLine mainLine,
                @Nullable LyricsLine romanizedLine,
                @Nullable List<LyricsLineView.WordUnit> fallbackUnits,
                boolean isBg) {
            if (mainLine == null || !mainLine.hasWords()) {
                return buildRomanizedWords(fallbackUnits, isBg);
            }
            List<Word> mainWords = mainLine.words();
            if (romanizedLine != null && romanizedLine.hasWords()
                    && romanizedLine.words().size() == mainWords.size()) {
                List<Word> rWords = romanizedLine.words();
                boolean anyRom = false;
                List<WordTimestamp> out = new ArrayList<>(rWords.size());
                for (int i = 0; i < mainWords.size(); i++) {
                    Word mw = mainWords.get(i);
                    Word rw = rWords.get(i);
                    String rawRom = rw.romaji();
                    if (rawRom == null || rawRom.trim().isEmpty()) {
                        String candidate = rw.text();
                        if (candidate != null && !isCJK(candidate)
                                && !candidate.trim().equalsIgnoreCase(mw.text() != null ? mw.text().trim() : "")) {
                            rawRom = candidate;
                        } else {
                            rawRom = "";
                        }
                    }
                    boolean hasExplicitSpace = rawRom != null && (rawRom.endsWith(" ") || rawRom.endsWith("\u00A0"));
                    String rom = (rawRom != null && !isCJK(rawRom)) ? rawRom : "";
                    if (isBg) {
                        rom = rom.replace("(", "").replace(")", "")
                                .replace("（", "").replace("）", "");
                    }
                    rom = rom.trim();
                    String mwTrim = mw.text() != null ? mw.text().trim() : "";
                    if (rom.equalsIgnoreCase(mwTrim)) {
                        rom = "";
                    }
                    if (!rom.isEmpty()) anyRom = true;
                    long sMs = (mw.startMs() != LyricsLine.NO_TIME) ? mw.startMs() : 0L;
                    long eMs = (mw.endMs() != LyricsLine.NO_TIME) ? mw.endMs() : sMs;
                    boolean trailingSpace = !rom.isEmpty() && (mw.endsWithSpace() || hasExplicitSpace);
                    out.add(new WordTimestamp(
                            rom,
                            sMs / 1000.0,
                            eMs / 1000.0,
                            trailingSpace,
                            isBg,
                            rom
                    ));
                }
                if (anyRom) return out;
            }

            boolean anyDirectRom = false;
            for (int i = 0; i < mainWords.size(); i++) {
                String r = mainWords.get(i).romaji();
                if (r != null && !r.trim().isEmpty() && !isCJK(r)) {
                    anyDirectRom = true;
                    break;
                }
            }
            if (anyDirectRom) {
                List<WordTimestamp> out = new ArrayList<>(mainWords.size());
                for (int i = 0; i < mainWords.size(); i++) {
                    Word mw = mainWords.get(i);
                    String rawRom = mw.romaji();
                    boolean hasExplicitSpace = rawRom != null && (rawRom.endsWith(" ") || rawRom.endsWith("\u00A0"));
                    String rom = (rawRom != null && !isCJK(rawRom)) ? rawRom.trim() : "";
                    if (isBg) {
                        rom = rom.replace("(", "").replace(")", "")
                                .replace("（", "").replace("）", "");
                    }
                    String mwTrim = mw.text() != null ? mw.text().trim() : "";
                    if (rom.equalsIgnoreCase(mwTrim)) {
                        rom = "";
                    }
                    long sMs = (mw.startMs() != LyricsLine.NO_TIME) ? mw.startMs() : 0L;
                    long eMs = (mw.endMs() != LyricsLine.NO_TIME) ? mw.endMs() : sMs;
                    boolean trailingSpace = !rom.isEmpty() && (mw.endsWithSpace() || hasExplicitSpace);
                    out.add(new WordTimestamp(
                            rom,
                            sMs / 1000.0,
                            eMs / 1000.0,
                            trailingSpace,
                            isBg,
                            rom
                    ));
                }
                return out;
            }

            if (fallbackUnits != null && !fallbackUnits.isEmpty()) {
                if (fallbackUnits.size() == mainWords.size()) {
                    return buildRomanizedWords(fallbackUnits, isBg);
                }
                String fullText = mainLine.text() != null ? mainLine.text() : "";
                int[] uStarts = new int[fallbackUnits.size()];
                int[] uEnds = new int[fallbackUnits.size()];
                int uSearch = 0;
                for (int uIdx = 0; uIdx < fallbackUnits.size(); uIdx++) {
                    String uk = fallbackUnits.get(uIdx).kanji != null ? fallbackUnits.get(uIdx).kanji.trim() : "";
                    int pos = uk.isEmpty() ? -1 : fullText.indexOf(uk, uSearch);
                    if (pos >= 0) {
                        uStarts[uIdx] = pos;
                        uEnds[uIdx] = pos + uk.length();
                        uSearch = uEnds[uIdx];
                    } else {
                        uStarts[uIdx] = uSearch;
                        uEnds[uIdx] = Math.min(fullText.length(), uSearch + Math.max(1, uk.length()));
                        uSearch = uEnds[uIdx];
                    }
                }
                boolean[] uConsumed = new boolean[fallbackUnits.size()];
                List<WordTimestamp> mapped = new ArrayList<>(mainWords.size());
                boolean anyMapped = false;
                int mSearch = 0;
                for (int i = 0; i < mainWords.size(); i++) {
                    Word mw = mainWords.get(i);
                    String mt = mw.text() != null ? mw.text().trim() : "";
                    int mPos = mt.isEmpty() ? -1 : fullText.indexOf(mt, mSearch);
                    int mStart = (mPos >= 0) ? mPos : mSearch;
                    int mEnd = (mPos >= 0) ? (mPos + mt.length()) : Math.min(fullText.length(), mSearch + Math.max(1, mt.length()));
                    mSearch = mEnd;
                    StringBuilder romSb = new StringBuilder();
                    for (int uIdx = 0; uIdx < fallbackUnits.size(); uIdx++) {
                        if (!uConsumed[uIdx] && uEnds[uIdx] > mStart && uStarts[uIdx] < mEnd) {
                            uConsumed[uIdx] = true;
                            String ur = fallbackUnits.get(uIdx).romaji;
                            if (ur != null && !ur.trim().isEmpty() && !isCJK(ur)) {
                                if (romSb.length() > 0) romSb.append(" ");
                                romSb.append(ur.trim());
                            }
                        }
                    }
                    String rom = romSb.toString().trim();
                    if (isBg) {
                        rom = rom.replace("(", "").replace(")", "")
                                .replace("（", "").replace("）", "");
                    }
                    if (!rom.isEmpty()) anyMapped = true;
                    long sMs = (mw.startMs() != LyricsLine.NO_TIME) ? mw.startMs() : 0L;
                    long eMs = (mw.endMs() != LyricsLine.NO_TIME) ? mw.endMs() : sMs;
                    boolean trailingSpace = !rom.isEmpty() && (mw.endsWithSpace() || i < mainWords.size() - 1);
                    mapped.add(new WordTimestamp(rom, sMs / 1000.0, eMs / 1000.0, trailingSpace, isBg, rom));
                }
                if (anyMapped) return mapped;
            }
            return null;
        }

        public static List<WordTimestamp> fromWordUnits(@Nullable List<LyricsLineView.WordUnit> units, boolean isBg) {
            if (units == null || units.isEmpty()) {
                return Collections.emptyList();
            }
            List<WordTimestamp> list = new ArrayList<>(units.size());
            for (int i = 0; i < units.size(); i++) {
                LyricsLineView.WordUnit u = units.get(i);
                double s = (u.startMs != LyricsLine.NO_TIME) ? (u.startMs / 1000.0) : 0.0;
                double e = (u.endMs != LyricsLine.NO_TIME) ? (u.endMs / 1000.0) : s;
                String text = u.kanji != null ? u.kanji : "";
                if (isBg) {
                    text = text.replace("(", "").replace(")", "")
                            .replace("（", "").replace("）", "");
                }
                list.add(new WordTimestamp(
                        text,
                        s,
                        e,
                        u.endsWithSpace,
                        isBg,
                        (u.romaji != null && !u.romaji.isEmpty()) ? u.romaji : null
                ));
            }
            return list;
        }

        @Nullable
        public static List<WordTimestamp> buildRomanizedWords(@Nullable List<LyricsLineView.WordUnit> units, boolean isBg) {
            if (units == null || units.isEmpty()) {
                return null;
            }
            boolean anyRomaji = false;
            for (int i = 0; i < units.size(); i++) {
                LyricsLineView.WordUnit u = units.get(i);
                if (u.romaji != null && !u.romaji.trim().isEmpty() && !isCJK(u.romaji)) {
                    anyRomaji = true;
                    break;
                }
            }
            if (!anyRomaji) {
                return null;
            }
            List<WordTimestamp> list = new ArrayList<>(units.size());
            for (int i = 0; i < units.size(); i++) {
                LyricsLineView.WordUnit u = units.get(i);
                double s = (u.startMs != LyricsLine.NO_TIME) ? (u.startMs / 1000.0) : 0.0;
                double e = (u.endMs != LyricsLine.NO_TIME) ? (u.endMs / 1000.0) : s;
                String r = (u.romaji != null && !isCJK(u.romaji)) ? u.romaji.trim() : "";
                if (isBg) {
                    r = r.replace("(", "").replace(")", "")
                            .replace("（", "").replace("）", "");
                }
                boolean trailingSpace = !r.isEmpty() && (u.endsWithSpace || i < units.size() - 1);
                list.add(new WordTimestamp(r, s, e, trailingSpace, isBg, r));
            }
            return list;
        }
    }

    public static final class AlignedSyllable {
        public final WordTimestamp syllable;
        public final int startOffset;
        public final int endOffset;

        public AlignedSyllable(WordTimestamp syllable, int startOffset, int endOffset) {
            this.syllable = syllable;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }
    }

    public static final class RomanizedWordCluster {
        public final int wordIndex;
        public final int startIndex;
        public final int endIndex;
        public final String romanText;
        public final int lineIdx;
        public final long wordStartMs;
        public final long wordEndMs;
        public final float originalCanvasX;
        public final Layout subLayout;
        public final float romanWidth;

        public RomanizedWordCluster(
                int wordIndex,
                int startIndex,
                int endIndex,
                String romanText,
                int lineIdx,
                long wordStartMs,
                long wordEndMs,
                float originalCanvasX,
                Layout subLayout,
                float romanWidth) {
            this.wordIndex = wordIndex;
            this.startIndex = startIndex;
            this.endIndex = endIndex;
            this.romanText = romanText;
            this.lineIdx = lineIdx;
            this.wordStartMs = wordStartMs;
            this.wordEndMs = wordEndMs;
            this.originalCanvasX = originalCanvasX;
            this.subLayout = subLayout;
            this.romanWidth = romanWidth;
        }

        public RomanizedWordCluster(
                int wordIndex,
                int startIndex,
                int endIndex,
                String romanText,
                int lineIdx,
                long wordStartMs,
                long wordEndMs,
                float originalCanvasX,
                Layout subLayout) {
            this(
                    wordIndex,
                    startIndex,
                    endIndex,
                    romanText,
                    lineIdx,
                    wordStartMs,
                    wordEndMs,
                    originalCanvasX,
                    subLayout,
                    Math.max(
                            subLayout.getWidth(),
                            subLayout.getText().length() > 0
                                    ? subLayout.getPrimaryHorizontal(subLayout.getText().length())
                                    : 0f
                    )
            );
        }
    }

    public static final class ReconstructedWord {
        public final int startIndex;
        public final int endIndex;
        public final long wordStartTimeMs;
        public final long wordEndTimeMs;
        public final int wordLen;
        public final List<AlignedSyllable> syllables;

        public ReconstructedWord(
                int startIndex,
                int endIndex,
                long wordStartTimeMs,
                long wordEndTimeMs,
                int wordLen,
                List<AlignedSyllable> syllables) {
            this.startIndex = startIndex;
            this.endIndex = endIndex;
            this.wordStartTimeMs = wordStartTimeMs;
            this.wordEndTimeMs = wordEndTimeMs;
            this.wordLen = wordLen;
            this.syllables = syllables;
        }
    }

    public static final class ClusterMeta {
        public final int start;
        public final int end;
        public final int lineIdx;
        public final float x;
        public final float y;
        public final float width;
        public boolean isHeavy = false;
        public boolean disableScale = false;
        public boolean isJoiningScript = false;
        public boolean isRtl = false;
        public long wordStartMs = 0L;
        public long wordEndMs = 0L;
        public float intensity = 0f;
        public float normX = 0f;
        public float sharedElevationCenter = -1f;
        public float charMaxScale = 1.0f;
        public float charShadowIntensity = 0f;
        public float charTranslateYPeak = 0f;
        public long charDelayMs = 0L;

        public ClusterMeta(int start, int end, int lineIdx, float x, float y, float width) {
            this.start = start;
            this.end = end;
            this.lineIdx = lineIdx;
            this.x = x;
            this.y = y;
            this.width = width;
        }
    }

    public static final class VisualCluster {
        public final int start;
        public final int end;
        public final int lineIdx;
        public final float x;
        public final float y;
        public final float width;
        public final boolean isHeavy;
        public final boolean disableScale;
        public final boolean isJoiningScript;
        public final boolean isRtl;
        public final long wordStartMs;
        public final long wordEndMs;
        public final float intensity;
        public final float normX;
        public final float sharedElevationCenter;
        public final Layout subLayout;
        public final float charMaxScale;
        public final float charShadowIntensity;
        public final float charTranslateYPeak;
        public final long charDelayMs;
        public final int syllableIndex;

        public VisualCluster(
                int start,
                int end,
                int lineIdx,
                float x,
                float y,
                float width,
                boolean isHeavy,
                boolean disableScale,
                boolean isJoiningScript,
                boolean isRtl,
                long wordStartMs,
                long wordEndMs,
                float intensity,
                float normX,
                float sharedElevationCenter,
                Layout subLayout,
                float charMaxScale,
                float charShadowIntensity,
                float charTranslateYPeak,
                long charDelayMs,
                int syllableIndex) {
            this.start = start;
            this.end = end;
            this.lineIdx = lineIdx;
            this.x = x;
            this.y = y;
            this.width = width;
            this.isHeavy = isHeavy;
            this.disableScale = disableScale;
            this.isJoiningScript = isJoiningScript;
            this.isRtl = isRtl;
            this.wordStartMs = wordStartMs;
            this.wordEndMs = wordEndMs;
            this.intensity = intensity;
            this.normX = normX;
            this.sharedElevationCenter = sharedElevationCenter;
            this.subLayout = subLayout;
            this.charMaxScale = charMaxScale;
            this.charShadowIntensity = charShadowIntensity;
            this.charTranslateYPeak = charTranslateYPeak;
            this.charDelayMs = charDelayMs;
            this.syllableIndex = syllableIndex;
        }
    }

    public static final class ActiveSylInfo {
        public final int index;
        public final int lineIdx;
        public final boolean isLastOfLine;

        public ActiveSylInfo(int index, int lineIdx, boolean isLastOfLine) {
            this.index = index;
            this.lineIdx = lineIdx;
            this.isLastOfLine = isLastOfLine;
        }
    }

    public static final class SyllableBounds {
        public final float[] startX;
        public final float[] endX;

        public SyllableBounds(float[] startX, float[] endX) {
            this.startX = startX;
            this.endX = endX;
        }
    }

    public static final class ClusterStatics {
        public final float[] baseX;
        public final float[] baseY;
        public final float[] pivotX;
        public final float[] pivotY;
        public final boolean[] isRtlLine;
        public final float[] lineLeft;
        public final float[] lineRight;
        public final float[] lineStart;
        public final float[] lineWidth;
        public final float[] localStart;
        public final float[] localEnd;

        public ClusterStatics(
                float[] baseX,
                float[] baseY,
                float[] pivotX,
                float[] pivotY,
                boolean[] isRtlLine,
                float[] lineLeft,
                float[] lineRight,
                float[] lineStart,
                float[] lineWidth,
                float[] localStart,
                float[] localEnd) {
            this.baseX = baseX;
            this.baseY = baseY;
            this.pivotX = pivotX;
            this.pivotY = pivotY;
            this.isRtlLine = isRtlLine;
            this.lineLeft = lineLeft;
            this.lineRight = lineRight;
            this.lineStart = lineStart;
            this.lineWidth = lineWidth;
            this.localStart = localStart;
            this.localEnd = localEnd;
        }
    }

    public static final class WipeResult {
        public int mode = WIPE_UNLIT;
        public float bandStart = 0f;
        public float bandEnd = 0f;
    }

    public static final class RomanLineStatics {
        public final List<RomanizedWordCluster> clusters;
        public final float[] drawX;
        public final float[] mainStart;
        public final float[] mainEnd;
        public final float[] localStart;
        public final float[] localEnd;
        public final float[] romWidth;
        public final float firstMainStart;
        public final float lastMainEnd;
        public final float lastRomEnd;
        public final float y;
        public final boolean[] hasWordIndex;
        public final boolean isRtl;

        public RomanLineStatics(
                List<RomanizedWordCluster> clusters,
                float[] drawX,
                float[] mainStart,
                float[] mainEnd,
                float[] localStart,
                float[] localEnd,
                float[] romWidth,
                float firstMainStart,
                float lastMainEnd,
                float lastRomEnd,
                float y,
                boolean[] hasWordIndex,
                boolean isRtl) {
            this.clusters = clusters;
            this.drawX = drawX;
            this.mainStart = mainStart;
            this.mainEnd = mainEnd;
            this.localStart = localStart;
            this.localEnd = localEnd;
            this.romWidth = romWidth;
            this.firstMainStart = firstMainStart;
            this.lastMainEnd = lastMainEnd;
            this.lastRomEnd = lastRomEnd;
            this.y = y;
            this.hasWordIndex = hasWordIndex;
            this.isRtl = isRtl;
        }
    }

    public static final class RomanStatics {
        public final RomanLineStatics[] lines;
        public final float extraPaddingPx;

        public RomanStatics(RomanLineStatics[] lines, float extraPaddingPx) {
            this.lines = lines;
            this.extraPaddingPx = extraPaddingPx;
        }

        public boolean isEmpty() {
            for (RomanLineStatics line : lines) {
                if (line != null) return false;
            }
            return true;
        }
    }

    public static final class CharPositionMaps {
        public final float[] charXMap;
        public final float[] romanStartShift;
        public final float[] lineTotalExtra;

        public CharPositionMaps(float[] charXMap, float[] romanStartShift, float[] lineTotalExtra) {
            this.charXMap = charXMap;
            this.romanStartShift = romanStartShift;
            this.lineTotalExtra = lineTotalExtra;
        }
    }

    public static final class ColorAlphaCache {
        private int baseColor = 0;
        private float alpha = -1f;
        private int cachedColor = Color.TRANSPARENT;

        public int get(int base, float a) {
            if (base == baseColor && a == alpha) return cachedColor;
            int newAlpha = Math.max(0, Math.min(255, Math.round(Color.alpha(base) * a)));
            int c = Color.argb(newAlpha, Color.red(base), Color.green(base), Color.blue(base));
            baseColor = base;
            alpha = a;
            cachedColor = c;
            return c;
        }
    }

    public static final class TintFilterCache {
        private int color = 0;
        @Nullable
        private ColorFilter filter = null;

        @NonNull
        public ColorFilter get(int target) {
            ColorFilter cached = filter;
            if (cached != null && target == color) return cached;
            ColorFilter created = new PorterDuffColorFilter(target, PorterDuff.Mode.SRC_IN);
            filter = created;
            color = target;
            return created;
        }
    }

    public static boolean isBidiOrInvisible(char ch) {
        switch (ch) {
            case '\u200B':
            case '\u200C':
            case '\u200D':
            case '\u200E':
            case '\u200F':
            case '\u202A':
            case '\u202B':
            case '\u202C':
            case '\u202D':
            case '\u202E':
            case '\u2066':
            case '\u2067':
            case '\u2068':
            case '\u2069':
            case '\uFEFF':
                return true;
            default:
                return false;
        }
    }

    @NonNull
    public static String stripBidiControls(@Nullable String s) {
        if (s == null || s.isEmpty()) return "";
        boolean hasAny = false;
        for (int i = 0; i < s.length(); i++) {
            if (isBidiOrInvisible(s.charAt(i))) {
                hasAny = true;
                break;
            }
        }
        if (!hasAny) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (!isBidiOrInvisible(ch)) {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    public static boolean isCJK(@Nullable String text) {
        if (text == null || text.isEmpty()) return false;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            if ((cp >= 0x4E00 && cp <= 0x9FFF)
                    || (cp >= 0x3400 && cp <= 0x4DBF)
                    || (cp >= 0x20000 && cp <= 0x2A6DF)
                    || (cp >= 0x3040 && cp <= 0x309F)
                    || (cp >= 0x30A0 && cp <= 0x30FF)
                    || (cp >= 0xAC00 && cp <= 0xD7AF)) {
                return true;
            }
            i += (cp > 0xFFFF) ? 2 : 1;
        }
        return false;
    }

    public static boolean isCJKChar(char c) {
        int cp = (int) c;
        return (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x3040 && cp <= 0x309F)
                || (cp >= 0x30A0 && cp <= 0x30FF)
                || (cp >= 0xAC00 && cp <= 0xD7AF);
    }

    public static boolean isProhibitedLineStart(char c) {
        switch (c) {
            case '、':
            case '。':
            case '！':
            case '？':
            case ')':
            case ']':
            case '}':
            case '”':
            case '’':
            case '…':
            case '—':
            case '）':
            case '】':
            case '』':
            case '」':
            case '≫':
            case '》':
            case '〉':
            case '＞':
            case '›':
            case '〕':
            case '〛':
            case ',':
            case '.':
            case '!':
            case '?':
            case ':':
            case ';':
                return true;
            default:
                return false;
        }
    }

    @Nullable
    public static Integer findSafeBreakOffsetOrNull(@NonNull String mainText, int rawBreakAt, int lineStartOffset) {
        if (rawBreakAt <= lineStartOffset || rawBreakAt > mainText.length()) return null;

        Character before = (rawBreakAt - 1 >= 0 && rawBreakAt - 1 < mainText.length())
                ? mainText.charAt(rawBreakAt - 1) : null;
        Character at = (rawBreakAt >= 0 && rawBreakAt < mainText.length())
                ? mainText.charAt(rawBreakAt) : null;

        boolean atCjkBoundary =
                (before == null || before == ' ' || isCJKChar(before))
                        && (at == null || at == ' ' || isCJKChar(at) || (before != null && isCJKChar(before)));

        int candidate = rawBreakAt;
        if (!atCjkBoundary) {
            int i = rawBreakAt;
            while (i > lineStartOffset) {
                char c = mainText.charAt(i - 1);
                Character next = (i < mainText.length()) ? mainText.charAt(i) : null;
                if (c == ' ' || isCJKChar(c) || (next != null && isCJKChar(next))) {
                    candidate = i;
                    break;
                }
                i--;
            }
            if (i <= lineStartOffset) return null;
        }

        while (candidate > lineStartOffset
                && candidate < mainText.length()
                && isProhibitedLineStart(mainText.charAt(candidate))) {
            candidate--;
            while (candidate > lineStartOffset
                    && !(mainText.charAt(candidate - 1) == ' ' || isCJKChar(mainText.charAt(candidate - 1)))) {
                candidate--;
            }
        }

        return (candidate > lineStartOffset) ? candidate : null;
    }

    public static int findSafeBreakOffset(@NonNull String mainText, int rawBreakAt, int lineStartOffset) {
        Integer res = findSafeBreakOffsetOrNull(mainText, rawBreakAt, lineStartOffset);
        return res != null ? res : rawBreakAt;
    }

    public static boolean isArabic(@Nullable String text) {
        if (text == null || text.isEmpty()) return false;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            if ((cp >= 0x0600 && cp <= 0x06FF)
                    || (cp >= 0x0750 && cp <= 0x077F)
                    || (cp >= 0x08A0 && cp <= 0x08FF)
                    || (cp >= 0xFB50 && cp <= 0xFDFF)
                    || (cp >= 0xFE70 && cp <= 0xFEFF)) {
                return true;
            }
            i += (cp > 0xFFFF) ? 2 : 1;
        }
        return false;
    }

    public static boolean isJoiningScript(@Nullable String text) {
        if (text == null || text.isEmpty()) return false;
        if (isArabic(text)) return true;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            int type = Character.getType(ch);
            if (type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK
                    || ch == '\u200D'
                    || ch == '\u200C') {
                return true;
            }
        }
        return false;
    }

    public static boolean containsJoiningScript(@Nullable String text) {
        return isJoiningScript(text);
    }

    public static boolean isRtlText(@Nullable String text) {
        if (text == null || text.isEmpty()) return false;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            if ((cp >= 0x0590 && cp <= 0x08FF)
                    || (cp >= 0xFB1D && cp <= 0xFDFF)
                    || (cp >= 0xFE70 && cp <= 0xFEFF)) {
                return true;
            }
            i += (cp > 0xFFFF) ? 2 : 1;
        }
        return false;
    }

    public static boolean isDash(char c) {
        switch (c) {
            case '-':
            case '—':
            case '–':
            case '−':
            case '―':
            case '‐':
            case '‑':
            case '~':
            case '〜':
            case '〰':
            case '\uFF0D':
                return true;
            default:
                return false;
        }
    }

    @NonNull
    public static List<int[]> shapingWordRanges(@Nullable List<WordTimestamp> words) {
        if (words == null || words.isEmpty()) return Collections.emptyList();
        List<int[]> out = new ArrayList<>(words.size());
        int start = 0;
        int lastIndex = words.size() - 1;
        while (start < words.size()) {
            int end = start;
            while (end < lastIndex
                    && !words.get(end).hasTrailingSpace
                    && !endsWithWhitespace(words.get(end).text)
                    && !startsWithWhitespace(words.get(end + 1).text)
                    && words.get(end).isBackground == words.get(end + 1).isBackground) {
                end++;
            }
            if (end > start) {
                StringBuilder joined = new StringBuilder();
                for (int k = start; k <= end; k++) {
                    joined.append(words.get(k).text);
                }
                if (isJoiningScript(joined.toString())) {
                    out.add(new int[]{start, end});
                } else {
                    for (int idx = start; idx <= end; idx++) {
                        out.add(new int[]{idx, idx});
                    }
                }
            } else {
                out.add(new int[]{start, start});
            }
            start = end + 1;
        }
        return out;
    }

    private static boolean endsWithWhitespace(@Nullable String s) {
        return s != null && !s.isEmpty() && Character.isWhitespace(s.charAt(s.length() - 1));
    }

    private static boolean startsWithWhitespace(@Nullable String s) {
        return s != null && !s.isEmpty() && Character.isWhitespace(s.charAt(0));
    }

    @NonNull
    public static List<WordTimestamp> mergeShapingWords(
            @NonNull List<WordTimestamp> words,
            @NonNull List<int[]> ranges) {
        if (ranges.size() == words.size()) {
            boolean allSingle = true;
            for (int i = 0; i < ranges.size(); i++) {
                int[] r = ranges.get(i);
                if (r[0] != r[1]) {
                    allSingle = false;
                    break;
                }
            }
            if (allSingle) return words;
        }

        List<WordTimestamp> out = new ArrayList<>(ranges.size());
        for (int rIdx = 0; rIdx < ranges.size(); rIdx++) {
            int[] range = ranges.get(rIdx);
            int first = range[0];
            int last = range[1];
            if (first == last) {
                out.add(words.get(first));
            } else {
                WordTimestamp wFirst = words.get(first);
                WordTimestamp wLast = words.get(last);
                StringBuilder sb = new StringBuilder();
                double maxEnd = wFirst.endTime;
                for (int k = first; k <= last; k++) {
                    WordTimestamp wk = words.get(k);
                    sb.append(wk.text);
                    if (wk.endTime > maxEnd) maxEnd = wk.endTime;
                }
                out.add(wFirst.copy(sb.toString(), wFirst.startTime, maxEnd, wLast.hasTrailingSpace));
            }
        }
        return out;
    }

    private static int indexOfIgnoreCase(@NonNull String haystack, @NonNull String needle, int fromIndex) {
        int max = haystack.length() - needle.length();
        int nLen = needle.length();
        for (int i = Math.max(0, fromIndex); i <= max; i++) {
            if (haystack.regionMatches(true, i, needle, 0, nLen)) {
                return i;
            }
        }
        return -1;
    }

    @NonNull
    public static List<AlignedSyllable> alignSyllablesForFormatting(
            @NonNull List<WordTimestamp> words,
            @NonNull String mainText) {
        if (words.isEmpty()) return Collections.emptyList();

        int[] strippedToOriginal = new int[mainText.length() + 1];
        StringBuilder strippedBuilder = new StringBuilder(mainText.length());
        int strippedLen = 0;
        for (int i = 0; i < mainText.length(); i++) {
            char c = mainText.charAt(i);
            if (!isBidiOrInvisible(c)) {
                strippedBuilder.append(c);
                strippedToOriginal[strippedLen++] = i;
            }
        }
        strippedToOriginal[strippedLen] = mainText.length();
        String strippedMain = strippedBuilder.toString();

        int totalSylLen = 0;
        for (int i = 0; i < words.size(); i++) {
            totalSylLen += stripBidiControls(words.get(i).text).length();
        }
        totalSylLen = Math.max(1, totalSylLen);

        List<AlignedSyllable> out = new ArrayList<>(words.size());
        int searchIdx = 0;
        for (int i = 0; i < words.size(); i++) {
            WordTimestamp syl = words.get(i);
            String sylText = stripBidiControls(syl.text);
            int found = sylText.isEmpty() ? -1 : indexOfIgnoreCase(strippedMain, sylText, searchIdx);
            if (found == -1 && !sylText.trim().isEmpty() && sylText.trim().length() != sylText.length()) {
                String trimmed = sylText.trim();
                found = indexOfIgnoreCase(strippedMain, trimmed, searchIdx);
                if (found != -1) {
                    sylText = trimmed;
                }
            }
            int si;
            int ei;
            if (found != -1) {
                si = strippedToOriginal[Math.min(found, strippedLen)];
                int endIdx = Math.min(found + sylText.length(), strippedLen);
                ei = strippedToOriginal[endIdx];
                searchIdx = found + sylText.length();
            } else {
                int fallbackStripped = Math.min(searchIdx, strippedLen);
                int propLen = Math.max(1, (int) (((double) sylText.length() / totalSylLen) * strippedLen));
                int endStripped = Math.min(fallbackStripped + propLen, strippedLen);
                si = strippedToOriginal[fallbackStripped];
                ei = strippedToOriginal[endStripped];
                searchIdx = endStripped;
            }
            out.add(new AlignedSyllable(syl, si, ei));
        }
        return out;
    }

    @NonNull
    public static String romanizedSyllableText(@NonNull WordTimestamp word, boolean hasMainTrailingSpace) {
        String clean = stripBidiControls(word.text);
        if (clean.isEmpty()) return "";
        return clean + ((word.hasTrailingSpace || hasMainTrailingSpace) ? " " : "");
    }

    public static Layout createStaticLayout(
            @NonNull CharSequence text,
            @NonNull TextPaint paint,
            int width,
            @NonNull Layout.Alignment alignment,
            boolean isRtl) {
        applyLyricsBoldPaint(paint, false);
        int safeW = Math.max(1, width);
        StaticLayout.Builder builder = StaticLayout.Builder
                .obtain(text, 0, text.length(), paint, safeW)
                .setAlignment(alignment)
                .setLineSpacing(0f, 1.0f)
                .setIncludePad(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUseLineSpacingFromFallbacks(true);
        }
        TextDirectionHeuristic dir = isRtl
                ? TextDirectionHeuristics.FIRSTSTRONG_RTL
                : TextDirectionHeuristics.FIRSTSTRONG_LTR;
        builder.setTextDirection(dir);
        return builder.build();
    }

    public static Layout measureRun(
            @NonNull String text,
            @NonNull TextPaint basePaint,
            int color,
            boolean hasJoiningScript) {
        TextPaint runPaint = new TextPaint(basePaint);
        applyLyricsBoldPaint(runPaint, false);
        runPaint.setColor(color);
        if (hasJoiningScript) {
            runPaint.setLetterSpacing(0f);
        }
        boolean rtl = isRtlText(text);
        int desiredW = Math.max(1, (int) Math.ceil(Layout.getDesiredWidth(text, runPaint)));
        if (!rtl && !hasJoiningScript) {
            BoringLayout.Metrics boring = BoringLayout.isBoring(text, runPaint);
            if (boring != null) {
                int bw = Math.max(1, Math.max(desiredW, boring.width));
                return BoringLayout.make(text, runPaint, bw, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0f, boring, false);
            }
        }
        return createStaticLayout(text, runPaint, desiredW, Layout.Alignment.ALIGN_NORMAL, rtl);
    }

    @NonNull
    public static TextPaint createRomanPaint(@NonNull TextPaint lyricPaint, int color) {
        TextPaint romPaint = new TextPaint(lyricPaint);
        romPaint.setTextSize(lyricPaint.getTextSize() * 0.7f);
        applyLyricsBoldPaint(romPaint, true);
        romPaint.setLetterSpacing(0f);
        romPaint.setColor(color);
        return romPaint;
    }

    @NonNull
    public static String rememberFormattedMainText(
            @NonNull String mainText,
            @NonNull List<WordTimestamp> words,
            @Nullable List<WordTimestamp> romanizedWords,
            @Nullable String romanizedText,
            boolean showSyllableRomanization,
            @NonNull TextPaint lyricPaint,
            int widthPx) {
        if (!showSyllableRomanization || widthPx <= 0 || isRtlText(mainText) || mainText.trim().isEmpty()) {
            return mainText;
        }
        return buildFormattedMainText(
                mainText,
                words,
                romanizedWords,
                romanizedText,
                lyricPaint,
                widthPx,
                lyricPaint.getTextSize()
        );
    }

    @NonNull
    public static String buildFormattedMainText(
            @NonNull String mainText,
            @NonNull List<WordTimestamp> words,
            @Nullable List<WordTimestamp> romanizedWords,
            @Nullable String romanizedText,
            @NonNull TextPaint lyricPaint,
            int widthPx,
            float fontSizePx) {
        if (mainText.isEmpty() || widthPx <= 0) return mainText;

        List<AlignedSyllable> alignedSyllables = alignSyllablesForFormatting(words, mainText);
        if (alignedSyllables.isEmpty()) return mainText;

        TextPaint romPaint = createRomanPaint(lyricPaint, lyricPaint.getColor());
        Layout unconstrainedResult = createStaticLayout(
                mainText, lyricPaint, Integer.MAX_VALUE / 4, Layout.Alignment.ALIGN_NORMAL, false);

        List<RomanizedWordCluster> romanClusters = buildRomanizedWordClusters(
                alignedSyllables,
                romanizedWords,
                romanizedText,
                mainText,
                unconstrainedResult,
                romPaint,
                lyricPaint.getColor()
        );
        if (romanClusters.isEmpty()) return mainText;

        Map<Integer, RomanizedWordCluster> clusterMap = new HashMap<>(romanClusters.size() * 2);
        for (int i = 0; i < romanClusters.size(); i++) {
            RomanizedWordCluster rc = romanClusters.get(i);
            clusterMap.put(rc.wordIndex, rc);
        }

        float[] sylSlotWidths = new float[alignedSyllables.size()];
        for (int sylIdx = 0; sylIdx < alignedSyllables.size(); sylIdx++) {
            AlignedSyllable syl = alignedSyllables.get(sylIdx);
            RomanizedWordCluster cluster = clusterMap.get(sylIdx);
            int sOff = Math.max(0, Math.min(mainText.length(), syl.startOffset));
            int eOff = Math.max(0, Math.min(mainText.length(), syl.endOffset));
            float startX = unconstrainedResult.getPrimaryHorizontal(sOff);
            float endX = unconstrainedResult.getPrimaryHorizontal(eOff);
            float mainWidth = Math.max(1f, endX - startX);
            float romWidth = (cluster != null) ? cluster.romanWidth : 0f;
            sylSlotWidths[sylIdx] = Math.max(mainWidth, romWidth);
        }

        List<Integer> breakIndices = new ArrayList<>();
        Set<Integer> spaceReplaced = new HashSet<>();

        float currentLineSlotSum = 0f;
        int syllablesOnCurrentLine = 0;
        int currentLineStartOffset = 0;
        float breakThreshold = widthPx - fontSizePx * 0.1f;

        for (int sylIdx = 0; sylIdx < alignedSyllables.size(); sylIdx++) {
            AlignedSyllable syl = alignedSyllables.get(sylIdx);
            float slotW = sylSlotWidths[sylIdx];
            AlignedSyllable prevSyl = (sylIdx > 0) ? alignedSyllables.get(sylIdx - 1) : null;

            if (prevSyl != null) {
                int gs = Math.max(0, Math.min(mainText.length(), prevSyl.endOffset));
                int ge = Math.max(gs, Math.min(mainText.length(), syl.startOffset));
                String gapText = mainText.substring(gs, ge);
                if (gapText.indexOf('\n') >= 0) {
                    currentLineSlotSum = 0f;
                    syllablesOnCurrentLine = 0;
                    currentLineStartOffset = Math.max(0, Math.min(mainText.length(), syl.startOffset));
                } else {
                    float gapStart = unconstrainedResult.getPrimaryHorizontal(gs);
                    float gapEnd = unconstrainedResult.getPrimaryHorizontal(ge);
                    float gapW = Math.max(0f, gapEnd - gapStart);
                    currentLineSlotSum += gapW;
                }
            }

            if (syllablesOnCurrentLine > 0 && currentLineSlotSum + slotW > breakThreshold) {
                int rawBreakAt = Math.max(0, Math.min(mainText.length(), syl.startOffset));
                Integer breakAt = findSafeBreakOffsetOrNull(mainText, rawBreakAt, currentLineStartOffset);
                if (breakAt != null && breakAt > 0) {
                    breakIndices.add(breakAt);
                    if (breakAt - 1 < mainText.length() && mainText.charAt(breakAt - 1) == ' ') {
                        spaceReplaced.add(breakAt - 1);
                    }
                    currentLineStartOffset = breakAt;
                    currentLineSlotSum = slotW;
                    syllablesOnCurrentLine = 1;
                } else {
                    currentLineSlotSum += slotW;
                    syllablesOnCurrentLine++;
                }
            } else {
                currentLineSlotSum += slotW;
                syllablesOnCurrentLine++;
            }
        }

        if (breakIndices.isEmpty()) return mainText;

        StringBuilder sb = new StringBuilder(mainText.length() + breakIndices.size());
        int prev = 0;
        for (int i = 0; i < breakIndices.size(); i++) {
            int safeBIdx = Math.max(0, Math.min(mainText.length(), breakIndices.get(i)));
            int safePrev = Math.max(0, Math.min(mainText.length(), prev));
            if (safeBIdx > safePrev) {
                int spaceIdx = safeBIdx - 1;
                if (spaceReplaced.contains(spaceIdx)
                        && spaceIdx >= 0
                        && spaceIdx < mainText.length()
                        && mainText.charAt(spaceIdx) == ' ') {
                    sb.append(mainText, safePrev, spaceIdx);
                    sb.append('\n');
                    prev = safeBIdx;
                } else {
                    sb.append(mainText, safePrev, safeBIdx);
                    sb.append('\n');
                    prev = safeBIdx;
                }
            }
        }
        int finalPrev = Math.max(0, Math.min(mainText.length(), prev));
        if (finalPrev < mainText.length()) {
            sb.append(mainText.substring(finalPrev));
        }
        return sb.toString();
    }

    @NonNull
    public static List<RomanizedWordCluster> rememberRomanClusters(
            boolean showSyllableRomanization,
            @NonNull List<AlignedSyllable> alignedSyllables,
            @Nullable List<WordTimestamp> romanizedWords,
            @Nullable String romanizedText,
            @NonNull String mainText,
            @NonNull Layout textLayoutResult,
            @NonNull TextPaint lyricPaint,
            int expressiveAccent) {
        if (!showSyllableRomanization) {
            return Collections.emptyList();
        }
        TextPaint romPaint = createRomanPaint(lyricPaint, expressiveAccent);
        return buildRomanizedWordClusters(
                alignedSyllables,
                romanizedWords,
                romanizedText,
                mainText,
                textLayoutResult,
                romPaint,
                expressiveAccent
        );
    }

    @NonNull
    public static List<RomanizedWordCluster> buildRomanizedWordClusters(
            @NonNull List<AlignedSyllable> alignedSyllables,
            @Nullable List<WordTimestamp> romanizedWords,
            @Nullable String romanizedText,
            @NonNull String mainText,
            @NonNull Layout textLayoutResult,
            @NonNull TextPaint romPaint,
            int expressiveAccent) {
        if (alignedSyllables.isEmpty()) return Collections.emptyList();

        List<WordTimestamp> rWords = null;
        if (romanizedWords != null && !romanizedWords.isEmpty()) {
            rWords = new ArrayList<>(romanizedWords.size());
            for (int i = 0; i < romanizedWords.size(); i++) {
                WordTimestamp rw = romanizedWords.get(i);
                if (!rw.isBackground) {
                    rWords.add(rw);
                }
            }
            if (rWords.isEmpty()) {
                rWords = romanizedWords;
            }
        } else if (romanizedText != null && !romanizedText.trim().isEmpty()) {
            String[] split = romanizedText.trim().split("\\s+");
            int sylSize = alignedSyllables.size();
            if (split.length == sylSize) {
                rWords = new ArrayList<>(split.length);
                for (int i = 0; i < split.length; i++) {
                    WordTimestamp orig = alignedSyllables.get(i).syllable;
                    rWords.add(new WordTimestamp(
                            split[i],
                            orig.startTime,
                            orig.endTime,
                            true,
                            orig.isBackground,
                            split[i]
                    ));
                }
            } else if (split.length > 0 && sylSize > 0) {
                rWords = new ArrayList<>(sylSize);
                if (split.length < sylSize) {
                    for (int i = 0; i < sylSize; i++) {
                        WordTimestamp orig = alignedSyllables.get(i).syllable;
                        int tokStart = (i * split.length) / sylSize;
                        int tokEnd = ((i + 1) * split.length) / sylSize;
                        String rom = "";
                        if (tokEnd > tokStart && tokStart < split.length) {
                            rom = split[tokStart];
                        }
                        rWords.add(new WordTimestamp(
                                rom,
                                orig.startTime,
                                orig.endTime,
                                !rom.isEmpty(),
                                orig.isBackground,
                                rom
                        ));
                    }
                } else {
                    for (int i = 0; i < sylSize; i++) {
                        WordTimestamp orig = alignedSyllables.get(i).syllable;
                        int tokStart = (i * split.length) / sylSize;
                        int tokEnd = (i == sylSize - 1) ? split.length : (((i + 1) * split.length) / sylSize);
                        StringBuilder sb = new StringBuilder();
                        for (int k = tokStart; k < tokEnd && k < split.length; k++) {
                            if (sb.length() > 0) sb.append(" ");
                            sb.append(split[k]);
                        }
                        String rom = sb.toString();
                        rWords.add(new WordTimestamp(
                                rom,
                                orig.startTime,
                                orig.endTime,
                                !rom.isEmpty(),
                                orig.isBackground,
                                rom
                        ));
                    }
                }
            }
        }

        if (rWords == null || rWords.isEmpty()) return Collections.emptyList();

        String cleanMainText = stripBidiControls(mainText).trim();
        String cleanRomanText = (romanizedText != null) ? stripBidiControls(romanizedText).trim() : null;
        if (cleanRomanText != null && cleanRomanText.equalsIgnoreCase(cleanMainText)) {
            return Collections.emptyList();
        }

        int n = alignedSyllables.size();
        String[] sylTextArr = new String[n];
        long[] sylStartMsArr = new long[n];
        long[] sylEndMsArr = new long[n];
        StringBuilder assembledBuilder = new StringBuilder();

        for (int i = 0; i < n; i++) {
            AlignedSyllable syl = alignedSyllables.get(i);
            WordTimestamp rw = (i < rWords.size()) ? rWords.get(i) : null;
            boolean hasMainTrailingSpace = syl.syllable.hasTrailingSpace
                    || (syl.endOffset < mainText.length() && mainText.charAt(syl.endOffset) == ' ');
            if (rw != null) {
                sylStartMsArr[i] = rw.startTimeMs();
                sylEndMsArr[i] = rw.endTimeMs();
                sylTextArr[i] = romanizedSyllableText(rw, hasMainTrailingSpace);
            } else {
                sylStartMsArr[i] = syl.syllable.startTimeMs();
                sylEndMsArr[i] = syl.syllable.endTimeMs();
                sylTextArr[i] = "";
            }
            assembledBuilder.append(sylTextArr[i]);
        }

        String assembledRoman = stripBidiControls(assembledBuilder.toString()).trim();
        if (assembledRoman.isEmpty() || assembledRoman.equalsIgnoreCase(cleanMainText)) {
            return Collections.emptyList();
        }

        List<RomanizedWordCluster> result = new ArrayList<>(n);
        int maxCharIdx = Math.max(0, mainText.length() - 1);
        for (int idx = 0; idx < n; idx++) {
            String text = sylTextArr[idx];
            if (text != null && !text.trim().isEmpty()) {
                AlignedSyllable syl = alignedSyllables.get(idx);
                int safeStart = Math.max(0, Math.min(maxCharIdx, syl.startOffset));
                int li = mainText.isEmpty() ? 0 : textLayoutResult.getLineForOffset(safeStart);
                Layout subLayout = measureRun(text, romPaint, expressiveAccent, false);
                float hPos = textLayoutResult.getPrimaryHorizontal(Math.min(syl.startOffset, mainText.length()));
                result.add(new RomanizedWordCluster(
                        idx,
                        syl.startOffset,
                        syl.endOffset,
                        text,
                        li,
                        sylStartMsArr[idx],
                        sylEndMsArr[idx],
                        hPos,
                        subLayout
                ));
            }
        }
        return result;
    }

    @NonNull
    public static float[] buildCharXMap(
            @NonNull String mainText,
            @NonNull Layout textLayoutResult,
            int lineCount,
            @NonNull boolean[] lineIsRtl,
            @NonNull float[] lineStarts) {
        float[] map = new float[mainText.length() + 1];
        int maxOff = Math.max(0, mainText.length() - 1);
        for (int i = 0; i <= mainText.length(); i++) {
            int idx = Math.min(i, maxOff);
            int li = mainText.isEmpty() ? 0 : textLayoutResult.getLineForOffset(idx);
            if (li >= lineCount) {
                map[i] = (lineStarts.length > 0) ? lineStarts[lineStarts.length - 1] : 0f;
            } else {
                float h = textLayoutResult.getPrimaryHorizontal(Math.min(i, mainText.length()));
                float local = lineIsRtl[li]
                        ? (textLayoutResult.getLineRight(li) - h)
                        : (h - textLayoutResult.getLineLeft(li));
                map[i] = lineStarts[li] + local;
            }
        }
        return map;
    }

    @NonNull
    public static CharPositionMaps rememberCharXMap(
            @NonNull String mainText,
            @NonNull Layout textLayoutResult,
            int lineCount,
            @NonNull boolean[] lineIsRtl,
            @NonNull float[] lineStarts,
            @NonNull List<AlignedSyllable> alignedSyllables,
            @NonNull List<RomanizedWordCluster> romanClusters,
            @NonNull Layout.Alignment alignment,
            float fontSizePx) {
        float[] charXMap = buildCharXMap(mainText, textLayoutResult, lineCount, lineIsRtl, lineStarts);
        float[][] shiftAndExtra = buildRomanStartShiftPair(
                mainText, charXMap, textLayoutResult, lineCount, alignedSyllables, romanClusters, alignment, fontSizePx);
        return new CharPositionMaps(charXMap, shiftAndExtra[0], shiftAndExtra[1]);
    }

    @NonNull
    public static float[] buildRomanStartShift(
            @NonNull List<RomanizedWordCluster> romanClusters,
            @NonNull String mainText,
            @NonNull List<AlignedSyllable> alignedSyllables,
            @NonNull Layout textLayoutResult,
            @NonNull float[] charXMap,
            @NonNull Layout.Alignment alignment,
            float fontSizePx) {
        return buildRomanStartShiftPair(
                mainText,
                charXMap,
                textLayoutResult,
                textLayoutResult.getLineCount(),
                alignedSyllables,
                romanClusters,
                alignment,
                fontSizePx
        )[0];
    }

    @NonNull
    private static float[][] buildRomanStartShiftPair(
            @NonNull String mainText,
            @NonNull float[] charXMap,
            @NonNull Layout textLayoutResult,
            int lineCount,
            @NonNull List<AlignedSyllable> alignedSyllables,
            @NonNull List<RomanizedWordCluster> romanClusters,
            @NonNull Layout.Alignment alignment,
            float fontSizePx) {
        float[] shiftByWordIndex = new float[romanClusters.size() + alignedSyllables.size()];
        float[] lineTotalExtra = new float[Math.max(1, lineCount)];
        if (romanClusters.isEmpty() || isRtlText(mainText) || containsJoiningScript(mainText)) {
            return new float[][]{shiftByWordIndex, lineTotalExtra};
        }

        Map<Integer, RomanizedWordCluster> clusterMap = new HashMap<>(romanClusters.size() * 2);
        for (int i = 0; i < romanClusters.size(); i++) {
            RomanizedWordCluster rc = romanClusters.get(i);
            clusterMap.put(rc.wordIndex, rc);
        }

        float runningShift = 0f;
        int currentLine = -1;
        int maxCharIdx = Math.max(0, mainText.length() - 1);

        for (int sylIdx = 0; sylIdx < alignedSyllables.size(); sylIdx++) {
            AlignedSyllable syl = alignedSyllables.get(sylIdx);
            int li = mainText.isEmpty() ? 0 : textLayoutResult.getLineForOffset(Math.max(0, Math.min(maxCharIdx, syl.startOffset)));
            if (li != currentLine) {
                currentLine = li;
                runningShift = 0f;
            }

            if (sylIdx < shiftByWordIndex.length) {
                shiftByWordIndex[sylIdx] = runningShift;
            }

            RomanizedWordCluster cluster = clusterMap.get(sylIdx);
            if (cluster == null) continue;

            int sylEndLine = mainText.isEmpty() ? 0 : textLayoutResult.getLineForOffset(Math.min(syl.endOffset, maxCharIdx));
            int lineEndOff = getVisibleLineEnd(textLayoutResult, mainText, li);

            float startX = charXMap[Math.max(0, Math.min(charXMap.length - 1, syl.startOffset))];
            float endX = (li == sylEndLine)
                    ? charXMap[Math.max(0, Math.min(charXMap.length - 1, syl.endOffset))]
                    : charXMap[Math.max(0, Math.min(charXMap.length - 1, lineEndOff))];
            float mainWidth = Math.max(1f, endX - startX);
            float romWidth = cluster.romanWidth;

            if (romWidth > mainWidth) {
                float extra = romWidth - mainWidth;
                runningShift += extra;
                if (li >= 0 && li < lineTotalExtra.length) {
                    lineTotalExtra[li] += extra;
                }
            }
        }

        if (alignment == Layout.Alignment.ALIGN_CENTER || alignment == Layout.Alignment.ALIGN_OPPOSITE) {
            for (int idx = 0; idx < shiftByWordIndex.length; idx++) {
                if (idx >= alignedSyllables.size()) continue;
                AlignedSyllable syl = alignedSyllables.get(idx);
                int li = mainText.isEmpty() ? 0 : textLayoutResult.getLineForOffset(Math.max(0, Math.min(maxCharIdx, syl.startOffset)));
                float totalExtra = (li >= 0 && li < lineTotalExtra.length) ? lineTotalExtra[li] : 0f;
                float correction = (alignment == Layout.Alignment.ALIGN_CENTER) ? (totalExtra / 2f) : totalExtra;
                shiftByWordIndex[idx] -= correction;
            }
        }

        return new float[][]{shiftByWordIndex, lineTotalExtra};
    }

    private static int getVisibleLineEnd(@NonNull Layout layout, @NonNull String text, int line) {
        int end = layout.getLineEnd(line);
        while (end > layout.getLineStart(line) && end - 1 < text.length()) {
            char ch = text.charAt(end - 1);
            if (ch == '\n' || ch == '\r' || ch == ' ' || ch == '\t') {
                end--;
            } else {
                break;
            }
        }
        return end;
    }

    public static float calculateGapShift(int syllableIndex, @NonNull float[] romanStartShift) {
        if (syllableIndex < 0 || syllableIndex >= romanStartShift.length) return 0f;
        return romanStartShift[syllableIndex];
    }

    @NonNull
    public static SyllableBounds buildSyllableBounds(
            @NonNull List<AlignedSyllable> alignedSyllables,
            @NonNull float[] charXMap,
            @NonNull Layout textLayoutResult,
            @NonNull String formattedMainText) {
        int n = alignedSyllables.size();
        float[] startX = new float[n];
        float[] endX = new float[n];
        int maxLen = Math.max(0, formattedMainText.length() - 1);

        for (int i = 0; i < n; i++) {
            AlignedSyllable current = alignedSyllables.get(i);
            int currentLine = formattedMainText.isEmpty()
                    ? 0
                    : textLayoutResult.getLineForOffset(Math.max(0, Math.min(maxLen, current.startOffset)));
            int startOff;
            if (i > 0) {
                AlignedSyllable prev = alignedSyllables.get(i - 1);
                int prevLine = formattedMainText.isEmpty()
                        ? 0
                        : textLayoutResult.getLineForOffset(Math.max(0, Math.min(maxLen, prev.endOffset)));
                startOff = (prevLine == currentLine) ? prev.endOffset : current.startOffset;
            } else {
                startOff = current.startOffset;
            }
            startX[i] = (startOff < 0 || charXMap.length == 0)
                    ? 0f
                    : charXMap[Math.max(0, Math.min(charXMap.length - 1, startOff))];
            endX[i] = (current.endOffset < 0 || charXMap.length == 0)
                    ? 0f
                    : charXMap[Math.max(0, Math.min(charXMap.length - 1, current.endOffset))];
        }
        return new SyllableBounds(startX, endX);
    }

    @NonNull
    public static List<ReconstructedWord> buildReconstructedWords(
            @NonNull List<AlignedSyllable> alignedSyllables,
            @NonNull String formattedMainText) {
        List<ReconstructedWord> result = new ArrayList<>();
        int idx = 0;
        while (idx < alignedSyllables.size()) {
            List<AlignedSyllable> group = new ArrayList<>();
            group.add(alignedSyllables.get(idx));
            idx++;
            while (idx < alignedSyllables.size()) {
                AlignedSyllable prev = group.get(group.size() - 1);
                AlignedSyllable next = alignedSyllables.get(idx);
                int gapStart = prev.endOffset;
                int gapEnd = next.startOffset;
                boolean hasSpaceBetween = gapEnd > gapStart
                        && formattedMainText.substring(
                                Math.max(0, Math.min(formattedMainText.length(), gapStart)),
                                Math.max(0, Math.min(formattedMainText.length(), gapEnd)))
                        .indexOf(' ') >= 0;
                if (hasSpaceBetween) break;
                group.add(next);
                idx++;
            }
            if (!group.isEmpty()) {
                int wordLen = 0;
                for (int k = 0; k < group.size(); k++) {
                    wordLen += group.get(k).syllable.text.length();
                }
                AlignedSyllable first = group.get(0);
                AlignedSyllable last = group.get(group.size() - 1);
                result.add(new ReconstructedWord(
                        first.startOffset,
                        last.endOffset,
                        first.syllable.startTimeMs(),
                        last.syllable.endTimeMs(),
                        wordLen,
                        group
                ));
            }
        }
        return result;
    }

    @NonNull
    public static ClusterStatics buildClusterStatics(
            @NonNull List<VisualCluster> clusters,
            @NonNull float[] romanStartShift,
            float lineExtraHeight,
            @NonNull boolean[] lineIsRtl,
            @NonNull float[] lineLefts,
            @NonNull float[] lineRights,
            @NonNull float[] lineStarts,
            @NonNull float[] lineWidths,
            float fontSizePx,
            boolean applePivotMode) {
        int n = clusters.size();
        float[] baseX = new float[n];
        float[] baseY = new float[n];
        float[] pivotX = new float[n];
        float[] pivotY = new float[n];
        boolean[] rtl = new boolean[n];
        float[] ll = new float[n];
        float[] lr = new float[n];
        float[] ls = new float[n];
        float[] lw = new float[n];
        float[] localStart = new float[n];
        float[] localEnd = new float[n];

        for (int i = 0; i < n; i++) {
            VisualCluster vc = clusters.get(i);
            int li = vc.lineIdx;
            boolean isRtl = lineIsRtl[li];
            float gapShift = (vc.isRtl || isRtl || vc.isJoiningScript) ? 0f : calculateGapShift(vc.syllableIndex, romanStartShift);
            baseX[i] = vc.x + gapShift;
            baseY[i] = vc.y + li * lineExtraHeight;
            rtl[i] = isRtl;
            float lineL = lineLefts[li];
            float lineR = lineRights[li];
            ll[i] = lineL;
            lr[i] = lineR;
            ls[i] = lineStarts[li];
            lw[i] = lineWidths[li];

            float lStart = isRtl ? (lineR - (vc.x + vc.width)) : (vc.x - lineL);
            localStart[i] = lStart;
            localEnd[i] = lStart + vc.width;

            pivotX[i] = vc.width / 2f;
            float subH = vc.subLayout.getHeight();
            pivotY[i] = applePivotMode ? (subH * 0.8f + fontSizePx * 0.3f) : subH;
        }

        return new ClusterStatics(
                baseX, baseY, pivotX, pivotY,
                rtl, ll, lr, ls, lw,
                localStart, localEnd);
    }

    public static void computeWipe(
            @NonNull VisualCluster vc,
            boolean isRtlLine,
            float lineStart,
            float lineWidth,
            float localStart,
            float localEnd,
            int activeSylIdx,
            int activeLineIdx,
            float curGX,
            boolean isActiveSylLastOfLine,
            float activeSylProgress,
            float gradientWidth,
            @NonNull WipeResult out) {
        float halfGradientWidth = gradientWidth * 0.5f;

        boolean isPartOfActiveSyl = vc.syllableIndex != -1 && vc.syllableIndex == activeSylIdx;
        int effectiveLineIdx = isPartOfActiveSyl ? vc.lineIdx : activeLineIdx;

        float rawLocalX = curGX - lineStart;
        float brushShift = (vc.lineIdx == effectiveLineIdx && isActiveSylLastOfLine)
                ? (activeSylProgress * halfGradientWidth)
                : 0f;

        float localX;
        if (activeSylIdx == -1) {
            localX = -gradientWidth * 2f;
        } else if (isPartOfActiveSyl) {
            localX = rawLocalX + brushShift;
        } else if (vc.lineIdx < activeLineIdx) {
            localX = lineWidth + gradientWidth * 2f;
        } else if (vc.lineIdx > activeLineIdx) {
            localX = -gradientWidth * 2f;
        } else {
            localX = rawLocalX + brushShift;
        }

        if (localX >= localEnd + halfGradientWidth) {
            out.mode = WIPE_PAST;
            return;
        }
        if (localX <= localStart - halfGradientWidth) {
            out.mode = WIPE_UNLIT;
            return;
        }

        out.mode = WIPE_BAND;
        float relWiperX = localX - localStart;
        if (localX < halfGradientWidth) {
            float growProgress = Math.max(0f, Math.min(1f, localX / halfGradientWidth));
            float growWidth = Math.max(0.1f, gradientWidth * growProgress);
            if (isRtlLine) {
                out.bandStart = vc.width + localStart;
                out.bandEnd = vc.width + localStart - growWidth;
            } else {
                out.bandStart = -localStart;
                out.bandEnd = growWidth - localStart;
            }
        } else {
            if (isRtlLine) {
                out.bandStart = vc.width - (relWiperX - halfGradientWidth);
                out.bandEnd = vc.width - (relWiperX + halfGradientWidth);
            } else {
                out.bandStart = relWiperX - halfGradientWidth;
                out.bandEnd = relWiperX + halfGradientWidth;
            }
        }
    }

    public static void drawLayoutWithPaint(
            @NonNull Canvas canvas,
            @NonNull Layout layout,
            float topLeftX,
            float topLeftY,
            int color,
            @Nullable Shader shader) {
        TextPaint paint = layout.getPaint();
        int prevColor = paint.getColor();
        Shader prevShader = paint.getShader();
        paint.setColor(color);
        paint.setShader(shader);
        canvas.save();
        canvas.translate(topLeftX, topLeftY);
        layout.draw(canvas);
        canvas.restore();
        paint.setShader(prevShader);
        paint.setColor(prevColor);
    }

    public static void drawWipeText(
            @NonNull Canvas canvas,
            @NonNull VisualCluster vc,
            float topLeftX,
            float topLeftY,
            boolean isRtlLine,
            float lineStart,
            float lineWidth,
            float localStart,
            float localEnd,
            int activeSylIdx,
            int activeLineIdx,
            float curGX,
            boolean isActiveSylLastOfLine,
            float activeSylProgress,
            float gradientWidth,
            int pastColor,
            int unlitColor,
            int litColor,
            @NonNull WipeResult wipe) {
        computeWipe(
                vc, isRtlLine, lineStart, lineWidth, localStart, localEnd,
                activeSylIdx, activeLineIdx, curGX, isActiveSylLastOfLine,
                activeSylProgress, gradientWidth, wipe);
        if (wipe.mode == WIPE_PAST) {
            drawLayoutWithPaint(canvas, vc.subLayout, topLeftX, topLeftY, pastColor, null);
        } else if (wipe.mode == WIPE_UNLIT) {
            drawLayoutWithPaint(canvas, vc.subLayout, topLeftX, topLeftY, unlitColor, null);
        } else {
            float bStart = wipe.bandStart;
            float bEnd = wipe.bandEnd;
            if (Math.abs(bEnd - bStart) < 0.01f) {
                bEnd = bStart + 0.01f;
            }
            LinearGradient shader = new LinearGradient(
                    bStart, 0f, bEnd, 0f,
                    new int[]{litColor, litColor, unlitColor, unlitColor},
                    GRADIENT_STOPS,
                    Shader.TileMode.CLAMP);
            drawLayoutWithPaint(canvas, vc.subLayout, topLeftX, topLeftY, Color.WHITE, shader);
        }
    }

    @NonNull
    public static List<RomanizedWordCluster>[] groupRomanClustersByLine(
            @NonNull List<RomanizedWordCluster> romanClusters,
            int lineCount) {
        @SuppressWarnings("unchecked")
        List<RomanizedWordCluster>[] out = new List[Math.max(0, lineCount)];
        for (int i = 0; i < out.length; i++) {
            out[i] = Collections.emptyList();
        }
        if (romanClusters.isEmpty() || lineCount <= 0) return out;

        List<RomanizedWordCluster> sorted = new ArrayList<>(romanClusters);
        Collections.sort(sorted, Comparator.comparingInt(a -> a.wordIndex));
        for (int li = 0; li < lineCount; li++) {
            List<RomanizedWordCluster> bucket = null;
            for (int i = 0; i < sorted.size(); i++) {
                RomanizedWordCluster rc = sorted.get(i);
                if (rc.lineIdx == li) {
                    if (bucket == null) bucket = new ArrayList<>();
                    bucket.add(rc);
                }
            }
            if (bucket != null) {
                out[li] = bucket;
            }
        }
        return out;
    }

    @NonNull
    public static RomanStatics buildRomanStatics(
            @NonNull List<RomanizedWordCluster>[] romanLines,
            @NonNull float[] charXMap,
            @NonNull float[] romanStartShift,
            @NonNull float[] lineBaselines,
            @NonNull float[] lineBottoms,
            @NonNull float[] lineLefts,
            @NonNull float[] lineStarts,
            int lineCount,
            float fontSizePx,
            float lineExtraHeight,
            float totalWidth,
            float extraPaddingPx) {
        return buildRomanStatics(
                romanLines, charXMap, romanStartShift, lineBaselines, lineBottoms,
                lineLefts, lineStarts, lineCount, fontSizePx, lineExtraHeight,
                totalWidth, extraPaddingPx, null, null, false);
    }

    @NonNull
    public static RomanStatics buildRomanStatics(
            @NonNull List<RomanizedWordCluster>[] romanLines,
            @NonNull float[] charXMap,
            @NonNull float[] romanStartShift,
            @NonNull float[] lineBaselines,
            @NonNull float[] lineBottoms,
            @NonNull float[] lineLefts,
            @NonNull float[] lineStarts,
            int lineCount,
            float fontSizePx,
            float lineExtraHeight,
            float totalWidth,
            float extraPaddingPx,
            @Nullable float[] lineRights,
            @Nullable boolean[] lineIsRtl,
            boolean isRightAligned) {
        int count = Math.min(lineCount, romanLines.length);
        RomanLineStatics[] lines = new RomanLineStatics[count];

        for (int li = 0; li < count; li++) {
            List<RomanizedWordCluster> cs = romanLines[li];
            if (cs == null || cs.isEmpty()) {
                lines[li] = null;
                continue;
            }
            boolean isRtl = (lineIsRtl != null && li < lineIsRtl.length) && lineIsRtl[li];
            float lineLeft = (li < lineLefts.length) ? lineLefts[li] : 0f;
            float lineRight = (lineRights != null && li < lineRights.length) ? lineRights[li] : 0f;
            float lineStart = (li < lineStarts.length) ? lineStarts[li] : 0f;
            int n = cs.size();
            float[] drawX = new float[n];
            float[] mainStart = new float[n];
            float[] mainEnd = new float[n];
            float[] romWidth = new float[n];
            int maxWordIdx = 0;

            if (isRtl) {
                float totalRomW = 0f;
                for (int i = 0; i < n; i++) {
                    romWidth[i] = cs.get(i).romanWidth;
                    totalRomW += romWidth[i];
                    if (cs.get(i).wordIndex > maxWordIdx) {
                        maxWordIdx = cs.get(i).wordIndex;
                    }
                }
                float startX;
                if (isRightAligned) {
                    float targetRight = lineRight > 0f ? lineRight : (lineLeft + totalWidth);
                    startX = Math.max(0f, targetRight - totalRomW);
                } else {
                    startX = lineLeft;
                }
                float curX = startX;
                for (int i = 0; i < n; i++) {
                    RomanizedWordCluster c = cs.get(i);
                    drawX[i] = curX;
                    curX += romWidth[i];
                    mainStart[i] = (charXMap.length == 0)
                            ? 0f
                            : charXMap[Math.max(0, Math.min(charXMap.length - 1, c.startIndex))];
                    mainEnd[i] = (charXMap.length == 0)
                            ? 0f
                            : charXMap[Math.max(0, Math.min(charXMap.length - 1, c.endIndex))];
                }
            } else {
                for (int i = 0; i < n; i++) {
                    RomanizedWordCluster c = cs.get(i);
                    float gx = (c.startIndex < 0)
                            ? 0f
                            : ((c.startIndex >= charXMap.length) ? totalWidth : charXMap[c.startIndex]);
                    float shift = (c.wordIndex >= 0 && c.wordIndex < romanStartShift.length)
                            ? romanStartShift[c.wordIndex]
                            : 0f;
                    drawX[i] = gx - lineStart + lineLeft + shift;
                    mainStart[i] = (charXMap.length == 0)
                            ? 0f
                            : charXMap[Math.max(0, Math.min(charXMap.length - 1, c.startIndex))];
                    mainEnd[i] = (charXMap.length == 0)
                            ? 0f
                            : charXMap[Math.max(0, Math.min(charXMap.length - 1, c.endIndex))];
                    romWidth[i] = c.romanWidth;
                    if (c.wordIndex > maxWordIdx) {
                        maxWordIdx = c.wordIndex;
                    }
                }
            }

            float firstRomX = drawX[0];
            float[] localStart = new float[n];
            float[] localEnd = new float[n];
            for (int i = 0; i < n; i++) {
                localStart[i] = drawX[i] - firstRomX;
                localEnd[i] = localStart[i] + romWidth[i];
            }

            boolean[] hasWordIndex = new boolean[maxWordIdx + 1];
            for (int i = 0; i < n; i++) {
                RomanizedWordCluster c = cs.get(i);
                if (c.wordIndex >= 0 && c.wordIndex < hasWordIndex.length) {
                    hasWordIndex[c.wordIndex] = true;
                }
            }

            float baseLineBottom = (li < lineBottoms.length)
                    ? lineBottoms[li]
                    : (lineBaselines[li] + fontSizePx * 0.25f);
            float y = baseLineBottom + li * lineExtraHeight + fontSizePx * 0.08f;

            lines[li] = new RomanLineStatics(
                    cs,
                    drawX,
                    mainStart,
                    mainEnd,
                    localStart,
                    localEnd,
                    romWidth,
                    mainStart[0],
                    mainEnd[n - 1],
                    localEnd[n - 1],
                    y,
                    hasWordIndex,
                    isRtl
            );
        }
        return new RomanStatics(lines, extraPaddingPx);
    }

    public static int lerpColor(int startColor, int endColor, float fraction) {
        float t = Math.max(0f, Math.min(1f, fraction));
        int a = Math.round(Color.alpha(startColor) + (Color.alpha(endColor) - Color.alpha(startColor)) * t);
        int r = Math.round(Color.red(startColor) + (Color.red(endColor) - Color.red(startColor)) * t);
        int g = Math.round(Color.green(startColor) + (Color.green(endColor) - Color.green(startColor)) * t);
        int b = Math.round(Color.blue(startColor) + (Color.blue(endColor) - Color.blue(startColor)) * t);
        return Color.argb(a, r, g, b);
    }

    public static void drawRomanizedWordClusters(
            @NonNull Canvas canvas,
            @NonNull RomanStatics romanStatics,
            boolean isActiveLine,
            int activeSylIdx,
            int activeLineIdx,
            float curGX,
            boolean isActiveSylLastOfLine,
            float activeSylProgress,
            float gradientWidth,
            int pastColor,
            int unlitColor,
            int litColor,
            int lineColor,
            float curSimpleState,
            int expressiveAccent) {
        RomanLineStatics[] lines = romanStatics.lines;
        if (lines.length == 0) return;

        float pad = romanStatics.extraPaddingPx;
        float halfGradientWidth = gradientWidth * 0.5f;
        int inactiveColor = Color.TRANSPARENT;
        if (!isActiveLine) {
            int baseColor = (lineColor != Color.TRANSPARENT) ? lineColor : expressiveAccent;
            inactiveColor = lerpColor(baseColor, expressiveAccent, Math.max(0f, Math.min(1f, curSimpleState)));
        }

        for (int li = 0; li < lines.length; li++) {
            RomanLineStatics line = lines[li];
            if (line == null) continue;
            List<RomanizedWordCluster> cs = line.clusters;
            int n = cs.size();
            float topY = line.y + pad;

            if (!isActiveLine) {
                for (int i = 0; i < n; i++) {
                    drawLayoutWithPaint(canvas, cs.get(i).subLayout, line.drawX[i] + pad, topY, inactiveColor, null);
                }
                continue;
            }

            boolean isLineInPast = activeLineIdx != -1 && li < activeLineIdx;
            boolean isLineInFuture = activeLineIdx != -1 && li > activeLineIdx;

            float curRomGX;
            if (isLineInPast) {
                curRomGX = Float.MAX_VALUE;
            } else if (isLineInFuture || activeSylIdx == -1) {
                curRomGX = -gradientWidth * 2f;
            } else if (line.isRtl) {
                int activeClusterIdx = -1;
                for (int i = 0; i < n; i++) {
                    if (cs.get(i).wordIndex == activeSylIdx) {
                        activeClusterIdx = i;
                        break;
                    }
                }
                if (activeClusterIdx >= 0) {
                    float prog = Math.max(0f, Math.min(1f, activeSylProgress));
                    curRomGX = line.localStart[activeClusterIdx] + prog * line.romWidth[activeClusterIdx];
                } else {
                    int lastWordIdx = cs.get(n - 1).wordIndex;
                    int firstWordIdx = cs.get(0).wordIndex;
                    if (activeSylIdx > lastWordIdx) {
                        curRomGX = line.lastRomEnd + gradientWidth * 2f;
                    } else if (activeSylIdx < firstWordIdx) {
                        curRomGX = -gradientWidth * 2f;
                    } else {
                        float foundEnd = 0f;
                        for (int i = 0; i < n; i++) {
                            if (cs.get(i).wordIndex < activeSylIdx) {
                                foundEnd = line.localEnd[i];
                            }
                        }
                        curRomGX = foundEnd;
                    }
                }
            } else if (curGX <= line.firstMainStart) {
                curRomGX = curGX - line.firstMainStart;
            } else if (curGX >= line.lastMainEnd) {
                curRomGX = line.lastRomEnd + (curGX - line.lastMainEnd);
            } else {
                float computedX = 0f;
                boolean found = false;
                for (int i = 0; i < n; i++) {
                    float sStart = line.mainStart[i];
                    float sEnd = line.mainEnd[i];
                    if (curGX >= sStart && curGX <= sEnd) {
                        float prog = (curGX - sStart) / Math.max(1f, sEnd - sStart);
                        computedX = line.localStart[i] + prog * line.romWidth[i];
                        found = true;
                        break;
                    }
                    if (i < n - 1) {
                        float nextStart = line.mainStart[i + 1];
                        if (curGX >= sEnd && curGX <= nextStart) {
                            float gapProg = (curGX - sEnd) / Math.max(1f, nextStart - sEnd);
                            float romEnd = line.localEnd[i];
                            computedX = romEnd + gapProg * (line.localStart[i + 1] - romEnd);
                            found = true;
                            break;
                        }
                    }
                }
                curRomGX = found ? computedX : (curGX - line.firstMainStart);
            }

            boolean activeWordInLine = activeSylIdx >= 0
                    && activeSylIdx < line.hasWordIndex.length
                    && line.hasWordIndex[activeSylIdx];
            float brushShift = ((li == activeLineIdx || activeWordInLine) && isActiveSylLastOfLine)
                    ? (activeSylProgress * halfGradientWidth)
                    : 0f;
            float localX = curRomGX + brushShift;

            for (int i = 0; i < n; i++) {
                float topLeftX = line.drawX[i] + pad;
                float localStart = line.localStart[i];
                float localEnd = line.localEnd[i];

                if (localX >= localEnd + halfGradientWidth) {
                    drawLayoutWithPaint(canvas, cs.get(i).subLayout, topLeftX, topY, pastColor, null);
                } else if (localX <= localStart - halfGradientWidth) {
                    drawLayoutWithPaint(canvas, cs.get(i).subLayout, topLeftX, topY, unlitColor, null);
                } else {
                    float relWiperX = localX - localStart;
                    float bandStartRel;
                    float bandEndRel;
                    if (localX < halfGradientWidth) {
                        float growProgress = Math.max(0f, Math.min(1f, localX / halfGradientWidth));
                        float growWidth = Math.max(0.1f, gradientWidth * growProgress);
                        bandStartRel = -localStart;
                        bandEndRel = growWidth - localStart;
                    } else {
                        bandStartRel = relWiperX - halfGradientWidth;
                        bandEndRel = relWiperX + halfGradientWidth;
                    }
                    if (Math.abs(bandEndRel - bandStartRel) < 0.01f) {
                        bandEndRel = bandStartRel + 0.01f;
                    }
                    LinearGradient shader = new LinearGradient(
                            bandStartRel, 0f, bandEndRel, 0f,
                            new int[]{litColor, litColor, unlitColor, unlitColor},
                            GRADIENT_STOPS,
                            Shader.TileMode.CLAMP);
                    drawLayoutWithPaint(canvas, cs.get(i).subLayout, topLeftX, topY, Color.WHITE, shader);
                }
            }
        }
    }
}