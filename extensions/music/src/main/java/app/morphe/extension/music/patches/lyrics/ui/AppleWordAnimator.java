/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.os.SystemClock;
import android.text.Layout;
import android.text.TextPaint;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.ui.LyricsGlyphMaskCache.ClusterMaskCache;
import app.morphe.extension.music.patches.lyrics.ui.LyricsGlyphMaskCache.GlowShadowTable;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.ActiveSylInfo;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.AlignedSyllable;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.CharPositionMaps;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.ClusterMeta;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.ClusterStatics;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.ColorAlphaCache;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.ReconstructedWord;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.RomanStatics;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.RomanizedWordCluster;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.SyllableBounds;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.TintFilterCache;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.VisualCluster;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.WipeResult;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.WordTimestamp;

public final class AppleWordAnimator {

    public static final int EMPHASIS_SAMPLE_FPS = 60;
    public static final int EMPHASIS_TABLE_FRAMES = 61;
    private static final float EXPRESSIVE_GLOW_BLUR_FACTOR = 0.6f;

    private static final Interpolator FAST_OUT_SLOW_IN = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    private static final Interpolator LINEAR_OUT_SLOW_IN = new PathInterpolator(0f, 0f, 0.2f, 1f);

    private static final String EMPHASIS_TRAILING_PUNCT = "!?.,;:~…‥。、！？";

    public static final class EmphasisSample {
        public float lift = 0f;
        public float pulse = 0f;
        public float glow = 0f;
    }

    public static float emphasisSpringProgress(float tSec, float responseSec) {
        if (tSec <= 0f) return 0f;
        float i = (2f * (float) Math.PI * tSec) / Math.max(0.001f, responseSec);
        return 1f - (1f + i) * (float) Math.exp(-i);
    }

    public static float emphasisResponseSec(long wordStartMs, long wordEndMs) {
        return Math.min(3f, Math.max(0.001f, (wordEndMs - wordStartMs) / 1000f));
    }

    public static float emphasisSpanMs(long wordStartMs, long wordEndMs, float holdMs) {
        return Math.max(1f, holdMs + emphasisResponseSec(wordStartMs, wordEndMs) * 2000f);
    }

    public static float emphasisGlowOpacity(float fraction) {
        if (fraction <= 0f || fraction >= 1f) return 0f;
        if (fraction < 0.15f) {
            return 0.8f * (fraction / 0.15f);
        } else if (fraction < 0.30f) {
            return 0.8f + 0.2f * ((fraction - 0.15f) / 0.15f);
        } else if (fraction < 0.60f) {
            return 1f - 0.7f * ((fraction - 0.30f) / 0.30f);
        } else {
            return 0.3f * ((1f - fraction) / 0.40f);
        }
    }

    public static void emphasisFrame(
            long wordStartMs,
            long wordEndMs,
            float holdMs,
            int frame,
            @NonNull EmphasisSample out) {
        if (frame >= 60) {
            out.lift = 1f;
            out.pulse = 0f;
            return;
        }
        float response = emphasisResponseSec(wordStartMs, wordEndMs);
        float hold = holdMs / 1000f;
        float spanSec = emphasisSpanMs(wordStartMs, wordEndMs, holdMs) / 1000f;
        float t = spanSec * (frame / 60f);
        float sp = emphasisSpringProgress(t, response);
        out.lift = sp;
        out.pulse = sp * (1f - emphasisSpringProgress(t - hold, response));
    }

    @NonNull
    public static float[] buildEmphasisTable(@NonNull VisualCluster vc) {
        EmphasisSample tmp = new EmphasisSample();
        float[] table = new float[EMPHASIS_TABLE_FRAMES * 2];
        for (int f = 0; f < EMPHASIS_TABLE_FRAMES; f++) {
            emphasisFrame(vc.wordStartMs, vc.wordEndMs, vc.charTranslateYPeak, f, tmp);
            table[f * 2] = tmp.lift;
            table[f * 2 + 1] = tmp.pulse;
        }
        return table;
    }

    @NonNull
    public static float[] buildEmphasisTable(long wordDurationMs, long holdMs) {
        EmphasisSample tmp = new EmphasisSample();
        float[] table = new float[EMPHASIS_TABLE_FRAMES * 2];
        for (int f = 0; f < EMPHASIS_TABLE_FRAMES; f++) {
            emphasisFrame(0L, wordDurationMs, (float) holdMs, f, tmp);
            table[f * 2] = tmp.lift;
            table[f * 2 + 1] = tmp.pulse;
        }
        return table;
    }

    public static void sampleEmphasisCached(
            @NonNull float[] table,
            float fraction,
            @NonNull EmphasisSample out) {
        out.glow = emphasisGlowOpacity(fraction);
        if (fraction <= 0f) {
            out.lift = 0f;
            out.pulse = 0f;
            return;
        }
        if (fraction >= 1f) {
            out.lift = 1f;
            out.pulse = 0f;
            return;
        }
        float pos = fraction * 60f;
        int i0 = Math.max(0, Math.min(59, (int) pos));
        float f = pos - i0;
        int a = i0 * 2;
        int b = a + 2;
        out.lift = table[a] + (table[b] - table[a]) * f;
        out.pulse = table[a + 1] + (table[b + 1] - table[a + 1]) * f;
    }

    public static void sampleEmphasisCached(
            @NonNull float[] table,
            float tableDurationMs,
            float timeMs,
            @NonNull float[] out) {
        if (timeMs <= 0f || tableDurationMs <= 0f) {
            out[0] = 0f;
            out[1] = 0f;
            return;
        }
        if (timeMs >= tableDurationMs) {
            out[0] = 1f;
            out[1] = 0f;
            return;
        }
        EmphasisSample sample = new EmphasisSample();
        sampleEmphasisCached(table, timeMs / tableDurationMs, sample);
        out[0] = sample.lift;
        out[1] = sample.pulse;
    }

    public static boolean isEmphasisAttached(char ch) {
        if (EMPHASIS_TRAILING_PUNCT.indexOf(ch) >= 0) return true;
        int code = (int) ch;
        return (code >= 0x0300 && code <= 0x036F)
                || (code >= 0x3099 && code <= 0x309A)
                || code == 0x200D
                || code == 0xFE0F
                || code == 0xFE0E;
    }

    public final LyricsAnimationStyle style;
    public final String mainText;
    public final String formattedMainText;
    public final List<WordTimestamp> shapedWords;
    @Nullable
    public final List<WordTimestamp> shapedRomanizedWords;
    public final boolean isBackground;
    public final boolean showSyllableRomanization;
    public final boolean hasSyllableRomanization;
    public final float fontSizePx;
    public final float gradientWidth;
    public final float elevationAmount;
    public final float extraPaddingPx;
    public final float emphasisLiftEm = 0.05f;
    public final float emphasisRiseEm = 0.035f;
    public final float emphasisShiftEm = 0.05f;
    public final float emphasisScaleGain = 0.1f;
    public final float lineExtraHeight;
    public final float glowBlurRadius;
    public final float shadowBlurRadius;
    public final Layout.Alignment alignment;

    public final Layout textLayoutResult;
    public final int lineCount;
    public final float[] lineLefts;
    public final float[] lineRights;
    public final float[] lineStarts;
    public final float[] lineWidths;
    public final float totalWidth;
    public final boolean[] lineIsRtl;
    public final float[] lineBaselines;
    public final float[] lineBottoms;

    public final List<AlignedSyllable> alignedSyllables;
    public final List<RomanizedWordCluster> romanClusters;
    public final List<ReconstructedWord> reconstructedWords;
    public final float[] charXMap;
    public final float[] romanStartShift;
    public final SyllableBounds sylBounds;
    public final List<VisualCluster> clusters;
    public final ClusterStatics statics;
    public final List<RomanizedWordCluster>[] romanLines;
    public final RomanStatics romanStatics;

    public final long lineStartTime;
    public final long lineEndTime;
    public final long effectiveEndTimeMs;
    public final float lineEndX;
    public final int lastSylIdx;
    public final long lineStartMs;
    public final int measuredWidth;
    public final int measuredHeight;

    public final boolean isArabicOrRtl;
    public final boolean[] clusterMotionOff;
    public final float[][] emphasisTables;
    public final float[] clusterSpanMs;

    public final int[] sylGroupPos;
    public final int[] sylGroupSize;
    public final boolean[] sylGroupIsLast;
    public final long[] sylGroupEndMs;
    public final boolean[] sylIsNextOfDash;
    public final int[] sylTextLen;
    public final int[] clusterGroupPos;
    public final int[] clusterGroupSize;
    public final float[] clusterGroupSizeF;
    public final float[] clusterGroupFrac;
    public final float[] clusterWordDurMs;
    public final boolean[] clusterInWordGroup;
    public final boolean[] clusterIsNextOfDash;
    public final int[] clusterSylGroupPos;
    public final float[] clusterBaseImpact;

    private final float[] clusterScaleX;
    private final float[] clusterScaleY;
    private final float[] clusterGlowAlphas;
    private final boolean[] clusterIsGlowing;
    private final float[] clusterDrawX;
    private final float[] clusterDrawY;
    private final float[] clusterLastDrawY;
    private final boolean[] clusterIsYMoving;
    private final float[] clusterFadeProgress;
    private final float[] sylSungFactorValues;
    private final float[] lineTotalPushScratch;
    private final float[] lineCurrentPushScratch;

    private final boolean[] sylElevTriggered;
    private final float[] sylElevAnimFrom;
    private final float[] sylElevAnimTarget;
    private final long[] sylElevAnimStartMs;
    private final float[] sylElevCurrent;

    private final boolean[] heavyRelTriggered;
    private final int[] heavyRelMode;
    private final float[] heavyRelAnimFrom;
    private final float[] heavyRelAnimTarget;
    private final float[] heavyRelAnimDurationMs;
    private final float[] heavyRelAnimDelayMs;
    private final long[] heavyRelAnimStartMs;
    private final float[] clusterGrowValues;
    private final float[] clusterReleaseValues;

    private boolean wasActiveLine = false;
    private boolean isFirstRun = true;
    private long deactivatedPosMs = 0L;
    private long deactivatedUptimeMs = -1L;

    private float lastActiveGX = 0f;
    private long lastActivePosMs = Long.MIN_VALUE;
    private long finishStartUptimeMs = -1L;
    private float finishStartGX = 0f;

    private float curColorState = 0f;
    private float curColorVel = 0f;
    private float curSimpleState = 0f;
    private float curSimpleVel = 0f;
    private long lastDrawUptimeMs = -1L;
    private long lastRawUptimeMs = -1L;
    private boolean lastWasPlaying = false;
    private long animClockMs = 10_000L;
    private boolean prewarmMasks = false;
    private boolean stillAnimating = false;

    private final EmphasisSample sampleScratch = new EmphasisSample();
    private final WipeResult wipeScratch = new WipeResult();
    private final ColorAlphaCache litColorCache = new ColorAlphaCache();
    private final ColorAlphaCache unlitColorCache = new ColorAlphaCache();
    private final ColorAlphaCache transparentAccentColorCache = new ColorAlphaCache();
    private final TintFilterCache fadeTintCache = new TintFilterCache();
    public final boolean glowPossible;
    public final GlowShadowTable glowShadowTable;
    public final ClusterMaskCache maskCacheStore;

    public AppleWordAnimator(
            @NonNull LyricsAnimationStyle style,
            @NonNull String mainText,
            @NonNull List<WordTimestamp> rawWords,
            @Nullable List<WordTimestamp> rawRomanizedWords,
            @Nullable String romanizedText,
            boolean showSyllableRomanization,
            boolean isBackground,
            @Nullable Long adjustedEndTimeMs,
            @NonNull TextPaint basePaint,
            @NonNull Layout.Alignment alignment,
            int widthPx,
            int expressiveAccent,
            boolean prewarmMasks) {
        this.style = style;
        this.mainText = mainText;
        this.isBackground = isBackground;
        this.alignment = alignment;
        this.fontSizePx = basePaint.getTextSize();

        if (style == LyricsAnimationStyle.SIMPLE) {
            this.gradientWidth = fontSizePx * 1.25f;
            this.extraPaddingPx = fontSizePx * 0.3f;
        } else if (style == LyricsAnimationStyle.EXPRESSIVE) {
            this.gradientWidth = fontSizePx * 0.5f;
            this.extraPaddingPx = fontSizePx * 0.4f;
        } else {
            this.gradientWidth = fontSizePx * 1.0f;
            this.extraPaddingPx = fontSizePx * 0.4f;
        }
        this.elevationAmount = fontSizePx * 0.05f;
        this.glowBlurRadius = fontSizePx * 0.1f;
        this.shadowBlurRadius = fontSizePx * 0.15f;

        List<int[]> ranges = WordAnimationUtils.shapingWordRanges(rawWords);
        this.shapedWords = WordAnimationUtils.mergeShapingWords(rawWords, ranges);
        if (rawRomanizedWords != null) {
            this.shapedRomanizedWords = (rawRomanizedWords.size() == rawWords.size())
                    ? WordAnimationUtils.mergeShapingWords(rawRomanizedWords, ranges)
                    : rawRomanizedWords;
        } else {
            this.shapedRomanizedWords = null;
        }
        this.showSyllableRomanization = showSyllableRomanization;

        this.formattedMainText = WordAnimationUtils.rememberFormattedMainText(
                mainText, shapedWords, shapedRomanizedWords, romanizedText,
                showSyllableRomanization, basePaint, widthPx);

        boolean isMainRtl = WordAnimationUtils.isRtlText(formattedMainText);
        this.textLayoutResult = WordAnimationUtils.createStaticLayout(
                formattedMainText, basePaint, widthPx, alignment, isMainRtl);

        this.lineCount = textLayoutResult.getLineCount();
        this.lineLefts = new float[lineCount];
        this.lineRights = new float[lineCount];
        this.lineStarts = new float[lineCount];
        this.lineWidths = new float[lineCount];
        this.lineIsRtl = new boolean[lineCount];
        this.lineBaselines = new float[lineCount];
        this.lineBottoms = new float[lineCount];

        float cum = 0f;
        for (int i = 0; i < lineCount; i++) {
            lineLefts[i] = textLayoutResult.getLineLeft(i);
            lineRights[i] = textLayoutResult.getLineRight(i);
            float lw = Math.max(1f, lineRights[i] - lineLefts[i]);
            lineStarts[i] = cum;
            lineWidths[i] = lw;
            cum += lw;
            lineIsRtl[i] = textLayoutResult.getParagraphDirection(i) == Layout.DIR_RIGHT_TO_LEFT;
            lineBaselines[i] = textLayoutResult.getLineBaseline(i);
            lineBottoms[i] = textLayoutResult.getLineBottom(i);
        }
        this.totalWidth = cum;

        this.alignedSyllables = WordAnimationUtils.alignSyllablesForFormatting(shapedWords, formattedMainText);
        this.romanClusters = WordAnimationUtils.rememberRomanClusters(
                showSyllableRomanization, alignedSyllables, shapedRomanizedWords, romanizedText,
                formattedMainText, textLayoutResult, basePaint, expressiveAccent);

        this.hasSyllableRomanization = showSyllableRomanization
                && !romanClusters.isEmpty();
        this.lineExtraHeight = hasSyllableRomanization ? (fontSizePx * 1.05f) : 0f;

        this.reconstructedWords = WordAnimationUtils.buildReconstructedWords(alignedSyllables, formattedMainText);

        CharPositionMaps posMaps = WordAnimationUtils.rememberCharXMap(
                formattedMainText, textLayoutResult, lineCount, lineIsRtl, lineStarts,
                alignedSyllables, romanClusters, alignment, fontSizePx);
        this.charXMap = posMaps.charXMap;
        this.romanStartShift = posMaps.romanStartShift;
        this.sylBounds = WordAnimationUtils.buildSyllableBounds(
                alignedSyllables, charXMap, textLayoutResult, formattedMainText);

        int sylCount = alignedSyllables.size();
        this.sylGroupPos = new int[sylCount];
        this.sylGroupSize = new int[sylCount];
        Arrays.fill(this.sylGroupSize, 1);
        this.sylGroupIsLast = new boolean[sylCount];
        this.sylGroupEndMs = new long[sylCount];
        int sylBase = 0;
        for (int w = 0; w < reconstructedWords.size(); w++) {
            ReconstructedWord word = reconstructedWords.get(w);
            int sz = word.syllables.size();
            if (sz > 1) {
                for (int pos = 0; pos < sz; pos++) {
                    int gi = sylBase + pos;
                    if (gi < sylCount) {
                        sylGroupPos[gi] = pos;
                        sylGroupSize[gi] = sz;
                        sylGroupIsLast[gi] = (pos == sz - 1);
                        sylGroupEndMs[gi] = word.wordEndTimeMs;
                    }
                }
            }
            sylBase += sz;
        }

        this.sylIsNextOfDash = new boolean[sylCount];
        this.sylTextLen = new int[sylCount];
        for (int i = 0; i < sylCount; i++) {
            AlignedSyllable cur = alignedSyllables.get(i);
            sylTextLen[i] = Math.max(1, cur.syllable.text.length());
            if (i > 0) {
                AlignedSyllable prev = alignedSyllables.get(i - 1);
                boolean prevHasDash = false;
                for (int c = 0; c < prev.syllable.text.length(); c++) {
                    if (WordAnimationUtils.isDash(prev.syllable.text.charAt(c))) {
                        prevHasDash = true;
                        break;
                    }
                }
                boolean gapHasDash = false;
                if (cur.startOffset > prev.endOffset) {
                    int gs = Math.max(0, Math.min(formattedMainText.length(), prev.endOffset));
                    int ge = Math.max(gs, Math.min(formattedMainText.length(), cur.startOffset));
                    for (int c = gs; c < ge; c++) {
                        if (WordAnimationUtils.isDash(formattedMainText.charAt(c))) {
                            gapHasDash = true;
                            break;
                        }
                    }
                }
                sylIsNextOfDash[i] = prevHasDash || gapHasDash;
            }
        }

        boolean hasJoining = WordAnimationUtils.containsJoiningScript(formattedMainText);
        this.clusters = buildClustersForStyle(style, basePaint, expressiveAccent, hasJoining);

        boolean applePivot = (style == LyricsAnimationStyle.APPLE);
        this.statics = WordAnimationUtils.buildClusterStatics(
                clusters, romanStartShift, lineExtraHeight, lineIsRtl,
                lineLefts, lineRights, lineStarts, lineWidths, fontSizePx, applePivot);

        this.lineStartTime = shapedWords.isEmpty() ? 0L : (long) (shapedWords.get(0).startTime * 1000.0);
        this.lineEndTime = shapedWords.isEmpty() ? 0L : (long) (shapedWords.get(shapedWords.size() - 1).endTime * 1000.0);
        this.effectiveEndTimeMs = (adjustedEndTimeMs != null && adjustedEndTimeMs != Long.MAX_VALUE)
                ? adjustedEndTimeMs
                : lineEndTime;

        this.lineEndX = alignedSyllables.isEmpty()
                ? 0f
                : globalX(alignedSyllables.get(alignedSyllables.size() - 1).endOffset);
        this.lastSylIdx = alignedSyllables.size() - 1;
        this.lineStartMs = alignedSyllables.isEmpty()
                ? Long.MAX_VALUE
                : (long) (alignedSyllables.get(0).syllable.startTime * 1000.0);

        int n = clusters.size();
        this.isArabicOrRtl = WordAnimationUtils.isRtlText(mainText)
                || WordAnimationUtils.containsJoiningScript(mainText);
        this.clusterMotionOff = new boolean[n];
        for (int i = 0; i < n; i++) {
            VisualCluster vc = clusters.get(i);
            clusterMotionOff[i] = isArabicOrRtl || vc.isRtl || vc.isJoiningScript;
        }

        this.emphasisTables = new float[n][];
        this.clusterSpanMs = new float[n];
        for (int i = 0; i < n; i++) {
            VisualCluster vc = clusters.get(i);
            if (style == LyricsAnimationStyle.APPLE && vc.isHeavy) {
                emphasisTables[i] = buildEmphasisTable(vc);
                clusterSpanMs[i] = emphasisSpanMs(vc.wordStartMs, vc.wordEndMs, vc.charTranslateYPeak);
            } else {
                emphasisTables[i] = null;
                clusterSpanMs[i] = 1f;
            }
        }

        this.clusterGroupPos = new int[n];
        this.clusterGroupSize = new int[n];
        Arrays.fill(this.clusterGroupSize, 1);
        int ci = 0;
        while (ci < n) {
            VisualCluster vc = clusters.get(ci);
            if (!vc.isHeavy) {
                ci++;
                continue;
            }
            int cj = ci;
            while (cj + 1 < n
                    && clusters.get(cj + 1).isHeavy
                    && clusters.get(cj + 1).wordStartMs == vc.wordStartMs
                    && clusters.get(cj + 1).wordEndMs == vc.wordEndMs) {
                cj++;
            }
            int gSize = cj - ci + 1;
            for (int k = ci; k <= cj; k++) {
                clusterGroupPos[k] = k - ci;
                clusterGroupSize[k] = gSize;
            }
            ci = cj + 1;
        }

        this.clusterGroupSizeF = new float[n];
        this.clusterGroupFrac = new float[n];
        this.clusterWordDurMs = new float[n];
        this.clusterInWordGroup = new boolean[n];
        this.clusterIsNextOfDash = new boolean[n];
        this.clusterSylGroupPos = new int[n];
        this.clusterBaseImpact = new float[n];

        for (int i = 0; i < n; i++) {
            VisualCluster vc = clusters.get(i);
            clusterGroupSizeF[i] = Math.max(1, clusterGroupSize[i]);
            clusterGroupFrac[i] = clusterGroupPos[i] / clusterGroupSizeF[i];
            clusterWordDurMs[i] = Math.max(100L, vc.wordEndMs - vc.wordStartMs);
            int si = vc.syllableIndex;
            clusterInWordGroup[i] = (si != -1 && si < sylGroupSize.length && sylGroupSize[si] > 1);
            clusterIsNextOfDash[i] = (si != -1 && si < sylIsNextOfDash.length && sylIsNextOfDash[si]);
            clusterSylGroupPos[i] = (si != -1 && si < sylGroupPos.length) ? sylGroupPos[si] : 0;

            float wDurMs = (float) (vc.wordEndMs - vc.wordStartMs);
            int len = (si != -1 && si < sylTextLen.length) ? sylTextLen[si] : Math.max(1, vc.end - vc.start);
            float impactRatio = wDurMs / len;
            float p1 = Math.max(0f, Math.min(1f, (impactRatio - 100f) / 250f)) * 0.6f;
            float p2 = Math.max(0f, Math.min(1f, (wDurMs - 300f) / 1500f)) * 0.4f;
            clusterBaseImpact[i] = Math.max(0f, Math.min(1f, p1 + p2));
        }

        this.clusterScaleX = new float[n];
        this.clusterScaleY = new float[n];
        Arrays.fill(this.clusterScaleX, 1f);
        Arrays.fill(this.clusterScaleY, 1f);
        this.clusterGlowAlphas = new float[n];
        this.clusterIsGlowing = new boolean[n];
        this.clusterDrawX = new float[n];
        this.clusterDrawY = new float[n];
        this.clusterLastDrawY = new float[n];
        Arrays.fill(this.clusterLastDrawY, Float.NaN);
        this.clusterIsYMoving = new boolean[n];
        this.clusterFadeProgress = new float[n];
        this.sylSungFactorValues = new float[sylCount];
        this.lineTotalPushScratch = new float[lineCount];
        this.lineCurrentPushScratch = new float[lineCount];

        this.sylElevTriggered = new boolean[sylCount];
        this.sylElevAnimFrom = new float[sylCount];
        this.sylElevAnimTarget = new float[sylCount];
        this.sylElevAnimStartMs = new long[sylCount];
        Arrays.fill(this.sylElevAnimStartMs, -1L);
        this.sylElevCurrent = new float[sylCount];

        this.heavyRelTriggered = new boolean[n];
        this.heavyRelMode = new int[n];
        this.heavyRelAnimFrom = new float[n];
        this.heavyRelAnimTarget = new float[n];
        this.heavyRelAnimDurationMs = new float[n];
        this.heavyRelAnimDelayMs = new float[n];
        this.heavyRelAnimStartMs = new long[n];
        Arrays.fill(this.heavyRelAnimStartMs, -1L);
        this.clusterGrowValues = new float[n];
        this.clusterReleaseValues = new float[n];

        boolean anyHeavy = false;
        for (int i = 0; i < n; i++) {
            if (clusters.get(i).isHeavy) {
                anyHeavy = true;
                break;
            }
        }
        this.glowPossible = anyHeavy;
        this.glowShadowTable = new GlowShadowTable(glowBlurRadius);

        this.prewarmMasks = prewarmMasks;
        this.maskCacheStore = new ClusterMaskCache(n, extraPaddingPx);

        this.romanLines = WordAnimationUtils.groupRomanClustersByLine(romanClusters, lineCount);
        boolean isRight = (alignment == Layout.Alignment.ALIGN_OPPOSITE);
        if (WordAnimationUtils.isRtlText(formattedMainText)) {
            isRight = (alignment == Layout.Alignment.ALIGN_NORMAL);
        }
        this.romanStatics = WordAnimationUtils.buildRomanStatics(
                romanLines, charXMap, romanStartShift, lineBaselines, lineBottoms,
                lineLefts, lineStarts, lineCount, fontSizePx, lineExtraHeight,
                totalWidth, extraPaddingPx, lineRights, lineIsRtl, isRight);

        int totalExtraPx = (int) (lineCount * lineExtraHeight);
        int bottomMargin = hasSyllableRomanization ? (int) (fontSizePx * 0.25f) : 0;
        this.measuredWidth = widthPx;
        this.measuredHeight = textLayoutResult.getHeight() + totalExtraPx + bottomMargin;
    }

    private float globalX(int offset) {
        if (offset < 0 || charXMap.length == 0) return 0f;
        if (offset >= charXMap.length) return totalWidth;
        return charXMap[Math.max(0, Math.min(charXMap.length - 1, offset))];
    }

    public boolean isStillAnimating() {
        return stillAnimating;
    }

    public void setPrewarmMasks(boolean prewarmMasks) {
        this.prewarmMasks = prewarmMasks;
    }

    private void assignSharedElev(@NonNull List<ClusterMeta> metaList, @NonNull List<AlignedSyllable> syls) {
        for (int s = 0; s < syls.size(); s++) {
            AlignedSyllable syl = syls.get(s);
            float sx = globalX(syl.startOffset);
            float ex = globalX(syl.endOffset);
            float center = sx + (ex - sx) / 2f;
            List<ClusterMeta> range = metasInRange(metaList, syl.startOffset, syl.endOffset);
            for (int m = 0; m < range.size(); m++) {
                range.get(m).sharedElevationCenter = center;
            }
        }
    }

    @NonNull
    private List<VisualCluster> buildClustersForStyle(
            @NonNull LyricsAnimationStyle style,
            @NonNull TextPaint basePaint,
            int expressiveAccent,
            boolean hasJoining) {
        List<ClusterMeta> metaList = new ArrayList<>();
        int i = 0;
        int textLen = formattedMainText.length();
        while (i < textLen) {
            int start = i;
            int end = (i + 1 < textLen
                    && Character.isHighSurrogate(formattedMainText.charAt(i))
                    && Character.isLowSurrogate(formattedMainText.charAt(i + 1)))
                    ? (i + 2) : (i + 1);
            i = end;

            if (formattedMainText.charAt(start) == '\n') continue;

            int safeStart = Math.max(0, Math.min(Math.max(0, textLen - 1), start));
            int safeEnd = Math.min(end, Math.max(0, textLen - 1));
            int li = textLayoutResult.getLineForOffset(safeStart);
            int eli = textLayoutResult.getLineForOffset(safeEnd);
            float hStart = textLayoutResult.getPrimaryHorizontal(safeStart);
            float hEnd = (li == eli)
                    ? textLayoutResult.getPrimaryHorizontal(Math.min(end, textLen))
                    : textLayoutResult.getLineRight(li);
            float x = Math.min(hStart, hEnd);
            float w = Math.abs(hEnd - hStart);
            float y = textLayoutResult.getLineTop(li);

            ClusterMeta cm = new ClusterMeta(start, end, li, x, y, w);
            cm.isRtl = lineIsRtl[li];
            metaList.add(cm);
        }

        if (style == LyricsAnimationStyle.APPLE) {
            for (int wIdx = 0; wIdx < reconstructedWords.size(); wIdx++) {
                ReconstructedWord word = reconstructedWords.get(wIdx);
                if (word.wordLen <= 0 || word.syllables.isEmpty()) continue;

                String firstSylText = word.syllables.get(0).syllable.text;
                boolean isJoining = WordAnimationUtils.isJoiningScript(firstSylText);

                StringBuilder wordTextSb = new StringBuilder();
                for (int s = 0; s < word.syllables.size(); s++) {
                    wordTextSb.append(word.syllables.get(s).syllable.text);
                }
                String wordText = wordTextSb.toString();

                List<List<ClusterMeta>> charGroups = new ArrayList<>();
                if (!isBackground && !WordAnimationUtils.isRtlText(wordText)) {
                    List<ClusterMeta> wordMetas = metasInRange(metaList, word.startIndex, word.endIndex);
                    for (int m = 0; m < wordMetas.size(); m++) {
                        ClusterMeta cm = wordMetas.get(m);
                        char ch = formattedMainText.charAt(cm.start);
                        if (Character.isWhitespace(ch)) continue;
                        if (!charGroups.isEmpty() && isEmphasisAttached(ch)) {
                            charGroups.get(charGroups.size() - 1).add(cm);
                        } else {
                            List<ClusterMeta> grp = new ArrayList<>();
                            grp.add(cm);
                            charGroups.add(grp);
                        }
                    }
                }

                int numChars = charGroups.size();
                float wordDurationMs = (float) (word.wordEndTimeMs - word.wordStartTimeMs);
                long gateDurationMs = 0L;
                for (int s = 0; s < word.syllables.size(); s++) {
                    AlignedSyllable syl = word.syllables.get(s);
                    gateDurationMs += (long) ((syl.syllable.endTime - syl.syllable.startTime) * 1000.0);
                }

                if (numChars >= 1 && numChars <= 7 && gateDurationMs >= 1000L) {
                    double e = Math.max(0.001, (double) wordDurationMs / 1000.0);
                    double delaySec = Math.min((e / numChars) * 0.4, 0.4);
                    float holdMs = (float) (2.0 * e / numChars * 1000.0);
                    float amplitude = (float) Math.max(0.0, Math.min(1.0, e - 1.0));
                    float glowMax = (float) (0.45 * Math.max(0.0, Math.min(1.0, (e - 1.0) / 0.5)));
                    float half = Math.max(1f, (numChars - 1) / 2f);

                    for (int charIdx = 0; charIdx < charGroups.size(); charIdx++) {
                        List<ClusterMeta> group = charGroups.get(charIdx);
                        long delayMs = (long) ((charIdx + 1) * delaySec * 1000.0);
                        float normX = (charIdx - (numChars - 1) / 2f) / half;
                        for (int gm = 0; gm < group.size(); gm++) {
                            ClusterMeta cm = group.get(gm);
                            cm.isHeavy = true;
                            cm.isJoiningScript = isJoining;
                            cm.wordStartMs = word.wordStartTimeMs;
                            cm.wordEndMs = word.wordEndTimeMs;
                            cm.intensity = amplitude;
                            cm.charShadowIntensity = glowMax;
                            cm.charDelayMs = delayMs;
                            cm.charTranslateYPeak = holdMs;
                            cm.normX = normX;
                        }
                    }
                } else {
                    assignSharedElev(metaList, word.syllables);
                }
            }
        } else if (style == LyricsAnimationStyle.EXPRESSIVE) {
            for (int wIdx = 0; wIdx < reconstructedWords.size(); wIdx++) {
                ReconstructedWord word = reconstructedWords.get(wIdx);
                for (int s = 0; s < word.syllables.size(); s++) {
                    AlignedSyllable syl = word.syllables.get(s);
                    String sylText = syl.syllable.text;
                    int sylLen = sylText.length();
                    if (sylLen <= 0) continue;

                    boolean isCjk = WordAnimationUtils.isCJK(sylText);
                    boolean isJoining = WordAnimationUtils.isJoiningScript(sylText);

                    long sylStartMs = (long) (syl.syllable.startTime * 1000.0);
                    long sylEndMs = (long) (syl.syllable.endTime * 1000.0);

                    float minMs = isCjk ? 400f : 200f;
                    float msRange = isCjk ? 400f : 300f;

                    float msPerLetter = (float) (sylEndMs - sylStartMs);
                    float ssx = globalX(syl.startOffset);
                    float sex = globalX(syl.endOffset);
                    float sw = sex - ssx;
                    float raw = (msPerLetter - minMs) / msRange;
                    float intensity = 0.3f + 0.7f * Math.max(0f, Math.min(1f, raw));

                    boolean isLongSyl = sylLen > 5;
                    boolean isShortDuration = msPerLetter < 1500f;
                    float maxDecayRate = 0f;
                    if (isLongSyl || isShortDuration) {
                        float decayStrength = 0f;
                        if (isLongSyl) {
                            decayStrength += Math.max(0f, Math.min(1f, (sylLen - 5f) / 3f)) * 0.4f;
                        }
                        if (isShortDuration) {
                            decayStrength += Math.max(0f, 1f - ((msPerLetter - 1000f) / 500f)) * 0.4f;
                        }
                        maxDecayRate = Math.max(0f, Math.min(0.85f, decayStrength));
                    }

                    List<ClusterMeta> sylMetas = metasInRange(metaList, syl.startOffset, syl.endOffset);
                    int numChars = sylMetas.size();
                    float baseDelayPerChar = (sylEndMs - sylStartMs) * 0.09f;

                    for (int charIdx = 0; charIdx < numChars; charIdx++) {
                        ClusterMeta cm = sylMetas.get(charIdx);
                        cm.isHeavy = true;
                        cm.isJoiningScript = isJoining;
                        cm.wordStartMs = sylStartMs;
                        cm.wordEndMs = sylEndMs;
                        cm.intensity = intensity;
                        float cc = globalX(cm.start) + (globalX(cm.end) - globalX(cm.start)) / 2f;
                        cm.normX = (sw > 0f) ? (((cc - ssx) / sw) * 2f - 1f) : 0f;
                        cm.charDelayMs = (long) (baseDelayPerChar * charIdx);

                        float positionInWord = (numChars > 1) ? ((float) charIdx / (float) (numChars - 1)) : 0f;
                        float decayFactor = 1.0f - positionInWord * maxDecayRate;
                        float charProgress = intensity * decayFactor;

                        float baseGrowth = (numChars <= 3) ? 0.04f : 0.03f;
                        cm.charMaxScale = 1.0f + baseGrowth + charProgress * 0.04f;
                        cm.charShadowIntensity = 0.15f + charProgress * 0.2f;

                        float normalizedGrowth = (cm.charMaxScale - 1.0f) / 0.08f;
                        cm.charTranslateYPeak = -normalizedGrowth * 6f;
                    }
                }
            }
        }

        int[] sylPointer = new int[]{0};
        List<VisualCluster> list = new ArrayList<>();
        int idx = 0;
        while (idx < metaList.size()) {
            ClusterMeta m = metaList.get(idx);
            if (style != LyricsAnimationStyle.SIMPLE && m.isHeavy && !m.isJoiningScript) {
                String runText = formattedMainText.substring(
                        Math.max(0, Math.min(textLen, m.start)),
                        Math.max(0, Math.min(textLen, m.end)));
                Layout subLayout = WordAnimationUtils.measureRun(runText, basePaint, expressiveAccent, hasJoining);
                float clusterY = lineBaselines[m.lineIdx] - subLayout.getLineBaseline(0);
                list.add(new VisualCluster(
                        m.start, m.end, m.lineIdx, m.x, clusterY, m.width,
                        true, m.disableScale, false, m.isRtl,
                        m.wordStartMs, m.wordEndMs, m.intensity, m.normX,
                        m.sharedElevationCenter, subLayout,
                        m.charMaxScale, m.charShadowIntensity, m.charTranslateYPeak, m.charDelayMs,
                        sylIndexFor(m, sylPointer)
                ));
                idx++;
            } else if (style != LyricsAnimationStyle.SIMPLE && m.isHeavy && m.isJoiningScript) {
                int j = idx;
                while (j + 1 < metaList.size()
                        && metaList.get(j + 1).isHeavy
                        && metaList.get(j + 1).isJoiningScript
                        && metaList.get(j + 1).lineIdx == m.lineIdx
                        && metaList.get(j + 1).wordStartMs == m.wordStartMs
                        && metaList.get(j + 1).wordEndMs == m.wordEndMs) {
                    j++;
                }
                List<ClusterMeta> runMetas = metaList.subList(idx, j + 1);
                float runLeft = Float.MAX_VALUE;
                float runRight = -Float.MAX_VALUE;
                for (int r = 0; r < runMetas.size(); r++) {
                    ClusterMeta rm = runMetas.get(r);
                    if (rm.x < runLeft) runLeft = rm.x;
                    if (rm.x + rm.width > runRight) runRight = rm.x + rm.width;
                }
                ClusterMeta last = metaList.get(j);
                String runText = formattedMainText.substring(
                        Math.max(0, Math.min(textLen, m.start)),
                        Math.max(0, Math.min(textLen, last.end)));
                Layout subLayout = WordAnimationUtils.measureRun(runText, basePaint, expressiveAccent, hasJoining);
                float clusterY = lineBaselines[m.lineIdx] - subLayout.getLineBaseline(0);
                list.add(new VisualCluster(
                        m.start, last.end, m.lineIdx, runLeft, clusterY, runRight - runLeft,
                        true, m.disableScale, true, m.isRtl,
                        m.wordStartMs, m.wordEndMs, m.intensity, 0f,
                        m.sharedElevationCenter, subLayout,
                        m.charMaxScale, m.charShadowIntensity, m.charTranslateYPeak, 0L,
                        sylIndexFor(m, sylPointer)
                ));
                idx = j + 1;
            } else {
                int runSylIdx = sylIndexFor(m, sylPointer);
                int j = idx;
                while (j + 1 < metaList.size()
                        && (style == LyricsAnimationStyle.SIMPLE || !metaList.get(j + 1).isHeavy)
                        && metaList.get(j + 1).lineIdx == m.lineIdx
                        && sylIndexFor(metaList.get(j + 1), sylPointer) == runSylIdx) {
                    j++;
                }
                List<ClusterMeta> runMetas = metaList.subList(idx, j + 1);
                float runLeft = Float.MAX_VALUE;
                float runRight = -Float.MAX_VALUE;
                for (int r = 0; r < runMetas.size(); r++) {
                    ClusterMeta rm = runMetas.get(r);
                    if (rm.x < runLeft) runLeft = rm.x;
                    if (rm.x + rm.width > runRight) runRight = rm.x + rm.width;
                }
                ClusterMeta last = metaList.get(j);
                String runText = formattedMainText.substring(
                        Math.max(0, Math.min(textLen, m.start)),
                        Math.max(0, Math.min(textLen, last.end)));
                Layout subLayout = WordAnimationUtils.measureRun(runText, basePaint, expressiveAccent, hasJoining);
                float clusterY = lineBaselines[m.lineIdx] - subLayout.getLineBaseline(0);
                list.add(new VisualCluster(
                        m.start, last.end, m.lineIdx, runLeft, clusterY, runRight - runLeft,
                        false, m.disableScale, false, m.isRtl,
                        0L, 0L, 0f, 0f,
                        m.sharedElevationCenter, subLayout,
                        1f, 0f, 0f, 0L,
                        runSylIdx
                ));
                idx = j + 1;
            }
        }
        return list;
    }

    private static int metaLowerBound(@NonNull List<ClusterMeta> metaList, int pos) {
        int lo = 0;
        int hi = metaList.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (metaList.get(mid).end <= pos) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    @NonNull
    private static List<ClusterMeta> metasInRange(@NonNull List<ClusterMeta> metaList, int startIdx, int endIdx) {
        int s = metaLowerBound(metaList, startIdx);
        int e = metaLowerBound(metaList, endIdx);
        if (s > e) s = e;
        return metaList.subList(s, e);
    }

    private int sylIndexFor(@NonNull ClusterMeta m, @NonNull int[] sylPointer) {
        while (sylPointer[0] < alignedSyllables.size()
                && alignedSyllables.get(sylPointer[0]).endOffset <= m.start) {
            sylPointer[0]++;
        }
        if (sylPointer[0] >= alignedSyllables.size()) return -1;
        AlignedSyllable s = alignedSyllables.get(sylPointer[0]);
        return (m.start >= s.startOffset && m.end <= s.endOffset) ? sylPointer[0] : -1;
    }

    public int findActiveSyllableIndex(long positionMs) {
        int lo = 0;
        int hi = alignedSyllables.size() - 1;
        int ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long ss = (long) (alignedSyllables.get(mid).syllable.startTime * 1000.0);
            if (ss <= positionMs) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    @NonNull
    public ActiveSylInfo getActiveSylInfo(int activeSylIdx) {
        if (activeSylIdx < 0 || activeSylIdx >= alignedSyllables.size()) {
            return new ActiveSylInfo(-1, -1, false);
        }
        AlignedSyllable s = alignedSyllables.get(activeSylIdx);
        int maxOff = Math.max(0, formattedMainText.length() - 1);
        int lineIdx = formattedMainText.isEmpty()
                ? 0
                : textLayoutResult.getLineForOffset(Math.max(0, Math.min(maxOff, s.startOffset)));
        boolean lastOfLine;
        if (activeSylIdx == alignedSyllables.size() - 1) {
            lastOfLine = true;
        } else {
            AlignedSyllable nextSyl = alignedSyllables.get(activeSylIdx + 1);
            int nextLineIdx = formattedMainText.isEmpty()
                    ? 0
                    : textLayoutResult.getLineForOffset(Math.max(0, Math.min(maxOff, nextSyl.startOffset)));
            int sOffset = Math.max(0, Math.min(formattedMainText.length(), Math.min(s.startOffset, nextSyl.startOffset)));
            int eOffset = Math.max(sOffset, Math.min(formattedMainText.length(), Math.max(s.endOffset, nextSyl.startOffset)));
            lastOfLine = (nextLineIdx > lineIdx)
                    || formattedMainText.substring(sOffset, eOffset).indexOf('\n') >= 0;
        }
        return new ActiveSylInfo(activeSylIdx, lineIdx, lastOfLine);
    }

    public float getActiveSylProgress(int activeSylIdx, long currentPosition) {
        if (activeSylIdx < 0 || activeSylIdx >= alignedSyllables.size()) return 0f;
        AlignedSyllable activeS = alignedSyllables.get(activeSylIdx);
        long ssMs = (long) (activeS.syllable.startTime * 1000.0);
        long seMs = (long) (activeS.syllable.endTime * 1000.0);
        long dur = Math.max(1L, seMs - ssMs);
        long elapsed = Math.max(0L, Math.min(dur, currentPosition - ssMs));
        return (float) elapsed / (float) dur;
    }

    private float updateGlobalX(
            int activeSylIdx,
            long currentPosition,
            boolean isActiveLine,
            boolean reachedLineEnd,
            long nowUptimeMs) {
        if (isActiveLine) {
            finishStartUptimeMs = -1L;
            // if backward seek more than 500ms, reset sweep x position so it dont get stuck
            boolean backwardSeek = (lastActivePosMs != Long.MIN_VALUE && currentPosition < lastActivePosMs - 500L);
            if (backwardSeek || currentPosition < lineStartMs) {
                lastActiveGX = 0f;
            }
            if (!backwardSeek && lastActivePosMs != Long.MIN_VALUE && currentPosition < lastActivePosMs) {
                currentPosition = lastActivePosMs;
            }
            lastActivePosMs = currentPosition;
            if (activeSylIdx == -1) {
                if (backwardSeek || currentPosition < lineStartMs) {
                    lastActiveGX = 0f;
                }
                return lastActiveGX;
            }
            // calculate cumulative from syllable 0 to activeSylIdx so fast sung words dont lag behind
            float gx = 0f;
            for (int i = 0; i <= activeSylIdx && i < alignedSyllables.size(); i++) {
                AlignedSyllable s = alignedSyllables.get(i);
                long ssMs = (long) (s.syllable.startTime * 1000.0);
                long seMs = (long) (s.syllable.endTime * 1000.0);
                float sgStart = sylBounds.startX[i];
                float sgEnd = sylBounds.endX[i];
                if (currentPosition >= seMs) {
                    gx = Math.max(gx, sgEnd);
                } else if (currentPosition > ssMs) {
                    long dur = Math.max(1L, seMs - ssMs);
                    float progress = Math.max(0f, Math.min(1f, (float) (currentPosition - ssMs) / (float) dur));
                    gx = Math.max(gx, sgStart + (sgEnd - sgStart) * progress);
                } else {
                    gx = Math.max(gx, sgStart);
                }
            }
            if (!backwardSeek && gx < lastActiveGX && currentPosition >= lineStartMs) {
                gx = lastActiveGX;
            }
            lastActiveGX = gx;
            return gx;
        } else {
            lastActivePosMs = Long.MIN_VALUE;
            if (!reachedLineEnd) {
                finishStartUptimeMs = -1L;
                lastActiveGX = 0f;
                return 0f;
            }
            if (lastActiveGX >= 1f && lastActiveGX <= (lineEndX - 0.5f)) {
                if (finishStartUptimeMs < 0L) {
                    finishStartUptimeMs = nowUptimeMs;
                    finishStartGX = lastActiveGX;
                }
                long elapsed = Math.max(0L, nowUptimeMs - finishStartUptimeMs);
                float t = Math.max(0f, Math.min(1f, elapsed / 180f));
                float eased = LINEAR_OUT_SLOW_IN.getInterpolation(t);
                float gx = finishStartGX + (lineEndX - finishStartGX) * eased;
                if (t >= 1f) {
                    lastActiveGX = lineEndX;
                }
                return gx;
            }
            return lastActiveGX;
        }
    }

    private void updateColorAndSimpleStates(long currentPosition, boolean isActiveLine, long nowUptimeMs) {
        float targetCS = (!isActiveLine || currentPosition > effectiveEndTimeMs) ? 1f : 0f;
        float targetSS = (isActiveLine && currentPosition >= lineStartTime && currentPosition <= effectiveEndTimeMs) ? 1f : 0f;

        if (isFirstRun || lastDrawUptimeMs < 0L) {
            curColorState = targetCS;
            curColorVel = 0f;
            curSimpleState = targetSS;
            curSimpleVel = 0f;
            return;
        }
        float dt = Math.max(0.001f, Math.min(0.1f, (nowUptimeMs - lastDrawUptimeMs) / 1000f));

        float omegaCS = 14.0f; // 1:1 PRD 5.2 critically-damped deactivation spring
        float errCS = curColorState - targetCS;
        if (Math.abs(errCS) > 0.0005f || Math.abs(curColorVel) > 0.005f) {
            float expCS = (float) Math.exp(-omegaCS * dt);
            float termCS = curColorVel + omegaCS * errCS;
            curColorState = targetCS + (errCS + termCS * dt) * expCS;
            curColorVel = (curColorVel - omegaCS * termCS * dt) * expCS;
        } else {
            curColorState = targetCS;
            curColorVel = 0f;
        }

        float omegaSS = 11.5f; // 1:1 PRD 5.2 critically-damped inactive spring
        float errSS = curSimpleState - targetSS;
        if (Math.abs(errSS) > 0.0005f || Math.abs(curSimpleVel) > 0.005f) {
            float expSS = (float) Math.exp(-omegaSS * dt);
            float termSS = curSimpleVel + omegaSS * errSS;
            curSimpleState = targetSS + (errSS + termSS * dt) * expSS;
            curSimpleVel = (curSimpleVel - omegaSS * termSS * dt) * expSS;
        } else {
            curSimpleState = targetSS;
            curSimpleVel = 0f;
        }
    }

    private void updateAppleAnimationStates(
            long currentPosition,
            boolean isActiveLine,
            float safeSpeed,
            long nowUptimeMs) {
        int n = clusters.size();
        int sylCount = alignedSyllables.size();

        if (isActiveLine) {
            for (int i = 0; i < n; i++) {
                VisualCluster vc = clusters.get(i);
                if (!vc.isHeavy) continue;
                heavyRelTriggered[i] = false;
                heavyRelMode[i] = 0;
                heavyRelAnimFrom[i] = 0f;
                heavyRelAnimTarget[i] = 0f;
                heavyRelAnimDurationMs[i] = 0f;
                heavyRelAnimDelayMs[i] = 0f;
                heavyRelAnimStartMs[i] = -1L;
                clusterReleaseValues[i] = 0f;

                if (currentPosition < vc.wordStartMs) {
                    clusterGrowValues[i] = 0f;
                } else {
                    float elapsedSinceOwnStart = (float) (currentPosition - vc.wordStartMs - vc.charDelayMs);
                    if (elapsedSinceOwnStart <= 0f) {
                        clusterGrowValues[i] = 0f;
                    } else {
                        clusterGrowValues[i] = Math.max(0f, Math.min(1f, elapsedSinceOwnStart / clusterSpanMs[i]));
                    }
                }
            }
        } else if (isFirstRun && !wasActiveLine) {
            deactivatedPosMs = currentPosition;
            deactivatedUptimeMs = -1L;
            for (int i = 0; i < n; i++) {
                VisualCluster hc = clusters.get(i);
                if (!hc.isHeavy) continue;
                heavyRelTriggered[i] = true;
                heavyRelMode[i] = 0;
                heavyRelAnimFrom[i] = 1f;
                heavyRelAnimTarget[i] = 1f;
                heavyRelAnimDurationMs[i] = 0f;
                heavyRelAnimDelayMs[i] = 0f;
                heavyRelAnimStartMs[i] = -1L;
                clusterReleaseValues[i] = 1f;
                clusterGrowValues[i] = 0f;
            }
        } else {
            if (wasActiveLine) {
                deactivatedPosMs = currentPosition;
                deactivatedUptimeMs = nowUptimeMs;
                for (int i = 0; i < n; i++) {
                    VisualCluster hc = clusters.get(i);
                    if (!hc.isHeavy) continue;
                    heavyRelTriggered[i] = false;
                }
            }

            float elapsedSinceDeactivate = (deactivatedUptimeMs >= 0L)
                    ? Math.max(0f, (float) (nowUptimeMs - deactivatedUptimeMs))
                    : 0f;

            for (int i = 0; i < n; i++) {
                VisualCluster hc = clusters.get(i);
                if (!hc.isHeavy) continue;

                if (!heavyRelTriggered[i]) {
                    heavyRelTriggered[i] = true;
                    float wordRemaining = 0f;
                    for (int cIdx = 0; cIdx < n; cIdx++) {
                        VisualCluster c = clusters.get(cIdx);
                        if (c.isHeavy && c.wordStartMs == hc.wordStartMs) {
                            float rem = clusterSpanMs[cIdx] - (deactivatedPosMs - c.wordStartMs - c.charDelayMs);
                            if (rem > wordRemaining) wordRemaining = rem;
                        }
                    }
                    float ownRemaining = clusterSpanMs[i] - (deactivatedPosMs - hc.wordStartMs - hc.charDelayMs);
                    if (deactivatedPosMs >= hc.wordStartMs) {
                        if (wordRemaining > 0f && ownRemaining > 0f) {
                            heavyRelMode[i] = 1;
                            heavyRelAnimFrom[i] = clusterReleaseValues[i];
                            heavyRelAnimTarget[i] = 1f;
                            heavyRelAnimDurationMs[i] = Math.max(1f, Math.min(500f, ownRemaining) / safeSpeed);
                            heavyRelAnimDelayMs[i] = 0f;
                            heavyRelAnimStartMs[i] = nowUptimeMs;
                        } else {
                            heavyRelMode[i] = 2;
                            heavyRelAnimFrom[i] = clusterReleaseValues[i];
                            heavyRelAnimTarget[i] = 1f;
                            heavyRelAnimDurationMs[i] = 1000f;
                            heavyRelAnimDelayMs[i] = Math.max(0f, wordRemaining) / safeSpeed;
                            heavyRelAnimStartMs[i] = nowUptimeMs;
                        }
                    } else {
                        heavyRelMode[i] = 0;
                        heavyRelAnimFrom[i] = 1f;
                        heavyRelAnimTarget[i] = 1f;
                        heavyRelAnimDurationMs[i] = 0f;
                        heavyRelAnimDelayMs[i] = 0f;
                        heavyRelAnimStartMs[i] = -1L;
                        clusterReleaseValues[i] = 1f;
                        clusterGrowValues[i] = 0f;
                    }
                }

                int mode = heavyRelMode[i];
                if (mode == 1) {
                    float elapsed = Math.max(0f, (float) (nowUptimeMs - heavyRelAnimStartMs[i]) - heavyRelAnimDelayMs[i]);
                    float t = Math.max(0f, Math.min(1f, elapsed / Math.max(1f, heavyRelAnimDurationMs[i])));
                    float eased = FAST_OUT_SLOW_IN.getInterpolation(t);
                    clusterReleaseValues[i] = heavyRelAnimFrom[i] + (heavyRelAnimTarget[i] - heavyRelAnimFrom[i]) * eased;
                } else if (mode == 2) {
                    float elapsed = (float) (nowUptimeMs - heavyRelAnimStartMs[i]) - heavyRelAnimDelayMs[i];
                    if (elapsed <= 0f) {
                        clusterReleaseValues[i] = heavyRelAnimFrom[i];
                    } else {
                        float t = Math.max(0f, Math.min(1f, elapsed / Math.max(1f, heavyRelAnimDurationMs[i])));
                        float eased = FAST_OUT_SLOW_IN.getInterpolation(t);
                        clusterReleaseValues[i] = heavyRelAnimFrom[i] + (heavyRelAnimTarget[i] - heavyRelAnimFrom[i]) * eased;
                    }
                } else {
                    clusterReleaseValues[i] = 1f;
                }

                if (deactivatedPosMs >= hc.wordStartMs && clusterGrowValues[i] < 1f) {
                    float virtualElapsed = (deactivatedPosMs - hc.wordStartMs - hc.charDelayMs)
                            + elapsedSinceDeactivate * safeSpeed;
                    if (virtualElapsed <= 0f) {
                        clusterGrowValues[i] = 0f;
                    } else {
                        clusterGrowValues[i] = Math.max(0f, Math.min(1f, virtualElapsed / clusterSpanMs[i]));
                    }
                } else if (deactivatedPosMs < hc.wordStartMs) {
                    clusterGrowValues[i] = 0f;
                }
            }
        }

        for (int sIdx = 0; sIdx < sylCount; sIdx++) {
            long startMs = (long) (alignedSyllables.get(sIdx).syllable.startTime * 1000.0);
            if (!isActiveLine) {
                sylElevTriggered[sIdx] = false;
                if (isFirstRun && !wasActiveLine) {
                    sylElevAnimFrom[sIdx] = 0f;
                    sylElevAnimTarget[sIdx] = 0f;
                    sylElevCurrent[sIdx] = 0f;
                    sylElevAnimStartMs[sIdx] = nowUptimeMs - 1000L;
                } else if (wasActiveLine) {
                    sylElevAnimFrom[sIdx] = sylElevCurrent[sIdx];
                    sylElevAnimTarget[sIdx] = 0f;
                    sylElevAnimStartMs[sIdx] = nowUptimeMs;
                }
            } else {
                if (wasActiveLine != isActiveLine && currentPosition < startMs) {
                    sylElevTriggered[sIdx] = false;
                    sylElevAnimFrom[sIdx] = 0f;
                    sylElevAnimTarget[sIdx] = 0f;
                    sylElevCurrent[sIdx] = 0f;
                    sylElevAnimStartMs[sIdx] = nowUptimeMs - 1000L;
                }
                if (currentPosition >= startMs && !sylElevTriggered[sIdx]) {
                    sylElevTriggered[sIdx] = true;
                    sylElevAnimFrom[sIdx] = sylElevCurrent[sIdx];
                    sylElevAnimTarget[sIdx] = -elevationAmount;
                    sylElevAnimStartMs[sIdx] = nowUptimeMs;
                } else if (currentPosition < startMs && sylElevTriggered[sIdx]) {
                    sylElevTriggered[sIdx] = false;
                    sylElevAnimFrom[sIdx] = 0f;
                    sylElevAnimTarget[sIdx] = 0f;
                    sylElevCurrent[sIdx] = 0f;
                    sylElevAnimStartMs[sIdx] = nowUptimeMs - 1000L;
                } else if (sylElevAnimStartMs[sIdx] < 0L) {
                    sylElevAnimFrom[sIdx] = 0f;
                    sylElevAnimTarget[sIdx] = 0f;
                    sylElevCurrent[sIdx] = 0f;
                    sylElevAnimStartMs[sIdx] = nowUptimeMs - 1000L;
                }
            }

            long elapsed = Math.max(0L, nowUptimeMs - sylElevAnimStartMs[sIdx]);
            if (elapsed >= 1000L) {
                sylElevCurrent[sIdx] = sylElevAnimTarget[sIdx];
            } else {
                float t = elapsed / 1000f;
                float eased = FAST_OUT_SLOW_IN.getInterpolation(t);
                sylElevCurrent[sIdx] = sylElevAnimFrom[sIdx] + (sylElevAnimTarget[sIdx] - sylElevAnimFrom[sIdx]) * eased;
            }
        }
    }

    public void draw(
            @NonNull Canvas canvas,
            long currentPosition,
            boolean isActiveLine,
            boolean allSung,
            int expressiveAccent,
            int lineColor,
            float focusedAlpha,
            float playbackSpeed) {
        draw(canvas, currentPosition, isActiveLine, allSung, expressiveAccent, lineColor, focusedAlpha, playbackSpeed, true);
    }

    public void draw(
            @NonNull Canvas canvas,
            long currentPosition,
            boolean isActiveLine,
            boolean allSung,
            int expressiveAccent,
            int lineColor,
            float focusedAlpha,
            float playbackSpeed,
            boolean isPlaying) {
        long rawUptimeMs = SystemClock.uptimeMillis();
        // animation clock locked when song paused, so glow/wipe/pulse freeze in place not moving alone
        if (lastRawUptimeMs >= 0L && isPlaying && lastWasPlaying) {
            long elapsed = rawUptimeMs - lastRawUptimeMs;
            if (elapsed > 0L && elapsed < 250L) {
                animClockMs += elapsed;
            }
        }
        lastRawUptimeMs = rawUptimeMs;
        lastWasPlaying = isPlaying;
        long nowUptimeMs = animClockMs;
        float safeSpeed = Math.max(0.1f, playbackSpeed);

        // mask prewarm given 1.5ms budget per frame so new active line entering dont cause frame drop
        if ((isActiveLine || prewarmMasks) && style != LyricsAnimationStyle.SIMPLE) {
            maskCacheStore.prewarmStep(
                    clusters.size(),
                    1_500_000L,
                    idx -> clusters.get(idx).subLayout,
                    idx -> {
                        VisualCluster vc = clusters.get(idx);
                        if (!vc.isHeavy) return 0f;
                        if (style == LyricsAnimationStyle.EXPRESSIVE) {
                            return shadowBlurRadius * EXPRESSIVE_GLOW_BLUR_FACTOR;
                        }
                        return !clusterMotionOff[idx] ? glowBlurRadius : 0f;
                    }
            );
        }

        boolean reachedLineEnd = currentPosition > 0L && currentPosition >= lineStartMs;
        int activeSylIdx = findActiveSyllableIndex(currentPosition);
        ActiveSylInfo activeInfo = getActiveSylInfo(activeSylIdx);
        float activeSylProgress = getActiveSylProgress(activeSylIdx, currentPosition);
        float curGX = updateGlobalX(activeSylIdx, currentPosition, isActiveLine, reachedLineEnd, nowUptimeMs);
        updateColorAndSimpleStates(currentPosition, isActiveLine, nowUptimeMs);

        boolean isFinishing = !isActiveLine
                && reachedLineEnd
                && lastSylIdx >= 0
                && curGX >= 1f
                && curGX <= (lineEndX - 0.5f);

        // keep wipe renderer running while line deactivating, so dimming look smooth when changing line not instantly turn off like light switch
        boolean isDeactivatingFade = !isActiveLine
                && reachedLineEnd
                && (curColorState < 0.995f || curSimpleState > 0.005f);

        boolean isFinishingOrDeactivating = isFinishing || isDeactivatingFade;
        int wipeSylIdx = isFinishingOrDeactivating ? lastSylIdx : activeInfo.index;
        int wipeLineIdx = isFinishingOrDeactivating ? (lineCount - 1) : activeInfo.lineIdx;
        boolean isActiveSylLastOfLine = activeInfo.isLastOfLine;
        boolean effLastOfLine = isActiveSylLastOfLine || isFinishingOrDeactivating;
        float effSylProgress = isFinishingOrDeactivating ? 1f : activeSylProgress;

        float litAlpha = 1f;
        int litColor = litColorCache.get(expressiveAccent, litAlpha);
        int unlitColor = unlitColorCache.get(expressiveAccent, focusedAlpha);
        int targetPastColor = isActiveLine ? unlitColor : lineColor;
        int pastColor = (curColorState <= 0f)
                ? litColor
                : WordAnimationUtils.lerpColor(litColor, targetPastColor, curColorState);

        boolean showWipe = isActiveLine || isFinishing || isDeactivatingFade;
        int lineColorValue = showWipe ? Color.TRANSPARENT : lineColor;
        float simpleFrac = (currentPosition > effectiveEndTimeMs) ? 0f : Math.max(0f, Math.min(1f, curSimpleState));
        int inactiveColor = !showWipe
                ? WordAnimationUtils.lerpColor(lineColorValue, expressiveAccent, simpleFrac)
                : Color.TRANSPARENT;

        canvas.save();
        canvas.translate(-extraPaddingPx, -extraPaddingPx);

        if (style == LyricsAnimationStyle.SIMPLE) {
            drawSimple(
                    canvas, showWipe, wipeSylIdx, wipeLineIdx, curGX,
                    effLastOfLine, effSylProgress,
                    pastColor, unlitColor, litColor, lineColor, expressiveAccent);
            stillAnimating = isActiveLine || isFinishing || isDeactivatingFade;
        } else if (style == LyricsAnimationStyle.EXPRESSIVE) {
            drawExpressive(
                    canvas, currentPosition, isActiveLine, isFinishing, showWipe,
                    wipeSylIdx, wipeLineIdx, curGX,
                    effLastOfLine, effSylProgress,
                    pastColor, unlitColor, litColor, inactiveColor, lineColor, expressiveAccent);
            float targetCS = (!isActiveLine || currentPosition > effectiveEndTimeMs) ? 1f : 0f;
            float targetSS = (isActiveLine && currentPosition >= lineStartTime && currentPosition <= effectiveEndTimeMs) ? 1f : 0f;
            stillAnimating = isActiveLine
                    || isFinishing
                    || isDeactivatingFade
                    || (!isActiveLine && (curSimpleState > 0.002f || (currentPosition >= lineStartTime && currentPosition <= effectiveEndTimeMs)))
                    || Math.abs(curColorState - targetCS) > 0.002f
                    || Math.abs(curSimpleState - targetSS) > 0.002f;
        } else {
            updateAppleAnimationStates(currentPosition, isActiveLine, safeSpeed, nowUptimeMs);
            drawApple(
                    canvas, currentPosition, isActiveLine, isFinishing, isDeactivatingFade, showWipe,
                    wipeSylIdx, wipeLineIdx, curGX,
                    effLastOfLine, effSylProgress,
                    pastColor, unlitColor, litColor, inactiveColor, lineColor, expressiveAccent, nowUptimeMs);
        }

        canvas.restore();

        wasActiveLine = isActiveLine;
        isFirstRun = false;
        lastDrawUptimeMs = nowUptimeMs;
    }

    private void drawSimple(
            @NonNull Canvas canvas,
            boolean showWipe,
            int wipeSylIdx,
            int wipeLineIdx,
            float curGX,
            boolean effLastOfLine,
            float effSylProgress,
            int pastColor,
            int unlitColor,
            int litColor,
            int lineColor,
            int expressiveAccent) {
        int n = clusters.size();
        for (int idx = 0; idx < n; idx++) {
            VisualCluster vc = clusters.get(idx);
            float topLeftX = statics.baseX[idx] + extraPaddingPx;
            float topLeftY = statics.baseY[idx] + extraPaddingPx;
            if (!showWipe) {
                WordAnimationUtils.drawLayoutWithPaint(canvas, vc.subLayout, topLeftX, topLeftY, lineColor, null);
            } else {
                WordAnimationUtils.drawWipeText(
                        canvas, vc, topLeftX, topLeftY,
                        statics.isRtlLine[idx], statics.lineStart[idx], statics.lineWidth[idx],
                        statics.localStart[idx], statics.localEnd[idx],
                        wipeSylIdx, wipeLineIdx, curGX,
                        effLastOfLine, effSylProgress, gradientWidth,
                        pastColor, unlitColor, litColor, wipeScratch);
            }
        }

        if (!romanStatics.isEmpty()) {
            WordAnimationUtils.drawRomanizedWordClusters(
                    canvas, romanStatics, showWipe, wipeSylIdx, wipeLineIdx, curGX,
                    effLastOfLine, effSylProgress, gradientWidth,
                    pastColor, unlitColor, litColor,
                    showWipe ? Color.TRANSPARENT : lineColor,
                    0f, expressiveAccent);
        }
    }

    private void drawApple(
            @NonNull Canvas canvas,
            long currentPosition,
            boolean isActiveLine,
            boolean isFinishing,
            boolean isDeactivatingFade,
            boolean showWipe,
            int wipeSylIdx,
            int wipeLineIdx,
            float curGX,
            boolean effLastOfLine,
            float effSylProgress,
            int pastColor,
            int unlitColor,
            int litColor,
            int inactiveColor,
            int lineColor,
            int expressiveAccent,
            long nowUptimeMs) {
        long drawCurrentPos = isActiveLine ? currentPosition : 0L;
        ClusterMaskCache maskCache = (isActiveLine || isDeactivatingFade) ? maskCacheStore : null;
        int n = clusters.size();
        boolean anyGlow = false;
        boolean anyRunningAnim = isActiveLine || isFinishing || isDeactivatingFade;

        for (int idx = 0; idx < n; idx++) {
            VisualCluster vc = clusters.get(idx);
            float elevValue;
            if (vc.isHeavy) {
                elevValue = clusterReleaseValues[idx];
                if (!isActiveLine && (elevValue < 1f || (deactivatedPosMs >= vc.wordStartMs && clusterGrowValues[idx] < 1f))) {
                    anyRunningAnim = true;
                }
            } else {
                int sIdx = vc.syllableIndex;
                if (sIdx >= 0 && sIdx < sylElevCurrent.length) {
                    elevValue = sylElevCurrent[sIdx];
                    if (nowUptimeMs - sylElevAnimStartMs[sIdx] < 1000L || Math.abs(elevValue - sylElevAnimTarget[sIdx]) > 0.001f) {
                        anyRunningAnim = true;
                    }
                } else {
                    elevValue = 0f;
                }
            }

            float scale = 1.0f;
            float shift = 0f;
            float glowAlpha = 0f;
            float lift = 0f;
            float rise = 0f;

            boolean disableGlowAndGrow = clusterMotionOff[idx];
            boolean notStartedYet = isActiveLine && drawCurrentPos < vc.wordStartMs;
            float growFraction = notStartedYet ? 0f : clusterGrowValues[idx];
            boolean isEmphasised = false;

            if (vc.isHeavy && emphasisTables[idx] != null) {
                sampleEmphasisCached(emphasisTables[idx], growFraction, sampleScratch);
                isEmphasised = growFraction > 0f && growFraction < 1f;
                if (isEmphasised) anyRunningAnim = true;

                lift = -emphasisLiftEm * fontSizePx * sampleScratch.lift * (1f - elevValue);

                if (!disableGlowAndGrow) {
                    float pulse = sampleScratch.pulse * vc.intensity;
                    rise = -emphasisRiseEm * fontSizePx * pulse;
                    if (!vc.disableScale) {
                        scale = 1f + emphasisScaleGain * pulse;
                        shift = vc.normX * emphasisShiftEm * fontSizePx * pulse;
                    }
                    glowAlpha = Math.max(0f, Math.min(1f, vc.charShadowIntensity * sampleScratch.glow));
                }
            }

            float drawX = statics.baseX[idx] + shift;
            float drawY = statics.baseY[idx] + (vc.isHeavy ? (lift + rise) : elevValue);
            boolean isGlowing = !disableGlowAndGrow && vc.isHeavy && isEmphasised;

            clusterScaleX[idx] = scale;
            clusterScaleY[idx] = scale;
            clusterGlowAlphas[idx] = glowAlpha;
            clusterIsGlowing[idx] = isGlowing;
            clusterDrawX[idx] = drawX;
            clusterDrawY[idx] = drawY;

            float lastY = clusterLastDrawY[idx];
            boolean isYMoving = !Float.isNaN(lastY) && Math.abs(drawY - lastY) > 0.001f;
            clusterIsYMoving[idx] = isYMoving;
            clusterLastDrawY[idx] = drawY;
            if (isGlowing) anyGlow = true;
        }

        if (anyGlow) {
            for (int idx = 0; idx < n; idx++) {
                if (clusterIsGlowing[idx]) {
                    float glowAlpha = clusterGlowAlphas[idx];
                    if (glowAlpha <= 0f) continue;
                    VisualCluster vc = clusters.get(idx);
                    float scale = clusterScaleX[idx];
                    float drawX = clusterDrawX[idx];
                    float drawY = clusterDrawY[idx];
                    float tx = drawX + extraPaddingPx;
                    float ty = drawY + extraPaddingPx;

                    Bitmap glowMask = (maskCache != null) ? maskCache.peekGlow(idx) : null;
                    if (glowMask != null && maskCache != null) {
                        canvas.save();
                        canvas.translate(tx, ty);
                        if (scale != 1.0f) {
                            canvas.scale(scale, scale, statics.pivotX[idx], statics.pivotY[idx]);
                        }
                        maskCache.drawMaskTinted(
                                canvas,
                                glowMask,
                                maskCache.glowScaleOf(idx),
                                maskCache.glowFilter,
                                glowAlpha,
                                PorterDuff.Mode.LIGHTEN);
                        canvas.restore();
                    } else {
                        TextPaint paint = vc.subLayout.getPaint();
                        int shadowCol = glowShadowTable.colorFor(glowAlpha);
                        float shadowRad = glowShadowTable.radiusFor(glowAlpha);
                        canvas.save();
                        if (scale != 1.0f) {
                            canvas.scale(scale, scale, tx + statics.pivotX[idx], ty + statics.pivotY[idx]);
                        }
                        paint.setShadowLayer(shadowRad, 0f, 0f, shadowCol);
                        WordAnimationUtils.drawLayoutWithPaint(canvas, vc.subLayout, tx, ty, Color.TRANSPARENT, null);
                        paint.clearShadowLayer();
                        canvas.restore();
                    }
                }
            }
        }

        ColorFilter pastFilter = (maskCache != null) ? maskCache.pastTint.get(pastColor) : null;
        ColorFilter unlitFilter = (maskCache != null) ? maskCache.unlitTint.get(unlitColor) : null;
        ColorFilter inactiveFilter = (maskCache != null && !showWipe) ? maskCache.inactiveTint.get(inactiveColor) : null;

        for (int idx = 0; idx < n; idx++) {
            VisualCluster vc = clusters.get(idx);
            float cdScale = clusterScaleX[idx];
            float cdDrawX = clusterDrawX[idx];
            float cdDrawY = clusterDrawY[idx];
            boolean isRtlLine = statics.isRtlLine[idx];
            float lgs = statics.lineStart[idx];
            float lw = statics.lineWidth[idx];
            float topLeftX = cdDrawX + extraPaddingPx;
            float topLeftY = cdDrawY + extraPaddingPx;

            boolean isScaling = cdScale != 1.0f;
            boolean isElevating = clusterIsYMoving[idx];
            boolean isAnimating = isScaling || isElevating;
            Bitmap mask = (isAnimating && maskCache != null) ? maskCache.peek(idx) : null;

            if (mask == null || maskCache == null) {
                if (isScaling) {
                    canvas.save();
                    canvas.translate(topLeftX, topLeftY);
                    canvas.scale(cdScale, cdScale, statics.pivotX[idx], statics.pivotY[idx]);
                    if (!showWipe) {
                        WordAnimationUtils.drawLayoutWithPaint(canvas, vc.subLayout, 0f, 0f, inactiveColor, null);
                    } else {
                        WordAnimationUtils.drawWipeText(
                                canvas, vc, 0f, 0f, isRtlLine, lgs, lw,
                                statics.localStart[idx], statics.localEnd[idx],
                                wipeSylIdx, wipeLineIdx, curGX,
                                effLastOfLine, effSylProgress, gradientWidth,
                                pastColor, unlitColor, litColor, wipeScratch);
                    }
                    canvas.restore();
                } else {
                    if (!showWipe) {
                        WordAnimationUtils.drawLayoutWithPaint(canvas, vc.subLayout, topLeftX, topLeftY, inactiveColor, null);
                    } else {
                        WordAnimationUtils.drawWipeText(
                                canvas, vc, topLeftX, topLeftY, isRtlLine, lgs, lw,
                                statics.localStart[idx], statics.localEnd[idx],
                                wipeSylIdx, wipeLineIdx, curGX,
                                effLastOfLine, effSylProgress, gradientWidth,
                                pastColor, unlitColor, litColor, wipeScratch);
                    }
                }
            } else {
                float maskScale = maskCache.scaleOf(idx);
                canvas.save();
                canvas.translate(topLeftX, topLeftY);
                if (isScaling) {
                    canvas.scale(cdScale, cdScale, statics.pivotX[idx], statics.pivotY[idx]);
                }
                if (!showWipe && inactiveFilter != null) {
                    maskCache.drawMaskTinted(canvas, mask, maskScale, inactiveFilter, 1f, null);
                } else {
                    WordAnimationUtils.computeWipe(
                            vc, isRtlLine, lgs, lw,
                            statics.localStart[idx], statics.localEnd[idx],
                            wipeSylIdx, wipeLineIdx, curGX,
                            effLastOfLine, effSylProgress, gradientWidth, wipeScratch);
                    if (wipeScratch.mode == WordAnimationUtils.WIPE_PAST) {
                        maskCache.drawMaskTinted(canvas, mask, maskScale, pastFilter, 1f, null);
                    } else if (wipeScratch.mode == WordAnimationUtils.WIPE_UNLIT) {
                        maskCache.drawMaskTinted(canvas, mask, maskScale, unlitFilter, 1f, null);
                    } else {
                        maskCache.drawMaskBand(canvas, mask, maskScale, wipeScratch, litColor, unlitColor);
                    }
                }
                canvas.restore();
            }
        }

        if (!romanStatics.isEmpty()) {
            WordAnimationUtils.drawRomanizedWordClusters(
                    canvas, romanStatics, showWipe, wipeSylIdx, wipeLineIdx, curGX,
                    effLastOfLine, effSylProgress, gradientWidth,
                    pastColor, unlitColor, litColor,
                    showWipe ? Color.TRANSPARENT : lineColor, curSimpleState, expressiveAccent);
        }

        float targetCS = (!isActiveLine || currentPosition > effectiveEndTimeMs) ? 1f : 0f;
        float targetSS = (isActiveLine && currentPosition >= lineStartTime && currentPosition <= effectiveEndTimeMs) ? 1f : 0f;
        stillAnimating = anyRunningAnim
                || isFinishing
                || isDeactivatingFade
                || (!isActiveLine && (curSimpleState > 0.002f || (currentPosition >= lineStartTime && currentPosition <= effectiveEndTimeMs)))
                || Math.abs(curColorState - targetCS) > 0.002f
                || Math.abs(curSimpleState - targetSS) > 0.002f;
    }

    private void drawExpressive(
            @NonNull Canvas canvas,
            long currentPosition,
            boolean isActiveLine,
            boolean isFinishing,
            boolean showWipe,
            int wipeSylIdx,
            int wipeLineIdx,
            float curGX,
            boolean effLastOfLine,
            float effSylProgress,
            int pastColor,
            int unlitColor,
            int litColor,
            int inactiveColor,
            int lineColor,
            int expressiveAccent) {
        long drawCurrentPos = isActiveLine ? currentPosition : 0L;
        ClusterMaskCache maskCache = isActiveLine ? maskCacheStore : null;

        if (isActiveLine) {
            for (int i = 0; i < alignedSyllables.size(); i++) {
                AlignedSyllable s = alignedSyllables.get(i);
                long ssMs = (long) (s.syllable.startTime * 1000.0);
                long seMs = (long) (s.syllable.endTime * 1000.0);
                if (drawCurrentPos > seMs) {
                    sylSungFactorValues[i] = 1f;
                } else if (drawCurrentPos >= ssMs) {
                    long dur = Math.max(1L, seMs - ssMs);
                    sylSungFactorValues[i] = Math.max(0f, Math.min(1f, (float) (drawCurrentPos - ssMs) / (float) dur));
                } else {
                    sylSungFactorValues[i] = 0f;
                }
            }
        }

        int n = clusters.size();
        boolean anyGlow = false;

        Arrays.fill(lineTotalPushScratch, 0f);
        Arrays.fill(lineCurrentPushScratch, 0f);

        for (int idx = 0; idx < n; idx++) {
            VisualCluster vc = clusters.get(idx);
            boolean disableMotion = clusterMotionOff[idx];

            long wStartMs = vc.wordStartMs;
            long wEndMs = vc.wordEndMs;
            boolean isWordSung = isActiveLine && drawCurrentPos > wEndMs;
            boolean isWordActive = isActiveLine && drawCurrentPos >= wStartMs && drawCurrentPos <= wEndMs;
            int sylIdxForFactor = vc.syllableIndex;
            float sungFactor = (isActiveLine && sylIdxForFactor != -1 && sylIdxForFactor < sylSungFactorValues.length)
                    ? sylSungFactorValues[sylIdxForFactor]
                    : 0f;
            float sylFadeProgress = (isFinishing || isWordSung) ? 1f : (isWordActive ? sungFactor : 0f);
            clusterFadeProgress[idx] = sylFadeProgress;

            float scaleX = 1f;
            float scaleY = 1f;
            float glowAlpha = 0f;

            if (!disableMotion && vc.isHeavy) {
                float timeSinceStart = (float) (drawCurrentPos - wStartMs);
                float wobble = 0f;
                if (isActiveLine && timeSinceStart >= 0f && timeSinceStart <= 750f) {
                    if (timeSinceStart < 125f) {
                        wobble = timeSinceStart / 125f;
                    } else {
                        wobble = Math.max(0f, 1f - (timeSinceStart - 125f) / 625f);
                    }
                }

                float groupSizeF = clusterGroupSizeF[idx];
                float charLp = 0f;
                if (isWordActive || isWordSung) {
                    float wProg = (drawCurrentPos - wStartMs) / clusterWordDurMs[idx];
                    charLp = Math.max(0f, Math.min(1f, (wProg - clusterGroupFrac[idx]) * groupSizeF));
                }
                float nudgeScale = (!isWordSung && sungFactor > 0f)
                        ? (0.038f * (float) Math.sin(charLp * Math.PI) * (float) Math.exp(-3f * charLp))
                        : 0f;

                float crescendoDeltaX = 0f;
                float crescendoDeltaY = 0f;
                boolean inWordGroup = clusterInWordGroup[idx];
                boolean isNextOfDash = clusterIsNextOfDash[idx];
                if (isActiveLine && (inWordGroup || isNextOfDash)) {
                    int groupPos = clusterSylGroupPos[idx];
                    float timeSinceEnd = (float) (drawCurrentPos - wEndMs);
                    float exitDuration = 600f;
                    float pOut = Math.max(0f, Math.min(1f, timeSinceEnd / exitDuration));
                    float peakScale = 0.06f;
                    float decay = 3.5f;
                    float freq = 5.0f;
                    float baseScalePerSegment = 0.012f;
                    if (isNextOfDash) {
                        if (pOut > 0f) {
                            float baseAtEnd = groupPos * baseScalePerSegment;
                            float totalAtEnd = baseAtEnd + peakScale;
                            float springOut = totalAtEnd
                                    * (float) Math.exp(-decay * pOut)
                                    * (float) Math.cos(freq * pOut * Math.PI)
                                    * (1f - pOut);
                            crescendoDeltaX = springOut;
                            crescendoDeltaY = springOut;
                        } else {
                            float base = groupPos * baseScalePerSegment;
                            float springPart = peakScale * (1f
                                    - (float) Math.exp(-decay * sungFactor)
                                    * (float) Math.cos(freq * sungFactor * Math.PI)
                                    * (1f - sungFactor));
                            crescendoDeltaX = base + springPart;
                            crescendoDeltaY = base + springPart;
                        }
                    } else if (inWordGroup) {
                        float boost = (sungFactor > 0f) ? (0.02f * (1f - sungFactor)) : 0f;
                        float base = (groupPos * baseScalePerSegment) + boost;
                        crescendoDeltaX = base;
                        crescendoDeltaY = base;
                    }
                }

                scaleX = 1f + (wobble * 0.025f) + crescendoDeltaX + (nudgeScale * 0.3f);
                scaleY = 1f + (wobble * 0.015f) + crescendoDeltaY + nudgeScale;

                if (!isWordSung && sungFactor > 0.001f) {
                    float fadeFactor = Math.max(0f, Math.min(1f, sungFactor * 5f))
                            * Math.max(0f, Math.min(1f, (1f - sungFactor) * 8f));
                    float impactFactor = clusterBaseImpact[idx] * fadeFactor;
                    glowAlpha = Math.max(0f, Math.min(0.4f, 0.35f * impactFactor));
                }
            }

            clusterScaleX[idx] = scaleX;
            clusterScaleY[idx] = scaleY;
            clusterGlowAlphas[idx] = glowAlpha;
            boolean isGlowing = glowAlpha > 0.001f;
            clusterIsGlowing[idx] = isGlowing;
            if (isGlowing) anyGlow = true;

            lineTotalPushScratch[vc.lineIdx] += vc.width * (scaleX - 1f);
        }

        for (int idx = 0; idx < n; idx++) {
            VisualCluster vc = clusters.get(idx);
            int lineIdx = vc.lineIdx;
            float alignShift;
            if (alignment == Layout.Alignment.ALIGN_CENTER) {
                alignShift = -lineTotalPushScratch[lineIdx] / 2f;
            } else if (alignment == Layout.Alignment.ALIGN_OPPOSITE) {
                alignShift = -lineTotalPushScratch[lineIdx];
            } else {
                alignShift = 0f;
            }
            clusterDrawX[idx] = statics.baseX[idx] + alignShift + lineCurrentPushScratch[lineIdx];
            clusterDrawY[idx] = statics.baseY[idx];
            lineCurrentPushScratch[lineIdx] += vc.width * (clusterScaleX[idx] - 1f);
        }

        float minVisibleGlowAlpha = 0.02f;
        if (anyGlow) {
            int transparentAccentColor = transparentAccentColorCache.get(expressiveAccent, 0f);
            for (int idx = 0; idx < n; idx++) {
                if (clusterIsGlowing[idx]) {
                    float glowAlpha = clusterGlowAlphas[idx];
                    if (glowAlpha < minVisibleGlowAlpha) continue;
                    VisualCluster vc = clusters.get(idx);
                    float sx = clusterScaleX[idx];
                    float sy = clusterScaleY[idx];
                    float tx = clusterDrawX[idx] + extraPaddingPx;
                    float ty = clusterDrawY[idx] + extraPaddingPx;

                    Bitmap glowMask = (maskCache != null) ? maskCache.peekGlow(idx) : null;
                    if (glowMask != null && maskCache != null) {
                        canvas.save();
                        canvas.translate(tx, ty);
                        if (sx != 1.0f || sy != 1.0f) {
                            canvas.scale(sx, sy, statics.pivotX[idx], statics.pivotY[idx]);
                        }
                        maskCache.drawMaskTinted(
                                canvas,
                                glowMask,
                                maskCache.glowScaleOf(idx),
                                maskCache.glowTint.get(expressiveAccent),
                                glowAlpha,
                                PorterDuff.Mode.LIGHTEN);
                        canvas.restore();
                    } else {
                        TextPaint paint = vc.subLayout.getPaint();
                        int shadowCol = litColorCache.get(expressiveAccent, glowAlpha);
                        float shadowRad = Math.max(shadowBlurRadius * 0.35f, shadowBlurRadius * glowAlpha * 2f);
                        canvas.save();
                        if (sx != 1.0f || sy != 1.0f) {
                            canvas.scale(sx, sy, tx + statics.pivotX[idx], ty + statics.pivotY[idx]);
                        }
                        paint.setShadowLayer(shadowRad, 0f, 0f, shadowCol);
                        WordAnimationUtils.drawLayoutWithPaint(canvas, vc.subLayout, tx, ty, transparentAccentColor, null);
                        paint.clearShadowLayer();
                        canvas.restore();
                    }
                }
            }
        }

        ColorFilter pastFilter = (maskCache != null) ? maskCache.pastTint.get(pastColor) : null;
        ColorFilter unlitFilter = (maskCache != null) ? maskCache.unlitTint.get(unlitColor) : null;
        ColorFilter inactiveFilter = (maskCache != null && !showWipe) ? maskCache.inactiveTint.get(inactiveColor) : null;

        for (int idx = 0; idx < n; idx++) {
            VisualCluster vc = clusters.get(idx);
            float cdScaleX = clusterScaleX[idx];
            float cdScaleY = clusterScaleY[idx];
            float cdDrawX = clusterDrawX[idx];
            float cdDrawY = clusterDrawY[idx];
            boolean isRtlLine = statics.isRtlLine[idx];
            float lgs = statics.lineStart[idx];
            float lw = statics.lineWidth[idx];
            float topLeftX = cdDrawX + extraPaddingPx;
            float topLeftY = cdDrawY + extraPaddingPx;

            float sylFadeProgress = showWipe ? clusterFadeProgress[idx] : 0f;
            int clusterUnlitColor;
            if (sylFadeProgress <= 0f) {
                clusterUnlitColor = unlitColor;
            } else if (sylFadeProgress >= 1f) {
                clusterUnlitColor = litColor;
            } else {
                clusterUnlitColor = WordAnimationUtils.lerpColor(unlitColor, litColor, sylFadeProgress);
            }

            boolean isScaling = cdScaleX != 1.0f || cdScaleY != 1.0f;
            boolean isAnimating = isActiveLine && isScaling;
            Bitmap mask = (isAnimating && maskCache != null) ? maskCache.peek(idx) : null;

            if (mask == null || maskCache == null) {
                if (isScaling) {
                    canvas.save();
                    canvas.translate(topLeftX, topLeftY);
                    canvas.scale(cdScaleX, cdScaleY, statics.pivotX[idx], statics.pivotY[idx]);
                    if (!showWipe) {
                        WordAnimationUtils.drawLayoutWithPaint(canvas, vc.subLayout, 0f, 0f, inactiveColor, null);
                    } else {
                        WordAnimationUtils.drawWipeText(
                                canvas, vc, 0f, 0f, isRtlLine, lgs, lw,
                                statics.localStart[idx], statics.localEnd[idx],
                                wipeSylIdx, wipeLineIdx, curGX,
                                effLastOfLine, effSylProgress, gradientWidth,
                                pastColor, clusterUnlitColor, litColor, wipeScratch);
                    }
                    canvas.restore();
                } else {
                    if (!showWipe) {
                        WordAnimationUtils.drawLayoutWithPaint(canvas, vc.subLayout, topLeftX, topLeftY, inactiveColor, null);
                    } else {
                        WordAnimationUtils.drawWipeText(
                                canvas, vc, topLeftX, topLeftY, isRtlLine, lgs, lw,
                                statics.localStart[idx], statics.localEnd[idx],
                                wipeSylIdx, wipeLineIdx, curGX,
                                effLastOfLine, effSylProgress, gradientWidth,
                                pastColor, clusterUnlitColor, litColor, wipeScratch);
                    }
                }
            } else {
                float maskScale = maskCache.scaleOf(idx);
                canvas.save();
                canvas.translate(topLeftX, topLeftY);
                if (isScaling) {
                    canvas.scale(cdScaleX, cdScaleY, statics.pivotX[idx], statics.pivotY[idx]);
                }
                if (!showWipe && inactiveFilter != null) {
                    maskCache.drawMaskTinted(canvas, mask, maskScale, inactiveFilter, 1f, null);
                } else {
                    WordAnimationUtils.computeWipe(
                            vc, isRtlLine, lgs, lw,
                            statics.localStart[idx], statics.localEnd[idx],
                            wipeSylIdx, wipeLineIdx, curGX,
                            effLastOfLine, effSylProgress, gradientWidth, wipeScratch);
                    if (wipeScratch.mode == WordAnimationUtils.WIPE_PAST) {
                        maskCache.drawMaskTinted(canvas, mask, maskScale, pastFilter, 1f, null);
                    } else if (wipeScratch.mode == WordAnimationUtils.WIPE_UNLIT) {
                        ColorFilter cf;
                        if (sylFadeProgress <= 0f) {
                            cf = unlitFilter;
                        } else if (sylFadeProgress >= 1f) {
                            cf = pastFilter;
                        } else {
                            cf = fadeTintCache.get(clusterUnlitColor);
                        }
                        maskCache.drawMaskTinted(canvas, mask, maskScale, cf, 1f, null);
                    } else {
                        maskCache.drawMaskBand(canvas, mask, maskScale, wipeScratch, litColor, clusterUnlitColor);
                    }
                }
                canvas.restore();
            }
        }

        if (!romanStatics.isEmpty()) {
            WordAnimationUtils.drawRomanizedWordClusters(
                    canvas, romanStatics, showWipe, wipeSylIdx, wipeLineIdx, curGX,
                    effLastOfLine, effSylProgress, gradientWidth,
                    pastColor, unlitColor, litColor,
                    showWipe ? Color.TRANSPARENT : lineColor,
                    curSimpleState, expressiveAccent);
        }
    }

    public void reset() {
        lastActiveGX = 0f;
        lastActivePosMs = Long.MIN_VALUE;
        finishStartUptimeMs = -1L;
        finishStartGX = 0f;
        curColorState = 0f;
        curColorVel = 0f;
        curSimpleState = 0f;
        curSimpleVel = 0f;
        deactivatedPosMs = 0L;
        deactivatedUptimeMs = -1L;
        wasActiveLine = false;
        isFirstRun = true;
        stillAnimating = false;
        lastDrawUptimeMs = -1L;
        lastRawUptimeMs = -1L;
        lastWasPlaying = false;
        Arrays.fill(clusterGrowValues, 0f);
        Arrays.fill(clusterReleaseValues, 0f);
        Arrays.fill(heavyRelTriggered, false);
        Arrays.fill(heavyRelMode, 0);
        Arrays.fill(heavyRelAnimFrom, 0f);
        Arrays.fill(heavyRelAnimTarget, 0f);
        Arrays.fill(heavyRelAnimDurationMs, 0f);
        Arrays.fill(heavyRelAnimDelayMs, 0f);
        Arrays.fill(heavyRelAnimStartMs, -1L);
        Arrays.fill(clusterScaleX, 1.0f);
        Arrays.fill(clusterScaleY, 1.0f);
        Arrays.fill(clusterGlowAlphas, 0f);
        Arrays.fill(clusterIsGlowing, false);
        Arrays.fill(clusterFadeProgress, 0f);
        Arrays.fill(sylSungFactorValues, 0f);
        Arrays.fill(sylElevTriggered, false);
        Arrays.fill(sylElevAnimStartMs, -1L);
        Arrays.fill(sylElevCurrent, 0f);
        Arrays.fill(sylElevAnimTarget, 0f);
        Arrays.fill(sylElevAnimFrom, 0f);
        Arrays.fill(clusterLastDrawY, Float.NaN);
    }

    public void release() {
        maskCacheStore.evictAndClear();
    }
}