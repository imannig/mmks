/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static app.morphe.extension.shared.StringRef.str;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.LayoutTransition;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.Pair;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.OverScroller;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Field;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static app.morphe.extension.music.patches.lyrics.ui.GapLineView.GAP_EXIT_DURATION_MS;
import app.morphe.extension.music.patches.lyrics.ui.GapLineView.GapInterval;
import app.morphe.extension.music.patches.lyrics.ui.LyricsLineView.WordTiming;
import app.morphe.extension.music.patches.lyrics.LyricsManager;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.LyricsMerge;
import app.morphe.extension.music.patches.lyrics.model.LyricsRetiming;
import app.morphe.extension.music.patches.lyrics.model.LyricsRetiming.RetimedEntry;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.patches.lyrics.parsers.LRCParser;
import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests;
import app.morphe.extension.music.patches.lyrics.storage.LyricsFileSaver;
import app.morphe.extension.music.patches.lyrics.derive.LyricsRomanizer;
import app.morphe.extension.music.patches.lyrics.derive.LyricsTranslator;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.ViewAnimations;

/**
 * Third party lyrics, drawn over the content of the lyrics engagement panel.
 *
 * <p>Hides itself when there are no lyrics to show, which leaves the built-in
 * lyrics visible underneath.
 */
public final class LyricsPanelView extends FrameLayout implements LyricsManager.Listener {

    private static final float INACTIVE_LINE_ALPHA = 0.8f;
    private static final float ACTIVE_LINE_ALPHA = 1.0f;
    private static final float BROWSING_ALPHA = 0.85f;
    private static final float INACTIVE_LINE_SCALE = 0.93f;
    private static final float ACTIVE_LINE_SCALE = 1.001f;
    private static final float PRESSED_LINE_SCALE = 1.0f;

    static final Interpolator TRANSITION_INTERPOLATOR = new PathInterpolator(0.25f, 0.1f, 0.25f, 1.0f);

    private static final float SCROLL_ANCHOR_RATIO = 0.25f;

    private static final Typeface LYRICS_TYPEFACE = WordAnimationUtils.getLyricsBoldTypeface();

    private static final class LyricsBoldSpan extends android.text.style.MetricAffectingSpan {
        @Override
        public void updateDrawState(TextPaint tp) {
            if (tp != null) {
                WordAnimationUtils.applyLyricsBoldPaint(tp);
            }
        }

        @Override
        public void updateMeasureState(@NonNull TextPaint tp) {
            WordAnimationUtils.applyLyricsBoldPaint(tp);
        }
    }

    private static final SparseArray<RenderEffect> BLUR_EFFECT_CACHE = new SparseArray<>();

    @Nullable
    static RenderEffect getCachedBlurEffect(float radius) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || radius < 0.5f) {
            return null;
        }
        int key = Math.round(radius * 10f);
        RenderEffect hit = BLUR_EFFECT_CACHE.get(key);
        if (hit == null) {
            hit = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL);
            BLUR_EFFECT_CACHE.put(key, hit);
        }
        return hit;
    }

    /** Applied on top of the secondary color, which alone is brighter than the app draws it. */
    private static final float FOOTER_ALPHA = 0.6f;

    /** line fade duration when moving highlight */
    private static final long HIGHLIGHT_FADE_DURATION_MILLISECONDS = 300L;

    /** Fade length when the panel appears over the built-in content. */
    private static final long OVERLAY_FADE_DURATION_MILLISECONDS = 150;

    /** How long auto scrolling stays off after the user touches the panel. */
    private static final long MANUAL_SCROLL_PAUSE_MILLISECONDS = 5000;

    private static final long OVERLAY_CACHE_TTL_MS = 300;

    private static final int SCROLL_INSTANT_THRESHOLD_FACTOR = 2;

    /** Own string, because the app string {@code lyrics_source} exists in English only. */
    private static final String LYRICS_SOURCE_KEY = "morphe_music_lyrics_source_label";

    /** Size of the source line under the lyrics. */
    private static final float FOOTER_TEXT_SIZE_SP = 16;

    private static final float BUTTON_TEXT_SIZE_SP = 14;

    /** Color the app uses for primary text. */
    private static final String APP_PRIMARY_TEXT_COLOR = "ytm_text_color_primary";

    /** Color the app uses for secondary text, applied to the translation. */
    private static final String APP_SECONDARY_TEXT_COLOR = "ytm_text_color_secondary";

    /** Background the app uses for the pill buttons under its own lyrics. */
    private static final String APP_BUTTON_BACKGROUND_COLOR = "ytm_color_white_at_10pct";

    /** Active (feature on) button background: pure white. */
    private static final int ACTIVE_BUTTON_BG_COLOR = 0xFFFFFFFF;
    /** Active (feature on) button foreground: pure black, readable on white. */
    private static final int ACTIVE_BUTTON_FG_COLOR = 0xFF000000;
    /** How long a button takes to cross between its inactive and active colors. */
    private static final long BUTTON_STATE_FADE_MILLISECONDS = 150;
    private static final ArgbEvaluator BUTTON_COLOR_EVALUATOR = new ArgbEvaluator();

    /** transparency for unsung words */
    static final int UNSUNG_ALPHA = 0x55;

    /** Icons of the buttons the app draws under its own lyrics. */
    private static final String APP_TRANSLATE_ICON = "yt_outline_experimental_translate_vd_theme_24";

    /** Icon for the romanize button, showing the pronunciation above each line. */
    private static final String APP_ROMANIZE_ICON = "yt_outline_experimental_waveform_vd_theme_24";

    private static final String REFRESH_ICON = "ic_mtrl_arrow_circle";

    /** Own icon, because the app ships no copy icon of its own. */
    private static final String COPY_ICON = "morphe_yt_copy_bold";

    /** Translation size relative to the lyrics line it belongs to (~70%). */
    static final float TRANSLATION_RELATIVE_SIZE = 0.70f;
    /** Per-word romanization size relative to the lyrics line it belongs to (~70%). */
    private static final float ROMAJI_RELATIVE_SIZE = 0.70f;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final DynamicBackgroundView dynamicBgView;
    private final ScrollView scrollView;
    private final LinearLayout linesContainer;
    private final TextView creditView;
    private final TextView footerView;
    @Nullable
    private TextView translateView;
    @Nullable
    private TextView romanizeView;
    /** Copy button, or {@code null} when hidden by settings. */
    @Nullable
    private TextView copyView;
    @Nullable
    private TextView refreshView;
    private final LinearLayout creditBlockContainer;
    private final LinearLayout footerContainer;
    private final LinearLayout buttonRow;
    private boolean creditLineActive = false;
    private float creditLookCurAlpha = 0.15f;
    private float creditLookCurScale = 0.93f;
    private float creditLookToAlpha = -1f;
    private float creditLookToScale = -1f;
    private float creditBlurRadius = 0f;
    @Nullable
    private ValueAnimator creditLookAnimator = null;
    @Nullable
    private ValueAnimator creditBlurAnimator = null;
    private final ProgressBar progressBar;
    private final LyricsScrollEngine scrollEngine;
    private boolean isDuetSong = false;

    /** One translated line per lyrics line, or {@code null} when showing the original only. */
    @Nullable
    private List<String> translatedLines;

    /** One romanized line per lyrics line, or {@code null} when not shown. */
    @Nullable
    private List<LyricsLine> romanizedLines;
    private boolean romanizedFromGoogle;
    /** When true, the translation shown came from Google (not the provider's native one). */
    private boolean translatedFromGoogle;
    private boolean translatedFromAI;
    private boolean romanizedFromAI;
    @Nullable
    private String aiModelName;
    @Nullable
    private String romanizedLocalDictionary;
    /** When true, romanization is carried per-word on each {@link Word} (rendered above each word). */
    private boolean perWordRomaji;
    /** URL to the song page on the provider's platform, opened when the source label is clicked. */
    @Nullable
    private String currentSourceUrl;
    /** When true, the next LOADED state was triggered by a refresh/cycle action. */
    private boolean refreshInProgress;
    private boolean translateInProgress;
    private boolean romanizeInProgress;

    private final ProgressBar topProgressBar;
    @Nullable
    private final TextView jumpToCurrentView;
    private final List<TextView> lineViews = new ArrayList<>();

    /** Wrapper holding the optional romanization line above each line of lyrics. */
    private final List<View> lineRows = new ArrayList<>();

    private final List<GapInterval> gapIntervals = new ArrayList<>();

    private static final long INTERVAL_MIN_GAP_MS = 7000L;

    private final List<GapLineView> gapLineViews = new ArrayList<>();
    private final SparseArray<GapLineView> gapLineViewsByLine = new SparseArray<>();

    private boolean isBrowsing;
    private boolean isProgrammaticScrolling;
    private int touchSlop = -1;
    private float touchDownX;
    private float touchDownY;
    private boolean isDraggingScroll;
    private boolean isUserTouchingScroll;
    private int highlightedIndex = -1;
    private int primaryActiveIndex = -1;
    private int previousPrimaryActiveIndex = -1;
    private long lastLivePosMs = 0L;
    private final Set<Integer> activeIndicesSet = new TreeSet<>();
    private final Set<Integer> previousActiveSet = new HashSet<>();
    private final Runnable resumeFollowRunnable = this::onResumeFollow;

    private static int getPrimaryChildHeight(@Nullable View row) {
        if (row == null) return 0;
        if (row instanceof ViewGroup vg && vg.getChildCount() > 0) {
            for (int c = 0; c < vg.getChildCount(); c++) {
                View child = vg.getChildAt(c);
                if (child instanceof LyricsLineView llv && llv.isSecondary()) {
                    continue;
                }
                if (child != null && child.getVisibility() != View.GONE && child.getHeight() > 0) {
                    return row.getPaddingTop() + child.getHeight() + row.getPaddingBottom();
                }
            }
            View first = vg.getChildAt(0);
            if (first != null && first.getVisibility() != View.GONE && first.getHeight() > 0) {
                return row.getPaddingTop() + first.getHeight() + row.getPaddingBottom();
            }
        }
        return row.getHeight();
    }

    // 1:1 with YouLyPlus (--lyrics-scroll-padding-top: 25%)
    private int getScrollAnchorPx() {
        final boolean isSynced = (lyrics != null && lyrics.synced());
        if (isSynced && scrollView != null && scrollView.getHeight() > 0) {
            return Math.max(Dim.dp(48), Math.round(scrollView.getHeight() * SCROLL_ANCHOR_RATIO));
        }
        return (linesContainer != null && linesContainer.getPaddingTop() > 0)
                ? linesContainer.getPaddingTop()
                : Dim.dp(48);
    }

    private int computeActiveGroupEndIndex(int targetIndex) {
        if (targetIndex < 0 || targetIndex >= lineRows.size()) {
            return targetIndex;
        }
        int runEnd = targetIndex;
        if (activeIndicesSet.size() > 1 && activeIndicesSet.contains(targetIndex)) {
            int scan = targetIndex + 1;
            while (scan < lineRows.size()) {
                if (isSecondaryLineView(scan)) {
                    scan++;
                    continue;
                }
                if (activeIndicesSet.contains(scan)) {
                    runEnd = scan;
                    scan++;
                } else {
                    break;
                }
            }
        }
        return runEnd;
    }

    private int computeBlockScrollTargetY(int targetIndex) {
        if (scrollView == null || scrollView.getHeight() <= 0 || targetIndex < 0) {
            return -1;
        }
        final int viewportH = scrollView.getHeight();
        final int anchorPx = getScrollAnchorPx();

        if (targetIndex == lineRows.size()) {
            if (creditBlockContainer == null || creditBlockContainer.getVisibility() == View.GONE
                    || creditBlockContainer.getHeight() <= 0) {
                return -1;
            }
            return Math.max(0, creditBlockContainer.getTop() - anchorPx);
        }
        if (targetIndex > lineRows.size()) {
            return -1;
        }

        View targetRow = lineRows.get(targetIndex);
        if (targetRow == null) return -1;
        if (targetIndex > 0 && (targetRow.getHeight() <= 0 || (targetRow.getTop() <= 0 && linesContainer.getHeight() <= 0))) {
            return -1;
        }

        int runStart = targetIndex;
        if (activeIndicesSet.size() > 1 && activeIndicesSet.contains(targetIndex)) {
            int scanBack = targetIndex - 1;
            while (scanBack >= 0) {
                if (isSecondaryLineView(scanBack)) {
                    scanBack--;
                    continue;
                }
                if (activeIndicesSet.contains(scanBack)) {
                    runStart = scanBack;
                    scanBack--;
                } else {
                    break;
                }
            }
        }
        int runEnd = computeActiveGroupEndIndex(targetIndex);

        int top = Integer.MAX_VALUE;
        int bottom = Integer.MIN_VALUE;
        View newestRow = null;
        int runCount = 0;
        View prevRow = null;
        for (int i = runStart; i <= runEnd && i < lineRows.size(); i++) {
            View row = lineRows.get(i);
            if (row == null || row == prevRow || row.getVisibility() == View.GONE) continue;
            prevRow = row;
            runCount++;
            int rTop = row.getTop();
            int rBottom = rTop + (row.getHeight() > 0 ? row.getHeight() : getPrimaryChildHeight(row));
            if (rTop < top) top = rTop;
            if (rBottom > bottom) bottom = rBottom;
            newestRow = row;
        }
        if (runCount <= 0 || top == Integer.MAX_VALUE) {
            return Math.max(0, targetRow.getTop() - anchorPx);
        }

        int span = bottom - top;
        int targetTop = top;
        int availableH = viewportH - anchorPx - Dim.dp(80);
        if (span > availableH && availableH > 0) {
            targetTop = bottom - availableH;
        }
        return Math.max(0, targetTop - anchorPx);
    }

    private void recomputeAndScroll(int referenceIndex) {
        if (scrollView == null || scrollView.getHeight() <= 0) return;
        if (creditLineActive || referenceIndex == lineRows.size()) {
            final int clamped = computeBlockScrollTargetY(lineRows.size());
            if (clamped < 0) return;
            lastScrollTarget = clamped;
            int refIdx = previousPrimaryActiveIndex >= 0 ? previousPrimaryActiveIndex : Math.max(0, lineRows.size() - 1);
            View refView = (refIdx >= 0 && refIdx < lineRows.size()) ? lineRows.get(refIdx) : creditBlockContainer;
            scrollEngine.scrollTo(clamped, creditBlockContainer, lineRows.size(), refView, refIdx, 350L, false);
            return;
        }
        int targetIdx = referenceIndex;
        if (targetIdx < 0 && primaryActiveIndex >= 0) {
            targetIdx = primaryActiveIndex;
        }
        if (targetIdx >= 0 && targetIdx < lineRows.size()) {
            View targetView = lineRows.get(targetIdx);
            int endIdx = computeActiveGroupEndIndex(targetIdx);
            View endView = (endIdx >= 0 && endIdx < lineRows.size()) ? lineRows.get(endIdx) : targetView;
            final int clamped = computeBlockScrollTargetY(targetIdx);
            if (clamped < 0) return;
            lastScrollTarget = clamped;
            int refIdx = previousPrimaryActiveIndex >= 0 ? previousPrimaryActiveIndex : targetIdx;
            View refView = (refIdx >= 0 && refIdx < lineRows.size()) ? lineRows.get(refIdx) : targetView;
            scrollEngine.scrollTo(clamped, targetView, targetIdx, endView, endIdx, refView, refIdx, 350L, 350L, false);
        }
    }

    private static void stopScrollViewFling(@Nullable ScrollView sv) {
        if (sv == null) return;
        try {
            Field scrollerField = ScrollView.class.getDeclaredField("mScroller");
            scrollerField.setAccessible(true);
            Object scroller = scrollerField.get(sv);
            if (scroller instanceof OverScroller os) {
                os.abortAnimation();
            }
        } catch (Throwable ignored) {
        }
    }

    private void onResumeFollow() {
        stopScrollViewFling(scrollView);
        if (scrollView != null) {
            scrollView.stopNestedScroll();
        }
        scrollEngine.reset();
        isBrowsing = false;
        userScrollUntilUptimeMs = 0;
        isDraggingScroll = false;
        isUserTouchingScroll = false;
        handler.removeCallbacks(resumeFollowRunnable);
        hideJumpToCurrentButton(true);

        long currentPos = LyricsManager.getInstance().getPositionMs();
        GapInterval activeGi = getActiveGapInterval(currentPos);
        boolean inGapBreak = (activeGi != null && currentPos < activeGi.endMs());
        if (lyrics != null && lyrics.synced()) {
            Set<Integer> activeSet = inGapBreak ? Collections.emptySet() : computeActiveLines(lyrics, currentPos);
            int active = inGapBreak ? -1 : computePrimaryActiveIndex(lyrics, activeSet, currentPos);
            if (active < 0 && activeGi != null) {
                active = activeGi.beforeLineIndex();
            }
            if (active < 0 && primaryActiveIndex >= 0) {
                active = primaryActiveIndex;
            }
            if (active < 0 && highlightedIndex >= 0) {
                active = highlightedIndex;
            }
            if (active < 0 && !lyrics.lines().isEmpty() && currentPos < lyrics.lines().get(0).startTimeMs()) {
                active = 0;
            }
            if (active < 0 && retimedLyrics != null && !retimedLyrics.isEmpty()) {
                int lastStarted = LyricsRetiming.findLastStartedLineIndex(retimedLyrics, currentPos);
                if (lastStarted >= 0) {
                    active = Math.min(lastStarted, lineRows.size() - 1);
                } else {
                    active = 0;
                }
            } else if (active < 0 && !lyrics.lines().isEmpty()) {
                for (int i = lyrics.lines().size() - 1; i >= 0; i--) {
                    if (currentPos >= lyrics.lines().get(i).startTimeMs()) {
                        active = i;
                        break;
                    }
                }
                if (active < 0) active = 0;
            }
            if (active >= 0) {
                lastScrollTarget = -1;
                previousPrimaryActiveIndex = primaryActiveIndex;
                if (activeSet.isEmpty() && !inGapBreak) {
                    activeSet = Collections.singleton(active);
                }
                activeIndicesSet.clear();
                activeIndicesSet.addAll(activeSet);
                highlightedIndex = active;
                primaryActiveIndex = active;
                updateLinesDepthOfField(activeIndicesSet, inGapBreak ? -1 : active, inGapBreak && activeGi != null ? activeGi.beforeLineIndex() : -1);
                GapLineView gv = inGapBreak && activeGi != null ? gapLineViewsByLine.get(activeGi.beforeLineIndex()) : null;
                if (gv != null) {
                    scrollToGapView(gv, false);
                } else {
                    scrollToActiveLine(active, false);
                }
                updateWordSync(currentPos);
            }
        } else if (highlightedIndex >= 0) {
            lastScrollTarget = -1;
            updateLinesDepthOfField(highlightedIndex);
            scrollToActiveLine(highlightedIndex, false);
        }
    }

    private void showJumpToCurrentButton() {
        if (jumpToCurrentView == null) return;
        jumpToCurrentView.bringToFront();
        jumpToCurrentView.animate().cancel();
        if (jumpToCurrentView.getVisibility() == VISIBLE && jumpToCurrentView.getAlpha() == 1f) return;
        jumpToCurrentView.setAlpha(0f);
        jumpToCurrentView.setVisibility(VISIBLE);
        jumpToCurrentView.animate().alpha(1f).setDuration(200).start();
    }

    private void hideJumpToCurrentButton() {
        hideJumpToCurrentButton(false);
    }

    private void hideJumpToCurrentButton(boolean instant) {
        if (jumpToCurrentView == null) return;
        jumpToCurrentView.animate().cancel();
        if (instant) {
            jumpToCurrentView.setAlpha(0f);
            jumpToCurrentView.setVisibility(GONE);
        } else {
            if (jumpToCurrentView.getVisibility() != VISIBLE) return;
            jumpToCurrentView.animate().alpha(0f).setDuration(200)
                    .withEndAction(() -> jumpToCurrentView.setVisibility(GONE)).start();
        }
    }

    private final List<List<WordTiming>> lineWordSpans = new ArrayList<>();
    private final List<Integer> lineOriginalStarts = new ArrayList<>();
    private final List<ForegroundColorSpan> lineUnsungSpans = new ArrayList<>();

    private static final ExecutorService LINE_BUILDER_EXECUTOR = Executors.newSingleThreadExecutor();

    private int buildGeneration;
    private boolean initialBuildComplete = false;

    private int lastWordLineIndex = -1;

    private int lastOverlayIndex = -1;

    private int pendingOldWordLineIndex = -1;

    private boolean seekPending;
    private long pendingSeekPosMs = -1L;
    private long pendingSeekUptimeMs = 0L;
    private int pendingSeekMainIndex = -1;

    /** Floating ruler for temporary offset adjustment via horizontal swipe. */
    private OffsetRulerView offsetRulerView;
    private GestureDetector offsetGestureDetector;
    private boolean isOffsetAdjusting;
    private float offsetSwipeStartX;
    private int offsetSwipeStartMs;
    private final Runnable hideOffsetRunnable = this::hideOffsetRuler;

    private boolean sbRecording;
    private long sbRecordStartMs;
    @Nullable
    private String sbRecordVideoId;

    private final Runnable recordTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!sbRecording) {
                return;
            }
            final LyricsManager manager = LyricsManager.getInstance();
            final long pos = manager.getVideoPositionMs();
            offsetRulerView.setRecordTimeMs(pos);
            handler.postDelayed(this, 100);
        }
    };

    private record BuildResult(Spannable text, @Nullable ForegroundColorSpan unsungSpan,
            int transStart, int transEnd, int romaStart, int romaEnd) {
    }


    private GapInterval getActiveGapInterval(long currentTime) {
        if (lyrics == null || !lyrics.synced() || gapIntervals.isEmpty()) {
            return null;
        }
        for (GapInterval gi : gapIntervals) {
            long startCheck = (gi.beforeLineIndex() == 0) ? gi.startMs() : (gi.startMs() - LyricsRetiming.HIGHLIGHT_LOOK_AHEAD_MS);
            if (currentTime >= startCheck && currentTime < gi.nextStartMs()) {
                return gi;
            }
        }
        return null;
    }

    private boolean shouldShowIntervalIndicator(long currentTime) {
        return getActiveGapInterval(currentTime) != null;
    }

    private void scrollToGapView(@Nullable GapLineView gv) {
        scrollToGapView(gv, false);
    }

    private void scrollToGapView(@Nullable GapLineView gv, boolean forceInstant) {
        if (gv != null && scrollView != null && scrollView.getHeight() > 0) {
            if (gv.getHeight() == 0 && gv.getTop() == 0) {
                gv.post(() -> scrollToGapView(gv, forceInstant));
                return;
            }
            final int target = (gv.getInterval().beforeLineIndex() == 0)
                    ? 0
                    : Math.max(0, gv.getTop() - getScrollAnchorPx());
            final int clamped = Math.max(0, target);
            lastScrollTarget = clamped;
            int idx = -1;
            for (int i = 0; i < lineRows.size(); i++) {
                if (lineRows.get(i).getTop() >= gv.getTop()) {
                    idx = i;
                    break;
                }
            }
            if (idx == -1) idx = 0;
            int refIdx = previousPrimaryActiveIndex >= 0 ? previousPrimaryActiveIndex : idx;
            View refView = (refIdx >= 0 && refIdx < lineRows.size()) ? lineRows.get(refIdx) : gv;
            scrollEngine.scrollTo(clamped, gv, idx, refView, refIdx, 350L, forceInstant);
        }
    }

    /**
     * Instrumental gap indicator line:
     * View inside linesContainer inserted before the target line that animates
     * expanding dots and collapses before the upcoming line begins.
     */


    @Nullable
    private Lyrics lyrics;

    private List<LyricsRetiming.RetimedEntry> retimedLyrics = Collections.emptyList();
    private long[] retimedAdjustedEndMax = new long[0];

    private boolean wordSyncWasEnabled = true;

    /** Whether this panel should currently cover the built-in content. */
    private boolean overlayVisible;

    private boolean cachedLyricsPanelOpen;
    private boolean cachedOtherPanelOpen;
    private long overlayCacheUptimeMs;

    /** Built-in views hidden by this panel, so that only what was hidden is shown again. */
    private final Set<View> hiddenSiblings = new HashSet<>();

    /** Suppresses auto scrolling for a while after the user scrolls manually. */
    private long userScrollUntilUptimeMs;

    /** Last scroll target to avoid redundant smoothScrollTo calls. */
    private int lastScrollTarget = -1;

    private long cachedTickInterval = 16;
    @Nullable
    private String cachedRefreshRateSetting;

    private long computeTickInterval() {
        String rate = SharedYouTubeSettings.APP_REFRESH_RATE.get();
        if (Objects.equals(rate, cachedRefreshRateSetting)) {
            return cachedTickInterval;
        }
        cachedRefreshRateSetting = rate;
        long interval;
        if ("DEFAULT".equals(rate)) {
            float deviceRate = 0f;
            try {
                android.view.Display display = getDisplay();
                if (display != null) {
                    deviceRate = display.getRefreshRate();
                }
            } catch (Exception ex) {
                Logger.printDebug(() -> "Could not get display refresh rate", ex);
            }
            interval = deviceRate > 0f ? Math.round(1000f / deviceRate) : 16;
        } else {
            try {
                int fps = Integer.parseInt(rate);
                interval = fps > 0 ? Math.round(1000f / fps) : 16;
            } catch (NumberFormatException ex) {
                Logger.printDebug(() -> "Could not parse refresh rate setting", ex);
                interval = 16;
            }
        }
        cachedTickInterval = interval;
        return interval;
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            try {
                updateHighlight();
                long currentPos = lastLivePosMs > 0 ? lastLivePosMs : LyricsManager.getInstance().getPositionMs();
                updateWordSync(currentPos);
                updateOffscreenCulling();

                if (shouldShowIntervalIndicator(currentPos)) {
                    postInvalidateOnAnimation();
                }

                // The app restores its own panel content asynchronously, and switching
                // to another engagement panel gives no lyrics state change to react to,
                // so the wanted state is reapplied on every tick rather than on changes.
                // The cached answer is kept here, since ticks carry no news of their own.
                applyOverlayVisibility();
            } catch (Exception ex) {
                Logger.printDebug(() -> "Could not update lyrics panel view", ex);
            }
            handler.postDelayed(this, computeTickInterval());
        }
    };

    public LyricsPanelView(Context context) {
        super(context);
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        final int horizontalPadding = Dim.dp24;
        final int topPadding = Dim.dp(40);
        final int bottomPadding = Dim.dp(32);

        dynamicBgView = new DynamicBackgroundView(context);
        addView(dynamicBgView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        linesContainer = new LinearLayout(context);
        linesContainer.setOrientation(LinearLayout.VERTICAL);
        linesContainer.setPadding(horizontalPadding, topPadding, horizontalPadding, bottomPadding);
        linesContainer.setClipChildren(false);
        linesContainer.setClipToPadding(false);

        footerView = new TextView(context);
        applyFooterStyle(footerView);
        footerView.setVisibility(GONE);

        // Same order as the buttons the app draws under its own lyrics.
        buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        LayoutTransition buttonTransition = new LayoutTransition();
        buttonTransition.enableTransitionType(LayoutTransition.CHANGING);
        buttonTransition.setDuration(LayoutTransition.CHANGING, BUTTON_STATE_FADE_MILLISECONDS);
        // Without this the panel around the row animates along with the buttons.
        buttonTransition.setAnimateParentHierarchy(false);
        buttonRow.setLayoutTransition(buttonTransition);
        buttonRow.setVisibility(GONE);

        createToolbarButtons();

        creditBlockContainer = new LinearLayout(context);
        creditBlockContainer.setOrientation(LinearLayout.VERTICAL);
        creditBlockContainer.setPadding(Dim.dp16, Dim.dp(6), Dim.dp16, Dim.dp(6));
        creditBlockContainer.setClipChildren(false);
        creditBlockContainer.setClipToPadding(false);
        creditBlockContainer.setVisibility(GONE);

        creditView = new TextView(context);
        applyFooterStyle(creditView);
        creditView.setLineSpacing(Dim.dp(2), 1.18f);
        creditView.setVisibility(GONE);
        creditView.setOnLongClickListener(v -> {
            CharSequence text = creditView.getText();
            //noinspection SizeReplaceableByIsEmpty
            if (text != null && text.length() > 0) {
                ClipboardManager clipboard = (ClipboardManager) getContext()
                        .getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("songwriters", text.toString()));
                    Utils.showToastShort(str("morphe_music_lyrics_copied"));
                }
            }
            return true;
        });
        LinearLayout.LayoutParams creditParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        creditParams.bottomMargin = Dim.dp(4);
        creditBlockContainer.addView(creditView, creditParams);

        creditBlockContainer.addView(footerView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams creditBlockParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        creditBlockParams.topMargin = Dim.dp(4);
        creditBlockParams.bottomMargin = Dim.dp(26);
        linesContainer.addView(creditBlockContainer, creditBlockParams);

        // Empty bottom spacer container after creditBlockContainer so the credit block
        // can scroll up to the 25% active anchor position just like a lyric line.
        footerContainer = new LinearLayout(context);
        footerContainer.setOrientation(LinearLayout.VERTICAL);
        footerContainer.setPadding(0, Dim.dp24, 0, Dim.dp(340));

        linesContainer.addView(footerContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        scrollView = new ScrollView(context) {
            @Override
            protected float getTopFadingEdgeStrength() {
                return 0.0f;
            }
        };
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setVerticalFadingEdgeEnabled(false);
        scrollView.setFadingEdgeLength(0);
        scrollView.setClipChildren(false);
        scrollView.setClipToPadding(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            scrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                updateOffscreenCulling();
                if (isProgrammaticScrolling) {
                    return;
                }
                if (!isDraggingScroll && !isUserTouchingScroll) {
                    return;
                }
                userScrollUntilUptimeMs = SystemClock.uptimeMillis() + MANUAL_SCROLL_PAUSE_MILLISECONDS;
                if (!isBrowsing) {
                    isBrowsing = true;
                    showJumpToCurrentButton();
                    updateLinesDepthOfField(activeIndicesSet, primaryActiveIndex, -1);
                }
                handler.removeCallbacks(resumeFollowRunnable);
                handler.postDelayed(resumeFollowRunnable, MANUAL_SCROLL_PAUSE_MILLISECONDS);
            });
        }
        scrollView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top != oldBottom - oldTop) {
                updateLinesContainerPadding();
            }
        });
        scrollView.addView(linesContainer, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT));
        addView(scrollView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        scrollEngine = new LyricsScrollEngine(scrollView, linesContainer, footerContainer,
                new LyricsScrollEngine.Callback() {
                    @Override
                    public boolean isUserScrolling() {
                        return isBrowsing || isDraggingScroll || isUserTouchingScroll
                                || SystemClock.uptimeMillis() < userScrollUntilUptimeMs;
                    }

                    @Override
                    public void setProgrammaticScrolling(boolean isProgrammatic) {
                        isProgrammaticScrolling = isProgrammatic;
                    }

                    @Override
                    public void onRecomputeAndScroll(int referenceIndex) {
                        recomputeAndScroll(referenceIndex);
                    }

                    @Override
                    public int computeReflowScrollTarget(@Nullable View targetRow) {
                        if (scrollView == null || scrollView.getHeight() <= 0) return -1;
                        if (targetRow instanceof GapLineView gv) {
                            return Math.max(0, gv.getTop() - getScrollAnchorPx());
                        }
                        if (targetRow == creditBlockContainer || creditLineActive) {
                            return computeBlockScrollTargetY(lineRows.size());
                        }
                        int idx = (targetRow != null) ? lineRows.indexOf(targetRow) : -1;
                        if (idx < 0) idx = primaryActiveIndex;
                        return (idx >= 0) ? computeBlockScrollTargetY(idx) : -1;
                    }
                });

        progressBar = new ProgressBar(context);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(GONE);
        addView(progressBar, new LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        // Added last, and outside the scroll view, so the buttons stay pinned at the
        // bottom while the lyrics scroll behind them, the way the app does it.
        LayoutParams buttonRowParams = new LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        buttonRowParams.bottomMargin = Dim.dp40;
        addView(buttonRow, buttonRowParams);

        topProgressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        topProgressBar.setIndeterminate(true);
        topProgressBar.setVisibility(GONE);
        LayoutParams topPbParams = new LayoutParams(
                LayoutParams.MATCH_PARENT, Dim.dp(3), Gravity.TOP);
        addView(topProgressBar, topPbParams);

        jumpToCurrentView = new TextView(context);
        applyButtonStyle(jumpToCurrentView, "yt_outline_experimental_chevron_down_vd_theme_24");
        jumpToCurrentView.setText("Current");
        jumpToCurrentView.setVisibility(GONE);
        jumpToCurrentView.setElevation(Dim.dp(8));
        jumpToCurrentView.setOnClickListener(v -> onResumeFollow());

        LayoutParams jumpParams = new LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        jumpParams.bottomMargin = Dim.dp(96);
        jumpParams.rightMargin = Dim.dp16;
        addView(jumpToCurrentView, jumpParams);
        jumpToCurrentView.bringToFront();

        offsetRulerView = new OffsetRulerView(context);
        LayoutParams rulerParams = new LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        rulerParams.bottomMargin = Dim.dp8;
        addView(offsetRulerView, rulerParams);
        offsetRulerView.setOnClickListener(view -> onOffsetLabelClicked());
        offsetRulerView.setOnLongClickListener(view -> {
            OffsetRulerView.showOffsetEditorDialog(getContext(), () -> {
                offsetRulerView.setOffsetMs(LyricsManager.getInstance().getTemporaryOffsetMs());
                updateHighlight();
            });
            return true;
        });

        offsetGestureDetector = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onScroll(@NonNull MotionEvent e1, @Nullable MotionEvent e2,
                            float distanceX, float distanceY) {
                        if (e2 == null) {
                            return false;
                        }
                        if (isOffsetAdjusting) {
                            float dx = e2.getX() - offsetSwipeStartX;
                            int deltaMs = Math.round(-dx / getResources().getDisplayMetrics().density) * 10;
                            int newMs = Math.max(-20000, Math.min(20000, offsetSwipeStartMs + deltaMs));
                            LyricsManager.getInstance().setTemporaryOffsetMs(newMs);
                            offsetRulerView.setOffsetMs(newMs);
                            scheduleHideOffsetRuler();
                            return true;
                        }
                        float density = getResources().getDisplayMetrics().density;
                        float touchY = e1.getY();
                        boolean inButtonArea = buttonRow.getVisibility() == VISIBLE
                                && touchY >= buttonRow.getTop() - Dim.dp8
                                && touchY <= buttonRow.getBottom() + Dim.dp8;
                        if (inButtonArea
                                && Math.abs(distanceX) > Math.abs(distanceY) * 1.5
                                && Math.abs(distanceX) > 15 * density) {
                            isOffsetAdjusting = true;
                            offsetSwipeStartX = e1.getX();
                            offsetSwipeStartMs = LyricsManager.getInstance().getTemporaryOffsetMs();
                            showOffsetRuler();
                            return true;
                        }
                        return false;
                    }
                });
    }

    private void updateLinesContainerPadding() {
        if (linesContainer == null) return;
        final boolean isSynced = (lyrics != null && lyrics.synced());
        final int viewportH = (scrollView != null) ? scrollView.getHeight() : 0;
        final int top = (isSynced && viewportH > 0)
                ? Math.max(Dim.dp(48), Math.round(viewportH * SCROLL_ANCHOR_RATIO))
                : Dim.dp(40);
        final int bottom = Dim.dp(32);
        if (linesContainer.getPaddingTop() != top || linesContainer.getPaddingBottom() != bottom) {
            linesContainer.setPadding(Dim.dp24, top, Dim.dp24, bottom);
        }
        if (footerContainer != null && viewportH > 0) {
            final int footerBottom = Math.max(Dim.dp(240), viewportH);
            if (footerContainer.getPaddingBottom() != footerBottom) {
                footerContainer.setPadding(0, Dim.dp24, 0, footerBottom);
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (h > 0) {
            updateLinesContainerPadding();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
    }

    private boolean isTouchInsideView(View v, MotionEvent event, int extraPadding) {
        if (v == null || v.getVisibility() != VISIBLE) {
            return false;
        }
        float x = event.getX();
        float y = event.getY();
        return x >= (v.getLeft() - extraPadding) && x <= (v.getRight() + extraPadding)
                && y >= (v.getTop() - extraPadding) && y <= (v.getBottom() + extraPadding);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (isTouchInsideView(jumpToCurrentView, event, Dim.dp16)
                || isTouchInsideView(buttonRow, event, Dim.dp8)) {
            return false;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = event.getX();
                touchDownY = event.getY();
                isDraggingScroll = false;
                isUserTouchingScroll = true;
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(event.getX() - touchDownX);
                float dy = Math.abs(event.getY() - touchDownY);
                int slop = (touchSlop > 0) ? touchSlop : Dim.dp8;
                if (dy > slop && dy > dx * 1.2f) {
                    isDraggingScroll = true;
                    userScrollUntilUptimeMs = SystemClock.uptimeMillis() + MANUAL_SCROLL_PAUSE_MILLISECONDS;
                    lastScrollTarget = -1;
                    if (!isBrowsing) {
                        isBrowsing = true;
                        showJumpToCurrentButton();
                        updateLinesDepthOfField(activeIndicesSet, primaryActiveIndex, -1);
                    }
                    handler.removeCallbacks(resumeFollowRunnable);
                    handler.postDelayed(resumeFollowRunnable, MANUAL_SCROLL_PAUSE_MILLISECONDS);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isDraggingScroll = false;
                isUserTouchingScroll = false;
                isOffsetAdjusting = false;
                break;
        }

        offsetGestureDetector.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            isOffsetAdjusting = false;
        }
        return isOffsetAdjusting || super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (isTouchInsideView(jumpToCurrentView, event, Dim.dp16)
                || isTouchInsideView(buttonRow, event, Dim.dp8)) {
            return super.onTouchEvent(event);
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = event.getX();
                touchDownY = event.getY();
                isDraggingScroll = false;
                isUserTouchingScroll = true;
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(event.getX() - touchDownX);
                float dy = Math.abs(event.getY() - touchDownY);
                int slop = (touchSlop > 0) ? touchSlop : Dim.dp8;
                if (dy > slop && dy > dx * 1.2f) {
                    isDraggingScroll = true;
                    userScrollUntilUptimeMs = SystemClock.uptimeMillis() + MANUAL_SCROLL_PAUSE_MILLISECONDS;
                    lastScrollTarget = -1;
                    if (!isBrowsing) {
                        isBrowsing = true;
                        showJumpToCurrentButton();
                        updateLinesDepthOfField(activeIndicesSet, primaryActiveIndex, -1);
                    }
                    handler.removeCallbacks(resumeFollowRunnable);
                    handler.postDelayed(resumeFollowRunnable, MANUAL_SCROLL_PAUSE_MILLISECONDS);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isDraggingScroll = false;
                isUserTouchingScroll = false;
                break;
        }

        if (isOffsetAdjusting) {
            offsetGestureDetector.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                isOffsetAdjusting = false;
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

    private void showOffsetRuler() {
        handler.removeCallbacks(hideOffsetRunnable);
        offsetRulerView.setOffsetMs(LyricsManager.getInstance().getTemporaryOffsetMs());
        if (offsetRulerView.getVisibility() != VISIBLE) {
            offsetRulerView.setAlpha(0f);
            offsetRulerView.setVisibility(VISIBLE);
            offsetRulerView.animate().alpha(1f).setDuration(200).start();
        }
    }

    private void hideOffsetRuler() {
        if (offsetRulerView.getVisibility() != VISIBLE) return;
        offsetRulerView.animate().alpha(0f).setDuration(200).withEndAction(() ->
                offsetRulerView.setVisibility(GONE)).start();
    }

    private void scheduleHideOffsetRuler() {
        handler.removeCallbacks(hideOffsetRunnable);
        handler.postDelayed(hideOffsetRunnable, 2000);
    }

    private void onOffsetLabelClicked() {
        final LyricsManager manager = LyricsManager.getInstance();
        if (sbRecording) {
            final long endMs = manager.getVideoPositionMs();
            final String videoId = sbRecordVideoId;
            stopSbRecording();
            scheduleHideOffsetRuler();
            if (videoId == null || !Settings.LYRICS_SB_MATCHING.get()) {
                return;
            }
            long startMs = sbRecordStartMs;
            long stopMs = endMs;
            if (stopMs < startMs) {
                final long swap = startMs;
                startMs = stopMs;
                stopMs = swap;
            }
            final Context context = getContext();
            if (context == null) {
                return;
            }
            SponsorBlockMusicSubmitDialog.show(context, videoId, startMs, stopMs,
                    offsetRulerView::setOffsetMs, this::scheduleHideOffsetRuler);
            return;
        }
        if (!Settings.LYRICS_SB_MATCHING.get()) {
            return;
        }
        final String videoId = manager.skipSegmentsVideoId();
        if (videoId == null) {
            Utils.showToastShort(str("morphe_music_lyrics_sb_no_video"));
            return;
        }
        sbRecording = true;
        sbRecordStartMs = manager.getVideoPositionMs();
        sbRecordVideoId = videoId;
        handler.removeCallbacks(hideOffsetRunnable);
        handler.removeCallbacks(recordTickRunnable);
        offsetRulerView.setRecordTimeMs(sbRecordStartMs);
        handler.post(recordTickRunnable);
    }

    private void stopSbRecording() {
        if (!sbRecording) {
            return;
        }
        sbRecording = false;
        sbRecordVideoId = null;
        handler.removeCallbacks(recordTickRunnable);
        offsetRulerView.setRecordTimeMs(null);
    }

    private void createToolbarButtons() {
        final Context context = getContext();
        if (Settings.LYRICS_SHOW_COPY_BUTTON.get()) {
            copyView = new TextView(context);
            applyButtonStyle(copyView, COPY_ICON);
            copyView.setOnClickListener(view -> onCopyClicked());
            copyView.setOnLongClickListener(view -> {
                onCopyLongPressed();
                return true;
            });
            buttonRow.addView(copyView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        } else {
            copyView = null;
        }

        if (Settings.LYRICS_SHOW_TRANSLATE_BUTTON.get()) {
            translateView = new TextView(context);
            applyButtonStyle(translateView, APP_TRANSLATE_ICON);
            translateView.setOnClickListener(view -> onTranslateClicked());
            LinearLayout.LayoutParams translateParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            translateParams.setMarginStart(Dim.dp12);
            buttonRow.addView(translateView, translateParams);
        } else {
            translateView = null;
        }

        if (Settings.LYRICS_SHOW_ROMANIZE_BUTTON.get()) {
            romanizeView = new TextView(context);
            applyButtonStyle(romanizeView, APP_ROMANIZE_ICON);
            romanizeView.setOnClickListener(view -> onRomanizeClicked());
            LinearLayout.LayoutParams romanizeParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            romanizeParams.setMarginStart(Dim.dp12);
            buttonRow.addView(romanizeView, romanizeParams);
        } else {
            romanizeView = null;
        }

        if (Settings.LYRICS_SHOW_REFRESH_BUTTON.get()) {
            refreshView = new TextView(context);
            applyButtonStyle(refreshView, REFRESH_ICON);
            refreshView.setOnClickListener(view -> onRefreshClicked());
            refreshView.setOnLongClickListener(view -> {
                onRefreshLongPressed();
                return true;
            });
            LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            refreshParams.setMarginStart(Dim.dp12);
            buttonRow.addView(refreshView, refreshParams);
        } else {
            refreshView = null;
        }

        TextView settingsBtn = new TextView(context);
        applyButtonStyle(settingsBtn, null);
        settingsBtn.setText("⚙");
        settingsBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        settingsBtn.setPadding(Dim.dp12, Dim.dp6, Dim.dp12, Dim.dp6);
        settingsBtn.setOnClickListener(view -> showLyricsQuickSettingsDialog());
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        settingsParams.setMarginStart(Dim.dp12);
        buttonRow.addView(settingsBtn, settingsParams);
    }

    public void applyToolbarButtonSettings() {
        buttonRow.removeAllViews();
        createToolbarButtons();
        updateTranslateLabel();
        updateRomanizeLabel();
        updateRefreshLabel();
    }

    public void applyTextSize() {
        final int textSize = Settings.LYRICS_TEXT_SIZE.get();
        for (TextView lineView : lineViews) {
            lineView.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize);
        }
    }

    @SuppressWarnings("SizeReplaceableByIsEmpty")
    public void applyHideInfo() {
        final CharSequence info = creditView.getText();
        final boolean visible = !Settings.LYRICS_HIDE_INFO.get()
                && info != null && info.length() > 0;
        creditView.setVisibility(visible ? VISIBLE : GONE);
    }

    public void applyDynamicBackgroundSettings() {
        applyOverlayVisibility();
        dynamicBgView.onSettingsChanged();
    }

    public void applyVisualAndScrollSettings() {
        updateOffscreenCulling();
        updateLinesDepthOfField(activeIndicesSet, primaryActiveIndex, -1);
        dynamicBgView.onSettingsChanged();
        for (TextView tv : lineViews) {
            if (tv != null) {
                tv.invalidate();
            }
        }
    }

    public void updateOffscreenCulling() {
        if (lineViews.isEmpty()) return;
        final boolean hideOffscreen = Settings.LYRICS_HIDE_OFFSCREEN.get();
        if (!hideOffscreen || scrollView == null || scrollView.getHeight() <= 0
                || linesContainer == null || linesContainer.getHeight() <= 0) {
            for (int i = 0; i < lineViews.size(); i++) {
                TextView tv = lineViews.get(i);
                if (tv instanceof LyricsLineView llv && llv.isOffscreenHidden()) {
                    llv.setOffscreenHidden(false);
                }
            }
            return;
        }
        final int scrollY = scrollView.getScrollY();
        final int viewportH = scrollView.getHeight();
        final int marginPx = Dim.dp(200);
        final float minVisibleY = scrollY - marginPx;
        final float maxVisibleY = scrollY + viewportH + marginPx;

        for (int i = 0; i < lineViews.size(); i++) {
            TextView tv = lineViews.get(i);
            if (!(tv instanceof LyricsLineView llv)) continue;
            View row = (i < lineRows.size()) ? lineRows.get(i) : llv;
            if (row == null || row.getHeight() <= 0 || row.getVisibility() == GONE) {
                if (llv.isOffscreenHidden()) {
                    llv.setOffscreenHidden(false);
                }
                continue;
            }
            float transY = row.getTranslationY();
            float rowTop = row.getTop() + Math.min(0f, transY);
            float rowBottom = row.getBottom() + Math.max(0f, transY);
            boolean isOffscreen = (rowBottom < minVisibleY) || (rowTop > maxVisibleY);
            llv.setOffscreenHidden(isOffscreen);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        LyricsManager.getInstance().addListener(this);
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        LyricsManager.getInstance().removeListener(this);
        handler.removeCallbacksAndMessages(null);
        handler.removeCallbacks(resumeFollowRunnable);
        stopSbRecording();
        LyricsManager.getInstance().resetTemporaryOffsetMs();
        offsetRulerView.setVisibility(GONE);
        if (!LyricsPanelInstaller.isOtherPanelForeground()) {
            restoreHiddenSiblings();
        }
    }

    @Override
    public void onLyricsChanged(LyricsManager.State state, @Nullable Lyrics newLyrics) {
        try {
            if (state == LyricsManager.State.LOADING && newLyrics == lyrics
                    && newLyrics != null && !newLyrics.isEmpty()) {
                return;
            }
            lyrics = newLyrics;
            highlightedIndex = -1;
            primaryActiveIndex = -1;
            previousPrimaryActiveIndex = -1;
            activeIndicesSet.clear();
            previousActiveSet.clear();
            pendingOldWordLineIndex = -1;
            lastWordLineIndex = -1;
            lastOverlayIndex = -1;
            lastScrollTarget = -1;
            lastLivePosMs = 0L;
            seekPending = true;
            userScrollUntilUptimeMs = 0;
            isBrowsing = false;
            handler.removeCallbacks(resumeFollowRunnable);
            hideJumpToCurrentButton(true);
            LyricsManager.getInstance().resetTemporaryOffsetMs();
            stopSbRecording();
            hideOffsetRuler();
            isOffsetAdjusting = false;
            translatedLines = null;
            romanizedLines = null;
            romanizedFromGoogle = false;
            translatedFromGoogle = false;
            translatedFromAI = false;
            romanizedFromAI = false;
            aiModelName = null;
            romanizedLocalDictionary = null;
            perWordRomaji = false;
            translateInProgress = false;
            romanizeInProgress = false;
            updateTranslateLabel();
            updateRomanizeLabel();

            switch (state) {
                case LOADING:
                    TrackInfo loadingTrack = LyricsManager.getInstance().getCurrentTrack();
                    Bitmap loadingArt = LyricsManager.getInstance().getCurrentArtworkBitmap();
                    dynamicBgView.setArtwork(loadingArt, VideoInformation.getVideoId(),
                            loadingTrack != null ? loadingTrack.title() : null,
                            loadingTrack != null ? loadingTrack.artist() : null);
                    if (lyrics != null && !lyrics.isEmpty()) {
                        if (topProgressBar != null) {
                            topProgressBar.setVisibility(VISIBLE);
                        }
                    } else {
                        showLoading();
                    }
                    setOverlayVisible(true);
                    break;
                case LOADED:
                    if (topProgressBar != null) {
                        topProgressBar.setVisibility(GONE);
                    }
                    if (newLyrics == null || newLyrics.isEmpty()) {
                        setOverlayVisible(false);
                        if (refreshInProgress) {
                            refreshInProgress = false;
                            updateRefreshLabel();
                        }
                    } else {
                        TrackInfo track = LyricsManager.getInstance().getCurrentTrack();
                        Bitmap art = LyricsManager.getInstance().getCurrentArtworkBitmap();
                        dynamicBgView.setArtwork(art, VideoInformation.getVideoId(),
                                track != null ? track.title() : null,
                                track != null ? track.artist() : null);

                        showLyrics(newLyrics);
                        setOverlayVisible(true);
                        if (Settings.LYRICS_TRANSLATE.get()) {
                            requestTranslation(newLyrics);
                        }
                        if (Settings.LYRICS_ROMANIZE.get()) {
                            requestRomanization(newLyrics);
                        }
                        if (refreshInProgress) {
                            refreshInProgress = false;
                            setButtonLabel(refreshView, str("morphe_music_lyrics_refreshed"), true);
                            handler.postDelayed(this::updateRefreshLabel, 1500);
                        }
                    }
                    break;
                case NOT_FOUND:
                case ERROR:
                case IDLE:
                default:
                    if (topProgressBar != null) {
                        topProgressBar.setVisibility(GONE);
                    }
                    if (lyrics != null && !lyrics.isEmpty()) {
                        setOverlayVisible(true);
                        if (refreshInProgress) {
                            refreshInProgress = false;
                            updateRefreshLabel();
                        }
                        return;
                    }
                    lyrics = null;
                    clearLines();
                    setOverlayVisible(false);
                    if (refreshInProgress) {
                        refreshInProgress = false;
                        updateRefreshLabel();
                    }
                    break;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onLyricsChanged failure", ex);
        }
    }

    /** Hides the built-in content along with showing this panel, so the two texts never overlap. */
    private void setOverlayVisible(boolean visible) {
        overlayVisible = visible;
        applyOverlayVisibility();
    }

    /**
     * Reapplies the wanted state, because reopening the panel makes the app restore
     * its own content, and opening another engagement panel makes it take the same
     * container over, neither of which is a lyrics state change to react to.
     *
     * <p>Called when the panel on screen has just changed, so the cached answer from
     * before the change would keep the built-in lyrics visible until it expires.
     */
    public void syncOverlay() {
        overlayCacheUptimeMs = 0;
        applyOverlayVisibility();
    }

    private void applyOverlayVisibility() {
        final long now = SystemClock.uptimeMillis();
        if (now - overlayCacheUptimeMs > OVERLAY_CACHE_TTL_MS) {
            overlayCacheUptimeMs = now;
            cachedLyricsPanelOpen = LyricsPanelInstaller.isLyricsPanelOpen();
            cachedOtherPanelOpen = LyricsPanelInstaller.isOtherPanelForeground();
        }

        if (cachedOtherPanelOpen) {
            if (getParent() instanceof ViewGroup parent) {
                parent.removeView(this);
            }
            setVisibility(GONE);
            dynamicBgView.setVisibility(GONE);
            return;
        }

        final boolean visible = overlayVisible && cachedLyricsPanelOpen;
        final boolean wasVisible = getVisibility() == VISIBLE;
        setVisibility(visible ? VISIBLE : GONE);
        final boolean bgVisible = visible && Settings.LYRICS_DYNAMIC_BACKGROUND.get();
        dynamicBgView.setVisibility(bgVisible ? VISIBLE : GONE);

        if (visible && !wasVisible) {
            animate().cancel();
            setAlpha(0f);
            animate().alpha(1f).setDuration(OVERLAY_FADE_DURATION_MILLISECONDS).start();
        }

        if (!(getParent() instanceof ViewGroup parent)) {
            return;
        }

        if (!visible) {
            restoreHiddenSiblings();
            return;
        }

        for (int i = 0; i < parent.getChildCount(); i++) {
            View sibling = parent.getChildAt(i);
            if (sibling == this) {
                continue;
            }
            if (sibling.getVisibility() != GONE) {
                sibling.setVisibility(GONE);
                hiddenSiblings.add(sibling);
            }
        }
    }

    /**
     * Shows the built-in views this panel hid, and only those, so that views the app
     * hides on its own and the content of a panel that took the container over are
     * left the way the app left them.
     */
    private void restoreHiddenSiblings() {
        for (View sibling : hiddenSiblings) {
            sibling.setVisibility(VISIBLE);
        }
        hiddenSiblings.clear();
    }

    private void showLoading() {
        clearLines();
        creditBlockContainer.setVisibility(GONE);
        footerContainer.setVisibility(GONE);
        buttonRow.setVisibility(GONE);
        scrollView.setVisibility(GONE);
        progressBar.setVisibility(VISIBLE);
    }

    private static boolean isSectionHeader(@Nullable String text) {
        if (text == null) return false;
        String t = text.trim();
        return t.startsWith("[") && t.endsWith("]") && t.length() > 2;
    }

    private static Lyrics resolveDuetIfApplicable(Lyrics lyrics, @Nullable TrackInfo track) {
        if (lyrics == null || lyrics.lines() == null || lyrics.lines().isEmpty()) {
            return lyrics;
        }
        if (lyrics.lines().stream().anyMatch(LyricsLine::isDuet)) {
            return lyrics;
        }

        // check if line already got agentId
        String firstAgent = null;
        boolean hasMultipleAgents = false;
        for (LyricsLine l : lyrics.lines()) {
            String a = l.agentId();
            if (a != null && !a.isEmpty() && !"bg".equalsIgnoreCase(a) && !"v1000".equalsIgnoreCase(a)
                    && !"group".equalsIgnoreCase(a) && !"duet".equalsIgnoreCase(a) && !"all".equalsIgnoreCase(a)) {
                if (firstAgent == null) {
                    firstAgent = a;
                } else if (!firstAgent.equalsIgnoreCase(a)) {
                    hasMultipleAgents = true;
                    break;
                }
            }
        }

        if (hasMultipleAgents) {
            List<LyricsLine> resolved = new ArrayList<>(lyrics.lines().size());
            String lastPersonAgent = null;
            boolean lastPersonIsDuet = false;
            for (LyricsLine orig : lyrics.lines()) {
                String a = orig.agentId();
                if (a == null || a.isEmpty() || "bg".equalsIgnoreCase(a) || "v1000".equalsIgnoreCase(a)
                        || "group".equalsIgnoreCase(a) || "duet".equalsIgnoreCase(a) || "all".equalsIgnoreCase(a)) {
                    resolved.add(orig);
                    continue;
                }
                boolean isDuet;
                if ("v1".equalsIgnoreCase(a) || "m".equalsIgnoreCase(a) || "male".equalsIgnoreCase(a)) {
                    isDuet = false;
                    lastPersonAgent = a;
                    lastPersonIsDuet = false;
                } else if ("v2".equalsIgnoreCase(a) || "f".equalsIgnoreCase(a) || "female".equalsIgnoreCase(a)) {
                    isDuet = true;
                    lastPersonAgent = a;
                    lastPersonIsDuet = true;
                } else if (lastPersonAgent == null) {
                    isDuet = false;
                    lastPersonAgent = a;
                    lastPersonIsDuet = false;
                } else if (a.equalsIgnoreCase(lastPersonAgent)) {
                    isDuet = lastPersonIsDuet;
                } else {
                    isDuet = !lastPersonIsDuet;
                    lastPersonAgent = a;
                    lastPersonIsDuet = isDuet;
                }
                resolved.add(new LyricsLine(orig.startTimeMs(), orig.endTimeMs(), orig.text(),
                        orig.words(), a, isDuet, orig.isBG(), orig.songPart()));
            }
            resolved = sanitizeDuetLines(resolved);
            return new Lyrics(resolved, lyrics.providerName(), lyrics.synced(), lyrics.romanization(),
                    lyrics.translations(), lyrics.romanizations(), lyrics.songwriters(),
                    lyrics.rawFormat(), lyrics.formatType(), lyrics.sourceUrl());
        }

        // check speaker tag in line text
        Pattern speakerPrefixPattern = Pattern.compile("^[\\[\\(]([A-Za-z0-9\\s.'-]+)[\\]\\)]:?\\s*|^([A-Za-z0-9\\s.'-]+):\\s+");
        List<String> detectedSpeakers = new ArrayList<>();
        for (LyricsLine l : lyrics.lines()) {
            if (l.text() == null || l.text().isEmpty()) continue;
            Matcher m = speakerPrefixPattern.matcher(l.text());
            if (m.find()) {
                String g1 = m.group(1);
                String g2 = m.group(2);
                String sp = (g1 != null ? g1 : (g2 != null ? g2 : "")).trim();
                if (sp.isEmpty()) continue;
                String spLower = sp.toLowerCase(Locale.ROOT);
                if (spLower.equals("chorus") || spLower.startsWith("verse") || spLower.equals("bridge")
                        || spLower.equals("intro") || spLower.equals("outro") || spLower.equals("hook")
                        || spLower.equals("pre-chorus") || spLower.equals("refrain")) {
                    continue;
                }
                if (!detectedSpeakers.contains(spLower)) {
                    detectedSpeakers.add(spLower);
                }
            }
        }

        if (detectedSpeakers.size() >= 2) {
            String primarySpeaker = detectedSpeakers.get(0);
            List<LyricsLine> resolved = new ArrayList<>(lyrics.lines().size());
            String currentSpeaker = primarySpeaker;
            for (LyricsLine orig : lyrics.lines()) {
                String text = orig.text();
                Matcher m = speakerPrefixPattern.matcher(text);
                if (m.find()) {
                    String g1 = m.group(1);
                    String g2 = m.group(2);
                    String sp = (g1 != null ? g1 : (g2 != null ? g2 : "")).trim();
                    String spLower = sp.toLowerCase(Locale.ROOT);
                    if (!spLower.isEmpty() && !spLower.equals("chorus") && !spLower.startsWith("verse") && !spLower.equals("bridge")
                            && !spLower.equals("intro") && !spLower.equals("outro") && !spLower.equals("hook")) {
                        currentSpeaker = spLower;
                        text = text.substring(m.end()).trim();
                    }
                }
                boolean isDuet = !currentSpeaker.equalsIgnoreCase(primarySpeaker)
                        && !"both".equalsIgnoreCase(currentSpeaker)
                        && !"all".equalsIgnoreCase(currentSpeaker)
                        && !"duet".equalsIgnoreCase(currentSpeaker);
                resolved.add(new LyricsLine(orig.startTimeMs(), orig.endTimeMs(), text,
                        orig.words(), currentSpeaker, isDuet, orig.isBG(), orig.songPart()));
            }
            resolved = sanitizeDuetLines(resolved);
            return new Lyrics(resolved, lyrics.providerName(), lyrics.synced(), lyrics.romanization(),
                    lyrics.translations(), lyrics.romanizations(), lyrics.songwriters(),
                    lyrics.rawFormat(), lyrics.formatType(), lyrics.sourceUrl());
        }

        // check if line match artist names
        if (track != null && track.artist() != null) {
            String artistStr = track.artist();
            String[] artistParts = artistStr.split("\\s*(?:&|feat\\.?|ft\\.?|x|with|,)\\s*");
            if (artistParts.length >= 2) {
                String artist1 = artistParts[0].trim().toLowerCase(Locale.ROOT);
                String artist2 = artistParts[1].trim().toLowerCase(Locale.ROOT);
                String artist1First = artist1.contains(" ") ? artist1.substring(0, artist1.indexOf(' ')) : artist1;
                String artist2First = artist2.contains(" ") ? artist2.substring(0, artist2.indexOf(' ')) : artist2;

                boolean foundArtistTags = false;
                for (LyricsLine l : lyrics.lines()) {
                    if (l.text() == null) continue;
                    String lower = l.text().toLowerCase(Locale.ROOT);
                    if (lower.contains(artist2) || lower.contains(artist2First)) {
                        foundArtistTags = true;
                        break;
                    }
                }

                if (foundArtistTags) {
                    List<LyricsLine> resolved = new ArrayList<>(lyrics.lines().size());
                    boolean currentIsDuet = false;
                    for (LyricsLine orig : lyrics.lines()) {
                        String text = orig.text();
                        Matcher m = speakerPrefixPattern.matcher(text);
                        if (m.find()) {
                            String g1 = m.group(1);
                            String g2 = m.group(2);
                            String sp = (g1 != null ? g1 : (g2 != null ? g2 : "")).trim().toLowerCase(Locale.ROOT);
                            if (sp.contains(artist2) || sp.contains(artist2First)) {
                                currentIsDuet = true;
                                text = text.substring(m.end()).trim();
                            } else if (sp.contains(artist1) || sp.contains(artist1First)) {
                                currentIsDuet = false;
                                text = text.substring(m.end()).trim();
                            }
                        }
                        resolved.add(new LyricsLine(orig.startTimeMs(), orig.endTimeMs(), text,
                                orig.words(), orig.agentId(), currentIsDuet, orig.isBG(), orig.songPart()));
                    }
                    resolved = sanitizeDuetLines(resolved);
                    return new Lyrics(resolved, lyrics.providerName(), lyrics.synced(), lyrics.romanization(),
                            lyrics.translations(), lyrics.romanizations(), lyrics.songwriters(),
                            lyrics.rawFormat(), lyrics.formatType(), lyrics.sourceUrl());
                }
            }
        }

        return lyrics;
    }

    private static List<LyricsLine> sanitizeDuetLines(List<LyricsLine> lines) {
        if (lines == null || lines.isEmpty()) return lines;
        int totalCount = 0;
        int rightCount = 0;
        for (LyricsLine l : lines) {
            totalCount++;
            if (l.isDuet()) rightCount++;
        }
        if (totalCount == 0) return lines;

        // flip side if almost all lines went to right side
        if (Math.round((rightCount * 100f) / totalCount) >= 85) {
            List<LyricsLine> flipped = new ArrayList<>(lines.size());
            for (LyricsLine orig : lines) {
                flipped.add(new LyricsLine(
                        orig.startTimeMs(), orig.endTimeMs(), orig.text(),
                        orig.words(), orig.agentId(), !orig.isDuet(), orig.isBG(), orig.songPart()));
            }
            lines = flipped;
            rightCount = totalCount - rightCount;
        }

        // keep left align if not really duet
        int leftCount = totalCount - rightCount;
        if (rightCount == 0 || (leftCount * 100f / totalCount) > 90) {
            List<LyricsLine> leftOnly = new ArrayList<>(lines.size());
            for (LyricsLine orig : lines) {
                if (orig.isDuet()) {
                    leftOnly.add(new LyricsLine(
                            orig.startTimeMs(), orig.endTimeMs(), orig.text(),
                            orig.words(), orig.agentId(), false, orig.isBG(), orig.songPart()));
                } else {
                    leftOnly.add(orig);
                }
            }
            return leftOnly;
        }

        return lines;
    }

    private void showLyrics(Lyrics rawLyrics) {
        showLyrics(rawLyrics, false);
    }

    private void showLyrics(Lyrics rawLyrics, boolean preserveScroll) {
        TrackInfo track = LyricsManager.getInstance().getCurrentTrack();
        final Lyrics newLyrics = (preserveScroll && rawLyrics == this.lyrics && rawLyrics != null)
                ? rawLyrics
                : resolveDuetIfApplicable(rawLyrics, track);
        this.lyrics = newLyrics;

        final boolean canPreserve = preserveScroll
                && !lineViews.isEmpty()
                && newLyrics != null
                && newLyrics.lines() != null
                && lineViews.size() == newLyrics.lines().size();

        if (!canPreserve) {
            clearLines();
            initialBuildComplete = false;
            isBrowsing = false;
            userScrollUntilUptimeMs = 0;
            isDraggingScroll = false;
            isUserTouchingScroll = false;
            lastScrollTarget = -1;
            isProgrammaticScrolling = true;
            handler.removeCallbacks(resumeFollowRunnable);
            hideJumpToCurrentButton(true);
            if (scrollView != null) {
                stopScrollViewFling(scrollView);
                scrollView.stopNestedScroll();
            }
            progressBar.setVisibility(GONE);
            scrollView.setVisibility(VISIBLE);
            updateLinesContainerPadding();

            Context context = getContext();
            final int textSize = Settings.LYRICS_TEXT_SIZE.get();
            final int foregroundColor = lineTextColor();
            final boolean tapToSeek = newLyrics.synced() && Settings.LYRICS_TAP_TO_SEEK.get();
            final boolean isSynced = newLyrics.synced();
            this.isDuetSong = newLyrics.lines().stream().anyMatch(LyricsLine::isDuet);

            boolean songHasJapanese = false;
            if (newLyrics.lines() != null) {
                for (LyricsLine l : newLyrics.lines()) {
                    if (LyricsRomanizer.containsJapanese(l.text())) {
                        songHasJapanese = true;
                        break;
                    }
                }
            }
            LyricsRomanizer.setCurrentSongIsJapanese(songHasJapanese);

            boolean songHasRtl = false;
            if (newLyrics.lines() != null) {
                for (LyricsLine l : newLyrics.lines()) {
                    if (LyricsRomanizer.containsArabic(l.text()) || LyricsRomanizer.containsHebrew(l.text())) {
                        songHasRtl = true;
                        break;
                    }
                }
            }

            gapIntervals.clear();
        for (GapLineView gv : gapLineViews) {
            gv.destroy();
            linesContainer.removeView(gv);
        }
        gapLineViews.clear();
        gapLineViewsByLine.clear();
        updateLinesContainerPadding();
        if (isSynced) {
            int prevMainIdx = -1;
            for (int i = 0; i < newLyrics.lines().size(); i++) {
                if (isSecondaryLyricLine(i, newLyrics.lines()) && prevMainIdx >= 0) {
                    continue;
                }
                LyricsLine line = newLyrics.lines().get(i);
                final boolean isLineRtl = LyricsRomanizer.containsArabic(line.text()) || LyricsRomanizer.containsHebrew(line.text());
                final boolean isRightAligned;
                if (isDuetSong) {
                    isRightAligned = songHasRtl ? !line.isDuet() : line.isDuet();
                } else {
                    isRightAligned = isLineRtl;
                }
                if (prevMainIdx < 0) {
                    long currentStart = line.startTimeMs();
                    if (currentStart != LyricsLine.NO_TIME
                            && currentStart >= INTERVAL_MIN_GAP_MS) {
                        long gapStart = 0L;
                        long gapEnd = Math.max(gapStart + 600L, currentStart - GAP_EXIT_DURATION_MS);
                        gapIntervals.add(new GapInterval(gapStart, gapEnd, currentStart, line.isDuet(), i, isRightAligned));
                    }
                    prevMainIdx = i;
                } else {
                    LyricsLine prevLine = newLyrics.lines().get(prevMainIdx);
                    long prevStart = prevLine.startTimeMs();
                    long currentStart = line.startTimeMs();
                    if (currentStart == LyricsLine.NO_TIME || prevStart == LyricsLine.NO_TIME) {
                        prevMainIdx = i;
                        continue;
                    }
                    long prevEnd = prevLine.endTimeMs();
                    if (!prevLine.words().isEmpty()) {
                        long wEnd = prevLine.words().get(prevLine.words().size() - 1).endMs();
                        if (wEnd != LyricsLine.NO_TIME) {
                            prevEnd = (prevEnd != LyricsLine.NO_TIME) ? Math.max(prevEnd, wEnd) : wEnd;
                        }
                    }
                    for (int s = prevMainIdx + 1; s < i; s++) {
                        LyricsLine secL = newLyrics.lines().get(s);
                        long sEnd = secL.endTimeMs();
                        if (!secL.words().isEmpty()) {
                            long swEnd = secL.words().get(secL.words().size() - 1).endMs();
                            if (swEnd != LyricsLine.NO_TIME) {
                                sEnd = (sEnd != LyricsLine.NO_TIME) ? Math.max(sEnd, swEnd) : swEnd;
                            }
                        }
                        if (sEnd != LyricsLine.NO_TIME) {
                            prevEnd = (prevEnd != LyricsLine.NO_TIME) ? Math.max(prevEnd, sEnd) : sEnd;
                        }
                    }
                    long gapBetweenStarts = currentStart - prevStart;
                    boolean hasKnownEnd = (prevEnd != LyricsLine.NO_TIME);
                    if (!hasKnownEnd) {
                        if (gapBetweenStarts < INTERVAL_MIN_GAP_MS + 4000L) {
                            prevEnd = currentStart;
                        } else {
                            long vocalHold = Math.max(5000L, (prevLine.text() != null ? prevLine.text().length() : 10) * 180L);
                            prevEnd = Math.min(prevStart + vocalHold, currentStart - INTERVAL_MIN_GAP_MS);
                        }
                    }
                    if (gapBetweenStarts < INTERVAL_MIN_GAP_MS + 4000L && prevLine.words().isEmpty()) {
                        prevMainIdx = i;
                        continue;
                    }
                    long safeEnd;
                    if (hasKnownEnd) {
                        safeEnd = prevEnd;
                    } else {
                        long minSafeEnd = prevStart + Math.min(4500L, Math.max(1000L, gapBetweenStarts / 2));
                        safeEnd = Math.max(prevEnd, minSafeEnd);
                    }
                    if ((currentStart - safeEnd) >= INTERVAL_MIN_GAP_MS) {
                        long gapStart = safeEnd + GapLineView.LYRICS_GAP_START_LAG_MS;
                        long gapEnd = Math.max(gapStart + 600L, currentStart - GAP_EXIT_DURATION_MS);
                        gapIntervals.add(new GapInterval(gapStart, gapEnd, currentStart, line.isDuet(), i, isRightAligned));
                    }
                    prevMainIdx = i;
                }
            }
        }

        Set<Integer> manualGaps = new HashSet<>();
        for (GapInterval gi : gapIntervals) {
            if (gi.beforeLineIndex() > 0) {
                manualGaps.add(gi.beforeLineIndex() - 1);
            }
        }
        boolean[] isSecFlags = new boolean[newLyrics.lines().size()];
        for (int i = 0; i < newLyrics.lines().size(); i++) {
            isSecFlags[i] = isSecondaryLyricLine(i, newLyrics.lines());
        }
        retimedLyrics = LyricsRetiming.retimeLyrics(newLyrics.lines(), manualGaps, isSecFlags);
        if (retimedLyrics != null && !retimedLyrics.isEmpty()) {
            for (int i = 0; i < retimedLyrics.size(); i++) {
                if (!isSecondaryLyricLine(i, newLyrics.lines())) {
                    long maxEnd = retimedLyrics.get(i).adjustedEndTimeMs;
                    for (int j = i + 1; j < retimedLyrics.size() && isSecondaryLyricLine(j, newLyrics.lines()); j++) {
                        maxEnd = Math.max(maxEnd, retimedLyrics.get(j).adjustedEndTimeMs);
                    }
                    if (maxEnd > retimedLyrics.get(i).adjustedEndTimeMs) {
                        LyricsRetiming.RetimedEntry old = retimedLyrics.get(i);
                        retimedLyrics.set(i, new LyricsRetiming.RetimedEntry(old.line, old.startMs, old.actualEndTimeMs, maxEnd));
                    }
                    for (int j = i + 1; j < retimedLyrics.size() && isSecondaryLyricLine(j, newLyrics.lines()); j++) {
                        LyricsRetiming.RetimedEntry oldSec = retimedLyrics.get(j);
                        retimedLyrics.set(j, new LyricsRetiming.RetimedEntry(oldSec.line, oldSec.startMs, oldSec.actualEndTimeMs, maxEnd, true));
                    }
                }
            }
        }
        retimedAdjustedEndMax = LyricsRetiming.buildAdjustedEndMaxByIndex(retimedLyrics);

        LinearLayout currentParentRow = null;
        int currentParentIdx = -1;

        for (int i = 0; i < newLyrics.lines().size(); i++) {
            LyricsLine line = newLyrics.lines().get(i);

            if (isSynced) {
                for (GapInterval gi : gapIntervals) {
                    if (gi.beforeLineIndex() == i) {
                        GapLineView gapView = new GapLineView(context, gi);
                        gapView.setScrollEngine(scrollEngine);
                        gapLineViews.add(gapView);
                        gapLineViewsByLine.put(i, gapView);
                        if (tapToSeek) {
                            final long seekTime = gi.startMs();
                            gapView.setOnClickListener(view -> {
                                resetScrollAndExitBrowsing();
                                if (!VideoInformation.seekTo(seekTime)) {
                                    Logger.printDebug(() -> "Seek to gap failed: " + seekTime);
                                }
                                seekPending = true;
                                pendingSeekPosMs = seekTime;
                                pendingSeekUptimeMs = SystemClock.uptimeMillis();
                                lastLivePosMs = seekTime;
                                handleSeekOrRewind(seekTime);
                                scrollToGapView(gapView);
                            });
                        }
                        int creditIndex = linesContainer.indexOfChild(creditBlockContainer);
                        int footerIndex = linesContainer.indexOfChild(footerContainer);
                        int insertPos = (creditIndex >= 0) ? creditIndex : ((footerIndex >= 0) ? footerIndex : Math.max(0, linesContainer.getChildCount() - 1));
                        linesContainer.addView(gapView, insertPos,
                                new LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.MATCH_PARENT, 0));
                        break;
                    }
                }
            }

            if (!isSynced && isSectionHeader(line.text())) {
                currentParentRow = null;
                currentParentIdx = -1;
                TextView chipView = new TextView(context);
                String title = line.text().replace("[", "").replace("]", "").trim().toUpperCase(Locale.ROOT);
                chipView.setText(title);
                chipView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                chipView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                chipView.setTextColor(0xE6FFFFFF);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    chipView.setLetterSpacing(0.12f);
                }
                chipView.setPadding(Dim.dp(11), Dim.dp(4), Dim.dp(11), Dim.dp(4));

                GradientDrawable chipBg = new GradientDrawable();
                chipBg.setShape(GradientDrawable.RECTANGLE);
                chipBg.setCornerRadius(Dim.dp(6));
                chipBg.setColor(0x24FFFFFF);
                chipView.setBackground(chipBg);

                LinearLayout lineRow = new LinearLayout(context);
                lineRow.setOrientation(LinearLayout.VERTICAL);
                lineRow.setClipChildren(false);
                lineRow.setClipToPadding(false);
                LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                chipParams.topMargin = (i == 0) ? Dim.dp(6) : Dim.dp(14);
                chipParams.bottomMargin = Dim.dp(6);
                chipParams.leftMargin = Dim.dp(16);
                lineRow.addView(chipView, chipParams);

                int creditIndex = linesContainer.indexOfChild(creditBlockContainer);
                int footerIndex = linesContainer.indexOfChild(footerContainer);
                int insertPos = (creditIndex >= 0) ? creditIndex : ((footerIndex >= 0) ? footerIndex : Math.max(0, linesContainer.getChildCount() - 1));
                linesContainer.addView(lineRow, insertPos,
                        new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT));
                lineViews.add(chipView);
                lineRows.add(lineRow);
                lineWordSpans.add(Collections.emptyList());
                lineOriginalStarts.add(0);
                lineUnsungSpans.add(null);
                continue;
            }

            // temp placeholder until real timing come
            lineWordSpans.add(new ArrayList<>());
            lineOriginalStarts.add(0);

            LyricsLineView lineView = new LyricsLineView(context);
            lineView.setScrollEngine(scrollEngine);
            boolean isSec = isSecondaryLyricLine(i, newLyrics.lines()) && (currentParentRow != null);
            lineView.setSecondary(isSec);
            String lineText = line.text();
            if (isSec) {
                lineText = stripParentheses(lineText).trim();
            }
            lineView.setText(lineText.isEmpty() ? "♪" : lineText);

            int effectiveTextSize = isSynced ? textSize : Math.max(textSize - 2, 22);
            if (isSec) {
                effectiveTextSize = Math.round(effectiveTextSize * 0.70f);
            }
            lineView.setTextSize(TypedValue.COMPLEX_UNIT_SP, effectiveTextSize);
            lineView.setTextColor(foregroundColor);
            lineView.updateLineLook(false, 2, 0L, true, isSynced, true);
            lineView.setTag(null);
            lineView.setPadding(Dim.dp16, Dim.dp(6), Dim.dp16, Dim.dp(6));
            lineView.setLineSpacing(Dim.dp(3), 1.15f);
            lineView.setIncludeFontPadding(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                lineView.getPaint().setElegantTextHeight(true);
            }
            lineView.setTypeface(LYRICS_TYPEFACE);
            WordAnimationUtils.applyLyricsBoldPaint(lineView.getPaint());

            GradientDrawable lineBg = new GradientDrawable();
            lineBg.setShape(GradientDrawable.RECTANGLE);
            lineBg.setCornerRadius(Dim.dp12);
            lineBg.setColor(Color.TRANSPARENT);
            lineView.setBackground(lineBg);

            if (tapToSeek) {
                final long videoLength = VideoInformation.getVideoLength();
                long target = line.startTimeMs()
                        + Settings.LYRICS_OFFSET_MS.get()
                        + LyricsManager.getInstance().getTemporaryOffsetMs();
                if (target < 0) {
                    target = 0;
                } else if (videoLength > 0 && target > videoLength) {
                    target = videoLength;
                }
                final long seekTime = target;
                final int lineIndex = i;
                final int mainIndex = (isSec && currentParentRow != null) ? currentParentIdx : i;
                lineView.setOnClickListener(view -> {
                    resetScrollAndExitBrowsing();
                    if (!VideoInformation.seekTo(seekTime)) {
                        Logger.printDebug(() -> "Seek to lyrics line failed: " + seekTime);
                    }
                    seekPending = true;
                    pendingSeekPosMs = seekTime;
                    pendingSeekUptimeMs = SystemClock.uptimeMillis();
                    pendingSeekMainIndex = mainIndex;
                    lastLivePosMs = seekTime;
                    handleSeekOrRewind(seekTime);

                    if (mainIndex >= 0) {
                        previousPrimaryActiveIndex = primaryActiveIndex;
                        primaryActiveIndex = mainIndex;
                        highlightedIndex = mainIndex;
                        activeIndicesSet.clear();
                        activeIndicesSet.add(mainIndex);
                        if (isSec) {
                            activeIndicesSet.add(lineIndex);
                        }
                        updateLinesDepthOfField(activeIndicesSet, mainIndex, -1);
                        scrollToActiveLine(mainIndex);
                    }
                });
            }

            final int shareLineIndex = i;
            lineView.setOnLongClickListener(v -> {
                LyricsLineView lv = (LyricsLineView) v;
                String subTextToCopy = lv.getCopyTextForTouch(lv.lastTouchY);
                if (subTextToCopy != null && !subTextToCopy.isEmpty()) {
                    ClipboardManager clipboard = (ClipboardManager) getContext()
                            .getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(
                                ClipData.newPlainText("lyric_line", subTextToCopy));
                        Utils.showToastShort(str("morphe_music_lyrics_copied"));
                    }
                    return true;
                }
                if (newLyrics != null && !newLyrics.lines().isEmpty()) {
                    LyricsShareDialog.show(
                            getContext(),
                            newLyrics.lines(),
                            romanizedLines,
                            translatedLines,
                            shareLineIndex);
                    return true;
                }
                return false;
            });

            final int duetSideInset = Math.max(Dim.dp(48), (int) (context.getResources().getDisplayMetrics().widthPixels * 0.18f));
            final boolean isLineRtl = LyricsRomanizer.containsArabic(line.text()) || LyricsRomanizer.containsHebrew(line.text());
            boolean isDuetLine = line.isDuet() || (isSec && currentParentIdx >= 0 && newLyrics.lines().get(currentParentIdx).isDuet());
            final boolean isRightAligned;
            if (isDuetSong) {
                isRightAligned = songHasRtl ? !isDuetLine : isDuetLine;
            } else {
                isRightAligned = isLineRtl;
            }

            lineView.setIsRightAligned(isRightAligned);
            if (isRightAligned) {
                lineView.setGravity(Gravity.RIGHT);
                lineView.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
            } else {
                lineView.setGravity(Gravity.LEFT);
                lineView.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
            }

            if (isSec && currentParentRow != null) {
                lineView.setParentLineIndex(currentParentIdx);
                boolean bgBeforeMain = false;
                if (Settings.LYRICS_DYNAMIC_BG_VOCAL_POS.get()
                        && currentParentIdx >= 0
                        && currentParentIdx < newLyrics.lines().size()) {
                    LyricsLine parentLine = newLyrics.lines().get(currentParentIdx);
                    long bgStart = (line.hasWords() && line.words().get(0).startMs() != LyricsLine.NO_TIME)
                            ? line.words().get(0).startMs()
                            : line.startTimeMs();
                    long mainStart = (parentLine.hasWords() && parentLine.words().get(0).startMs() != LyricsLine.NO_TIME)
                            ? parentLine.words().get(0).startMs()
                            : parentLine.startTimeMs();
                    if (bgStart != LyricsLine.NO_TIME && mainStart != LyricsLine.NO_TIME && bgStart < mainStart) {
                        bgBeforeMain = true;
                    }
                }
                lineView.setBgBeforeMain(bgBeforeMain);
                if (isSynced) {
                    lineView.setSecondaryExpandFraction(0f);
                    lineView.setVisibility(View.GONE);
                    lineView.setAlpha(0f);
                } else {
                    lineView.setSecondaryExpandFraction(1f);
                    lineView.setVisibility(View.VISIBLE);
                    lineView.setAlpha(0.85f);
                }
                LinearLayout.LayoutParams bgParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                if (bgBeforeMain) {
                    bgParams.bottomMargin = Dim.dp(4);
                    currentParentRow.addView(lineView, 0, bgParams);
                } else {
                    bgParams.topMargin = Dim.dp(4);
                    currentParentRow.addView(lineView, bgParams);
                }

                lineViews.add(lineView);
                lineRows.add(currentParentRow);
                lineUnsungSpans.add(null);
                continue;
            }

            currentParentIdx = i;
            LinearLayout lineRow = new LinearLayout(context);
            lineRow.setOrientation(LinearLayout.VERTICAL);
            lineRow.setClipChildren(false);
            lineRow.setClipToPadding(false);
            currentParentRow = lineRow;

            if (isDuetSong) {
                if (isRightAligned) {
                    lineRow.setPadding(duetSideInset, Dim.dp(4), 0, Dim.dp(4));
                } else {
                    lineRow.setPadding(0, Dim.dp(4), duetSideInset, Dim.dp(4));
                }
            } else {
                lineRow.setPadding(0, Dim.dp(4), 0, Dim.dp(4));
            }

            lineRow.addView(lineView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = Dim.dp(26);
            int creditIndex = linesContainer.indexOfChild(creditBlockContainer);
            int footerIndex = linesContainer.indexOfChild(footerContainer);
            int insertPos = (creditIndex >= 0) ? creditIndex : ((footerIndex >= 0) ? footerIndex : Math.max(0, linesContainer.getChildCount() - 1));
            linesContainer.addView(lineRow, insertPos, rowParams);
            lineViews.add(lineView);
            lineRows.add(lineRow);
            lineUnsungSpans.add(null);
        }
        }

        final int generation = ++buildGeneration;
        final int snapCount = newLyrics.lines().size();
        final int[] snapAvailW = new int[snapCount];
        final TextPaint[] snapPaints = new TextPaint[snapCount];
        final boolean[] snapRight = new boolean[snapCount];
        final boolean[] snapSec = new boolean[snapCount];
        final LyricsAnimationStyle[] snapStyles = new LyricsAnimationStyle[snapCount];
        final Long[] snapAdjEnd = new Long[snapCount];
        final int snapAccent = lineTextColor();
        final int containerW = (linesContainer != null && linesContainer.getWidth() > 0)
                ? (linesContainer.getWidth() - linesContainer.getPaddingLeft() - linesContainer.getPaddingRight())
                : 0;
        for (int i = 0; i < snapCount && i < lineViews.size(); i++) {
            TextView tv = lineViews.get(i);
            if (tv instanceof LyricsLineView llv) {
                int vw = llv.getWidth();
                if (vw <= 0 && containerW > 0) {
                    View row = (i < lineRows.size()) ? lineRows.get(i) : null;
                    int rowPad = (row != null) ? (row.getPaddingLeft() + row.getPaddingRight()) : 0;
                    vw = Math.max(0, containerW - rowPad);
                }
                snapAvailW[i] = Math.max(0, vw - llv.getCompoundPaddingLeft() - llv.getCompoundPaddingRight());
                snapPaints[i] = new TextPaint(llv.getPaint());
                snapRight[i] = llv.isRightAligned();
                snapSec[i] = llv.isSecondary();
                snapStyles[i] = llv.getAnimationStyle();
                snapAdjEnd[i] = (retimedLyrics != null && i < retimedLyrics.size())
                        ? retimedLyrics.get(i).adjustedEndTimeMs : null;
            }
        }

        LINE_BUILDER_EXECUTOR.execute(() -> {
            try {
                final int lineCount = newLyrics.lines().size();
                List<List<WordTiming>> allTimings = new ArrayList<>(lineCount);
                List<Integer> allOrigStarts = new ArrayList<>(lineCount);
                List<List<LyricsLineView.WordUnit>> allWordUnits = new ArrayList<>(lineCount);
                List<String> allTranslations = new ArrayList<>(lineCount);
                List<BuildResult> allBuildResults = new ArrayList<>(lineCount);
                List<LyricsLineView.PrebuiltWordLayout> allPrebuiltLayouts = new ArrayList<>(lineCount);

                for (int i = 0; i < lineCount; i++) {
                    final int lineIdx = i;
                    LyricsLine l = newLyrics.lines().get(i);
                    LyricsLine rLine = (romanizedLines != null && i < romanizedLines.size())
                            ? romanizedLines.get(i) : null;
                    List<WordTiming> timings;
                    try {
                        timings = computeWordTimings(l, rLine);
                    } catch (Exception t) {
                        Logger.printDebug(() -> "computeWordTimings failed for line " + lineIdx, t);
                        timings = Collections.emptyList();
                    }
                    allTimings.add(timings);

                    List<LyricsLineView.WordUnit> units;
                    try {
                        units = buildWordUnits(l, rLine, timings);
                    } catch (Exception t) {
                        Logger.printDebug(() -> "buildWordUnits failed for line " + lineIdx, t);
                        units = null;
                    }
                    allWordUnits.add(units);

                    boolean usesWordRenderer = (units != null && !units.isEmpty()) || l.hasWords();
                    int origStart = 0;
                    try {
                        origStart = computeOriginalTextStart(l, i, units != null && !units.isEmpty());
                    } catch (Exception t) {
                        origStart = 0;
                    }
                    allOrigStarts.add(origStart);

                    String origText = l.text();
                    String origTrim = origText != null ? origText.trim() : "";
                    String trans = null;
                    if (translatedLines != null && i < translatedLines.size()) {
                        String rawT = translatedLines.get(i);
                        if (rawT != null) {
                            String t = rawT.trim();
                            if (!t.isEmpty() && !isSkipTranslation(t) && !t.equalsIgnoreCase(origTrim)) {
                                trans = t;
                            }
                        }
                    }
                    allTranslations.add(trans);

                    BuildResult bRes = null;
                    try {
                        bRes = buildLineText(l, timings, i, usesWordRenderer);
                    } catch (Exception t) {
                        Logger.printDebug(() -> "buildLineText failed for line " + lineIdx, t);
                    }
                    allBuildResults.add(bRes);

                    LyricsLineView.PrebuiltWordLayout prebuilt = null;
                    if (usesWordRenderer && i < snapAvailW.length && snapAvailW[i] > 0 && snapPaints[i] != null) {
                        try {
                            prebuilt = LyricsLineView.buildPrebuiltWordLayout(
                                    snapAvailW[i],
                                    snapPaints[i],
                                    snapRight[i],
                                    snapSec[i],
                                    snapStyles[i],
                                    snapAccent,
                                    snapAdjEnd[i],
                                    l,
                                    rLine,
                                    units,
                                    trans,
                                    false);
                        } catch (Exception t) {
                            Logger.printDebug(() -> "buildPrebuiltWordLayout failed for line " + lineIdx, t);
                        }
                    }
                    allPrebuiltLayouts.add(prebuilt);
                }

                handler.post(() -> {
                    try {
                        if (generation != buildGeneration || lyrics != newLyrics) {
                            return;
                        }
                        final int count = Math.min(allTimings.size(), lineViews.size());
                        suppressContainerLayout(linesContainer, true);
                        try {
                            for (int i = 0; i < count; i++) {
                                lineWordSpans.set(i, allTimings.get(i));
                                lineOriginalStarts.set(i, allOrigStarts.get(i));
                                TextView tv = lineViews.get(i);
                                if (i < newLyrics.lines().size()) {
                                    List<LyricsLineView.WordUnit> units = allWordUnits.get(i);
                                    String trans = allTranslations.get(i);
                                    LyricsLine origLineObj = newLyrics.lines().get(i);
                                    String origText = origLineObj.text();
                                    String origTrim = origText != null ? origText.trim() : "";
                                    String liveTrans = null;
                                    if (translatedLines != null && i < translatedLines.size()) {
                                        String rawT = translatedLines.get(i);
                                        if (rawT != null) {
                                            String t = rawT.trim();
                                            if (!t.isEmpty() && !isSkipTranslation(t) && !t.equalsIgnoreCase(origTrim)) {
                                                liveTrans = t;
                                            }
                                        }
                                    }
                                    String effectiveTrans = (translatedLines != null) ? liveTrans : trans;
                                    LyricsLine romLineObj = (romanizedLines != null && i < romanizedLines.size())
                                            ? romanizedLines.get(i) : null;
                                    boolean usesWordRenderer = (units != null && !units.isEmpty()) || origLineObj.hasWords();
                                    LyricsLineView.PrebuiltWordLayout prebuilt = allPrebuiltLayouts.get(i);
                                    if (tv instanceof LyricsLineView lineView) {
                                        if (retimedLyrics != null && i < retimedLyrics.size()) {
                                            lineView.setAdjustedEndTimeMs(retimedLyrics.get(i).adjustedEndTimeMs);
                                        }
                                        if (prebuilt != null) {
                                            lineView.applyPrebuiltWordLayout(
                                                    origLineObj,
                                                    romLineObj,
                                                    units,
                                                    usesWordRenderer ? trans : null,
                                                    prebuilt);
                                            if (!Objects.equals(effectiveTrans, trans)) {
                                                lineView.setTranslationText(usesWordRenderer ? effectiveTrans : null);
                                            }
                                        } else {
                                            lineView.setRawLyricsLine(origLineObj, romLineObj);
                                            lineView.setWordUnits(units);
                                            lineView.setTranslationText(usesWordRenderer ? effectiveTrans : null);
                                        }
                                    }
                                    BuildResult result = allBuildResults.get(i);
                                    if (result == null || (!usesWordRenderer && !Objects.equals(effectiveTrans, trans))) {
                                        result = buildLineText(origLineObj, allTimings.get(i), i, usesWordRenderer);
                                    }
                                    if (!usesWordRenderer || tv.getText() == null || tv.getText().length() == 0) {
                                        tv.setText(result.text());
                                    }
                                    lineUnsungSpans.set(i, result.unsungSpan());
                                    if (tv instanceof LyricsLineView lineView) {
                                        lineView.setTranslationBounds(result.transStart(), result.transEnd());
                                        lineView.setRomanizationBounds(result.romaStart(), result.romaEnd());
                                    }
                                }
                            }
                        } finally {
                            suppressContainerLayout(linesContainer, false);
                        }
                        initialBuildComplete = true;
                        long curPos = LyricsManager.getInstance().getPositionMs();
                        lastLivePosMs = curPos;
                        seekPending = true;
                        handleSeekOrRewind(curPos);
                        updateHighlight();
                        updateWordSync(curPos);
                        updateLinesDepthOfField(activeIndicesSet, primaryActiveIndex, -1);

                        if (canPreserve && lastScrollTarget >= 0) {
                            isProgrammaticScrolling = false;
                        } else {
                            linesContainer.post(() -> {
                                try {
                                    if (lyrics == newLyrics) {
                                        scrollEngine.reset();
                                        isBrowsing = false;
                                        userScrollUntilUptimeMs = 0;
                                        isDraggingScroll = false;
                                        isUserTouchingScroll = false;
                                        hideJumpToCurrentButton(true);
                                        seekPending = true;
                                        long nowPos = LyricsManager.getInstance().getPositionMs();
                                        lastLivePosMs = nowPos;
                                        GapInterval initGi = getActiveGapInterval(nowPos);
                                        if (initGi != null && nowPos < initGi.endMs()) {
                                            GapLineView gv = gapLineViewsByLine.get(initGi.beforeLineIndex());
                                            if (gv != null) {
                                                scrollToGapView(gv, true);
                                            }
                                        } else if (primaryActiveIndex >= 0) {
                                            scrollToActiveLine(primaryActiveIndex, true);
                                        } else if (newLyrics.synced() && !newLyrics.lines().isEmpty()) {
                                            scrollToActiveLine(0, true);
                                        }
                                        updateHighlight();
                                        updateWordSync(nowPos);
                                    }
                                } catch (Exception t) {
                                    Logger.printDebug(() -> "linesContainer.post error", t);
                                } finally {
                                    isProgrammaticScrolling = false;
                                }
                            });
                        }
                    } catch (Exception t) {
                        Logger.printDebug(() -> "handler.post line update failed", t);
                    } finally {
                        if (generation == buildGeneration) {
                            isProgrammaticScrolling = false;
                        }
                    }
                });
            } catch (Exception t) {
                Logger.printDebug(() -> "LINE_BUILDER_EXECUTOR failed", t);
            }
        });

        currentSourceUrl = newLyrics.sourceUrl();
        footerView.setText(sourceText(newLyrics.providerName(),
                translatedLines != null, translatedFromGoogle, translatedFromAI,
                romanizedFromGoogle, romanizedFromAI, aiModelName, romanizedLocalDictionary));
        footerView.setOnClickListener(view -> onSourceClicked());
        footerView.setOnLongClickListener(view -> {
            showProviderSelectionDialog();
            return true;
        });

        List<String> songwriters = newLyrics.songwriters();
        if (songwriters != null && !songwriters.isEmpty()) {
            creditView.setText(formatSongwritersSpannable(songwriters));
            creditView.setVisibility(Settings.LYRICS_HIDE_INFO.get() ? GONE : VISIBLE);
        } else {
            creditView.setText("");
            creditView.setVisibility(GONE);
        }
        creditBlockContainer.setVisibility(VISIBLE);
        animateCreditBlockLook(false, 4, 0L, !isBrowsing, newLyrics.synced(),
                Settings.LYRICS_BLUR_INACTIVE.get() && !isBrowsing && newLyrics.synced(), true);
        footerContainer.setVisibility(VISIBLE);
        footerView.setVisibility(VISIBLE);
        buttonRow.setVisibility(VISIBLE);
        updateTranslateLabel();
        updateRomanizeLabel();

        if (!canPreserve) {
            isProgrammaticScrolling = true;
            scrollView.scrollTo(0, 0);
            scrollView.post(() -> isProgrammaticScrolling = false);
        }
    }

    private static List<WordTiming> computeWordTimings(LyricsLine line, @Nullable LyricsLine rLine) {
        if (!line.hasWords()) {
            return Collections.emptyList();
        }

        final boolean isSec = isSecondaryLyric(line);
        String text = getCleanLineText(line);

        if (line.words().size() == 1) {
            Word single = line.words().get(0);
            String rawWord = isSec ? cleanWordText(single.text()) : single.text();
            String wText = cleanJapaneseQuotes(rawWord).trim();
            if (!wText.isEmpty()) {
                long wordStart = single.startMs() != LyricsLine.NO_TIME ? single.startMs() : line.startTimeMs();
                long wordEnd = single.endMs() != LyricsLine.NO_TIME ? single.endMs() : line.endTimeMs();
                if (wordEnd <= wordStart) {
                    wordEnd = wordStart + 200L;
                }
                String romaji = single.romaji();
                if ((romaji == null || romaji.isEmpty()) && rLine != null && rLine.hasWords() && !rLine.words().isEmpty()) {
                    romaji = rLine.words().get(0).romaji();
                }
                int idx = text.indexOf(wText);
                int s = (idx >= 0) ? idx : 0;
                int e = (idx >= 0) ? Math.min(text.length(), idx + wText.length()) : text.length();
                if (e > s) {
                    return Collections.singletonList(new WordTiming(s, e, wordStart, wordEnd, romaji));
                }
            }
            return Collections.emptyList();
        }

        List<WordTiming> timings = new ArrayList<>(line.words().size());
        int textLength = text.length();
        int offset = 0;
        for (int wi = 0; wi < line.words().size(); wi++) {
            Word word = line.words().get(wi);
            String rawWord = isSec ? cleanWordText(word.text()) : word.text();
            String wordText = java.text.Normalizer.normalize(
                    cleanJapaneseQuotes(rawWord).trim(), java.text.Normalizer.Form.NFKC);
            wordText = wordText.replace('\u2005', ' ').replace('\u3000', ' ').replace('\u00A0', ' ').trim();
            int wordLength = wordText.length();
            if (wordLength == 0) {
                continue;
            }
            int start = text.indexOf(wordText, offset);
            int len = wordLength;
            if (start < 0) {
                String trimmed = wordText.trim();
                if (!trimmed.isEmpty()) {
                    start = text.indexOf(trimmed, offset);
                    len = trimmed.length();
                }
            }
            if (start < 0) {
                String cleaned = cleanWordText(wordText);
                if (!cleaned.isEmpty()) {
                    start = text.indexOf(cleaned, offset);
                    len = cleaned.length();
                }
            }
            if (start < 0) {
                // Unmatched word: advance past it so following words stay aligned,
                // rather than emitting a span that falls outside the line text.
                offset = Math.min(offset + len, textLength);
                continue;
            }
            int end = Math.min(start + len, textLength);
            if (start >= end) {
                continue;
            }
            String wordRom = word.romaji();
            if ((wordRom == null || wordRom.isEmpty()) && rLine != null && rLine.hasWords() && wi < rLine.words().size()) {
                wordRom = rLine.words().get(wi).romaji();
            }
            timings.add(new WordTiming(start, end, word.startMs(), word.endMs(), wordRom));
            offset = end;
        }
        for (int i = 1; i < timings.size(); i++) {
            WordTiming prev = timings.get(i - 1);
            WordTiming curr = timings.get(i);
            if (curr.startMs() < prev.endMs()) {
                timings.set(i - 1, new WordTiming(prev.start(), prev.end(),
                        prev.startMs(), Math.max(prev.startMs() + 1L, curr.startMs()), prev.romaji()));
            }
        }
        return timings;
    }

    private static boolean hasCjk(@Nullable CharSequence s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                    || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                    || block == Character.UnicodeBlock.HIRAGANA
                    || block == Character.UnicodeBlock.KATAKANA
                    || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                    || block == Character.UnicodeBlock.HANGUL_JAMO
                    || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO) {
                return true;
            }
        }
        return false;
    }

    public static String cleanJapaneseQuotes(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text;
    }

    public static String stripParentheses(@Nullable String text) {
        if (text == null) {
            return "";
        }
        String trimmed = cleanJapaneseQuotes(text).trim();
        boolean stripped = true;
        while (stripped && trimmed.length() >= 2) {
            stripped = false;
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '(' && last == ')')
                    || (first == '（' && last == '）')
                    || (first == '[' && last == ']')
                    || (first == '{' && last == '}')
                    || (first == '<' && last == '>')
                    || (first == '"' && last == '"')
                    || (first == '“' && last == '”')
                    || (first == '\'' && last == '\'')) {
                trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
                stripped = true;
            }
        }
        return trimmed;
    }

    @NonNull
    public static String cleanWordText(@Nullable String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        int start = 0;
        int end = trimmed.length();
        while (start < end) {
            char c = trimmed.charAt(start);
            if (c == '(' || c == '（' || c == '[' || c == '【' || c == '{' || c == '<') {
                start++;
            } else {
                break;
            }
        }
        while (end > start) {
            char c = trimmed.charAt(end - 1);
            if (c == ')' || c == '）' || c == ']' || c == '】' || c == '}' || c == '>') {
                end--;
            } else {
                break;
            }
        }
        return (start >= end) ? "" : trimmed.substring(start, end).trim();
    }

    @NonNull
    public static String normalizeRomajiText(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String s = cleanJapaneseQuotes(stripParentheses(raw)).trim();
        if (s.isEmpty()) {
            return "";
        }
        return s.replaceAll("\\s+", " ").trim();
    }

    private static long getInterpolatedCharTimeMs(int charIdx, boolean isEnd, List<WordTiming> mainTimings,
                                                  int totalTextLength, long lineStartMs, long lineEndMs) {
        if (mainTimings == null || mainTimings.isEmpty()) {
            if (lineStartMs != LyricsLine.NO_TIME && lineEndMs != LyricsLine.NO_TIME && lineEndMs > lineStartMs && totalTextLength > 0) {
                return lineStartMs + ((lineEndMs - lineStartMs) * Math.max(0, Math.min(charIdx, totalTextLength))) / totalTextLength;
            }
            return LyricsLine.NO_TIME;
        }
        for (int i = 0; i < mainTimings.size(); i++) {
            WordTiming wt = mainTimings.get(i);
            if (charIdx >= wt.start() && charIdx <= wt.end()) {
                int span = Math.max(1, wt.end() - wt.start());
                int offset = charIdx - wt.start();
                long dur = Math.max(1L, wt.endMs() - wt.startMs());
                return wt.startMs() + (dur * offset) / span;
            }
            if (i + 1 < mainTimings.size()) {
                WordTiming nxt = mainTimings.get(i + 1);
                if (charIdx > wt.end() && charIdx < nxt.start()) {
                    int gapSpan = Math.max(1, nxt.start() - wt.end());
                    int gapOffset = charIdx - wt.end();
                    long gapDur = Math.max(0L, nxt.startMs() - wt.endMs());
                    return wt.endMs() + (gapDur * gapOffset) / gapSpan;
                }
            }
        }
        WordTiming first = mainTimings.get(0);
        if (charIdx < first.start()) {
            return first.startMs();
        }
        WordTiming last = mainTimings.get(mainTimings.size() - 1);
        if (charIdx > last.end()) {
            return last.endMs();
        }
        return LyricsLine.NO_TIME;
    }

    public static boolean isSecondaryLyric(@Nullable LyricsLine line) {
        if (line == null) {
            return false;
        }
        if (line.isBG()) {
            return true;
        }
        return isSecondaryText(line.text());
    }

    public static boolean isSecondaryText(@Nullable String rawText) {
        if (rawText == null) {
            return false;
        }
        String text = cleanJapaneseQuotes(rawText).trim();
        // japanese square brackets should never be treated as secondary lyrics
        if (text.contains("【") || text.contains("】")) {
            return false;
        }
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '(' && last == ')')
                    || (first == '（' && last == '）')
                    || (first == '[' && last == ']')
                    || (first == '{' && last == '}')
                    || (first == '<' && last == '>')) {
                return true;
            }
        }
        String stripped = stripParentheses(text).trim();
        if (!stripped.isEmpty() && stripped.length() < text.length()) {
            return true;
        }
        return LRCParser.hasOuterParentheses(rawText);
    }

    public static boolean isSecondaryLyricLine(int index, @Nullable List<LyricsLine> lines) {
        if (lines == null || index <= 0 || index >= lines.size()) {
            return false;
        }
        LyricsLine line = lines.get(index);
        boolean candidate = line.isBG() || isSecondaryText(line.text());
        if (!candidate && index > 0) {
            // echo if lyric is duplicate with short gap
            LyricsLine prev = lines.get(index - 1);
            long gap = line.startTimeMs() - prev.startTimeMs();
            if (gap >= 0 && gap <= 3500L) {
                String cleanCurr = LRCParser.cleanTextForComparison(line.text());
                String cleanPrev = LRCParser.cleanTextForComparison(prev.text());
                if (!cleanCurr.isEmpty() && cleanCurr.equals(cleanPrev)) {
                    // never make japanese brackets secondary even on echo
                    if (!line.text().contains("【") && !line.text().contains("】")) {
                        candidate = true;
                    }
                }
            }
        }
        if (!candidate) {
            return false;
        }

        // secondary lyric must accompany previous main lyric
        int parentIdx = index - 1;
        while (parentIdx >= 0) {
            LyricsLine p = lines.get(parentIdx);
            if (!p.isBG() && !isSecondaryText(p.text())) {
                break;
            }
            parentIdx--;
        }
        if (parentIdx < 0) {
            return false;
        }

        // ttml background lines are confirmed secondary unless containing japanese brackets
        if (line.isBG() && !line.text().contains("【") && !line.text().contains("】")) {
            return true;
        }

        LyricsLine parentLine = lines.get(parentIdx);
        if (line.startTimeMs() != LyricsLine.NO_TIME && parentLine.startTimeMs() != LyricsLine.NO_TIME) {
            long timeDiff = line.startTimeMs() - parentLine.startTimeMs();
            if (timeDiff < -2000L || timeDiff > 30000L) {
                return false;
            }
            if (parentLine.endTimeMs() != LyricsLine.NO_TIME && parentLine.endTimeMs() > parentLine.startTimeMs()) {
                if (line.startTimeMs() > Math.max(parentLine.endTimeMs() + 4000L, parentLine.startTimeMs() + 15000L)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isSecondaryLineView(int index) {
        if (index <= 0) {
            return false;
        }
        if (index < lineViews.size()) {
            TextView tv = lineViews.get(index);
            if (tv instanceof LyricsLineView llv) {
                return llv.isSecondary() && llv.getParentLineIndex() >= 0;
            }
        }
        if (retimedLyrics != null && index < retimedLyrics.size()) {
            return retimedLyrics.get(index).line.isBG();
        }
        return false;
    }

    private static String getCleanLineText(LyricsLine line) {
        String text = line.text();
        if (text == null) {
            return "";
        }
        text = text.replace('\u2005', ' ').replace('\u3000', ' ').replace('\u00A0', ' ');
        text = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC);
        if (isSecondaryLyric(line)) {
            return cleanJapaneseQuotes(stripParentheses(text)).trim();
        }
        return cleanJapaneseQuotes(text).trim();
    }

    @Nullable
    private static List<LyricsLineView.WordUnit> formatSecondaryLyricsUnits(
            LyricsLine line, @Nullable List<LyricsLineView.WordUnit> units) {
        if (units == null || units.isEmpty() || !isSecondaryLyric(line)) {
            return units;
        }
        for (int i = 0; i < units.size(); i++) {
            LyricsLineView.WordUnit u = units.get(i);
            if (!u.romaji.isEmpty()) {
                u.romaji = cleanWordText(u.romaji).trim();
            }
        }
        return units;
    }

    @Nullable
    private static List<LyricsLineView.WordUnit> buildWordUnits(
            LyricsLine line, @Nullable LyricsLine rLine, List<WordTiming> mainTimings) {
        final boolean romanizeEnabled = Settings.LYRICS_ROMANIZE.get();
        final boolean wordSyncEnabled = Settings.LYRICS_WORD_SYNC.get();
        String rawText = getCleanLineText(line);
        if (rawText.isEmpty()) {
            return null;
        }

        boolean hasTimings = wordSyncEnabled && (mainTimings != null && !mainTimings.isEmpty());
        boolean needsRoma = romanizeEnabled && LyricsRomanizer.needsRomanization(rawText) && !LyricsRomanizer.isPurelyLatinScript(rawText);

        if (!needsRoma && !hasTimings) {
            return null;
        }

        String normRoma = "";
        if (needsRoma) {
            String rawRoma = "";
            if (rLine != null && rLine.text() != null) {
                String r = cleanJapaneseQuotes(stripParentheses(rLine.text())).trim();
                if (!r.isEmpty() && !hasCjk(r)) {
                    rawRoma = r.replace("～", " ").replace("~", " ");
                }
            }
            if (rawRoma.isEmpty() && mainTimings != null && !mainTimings.isEmpty()) {
                StringBuilder rSb = new StringBuilder();
                for (int ti = 0; ti < mainTimings.size(); ti++) {
                    String tr = mainTimings.get(ti).romaji();
                    if (tr != null && !tr.trim().isEmpty() && !hasCjk(tr)) {
                        if (rSb.length() > 0) rSb.append(" ");
                        rSb.append(tr.trim());
                    }
                }
                if (rSb.length() > 0) {
                    rawRoma = rSb.toString();
                }
            }
            normRoma = normalizeRomajiText(rawRoma).trim();
        }

        // 1. Japanese lines
        if (LyricsRomanizer.containsJapanese(rawText) || (LyricsRomanizer.isSongJapanese() && containsKanji(rawText))) {
            List<LyricsRomanizer.JapaneseSegment> segments;
            List<String> readings;
            try {
                segments = LyricsRomanizer.segmentJapaneseLine(rawText);
                readings = (needsRoma && !segments.isEmpty())
                        ? LyricsRomanizer.alignJapaneseSegments(segments, normRoma)
                        : Collections.emptyList();
            } catch (Exception t) {
                Logger.printDebug(() -> "Japanese segmentation error for: " + rawText, t);
                segments = Collections.emptyList();
                readings = Collections.emptyList();
            }

            List<LyricsLineView.WordUnit> units = new ArrayList<>();

            int s = 0;
            while (s < segments.size()) {
                LyricsRomanizer.JapaneseSegment seg = segments.get(s);
                int endS = s;
                // Merge kanji + okurigana into single WordUnit (e.g. 喜 + んで -> 喜んで, 差 + し -> 差し)
                while (endS + 1 < segments.size()) {
                    LyricsRomanizer.JapaneseSegment cur = segments.get(endS);
                    LyricsRomanizer.JapaneseSegment nxt = segments.get(endS + 1);
                    boolean noSpace = (cur.end == nxt.start) && (cur.end >= rawText.length() || !Character.isWhitespace(rawText.charAt(cur.end)));
                    if (!noSpace) {
                        break;
                    }
                    if ((containsKanji(cur.text) || isOkurigana(cur.text)) && !nxt.isParticle && isOkurigana(nxt.text)) {
                        endS++;
                    } else if (nxt.isParticle && !hasTimingStartingAt(nxt.start, mainTimings)) {
                        endS++;
                    } else {
                        break;
                    }
                }

                int mStart = Math.max(0, Math.min(rawText.length(), seg.start));
                int mEnd = Math.max(mStart, Math.min(rawText.length(), segments.get(endS).end));
                String kanji = rawText.substring(mStart, mEnd);
                String rom = needsRoma ? getJapaneseRomajiForRange(rawText, mStart, mEnd, segments, readings, mainTimings) : "";

                long startMs = LyricsLine.NO_TIME;
                long endMs = LyricsLine.NO_TIME;

                if (hasTimings) {
                    for (int ti = 0; ti < mainTimings.size(); ti++) {
                        WordTiming wt = mainTimings.get(ti);
                        if (wt.end() > mStart && wt.start() < mEnd) {
                            if (wt.startMs() != LyricsLine.NO_TIME && wt.endMs() != LyricsLine.NO_TIME) {
                                long sMs;
                                long eMs;
                                if (wt.start() <= mStart && wt.end() >= mEnd) {
                                    long totalDuration = wt.endMs() - wt.startMs();
                                    int totalChars = Math.max(1, wt.end() - wt.start());
                                    sMs = wt.startMs() + (totalDuration * (mStart - wt.start())) / totalChars;
                                    eMs = wt.startMs() + (totalDuration * (mEnd - wt.start())) / totalChars;
                                } else {
                                    sMs = wt.startMs();
                                    eMs = wt.endMs();
                                }
                                startMs = (startMs == LyricsLine.NO_TIME) ? sMs : Math.min(startMs, sMs);
                                endMs = (endMs == LyricsLine.NO_TIME) ? eMs : Math.max(endMs, eMs);
                            }
                        }
                    }
                    if (startMs == LyricsLine.NO_TIME || endMs == LyricsLine.NO_TIME) {
                        startMs = getInterpolatedCharTimeMs(mStart, false, mainTimings, rawText.length(), line.startTimeMs(), line.endTimeMs());
                        endMs = getInterpolatedCharTimeMs(mEnd, true, mainTimings, rawText.length(), line.startTimeMs(), line.endTimeMs());
                    }
                    if (startMs != LyricsLine.NO_TIME && endMs != LyricsLine.NO_TIME && endMs <= startMs) {
                        endMs = startMs + 1L;
                    }
                }

                boolean space = (mEnd < rawText.length() && Character.isWhitespace(rawText.charAt(mEnd)));
                units.add(new LyricsLineView.WordUnit(kanji, rom, startMs, endMs, space));
                s = endS + 1;
            }

            if (!units.isEmpty()) {
                ensureDistinctTimestamps(units);
                return formatSecondaryLyricsUnits(line, units);
            }
        }

        // 2. Korean lines
        if (LyricsRomanizer.containsHangul(rawText)) {
            if (hasTimings) {
                List<LyricsLineView.WordUnit> units = new ArrayList<>();
                int cursor = 0;
                for (int ti = 0; ti < mainTimings.size(); ti++) {
                    WordTiming wt = mainTimings.get(ti);
                    if (wt.end() <= wt.start() || wt.start() >= rawText.length()) {
                        continue;
                    }
                    int sIdx = Math.max(0, wt.start());
                    int eIdx = Math.min(rawText.length(), wt.end());

                    if (sIdx > cursor) {
                        String untimed = rawText.substring(cursor, sIdx);
                        if (!untimed.trim().isEmpty()) {
                            if (untimed.matches("[\\p{Punct}\\s]+|[\"“”‘’'\\s]+")) {
                                if (units.isEmpty()) {
                                    sIdx = cursor;
                                } else {
                                    LyricsLineView.WordUnit prev = units.get(units.size() - 1);
                                    boolean prevEndsWithSpace = prev.endsWithSpace
                                            || untimed.contains(" ")
                                            || (sIdx < rawText.length() && Character.isWhitespace(rawText.charAt(sIdx)));
                                    units.set(units.size() - 1, new LyricsLineView.WordUnit(
                                            prev.kanji + untimed.trim(), prev.romaji, prev.startMs, prev.endMs, prevEndsWithSpace));
                                }
                            } else {
                                long uStart = getInterpolatedCharTimeMs(cursor, false, mainTimings, rawText.length(), line.startTimeMs(), line.endTimeMs());
                                long uEnd = getInterpolatedCharTimeMs(sIdx, true, mainTimings, rawText.length(), line.startTimeMs(), line.endTimeMs());
                                boolean uSpace = (sIdx < rawText.length() && Character.isWhitespace(rawText.charAt(sIdx)));
                                String uRom = (needsRoma && LyricsRomanizer.containsHangul(untimed.trim()))
                                        ? LyricsRomanizer.romanizeHangul(untimed.trim()) : "";
                                units.add(new LyricsLineView.WordUnit(untimed.trim(), uRom, uStart, uEnd, uSpace));
                            }
                        } else if (!units.isEmpty() && untimed.contains(" ")) {
                            units.get(units.size() - 1).endsWithSpace = true;
                        }
                    }

                    String text = rawText.substring(sIdx, eIdx);
                    if (text.isEmpty()) {
                        cursor = eIdx;
                        continue;
                    }

                    boolean space = (eIdx < rawText.length() && Character.isWhitespace(rawText.charAt(eIdx)));
                    if (!space && ti < line.words().size() && line.words().get(ti).endsWithSpace()) {
                        space = true;
                    }
                    if (!space && ti + 1 < mainTimings.size()) {
                        WordTiming nextWt = mainTimings.get(ti + 1);
                        if (nextWt.start() > eIdx) {
                            String between = rawText.substring(eIdx, Math.min(rawText.length(), nextWt.start()));
                            if (between.contains(" ") || between.matches(".*\\s+.*")) {
                                space = true;
                            }
                        }
                    }
                    if (ti == mainTimings.size() - 1) {
                        space = true;
                    }

                    long sMs = wt.startMs();
                    long eMs = wt.endMs();
                    if (sMs == LyricsLine.NO_TIME || eMs == LyricsLine.NO_TIME) {
                        sMs = getInterpolatedCharTimeMs(sIdx, false, mainTimings, rawText.length(), line.startTimeMs(), line.endTimeMs());
                        eMs = getInterpolatedCharTimeMs(eIdx, true, mainTimings, rawText.length(), line.startTimeMs(), line.endTimeMs());
                    }
                    if (eMs <= sMs) {
                        eMs = sMs + 150L;
                    }

                    if (text.startsWith(" ")) {
                        if (!units.isEmpty()) {
                            units.get(units.size() - 1).endsWithSpace = true;
                        }
                        text = text.trim();
                    }
                    if (text.endsWith(" ")) {
                        space = true;
                        text = text.trim();
                    }
                    if (text.isEmpty()) {
                        cursor = eIdx;
                        continue;
                    }

                    String itemRom = "";
                    if (needsRoma) {
                        if (LyricsRomanizer.containsHangul(text)) {
                            if (text.length() == 1) {
                                Character nextC = (eIdx < rawText.length()) ? rawText.charAt(eIdx) : null;
                                itemRom = LyricsRomanizer.romanizeHangulChar(text.charAt(0), nextC);
                            } else {
                                itemRom = LyricsRomanizer.romanizeHangul(text);
                            }
                        } else {
                            itemRom = "";
                        }
                    }

                    if (text.contains(" ")) {
                        String[] parts = text.split("\\s+");
                        int partChars = 0;
                        for (String p : parts) partChars += p.length();
                        long totalDur = eMs - sMs;
                        long curPartStart = sMs;
                        for (int pi = 0; pi < parts.length; pi++) {
                            String part = parts[pi];
                            if (part.isEmpty()) continue;
                            long pDur = partChars > 0 ? (totalDur * part.length()) / partChars : totalDur / parts.length;
                            long pEnd = curPartStart + Math.max(1L, pDur);
                            boolean isLastPart = (pi == parts.length - 1);
                            boolean partSpace = isLastPart ? space : true;
                            String pRom = (needsRoma && LyricsRomanizer.containsHangul(part))
                                    ? LyricsRomanizer.romanizeHangul(part) : "";
                            units.add(new LyricsLineView.WordUnit(part, pRom, curPartStart, pEnd, partSpace));
                            curPartStart = pEnd;
                        }
                    } else {
                        units.add(new LyricsLineView.WordUnit(text, itemRom, sMs, eMs, space));
                    }

                    cursor = eIdx;
                }

                if (cursor < rawText.length()) {
                    String trailing = rawText.substring(cursor).trim();
                    if (!trailing.isEmpty() && !units.isEmpty()) {
                        LyricsLineView.WordUnit last = units.get(units.size() - 1);
                        units.set(units.size() - 1, new LyricsLineView.WordUnit(
                                last.kanji + trailing, last.romaji, last.startMs, last.endMs, true));
                    }
                }

                if (!units.isEmpty()) {
                    ensureDistinctTimestamps(units);
                    return formatSecondaryLyricsUnits(line, units);
                }
            } else {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\S+").matcher(rawText);
                List<LyricsLineView.WordUnit> units = new ArrayList<>();
                while (m.find()) {
                    int wEnd = m.end();
                    String word = m.group();
                    boolean space = (wEnd < rawText.length() && Character.isWhitespace(rawText.charAt(wEnd)));
                    String rom = "";
                    if (needsRoma && LyricsRomanizer.containsHangul(word)) {
                        rom = LyricsRomanizer.romanizeHangul(word);
                    }
                    units.add(new LyricsLineView.WordUnit(word, rom, line.startTimeMs(), line.endTimeMs(), space));
                }
                if (!units.isEmpty()) {
                    return formatSecondaryLyricsUnits(line, units);
                }
            }
        }

        // 3. Chinese lines
        if (LyricsRomanizer.containsHanzi(rawText) && !LyricsRomanizer.isSongJapanese()) {
            List<LyricsLineView.WordUnit> units = new ArrayList<>(rawText.length());

            String[] romaTokens = null;
            if (needsRoma && !normRoma.isEmpty()) {
                String[] split = normRoma.split("\\s+");
                if (split.length > 0) {
                    romaTokens = split;
                }
            }

            int charIdx = 0;
            for (int i = 0; i < rawText.length(); ) {
                int codePoint = rawText.codePointAt(i);
                int charCount = Character.charCount(codePoint);
                int chStart = i;
                int chEnd = i + charCount;
                String ch = rawText.substring(chStart, chEnd);
                if (ch.trim().isEmpty()) {
                    i += charCount;
                    continue;
                }

                String r = "";
                if (needsRoma) {
                    if (LyricsRomanizer.isPurelyLatinScript(ch)) {
                        r = "";
                    } else {
                        if (hasTimings) {
                            for (WordTiming wt : mainTimings) {
                                if (wt.end() > chStart && wt.start() < chEnd && wt.romaji() != null && !wt.romaji().isEmpty()) {
                                    r = wt.romaji();
                                    break;
                                }
                            }
                        }
                        if (r.isEmpty() && romaTokens != null && charIdx < romaTokens.length) {
                            r = romaTokens[charIdx];
                        }
                        if (!r.isEmpty()) {
                            r = normalizeRomajiText(r).trim();
                        }
                    }
                }
                charIdx++;
                long startMs = LyricsLine.NO_TIME;
                long endMs = LyricsLine.NO_TIME;

                if (hasTimings) {
                    for (WordTiming wt : mainTimings) {
                        if (wt.end() > chStart && wt.start() < chEnd) {
                            if (wt.startMs() != LyricsLine.NO_TIME && wt.endMs() != LyricsLine.NO_TIME) {
                                long totalDuration = wt.endMs() - wt.startMs();
                                int totalChars = Math.max(1, wt.end() - wt.start());
                                int ss = Math.max(wt.start(), chStart);
                                int se = Math.min(wt.end(), chEnd);
                                long sMs = wt.startMs() + (totalDuration * (ss - wt.start())) / totalChars;
                                long eMs = wt.startMs() + (totalDuration * (se - wt.start())) / totalChars;
                                if (eMs <= sMs) eMs = sMs + 1L;
                                startMs = (startMs == LyricsLine.NO_TIME) ? sMs : Math.min(startMs, sMs);
                                endMs = (endMs == LyricsLine.NO_TIME) ? eMs : Math.max(endMs, eMs);
                            }
                        }
                    }
                    if (startMs == LyricsLine.NO_TIME || endMs == LyricsLine.NO_TIME) {
                        startMs = getInterpolatedCharTimeMs(chStart, false, mainTimings, rawText.length(), line.startTimeMs(), line.endTimeMs());
                        endMs = getInterpolatedCharTimeMs(chEnd, true, mainTimings, rawText.length(), line.startTimeMs(), line.endTimeMs());
                    }
                    if (startMs != LyricsLine.NO_TIME && endMs != LyricsLine.NO_TIME && endMs <= startMs) {
                        endMs = startMs + 1L;
                    }
                }

                units.add(new LyricsLineView.WordUnit(ch, r, startMs, endMs, true));
                i += charCount;
            }
            if (!units.isEmpty()) {
                ensureDistinctTimestamps(units);
                return formatSecondaryLyricsUnits(line, units);
            }
        }

        // 4. Cyrillic (Russian, etc.) lines
        if (LyricsRomanizer.containsCyrillic(rawText)) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\S+").matcher(rawText);
            List<LyricsLineView.WordUnit> units = new ArrayList<>();

            String[] romaTokens = null;
            if (needsRoma && !normRoma.isEmpty()) {
                String[] split = normRoma.split("\\s+");
                if (split.length > 0) {
                    romaTokens = split;
                }
            }

            int totalWords = 0;
            while (m.find()) {
                totalWords++;
            }
            m.reset();

            final boolean tokenCountMatches = (romaTokens != null && romaTokens.length == totalWords);
            int wordIdx = 0;

            while (m.find()) {
                int wStart = m.start();
                int wEnd = m.end();
                String word = m.group();
                boolean space = (wEnd < rawText.length() && Character.isWhitespace(rawText.charAt(wEnd)));

                String rom = "";
                if (needsRoma) {
                    if (LyricsRomanizer.isPurelyLatinScript(word)) {
                        rom = "";
                    } else {
                        if (hasTimings) {
                            for (WordTiming wt : mainTimings) {
                                if (wt.end() > wStart && wt.start() < wEnd && wt.romaji() != null && !wt.romaji().isEmpty()) {
                                    rom = wt.romaji();
                                    break;
                                }
                            }
                        }
                        if (rom.isEmpty() && wordIdx < mainTimings.size() && mainTimings.get(wordIdx).romaji() != null && !mainTimings.get(wordIdx).romaji().isEmpty()) {
                            rom = mainTimings.get(wordIdx).romaji();
                        } else if (rom.isEmpty() && tokenCountMatches && wordIdx < romaTokens.length) {
                            rom = romaTokens[wordIdx];
                        } else if (rom.isEmpty() && romaTokens != null && wordIdx < romaTokens.length) {
                            rom = romaTokens[wordIdx];
                        }
                        if (!rom.isEmpty()) {
                            rom = normalizeRomajiText(rom).trim();
                        }
                    }
                }

                long startMs = LyricsLine.NO_TIME;
                long endMs = LyricsLine.NO_TIME;

                if (hasTimings) {
                    for (WordTiming wt : mainTimings) {
                        if (wt.end() > wStart && wt.start() < wEnd) {
                            if (wt.startMs() != LyricsLine.NO_TIME && wt.endMs() != LyricsLine.NO_TIME) {
                                long totalDuration = wt.endMs() - wt.startMs();
                                int totalChars = Math.max(1, wt.end() - wt.start());
                                int ss = Math.max(wt.start(), wStart);
                                int se = Math.min(wt.end(), wEnd);
                                long sMs = wt.startMs() + (totalDuration * (ss - wt.start())) / totalChars;
                                long eMs = wt.startMs() + (totalDuration * (se - wt.start())) / totalChars;
                                if (eMs <= sMs) eMs = sMs + 1L;
                                startMs = (startMs == LyricsLine.NO_TIME) ? sMs : Math.min(startMs, sMs);
                                endMs = (endMs == LyricsLine.NO_TIME) ? eMs : Math.max(endMs, eMs);
                            }
                        }
                    }
                    if (startMs == LyricsLine.NO_TIME || endMs == LyricsLine.NO_TIME) {
                        startMs = getInterpolatedCharTimeMs(wStart, false, mainTimings, rawText.length(), line.startTimeMs(), line.endTimeMs());
                        endMs = getInterpolatedCharTimeMs(wEnd, true, mainTimings, rawText.length(), line.startTimeMs(), line.endTimeMs());
                    }
                    if (startMs != LyricsLine.NO_TIME && endMs != LyricsLine.NO_TIME && endMs <= startMs) {
                        endMs = startMs + 1L;
                    }
                }

                units.add(new LyricsLineView.WordUnit(word, rom, startMs, endMs, space));
                wordIdx++;
            }
            if (!units.isEmpty()) {
                ensureDistinctTimestamps(units);
                return formatSecondaryLyricsUnits(line, units);
            }
        }

        // 5. Arabic / Hebrew (RTL) lines
        if (LyricsRomanizer.containsArabic(rawText) || LyricsRomanizer.containsHebrew(rawText)) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\S+").matcher(rawText);
            List<LyricsLineView.WordUnit> units = new ArrayList<>();

            String[] romaTokens = null;
            if (needsRoma && !normRoma.isEmpty()) {
                String[] split = normRoma.split("\\s+");
                if (split.length > 0) {
                    romaTokens = split;
                }
            }

            int totalWords = 0;
            while (m.find()) {
                totalWords++;
            }
            m.reset();

            final boolean tokenCountMatches = (romaTokens != null && romaTokens.length == totalWords);
            int wordIdx = 0;

            while (m.find()) {
                int wStart = m.start();
                int wEnd = m.end();
                String word = m.group();
                boolean space = (wEnd < rawText.length() && Character.isWhitespace(rawText.charAt(wEnd)));

                String rom = "";
                if (needsRoma) {
                    if (LyricsRomanizer.isPurelyLatinScript(word)) {
                        rom = "";
                    } else {
                        if (hasTimings) {
                            for (WordTiming wt : mainTimings) {
                                if (wt.end() > wStart && wt.start() < wEnd && wt.romaji() != null && !wt.romaji().isEmpty()) {
                                    rom = wt.romaji();
                                    break;
                                }
                            }
                        }
                        if (rom.isEmpty() && wordIdx < mainTimings.size() && mainTimings.get(wordIdx).romaji() != null && !mainTimings.get(wordIdx).romaji().isEmpty()) {
                            rom = mainTimings.get(wordIdx).romaji();
                        } else if (rom.isEmpty() && tokenCountMatches && wordIdx < romaTokens.length) {
                            rom = romaTokens[wordIdx];
                        } else if (rom.isEmpty() && romaTokens != null && wordIdx < romaTokens.length) {
                            rom = romaTokens[wordIdx];
                        }
                        if (!rom.isEmpty()) {
                            rom = normalizeRomajiText(rom).trim();
                        }
                    }
                }

                long startMs = LyricsLine.NO_TIME;
                long endMs = LyricsLine.NO_TIME;

                if (hasTimings) {
                    for (WordTiming wt : mainTimings) {
                        if (wt.end() > wStart && wt.start() < wEnd) {
                            if (wt.startMs() != LyricsLine.NO_TIME && wt.endMs() != LyricsLine.NO_TIME) {
                                long totalDuration = wt.endMs() - wt.startMs();
                                int totalChars = Math.max(1, wt.end() - wt.start());
                                int ss = Math.max(wt.start(), wStart);
                                int se = Math.min(wt.end(), wEnd);
                                long sMs = wt.startMs() + (totalDuration * (ss - wt.start())) / totalChars;
                                long eMs = wt.startMs() + (totalDuration * (se - wt.start())) / totalChars;
                                if (eMs <= sMs) eMs = sMs + 1L;
                                startMs = (startMs == LyricsLine.NO_TIME) ? sMs : Math.min(startMs, sMs);
                                endMs = (endMs == LyricsLine.NO_TIME) ? eMs : Math.max(endMs, eMs);
                            }
                        }
                    }
                    if (startMs == LyricsLine.NO_TIME || endMs == LyricsLine.NO_TIME) {
                        startMs = getInterpolatedCharTimeMs(wStart, false, mainTimings, rawText.length(), line.startTimeMs(), line.endTimeMs());
                        endMs = getInterpolatedCharTimeMs(wEnd, true, mainTimings, rawText.length(), line.startTimeMs(), line.endTimeMs());
                    }
                    if (startMs != LyricsLine.NO_TIME && endMs != LyricsLine.NO_TIME && endMs <= startMs) {
                        endMs = startMs + 1L;
                    }
                }

                units.add(new LyricsLineView.WordUnit(word, rom, startMs, endMs, space));
                wordIdx++;
            }
            if (!units.isEmpty()) {
                ensureDistinctTimestamps(units);
                return formatSecondaryLyricsUnits(line, units);
            }
        }

        // 6. Latin / Non-CJK lines with word sync timings
        if (hasTimings) {
            List<LyricsLineView.WordUnit> units = new ArrayList<>();
            int cursor = 0;
            for (int ti = 0; ti < mainTimings.size(); ti++) {
                WordTiming wt = mainTimings.get(ti);
                if (wt.end() <= wt.start() || wt.start() >= rawText.length()) {
                    continue;
                }
                int sIdx = Math.max(0, wt.start());
                int eIdx = Math.min(rawText.length(), wt.end());

                if (sIdx > cursor) {
                    String untimed = rawText.substring(cursor, sIdx);
                    if (!untimed.trim().isEmpty()) {
                        if (untimed.matches("[\\p{Punct}\\s]+|[\"“”‘’'\\s]+")) {
                            if (units.isEmpty()) {
                                sIdx = cursor;
                            } else {
                                LyricsLineView.WordUnit prev = units.get(units.size() - 1);
                                boolean prevEndsWithSpace = prev.endsWithSpace
                                        || untimed.contains(" ")
                                        || (sIdx < rawText.length() && Character.isWhitespace(rawText.charAt(sIdx)));
                                units.set(units.size() - 1, new LyricsLineView.WordUnit(
                                        prev.kanji + untimed.trim(), "", prev.startMs, prev.endMs, prevEndsWithSpace));
                            }
                        } else {
                            long uStart = getInterpolatedCharTimeMs(cursor, false, mainTimings, rawText.length(), line.startTimeMs(), line.endTimeMs());
                            long uEnd = getInterpolatedCharTimeMs(sIdx, true, mainTimings, rawText.length(), line.startTimeMs(), line.endTimeMs());
                            boolean uSpace = (sIdx < rawText.length() && Character.isWhitespace(rawText.charAt(sIdx)));
                            String uRom = "";
                            units.add(new LyricsLineView.WordUnit(untimed.trim(), uRom, uStart, uEnd, uSpace));
                        }
                    } else if (!units.isEmpty() && untimed.contains(" ")) {
                        units.get(units.size() - 1).endsWithSpace = true;
                    }
                }

                String text = rawText.substring(sIdx, eIdx);
                if (text.isEmpty()) {
                    cursor = eIdx;
                    continue;
                }

                boolean space = (eIdx < rawText.length() && Character.isWhitespace(rawText.charAt(eIdx)));
                if (!space && ti < line.words().size() && line.words().get(ti).endsWithSpace()) {
                    space = true;
                }
                if (!space && ti + 1 < mainTimings.size()) {
                    WordTiming nextWt = mainTimings.get(ti + 1);
                    if (nextWt.start() > eIdx) {
                        String between = rawText.substring(eIdx, Math.min(rawText.length(), nextWt.start()));
                        if (between.contains(" ") || between.matches(".*\\s+.*")) {
                            space = true;
                        }
                    }
                }

                long sMs = wt.startMs();
                long eMs = wt.endMs();
                if (sMs == LyricsLine.NO_TIME || eMs == LyricsLine.NO_TIME) {
                    sMs = getInterpolatedCharTimeMs(sIdx, false, mainTimings, rawText.length(), line.startTimeMs(), line.endTimeMs());
                    eMs = getInterpolatedCharTimeMs(eIdx, true, mainTimings, rawText.length(), line.startTimeMs(), line.endTimeMs());
                }
                if (eMs <= sMs) {
                    eMs = sMs + 150L;
                }

                String itemRom = "";
                if (needsRoma) {
                    if (wt.romaji() != null && !wt.romaji().isEmpty()) {
                        itemRom = normalizeRomajiText(wt.romaji()).trim();
                    }
                }

                if (text.contains(" ")) {
                    String[] parts = text.split("\\s+");
                    int partChars = 0;
                    for (String p : parts) partChars += p.length();
                    long totalDur = eMs - sMs;
                    long curPartStart = sMs;
                    String[] romParts = (!itemRom.isEmpty() && itemRom.contains(" ")) ? itemRom.split("\\s+") : null;
                    for (int pIdx = 0; pIdx < parts.length; pIdx++) {
                        String part = parts[pIdx];
                        if (part.isEmpty()) continue;
                        long partDur = (partChars > 0) ? (totalDur * part.length() / partChars) : (totalDur / parts.length);
                        long partEnd = (pIdx == parts.length - 1) ? eMs : (curPartStart + partDur);
                        boolean pSpace = (pIdx < parts.length - 1) || space;
                        String partRom = (romParts != null && pIdx < romParts.length) ? romParts[pIdx]
                                : (pIdx == 0 && romParts == null ? itemRom : "");
                        units.add(new LyricsLineView.WordUnit(part, partRom, curPartStart, partEnd, pSpace));
                        curPartStart = partEnd;
                    }
                } else {
                    units.add(new LyricsLineView.WordUnit(text, itemRom, sMs, eMs, space));
                }
                cursor = eIdx;
            }

            if (cursor < rawText.length()) {
                String trailing = rawText.substring(cursor);
                if (!trailing.trim().isEmpty()) {
                    if (!units.isEmpty() && trailing.matches("[\\p{Punct}\\s]+|[\"“”‘’'\\s]+")) {
                        LyricsLineView.WordUnit prev = units.get(units.size() - 1);
                        units.set(units.size() - 1, new LyricsLineView.WordUnit(
                                prev.kanji + trailing.trim(), "", prev.startMs, prev.endMs, prev.endsWithSpace));
                    } else {
                        long uStart = getInterpolatedCharTimeMs(cursor, false, mainTimings, rawText.length(), line.startTimeMs(), line.endTimeMs());
                        long uEnd = getInterpolatedCharTimeMs(rawText.length(), true, mainTimings, rawText.length(), line.startTimeMs(), line.endTimeMs());
                        String uRom = "";
                        units.add(new LyricsLineView.WordUnit(trailing.trim(), uRom, uStart, uEnd, false));
                    }
                }
            }

            if (!units.isEmpty()) {
                ensureDistinctTimestamps(units);
                return formatSecondaryLyricsUnits(line, units);
            }
        }

        return null;
    }

    static boolean containsKanji(@Nullable String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            if (LyricsRomanizer.isKanji(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOkurigana(@Nullable String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!LyricsRomanizer.isHiragana(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasTimingStartingAt(int charOffset, @Nullable List<WordTiming> timings) {
        if (timings == null || timings.isEmpty()) return false;
        for (int i = 0; i < timings.size(); i++) {
            if (timings.get(i).start() == charOffset) return true;
        }
        return false;
    }

    private static String stripRemainingCjk(@Nullable String s) {
        if (s == null || s.isEmpty()) return "";
        if (!hasCjk(s)) return s;
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!WordAnimationUtils.isCJKChar(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String getJapaneseRomajiForRange(
            String rawText, int start, int end,
            List<LyricsRomanizer.JapaneseSegment> segments,
            List<String> readings,
            @Nullable List<WordTiming> mainTimings) {
        if (start >= end || start >= rawText.length()) return "";
        StringBuilder sb = new StringBuilder();
        if (segments != null && readings != null) {
            for (int k = 0; k < segments.size(); k++) {
                LyricsRomanizer.JapaneseSegment seg = segments.get(k);
                if (seg.end > start && seg.start < end) {
                    String r = (k < readings.size()) ? readings.get(k) : null;
                    if (r == null || r.isEmpty() || containsKanji(r)) {
                        String fromTiming = null;
                        if (mainTimings != null) {
                            for (int ti = 0; ti < mainTimings.size(); ti++) {
                                WordTiming wt = mainTimings.get(ti);
                                if (wt.end() > seg.start && wt.start() < seg.end
                                        && wt.romaji() != null && !wt.romaji().trim().isEmpty()
                                        && !hasCjk(wt.romaji())) {
                                    fromTiming = wt.romaji().trim();
                                    break;
                                }
                            }
                        }
                        if (fromTiming != null && !fromTiming.isEmpty()) {
                            r = fromTiming;
                        } else {
                            r = stripRemainingCjk(LyricsRomanizer.romanizeJapanese(seg.text));
                        }
                    } else if (hasCjk(r)) {
                        r = stripRemainingCjk(LyricsRomanizer.romanizeJapanese(r));
                    }
                    if (r != null && !r.isEmpty()) {
                        sb.append(r);
                    }
                }
            }
        }
        if (sb.length() == 0 && mainTimings != null) {
            for (int ti = 0; ti < mainTimings.size(); ti++) {
                WordTiming wt = mainTimings.get(ti);
                if (wt.end() > start && wt.start() < end
                        && wt.romaji() != null && !wt.romaji().trim().isEmpty()
                        && !hasCjk(wt.romaji())) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(wt.romaji().trim());
                }
            }
        }
        if (sb.length() == 0) {
            String sub = rawText.substring(start, Math.min(end, rawText.length()));
            String r = stripRemainingCjk(LyricsRomanizer.romanizeJapanese(sub));
            if (r != null) sb.append(r);
        }
        return normalizeRomajiText(stripRemainingCjk(sb.toString())).trim();
    }

    private static void ensureDistinctTimestamps(List<LyricsLineView.WordUnit> units) {
        if (units == null || units.size() < 2) {
            return;
        }

        // Pass 1: Fill any remaining missing timestamps from adjacent units
        for (int i = 0; i < units.size(); i++) {
            LyricsLineView.WordUnit u = units.get(i);
            if (u.startMs == LyricsLine.NO_TIME || u.endMs == LyricsLine.NO_TIME) {
                if (i > 0 && units.get(i - 1).endMs != LyricsLine.NO_TIME) {
                    u.startMs = units.get(i - 1).endMs;
                }
                if (i + 1 < units.size() && units.get(i + 1).startMs != LyricsLine.NO_TIME) {
                    u.endMs = units.get(i + 1).startMs;
                }
                if (u.startMs != LyricsLine.NO_TIME && u.endMs == LyricsLine.NO_TIME) {
                    u.endMs = u.startMs + 250L;
                } else if (u.startMs == LyricsLine.NO_TIME && u.endMs != LyricsLine.NO_TIME) {
                    u.startMs = Math.max(0L, u.endMs - 250L);
                }
                if (u.startMs != LyricsLine.NO_TIME && u.endMs <= u.startMs) {
                    u.endMs = u.startMs + 1L;
                }
            }
        }

        // Pass 2: Enforce strict chronological non-overlapping monotonicity
        for (int i = 1; i < units.size(); i++) {
            LyricsLineView.WordUnit prev = units.get(i - 1);
            LyricsLineView.WordUnit curr = units.get(i);
            if (prev.startMs != LyricsLine.NO_TIME && prev.endMs != LyricsLine.NO_TIME
                    && curr.startMs != LyricsLine.NO_TIME && curr.endMs != LyricsLine.NO_TIME) {
                if (curr.startMs <= prev.startMs) {
                    long span = Math.max(2L, prev.endMs - prev.startMs);
                    curr.startMs = prev.startMs + (span / 2);
                    prev.endMs = curr.startMs;
                    if (curr.endMs <= curr.startMs) {
                        curr.endMs = curr.startMs + 1L;
                    }
                } else if (curr.startMs < prev.endMs) {
                    prev.endMs = curr.startMs;
                }
                if (prev.endMs <= prev.startMs) {
                    prev.endMs = prev.startMs + 1L;
                }
                if (curr.endMs <= curr.startMs) {
                    curr.endMs = curr.startMs + 1L;
                }
            }
        }
    }

    public static boolean isSkipTranslation(@Nullable String s) {
        if (s == null) return true;
        String t = s.trim();
        return t.isEmpty() || t.equalsIgnoreCase("SKIP") || t.equalsIgnoreCase("[SKIP]")
                || t.equalsIgnoreCase("<SKIP>") || t.equalsIgnoreCase("(SKIP)")
                || t.equalsIgnoreCase("SKIP.");
    }

    /**
     * Builds the displayed text for a line, appending the translation (when shown) in a
     * smaller, dimmer style and coloring each word sung or unsung for the karaoke
     * highlight.
     *
     * <p>A fresh {@link SpannableString} is returned on every call so that
     * {@link android.widget.TextView#setText(CharSequence)} performs a full re-layout
     * and repaint. Mutating an existing Spannable in place was not reliably redrawn by
     * this TextView, which left the highlight invisible.
     */
    private BuildResult buildLineText(LyricsLine line, List<WordTiming> timings, int index, boolean hasWordUnits) {
        String original = getCleanLineText(line);
        String originalTrimmed = original;

        String romanization = null;
        if (!hasWordUnits && romanizedLines != null && index < romanizedLines.size()) {
            LyricsLine rLine = romanizedLines.get(index);
            String rawRoma = rLine != null ? rLine.text() : null;
            if (rawRoma != null) {
                String roma = rawRoma.trim();
                if (!roma.isEmpty() && !roma.equalsIgnoreCase(originalTrimmed)) {
                    if (isSecondaryLyric(line)) {
                        String cleanRoma = stripParentheses(roma).trim();
                        if (!cleanRoma.isEmpty()) {
                            roma = cleanRoma;
                        }
                    }
                    romanization = roma;
                }
            }
        }


        List<String> translated = translatedLines;
        String translation = null;
        if (!hasWordUnits && translated != null && index < translated.size()) {
            String rawT = translated.get(index);
            if (rawT != null) {
                String t = rawT.trim();
                if (!t.isEmpty() && !isSkipTranslation(t) && !t.equalsIgnoreCase(originalTrimmed)) {
                    translation = t;
                }
            }
        }

        StringBuilder builder = new StringBuilder();
        int romaStart = -1;
        int romaEnd = -1;
        int transStart = -1;
        int transEnd = -1;

        // Layer 1 (Top): Main vocal text (Kanji/Kana) always first!
        final int originalStart = 0;
        builder.append(original);
        final int originalEnd = builder.length();

        // For non-WordUnit lines, append Layer 2 (Romaji) and Layer 3 (Translation) below
        if (!hasWordUnits) {
            if (romanization != null && !romanization.isEmpty()) {
                builder.append('\n');
                romaStart = builder.length();
                builder.append(romanization);
                romaEnd = builder.length();
            }
            if (translation != null && !translation.isEmpty()) {
                builder.append('\n');
                transStart = builder.length();
                builder.append(translation);
                transEnd = builder.length();
            }
        }

        SpannableString text = new SpannableString(builder.toString());
        ForegroundColorSpan unsungSpan = applySpans(text, timings, originalStart, originalEnd,
                romaStart, romaEnd, transStart, transEnd);
        return new BuildResult(text, unsungSpan, transStart, transEnd, romaStart, romaEnd);
    }

    @Nullable
    private static ForegroundColorSpan applySpans(SpannableString text, List<WordTiming> timings,
            int originalStart, int originalEnd,
            int romaStart, int romaEnd, int transStart, int transEnd) {
        ForegroundColorSpan unsungSpan = null;
        if (romaStart >= 0 && romaEnd > romaStart) {
            text.setSpan(new RelativeSizeSpan(ROMAJI_RELATIVE_SIZE), romaStart, romaEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new ForegroundColorSpan(auxiliaryTextColor()), romaStart, romaEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (transStart >= 0 && transEnd > transStart) {
            text.setSpan(new RelativeSizeSpan(TRANSLATION_RELATIVE_SIZE), transStart, transEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int transColor = auxiliaryTextColor();
            int alpha58 = Math.round(Color.alpha(transColor) * 0.58f);
            int transWithAlpha = Color.argb(alpha58, Color.red(transColor), Color.green(transColor), Color.blue(transColor));
            text.setSpan(new ForegroundColorSpan(transWithAlpha), transStart, transEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        if (Settings.LYRICS_WORD_SYNC.get() && !timings.isEmpty()) {
            int unsung = unsungWordColor();
            unsungSpan = new ForegroundColorSpan(unsung);
            text.setSpan(unsungSpan, originalStart, originalEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        return unsungSpan;
    }

    private void requestTranslation(@NonNull Lyrics targetLyrics) {
        TrackInfo track = LyricsManager.getInstance().getCurrentTrack();
        if (track == null || translateInProgress) {
            return;
        }
        translateInProgress = true;
        setButtonLabel(translateView, str("morphe_music_lyrics_translating"), true);

        LyricsTranslator.translate(track, targetLyrics, targetLyrics.providerName(),
                (lines, fromGoogle, fromAI, model) -> {
            if (lyrics != targetLyrics || !translateInProgress) {
                return;
            }
            translateInProgress = false;

            final boolean transOk = (lines != null && !lines.isEmpty());
            translatedLines = transOk ? lines : null;
            translatedFromGoogle = transOk && fromGoogle;
            translatedFromAI = transOk && fromAI;
            if (translatedFromAI && model != null) {
                aiModelName = model;
            }
            if (transOk) {
                setButtonLabel(translateView, str("morphe_music_lyrics_translate_hide"), true);
                handler.postDelayed(this::updateTranslateLabel, 3000);
            } else {
                setButtonLabel(translateView, null, false);
            }
            if (!applyTranslationsInPlace(targetLyrics)) {
                showLyrics(targetLyrics, true);
            }
        });
    }

    private void requestRomanization(@NonNull Lyrics targetLyrics) {
        TrackInfo track = LyricsManager.getInstance().getCurrentTrack();
        if (track == null || romanizeInProgress) {
            return;
        }
        romanizeInProgress = true;
        setButtonLabel(romanizeView, str("morphe_music_lyrics_romanizing"), true);

        LyricsRomanizer.romanize(track, targetLyrics, targetLyrics.providerName(),
                (lines, fromGoogle, fromAI, model, perWord, localDictionary) -> {
            if (lyrics != targetLyrics || !romanizeInProgress) {
                return;
            }
            romanizeInProgress = false;

            final boolean romaOk = LyricsMerge.hasText(lines);
            romanizedLines = romaOk ? lines : null;
            romanizedFromGoogle = romaOk && fromGoogle;
            romanizedFromAI = romaOk && fromAI;
            if (romanizedFromAI && model != null) {
                aiModelName = model;
            }
            romanizedLocalDictionary = romaOk ? localDictionary : null;
            perWordRomaji = romaOk && perWord;
            if (romaOk) {
                setButtonLabel(romanizeView, str("morphe_music_lyrics_romanize_hide"), true);
                handler.postDelayed(this::updateRomanizeLabel, 3000);
            } else {
                setButtonLabel(romanizeView, null, false);
            }
            showLyrics(targetLyrics, true);
        });
    }

    private void onTranslateClicked() {
        try {
            isBrowsing = false;
            userScrollUntilUptimeMs = 0;
            handler.removeCallbacks(resumeFollowRunnable);
            hideJumpToCurrentButton(true);
            // remember translation state across songs
            if (translateView == null) {
                return;
            }

            Lyrics current = lyrics;
            TrackInfo track = LyricsManager.getInstance().getCurrentTrack();
            if (current == null || track == null) {
                return;
            }

            if (translateInProgress) {
                translateInProgress = false;
                Settings.LYRICS_TRANSLATE.save(false);
                translatedLines = null;
                translatedFromGoogle = false;
                translatedFromAI = false;
                aiModelName = null;
                setButtonLabel(translateView, null, false);
                return;
            }

            if (translatedLines != null) {
                Settings.LYRICS_TRANSLATE.save(false);
                translatedLines = null;
                translatedFromGoogle = false;
                translatedFromAI = false;
                aiModelName = null;
                if (!applyTranslationsInPlace(current)) {
                    showLyrics(current, true);
                }
                return;
            }

            Settings.LYRICS_TRANSLATE.save(true);
            requestTranslation(current);
        } catch (Exception ex) {
            Logger.printDebug(() -> "onTranslateClicked failure", ex);
        }
    }

    private boolean applyTranslationsInPlace(@NonNull Lyrics current) {
        if (lyrics != current || lineViews.isEmpty() || lineViews.size() != current.lines().size()) {
            return false;
        }
        suppressContainerLayout(linesContainer, true);
        try {
            final int count = lineViews.size();
            for (int i = 0; i < count; i++) {
                LyricsLine origLineObj = current.lines().get(i);
                String origText = origLineObj.text();
                String origTrim = origText != null ? origText.trim() : "";
                String trans = null;
                if (translatedLines != null && i < translatedLines.size()) {
                    String rawT = translatedLines.get(i);
                    if (rawT != null) {
                        String t = rawT.trim();
                        if (!t.isEmpty() && !isSkipTranslation(t) && !t.equalsIgnoreCase(origTrim)) {
                            trans = t;
                        }
                    }
                }
                TextView tv = lineViews.get(i);
                if (tv instanceof LyricsLineView lineView && lineView.hasWordUnits()) {
                    lineView.setTranslationText(trans);
                } else {
                    List<WordTiming> timings = (i < lineWordSpans.size())
                            ? lineWordSpans.get(i) : Collections.emptyList();
                    BuildResult result = buildLineText(origLineObj, timings, i, false);
                    tv.setText(result.text());
                    lineUnsungSpans.set(i, result.unsungSpan());
                    if (tv instanceof LyricsLineView lineView) {
                        lineView.setTranslationBounds(result.transStart(), result.transEnd());
                        lineView.setRomanizationBounds(result.romaStart(), result.romaEnd());
                    }
                }
            }
        } finally {
            suppressContainerLayout(linesContainer, false);
        }
        footerView.setText(sourceText(current.providerName(),
                translatedLines != null, translatedFromGoogle, translatedFromAI,
                romanizedFromGoogle, romanizedFromAI, aiModelName, romanizedLocalDictionary));
        updateTranslateLabel();
        updateRomanizeLabel();
        updateLinesDepthOfField(activeIndicesSet, primaryActiveIndex, -1);
        return true;
    }

    private void updateTranslateLabel() {
        if (translateView != null) {
            setButtonLabel(translateView, null, translatedLines != null);
        }
    }

    private void onRomanizeClicked() {
        try {
            isBrowsing = false;
            userScrollUntilUptimeMs = 0;
            handler.removeCallbacks(resumeFollowRunnable);
            hideJumpToCurrentButton(true);
            if (romanizeView == null) {
                return;
            }

            Lyrics current = lyrics;
            TrackInfo track = LyricsManager.getInstance().getCurrentTrack();
            if (current == null || track == null) {
                return;
            }

            if (romanizeInProgress) {
                romanizeInProgress = false;
                Settings.LYRICS_ROMANIZE.save(false);
                romanizedLines = null;
                perWordRomaji = false;
                romanizedFromGoogle = false;
                romanizedFromAI = false;
                aiModelName = null;
                romanizedLocalDictionary = null;
                setButtonLabel(romanizeView, null, false);
                return;
            }

            if (romanizedLines != null || perWordRomaji) {
                Settings.LYRICS_ROMANIZE.save(false);
                romanizedLines = null;
                perWordRomaji = false;
                romanizedFromGoogle = false;
                romanizedFromAI = false;
                aiModelName = null;
                romanizedLocalDictionary = null;
                showLyrics(current, true);
                return;
            }

            Settings.LYRICS_ROMANIZE.save(true);
            requestRomanization(current);
        } catch (Exception ex) {
            Logger.printDebug(() -> "onRomanizeClicked failure", ex);
        }
    }

    private void updateRomanizeLabel() {
        if (romanizeView != null) {
            final boolean on = romanizedLines != null || perWordRomaji;
            setButtonLabel(romanizeView, null, on);
        }
    }

    private void onRefreshClicked() {
        if (refreshView == null) {
            return;
        }
        isBrowsing = false;
        userScrollUntilUptimeMs = 0;
        handler.removeCallbacks(resumeFollowRunnable);
        hideJumpToCurrentButton(true);
        refreshInProgress = true;
        setButtonLabel(refreshView, str("morphe_music_lyrics_refreshing"), true);
        LyricsManager.getInstance().fetchNextCandidate();
    }

    private void onRefreshLongPressed() {
        showRefreshOptionsDialog();
    }

    private void showRefreshOptionsDialog() {
        try {
            openRefreshOptionsDialog();
        } catch (Throwable ignored) {
        }
    }

    private void openRefreshOptionsDialog() {
        final Context context = getContext();
        if (context == null || refreshView == null) {
            return;
        }
        LyricsManager manager = LyricsManager.getInstance();
        TrackInfo track = manager.getCurrentTrack();
        if (track == null) {
            return;
        }

        List<LyricsManager.ProviderInfo> providers = manager.getAvailableProvidersForCurrentTrack();

        boolean isIndo = Locale.getDefault().getLanguage().startsWith("in")
                || Locale.getDefault().getLanguage().startsWith("id");
        String cancelText = isIndo ? "Batal" : "Cancel";

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                str("morphe_music_lyrics_provider_dialog_title"),
                null,
                null,
                cancelText,
                () -> {},
                null,
                null,
                null,
                true
        );

        Dialog dialog = dialogPair.first;
        LinearLayout mainLayout = dialogPair.second;

        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.topMargin = Dim.dp8;
        scrollLp.bottomMargin = Dim.dp8;

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);

        // option 1 manual search
        LinearLayout searchItem = new LinearLayout(context);
        searchItem.setOrientation(LinearLayout.HORIZONTAL);
        searchItem.setGravity(Gravity.CENTER_VERTICAL);
        searchItem.setPadding(Dim.dp16, Dim.dp12, Dim.dp16, Dim.dp12);
        searchItem.setClickable(true);
        searchItem.setFocusable(true);

        GradientDrawable searchBg = new GradientDrawable();
        searchBg.setShape(GradientDrawable.RECTANGLE);
        searchBg.setCornerRadius(Dim.dp10);
        searchBg.setColor(0x22FFFFFF);
        searchBg.setStroke(Dim.dp(1), 0x44FFFFFF);
        searchItem.setBackground(searchBg);

        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchLp.bottomMargin = Dim.dp12;
        searchItem.setLayoutParams(searchLp);

        LinearLayout searchTextCol = new LinearLayout(context);
        searchTextCol.setOrientation(LinearLayout.VERTICAL);
        searchTextCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView searchTitleTv = new TextView(context);
        searchTitleTv.setText(str("morphe_music_lyrics_search_dialog_title"));
        searchTitleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        searchTitleTv.setTypeface(Typeface.DEFAULT_BOLD);
        searchTitleTv.setTextColor(Color.WHITE);
        searchTextCol.addView(searchTitleTv);

        TextView searchDescTv = new TextView(context);
        searchDescTv.setText(str("morphe_music_lyrics_search_dialog_desc"));
        searchDescTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        searchDescTv.setTextColor(0x88FFFFFF);
        searchTextCol.addView(searchDescTv);

        searchItem.addView(searchTextCol);

        TextView searchBadge = new TextView(context);
        searchBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        searchBadge.setTypeface(Typeface.DEFAULT_BOLD);
        searchBadge.setPadding(Dim.dp8, Dim.dp4, Dim.dp8, Dim.dp4);
        GradientDrawable searchBadgeBg = new GradientDrawable();
        searchBadgeBg.setShape(GradientDrawable.RECTANGLE);
        searchBadgeBg.setCornerRadius(Dim.dp6);
        searchBadge.setText("SEARCH");
        searchBadge.setTextColor(0xFF000000);
        searchBadgeBg.setColor(0xFFFFD54F);
        searchBadge.setBackground(searchBadgeBg);
        searchItem.addView(searchBadge);

        searchItem.setOnClickListener(v -> {
            dialog.dismiss();
            openSearchDialog();
        });

        list.addView(searchItem);

        // option 2 choose provider
        for (LyricsManager.ProviderInfo p : providers) {
            LinearLayout item = new LinearLayout(context);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(Dim.dp16, Dim.dp12, Dim.dp16, Dim.dp12);
            item.setClickable(true);
            item.setFocusable(true);

            GradientDrawable itemBg = new GradientDrawable();
            itemBg.setShape(GradientDrawable.RECTANGLE);
            itemBg.setCornerRadius(Dim.dp10);
            if (p.isCurrent()) {
                itemBg.setColor(0x33FFFFFF);
                itemBg.setStroke(Dim.dp(1), 0x80FFFFFF);
            } else {
                itemBg.setColor(0x14FFFFFF);
            }
            item.setBackground(itemBg);

            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            itemLp.bottomMargin = Dim.dp8;
            item.setLayoutParams(itemLp);

            LinearLayout textCol = new LinearLayout(context);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            textCol.setLayoutParams(colLp);

            TextView nameTv = new TextView(context);
            nameTv.setText(p.name());
            nameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            nameTv.setTypeface(Typeface.DEFAULT_BOLD);
            nameTv.setTextColor(p.isCurrent() ? Color.WHITE : 0xDDFFFFFF);
            textCol.addView(nameTv);

            String syncDesc;
            if (p.syncType() == LyricsManager.SYNC_TYPE_WORD) {
                syncDesc = "Syllable / Word Sync";
            } else if (p.syncType() == LyricsManager.SYNC_TYPE_LINE) {
                syncDesc = "Line Synced";
            } else {
                syncDesc = "Plain Lyrics";
            }

            TextView descTv = new TextView(context);
            descTv.setText(syncDesc);
            descTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            descTv.setTextColor(0x88FFFFFF);
            textCol.addView(descTv);

            item.addView(textCol);

            TextView statusBadge = new TextView(context);
            statusBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            statusBadge.setTypeface(Typeface.DEFAULT_BOLD);
            statusBadge.setPadding(Dim.dp8, Dim.dp4, Dim.dp8, Dim.dp4);
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setShape(GradientDrawable.RECTANGLE);
            badgeBg.setCornerRadius(Dim.dp6);

            if (p.isCurrent()) {
                statusBadge.setText("ACTIVE");
                statusBadge.setTextColor(0xFF000000);
                badgeBg.setColor(0xFFFFFFFF);
            } else if (p.statusCode() == LyricsManager.STATUS_AVAILABLE) {
                statusBadge.setText("AVAILABLE");
                statusBadge.setTextColor(0xFF81C784);
                badgeBg.setColor(0x3381C784);
            } else {
                statusBadge.setText("FETCH");
                statusBadge.setTextColor(0xFF90CAF9);
                badgeBg.setColor(0x3390CAF9);
            }
            statusBadge.setBackground(badgeBg);
            item.addView(statusBadge);

            item.setOnClickListener(v -> {
                dialog.dismiss();
                isBrowsing = false;
                userScrollUntilUptimeMs = 0;
                handler.removeCallbacks(resumeFollowRunnable);
                hideJumpToCurrentButton(true);
                manager.fetchExplicitProvider(p.id());
            });

            list.addView(item);
        }

        scroll.addView(list);
        mainLayout.addView(scroll, 1, scrollLp);
        dialog.show();
    }

    public void showSearchDialog() {
        try {
            openSearchDialog();
        } catch (Throwable ignored) {
        }
    }

    private void openSearchDialog() {
        if (refreshView == null) {
            return;
        }
        LyricsManager manager = LyricsManager.getInstance();
        TrackInfo track = manager.getCurrentTrack();
        if (track == null) {
            return;
        }
        if (!manager.hasSearchProviders()) {
            Utils.showToastShort(str("morphe_music_lyrics_search_no_providers"));
            return;
        }
        final Context context = getContext();
        if (context == null) {
            return;
        }

        AutoCompleteTextView titleInput = createSearchInput(context,
                str("morphe_music_lyrics_search_title_hint"));
        AutoCompleteTextView artistInput = createSearchInput(context,
                str("morphe_music_lyrics_search_artist_hint"));

        final String defaultTitle = track.title() == null ? "" : track.title();
        final String defaultArtist = track.artist() == null ? "" : track.artist();
        String[] remembered = manager.rememberedSearchTerms();
        String initialTitle = remembered != null && remembered[0] != null
                ? remembered[0] : defaultTitle;
        String initialArtist = remembered != null && remembered[1] != null
                ? remembered[1] : defaultArtist;
        titleInput.setText(initialTitle);
        titleInput.setSelection(initialTitle.length());
        artistInput.setText(initialArtist);
        artistInput.setSelection(initialArtist.length());

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(titleInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams artistParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        artistParams.topMargin = Dim.dp8;
        content.addView(artistInput, artistParams);

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                str("morphe_music_lyrics_search_dialog_title"),
                null,
                null,
                null,
                () -> {
                    String title = titleInput.getText().toString().trim();
                    String artist = artistInput.getText().toString().trim();
                    if (title.isEmpty() || artist.isEmpty()) {
                        Utils.showToastShort(str("morphe_music_lyrics_search_empty"));
                        return;
                    }
                    refreshInProgress = true;
                    setButtonLabel(refreshView, str("morphe_music_lyrics_refreshing"), true);
                    boolean defaultTerms = title.equals(defaultTitle)
                            && artist.equals(defaultArtist);
                    manager.searchWithCustomQuery(title, artist, defaultTerms);
                },
                () -> { },
                null,
                null,
                false
        );

        Dialog dialog = dialogPair.first;
        LinearLayout mainLayout = dialogPair.second;
        mainLayout.addView(content, mainLayout.getChildCount() - 1,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        Window window = dialog.getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();

        final String titleSuggestion = titleInput.getText().toString();
        final String artistSuggestion = artistInput.getText().toString();
        Utils.runOnBackgroundThread(() -> {
            List<String> titles = LyricsRequests.MusicBrainzClient.suggestTitles(titleSuggestion);
            List<String> artists = LyricsRequests.MusicBrainzClient.suggestArtists(artistSuggestion);
            Utils.runOnMainThread(() -> {
                try {
                    if (titles.isEmpty() && artists.isEmpty()) {
                        return;
                    }
                    if (!dialog.isShowing()) {
                        return;
                    }
                    if (!titles.isEmpty()) {
                        setSuggestionAdapter(context, titleInput, titles);
                    }
                    if (!artists.isEmpty()) {
                        setSuggestionAdapter(context, artistInput, artists);
                    }
                } catch (Throwable ignored) {
                }
            });
        });
    }

    private static AutoCompleteTextView createSearchInput(Context context, String hint) {
        AutoCompleteTextView input = new AutoCompleteTextView(context);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setThreshold(1);
        input.setTextSize(16);
        input.setTextColor(ThemeUtils.getAppForegroundColor());

        ShapeDrawable background = new ShapeDrawable(new RoundRectShape(
                Dim.roundedCorners(10), null, null));
        background.getPaint().setColor(ThemeUtils.getEditTextBackground());
        input.setPadding(Dim.dp12, Dim.dp8, Dim.dp12, Dim.dp8);
        input.setBackground(background);
        input.setClipToOutline(true);

        input.addOnLayoutChangeListener((v, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = v.getWidth();
            if (width > 0) {
                input.setDropDownWidth(width);
            }
        });

        return input;
    }

    private static TextView createSuggestionRow(Context context) {
        TextView row = new TextView(context);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        row.setTextColor(ThemeUtils.getAppForegroundColor());
        row.setPadding(Dim.dp12, Dim.dp8, Dim.dp12, Dim.dp8);
        row.setLayoutParams(new AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private static final class SuggestionAdapter extends ArrayAdapter<String> {
        private final AutoCompleteTextView input;

        SuggestionAdapter(Context context, List<String> suggestions, AutoCompleteTextView input) {
            super(context, 0, suggestions);
            this.input = input;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView row = convertView instanceof TextView
                    ? (TextView) convertView : createSuggestionRow(getContext());
            int width = input.getWidth();
            if (width > 0) {
                row.setMaxWidth(width);
            }
            row.setText(getItem(position));
            return row;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            return getView(position, convertView, parent);
        }
    }

    private static void setSuggestionAdapter(Context context, AutoCompleteTextView input,
                                             List<String> suggestions) {
        input.setAdapter(new SuggestionAdapter(context, suggestions, input));
    }

    private void showProviderSelectionDialog() {
        Context context = getContext();
        LyricsManager manager = LyricsManager.getInstance();
        List<LyricsManager.ProviderInfo> providers = manager.getAvailableProvidersForCurrentTrack();
        if (providers.isEmpty()) {
            Utils.showToastShort(str("morphe_music_lyrics_no_other_candidates"));
            return;
        }

        boolean isIndo = Locale.getDefault().getLanguage().startsWith("in") 
                || Locale.getDefault().getLanguage().startsWith("id");
        String cancelText = isIndo ? "Batal" : "Cancel";

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                "Lyrics Providers",
                null,
                null,
                cancelText,
                () -> {},
                null,
                null,
                null,
                true
        );

        Dialog dialog = dialogPair.first;
        LinearLayout mainLayout = dialogPair.second;

        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.topMargin = Dim.dp8;
        scrollLp.bottomMargin = Dim.dp8;

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);

        LinearLayout quickSettingsItem = new LinearLayout(context);
        quickSettingsItem.setOrientation(LinearLayout.HORIZONTAL);
        quickSettingsItem.setGravity(Gravity.CENTER_VERTICAL);
        quickSettingsItem.setPadding(Dim.dp16, Dim.dp12, Dim.dp16, Dim.dp12);
        quickSettingsItem.setClickable(true);
        quickSettingsItem.setFocusable(true);
        GradientDrawable qsBg = new GradientDrawable();
        qsBg.setShape(GradientDrawable.RECTANGLE);
        qsBg.setCornerRadius(Dim.dp10);
        qsBg.setColor(0x26FFFFFF);
        qsBg.setStroke(Dim.dp(1), 0x66FFFFFF);
        quickSettingsItem.setBackground(qsBg);
        LinearLayout.LayoutParams qsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qsLp.bottomMargin = Dim.dp12;
        quickSettingsItem.setLayoutParams(qsLp);
        TextView qsTitle = new TextView(context);
        qsTitle.setText(isIndo ? "⚙ Pengaturan Animasi & Tampilan Lirik" : "⚙ Lyrics Visual & Animation Settings");
        qsTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        qsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        qsTitle.setTextColor(Color.WHITE);
        quickSettingsItem.addView(qsTitle);
        quickSettingsItem.setOnClickListener(v -> {
            dialog.dismiss();
            showLyricsQuickSettingsDialog();
        });
        list.addView(quickSettingsItem);

        for (LyricsManager.ProviderInfo p : providers) {
            LinearLayout item = new LinearLayout(context);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(Dim.dp16, Dim.dp12, Dim.dp16, Dim.dp12);
            item.setClickable(true);
            item.setFocusable(true);

            GradientDrawable itemBg = new GradientDrawable();
            itemBg.setShape(GradientDrawable.RECTANGLE);
            itemBg.setCornerRadius(Dim.dp10);
            if (p.isCurrent()) {
                itemBg.setColor(0x33FFFFFF);
                itemBg.setStroke(Dim.dp(1), 0x80FFFFFF);
            } else {
                itemBg.setColor(0x14FFFFFF);
            }
            item.setBackground(itemBg);

            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            itemLp.bottomMargin = Dim.dp8;
            item.setLayoutParams(itemLp);

            LinearLayout textCol = new LinearLayout(context);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            textCol.setLayoutParams(colLp);

            TextView nameTv = new TextView(context);
            nameTv.setText(p.name());
            nameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            nameTv.setTypeface(Typeface.DEFAULT_BOLD);
            nameTv.setTextColor(p.isCurrent() ? Color.WHITE : 0xDDFFFFFF);
            textCol.addView(nameTv);

            String syncDesc;
            if (p.syncType() == LyricsManager.SYNC_TYPE_WORD) {
                syncDesc = "Syllable / Word Sync";
            } else if (p.syncType() == LyricsManager.SYNC_TYPE_LINE) {
                syncDesc = "Line Synced";
            } else {
                syncDesc = "Plain Lyrics";
            }

            TextView descTv = new TextView(context);
            descTv.setText(syncDesc);
            descTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            descTv.setTextColor(0x88FFFFFF);
            textCol.addView(descTv);

            item.addView(textCol);

            TextView statusBadge = new TextView(context);
            statusBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            statusBadge.setTypeface(Typeface.DEFAULT_BOLD);
            statusBadge.setPadding(Dim.dp8, Dim.dp4, Dim.dp8, Dim.dp4);
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setShape(GradientDrawable.RECTANGLE);
            badgeBg.setCornerRadius(Dim.dp6);

            if (p.isCurrent()) {
                statusBadge.setText("ACTIVE");
                statusBadge.setTextColor(0xFF000000);
                badgeBg.setColor(0xFFFFFFFF);
            } else if (p.statusCode() == LyricsManager.STATUS_AVAILABLE) {
                statusBadge.setText("AVAILABLE");
                statusBadge.setTextColor(0xFF81C784);
                badgeBg.setColor(0x3381C784);
            } else {
                statusBadge.setText("FETCH");
                statusBadge.setTextColor(0xFF90CAF9);
                badgeBg.setColor(0x3390CAF9);
            }
            statusBadge.setBackground(badgeBg);
            item.addView(statusBadge);

            item.setOnClickListener(v -> {
                dialog.dismiss();
                isBrowsing = false;
                userScrollUntilUptimeMs = 0;
                handler.removeCallbacks(resumeFollowRunnable);
                hideJumpToCurrentButton(true);
                manager.fetchExplicitProvider(p.id());
            });

            list.addView(item);
        }

        scroll.addView(list);
        mainLayout.addView(scroll, 1, scrollLp);
        dialog.show();
    }


    public void showLyricsQuickSettingsDialog() {
        Context context = getContext();
        boolean isIndo = Locale.getDefault().getLanguage().startsWith("in")
                || Locale.getDefault().getLanguage().startsWith("id");
        String closeText = isIndo ? "Tutup" : "Close";
        String dialogTitle = isIndo ? "Pengaturan Lirik (YouLyPlus)" : "Lyrics Settings (YouLyPlus)";

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                dialogTitle,
                null,
                null,
                closeText,
                () -> {},
                null,
                null,
                null,
                true
        );

        Dialog dialog = dialogPair.first;
        LinearLayout mainLayout = dialogPair.second;

        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.topMargin = Dim.dp8;
        scrollLp.bottomMargin = Dim.dp8;

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);

        record ToggleItem(
                app.morphe.extension.shared.settings.BooleanSetting setting,
                String title,
                String summary,
                boolean reloadLyrics,
                boolean isDynamicBg) {}

        ToggleItem[] items = new ToggleItem[]{
                new ToggleItem(
                        Settings.LYRICS_DYNAMIC_BG_VOCAL_POS,
                        "Dynamic Background Vocal Positioning",
                        isIndo
                                ? "Vokal latar yang mulai lebih dulu dari lirik utama ditampilkan di atas."
                                : "Background lyrics that start before main lyrics will be displayed on top.",
                        true, false),
                new ToggleItem(
                        Settings.LYRICS_BLUR_INACTIVE,
                        "Blur Inactive Lyrics",
                        isIndo
                                ? "Menerapkan efek blur pada baris lirik yang sedang tidak aktif."
                                : "Applies a blur effect to lyrics lines that are not currently active.",
                        false, false),
                new ToggleItem(
                        Settings.LYRICS_LIGHTWEIGHT_MODE,
                        "Lightweight Mode",
                        isIndo
                                ? "Mengurangi efek glow dan animasi berat agar lebih ringan di perangkat low-end."
                                : "Reduces heavy character glow and visual effects for better performance.",
                        true, false),
                new ToggleItem(
                        Settings.LYRICS_HIDE_OFFSCREEN,
                        "Hide Off-screen Lyrics Line",
                        "Hides & remove all animation lyrics lines that are not currently visible on the screen. Recommended for low-end devices.",
                        false, false),
                new ToggleItem(
                        Settings.LYRICS_DYNAMIC_BACKGROUND,
                        "Enable Dynamic Background (Player View)",
                        "Applies animated album backgrounds when the player is expanded. This setting has no effect in fullscreen mode, where dynamic background is always enabled.",
                        false, true),
                new ToggleItem(
                        Settings.LYRICS_AUDIO_BEAT_SYNC,
                        "Audio-based Beat Pulse for Dynamic Background",
                        "Makes the dynamic background layers pulse to the beat of the audio. If you use Better Lyrics Shaders or another Visualizer, you should disable this setting.",
                        false, true),
                new ToggleItem(
                        Settings.LYRICS_RELAX_SCROLL,
                        "Relax Scrolling",
                        "Slower scrolling animation & disable text resizing animation for the lyrics.",
                        false, false)
        };

        for (ToggleItem ti : items) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Dim.dp16, Dim.dp12, Dim.dp16, Dim.dp12);
            row.setClickable(true);
            row.setFocusable(true);

            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setShape(GradientDrawable.RECTANGLE);
            rowBg.setCornerRadius(Dim.dp10);
            rowBg.setColor(0x18FFFFFF);
            row.setBackground(rowBg);

            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = Dim.dp8;
            row.setLayoutParams(rowLp);

            LinearLayout textCol = new LinearLayout(context);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            colLp.setMarginEnd(Dim.dp12);
            textCol.setLayoutParams(colLp);

            TextView titleTv = new TextView(context);
            titleTv.setText(ti.title());
            titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            titleTv.setTypeface(Typeface.DEFAULT_BOLD);
            titleTv.setTextColor(Color.WHITE);
            textCol.addView(titleTv);

            TextView sumTv = new TextView(context);
            sumTv.setText(ti.summary());
            sumTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            sumTv.setTextColor(0xAAFFFFFF);
            sumTv.setPadding(0, Dim.dp(2), 0, 0);
            textCol.addView(sumTv);

            row.addView(textCol);

            android.widget.Switch toggle = new android.widget.Switch(context);
            toggle.setChecked(ti.setting().get());
            toggle.setClickable(false);
            toggle.setFocusable(false);
            row.addView(toggle);

            row.setOnClickListener(v -> {
                boolean next = !ti.setting().get();
                ti.setting().save(next);
                toggle.setChecked(next);
                if (ti.reloadLyrics()) {
                    LyricsManager.getInstance().reloadAfterSettingsChange();
                } else if (ti.isDynamicBg()) {
                    applyDynamicBackgroundSettings();
                } else {
                    applyVisualAndScrollSettings();
                }
            });

            list.addView(row);
        }

        scroll.addView(list);
        mainLayout.addView(scroll, 1, scrollLp);
        dialog.show();
    }

    private void updateRefreshLabel() {
        if (refreshView != null) {
            boolean active = LyricsManager.getInstance().isOverrideNative();
            setButtonLabel(refreshView, null, active);
        }
    }

    private void onCopyClicked() {
        if (lyrics == null || lyrics.isEmpty()) {
            return;
        }

        StringBuilder fullText = new StringBuilder();
        List<LyricsLine> lines = lyrics.lines();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) fullText.append('\n');
            fullText.append(lines.get(i).text());
        }

        ClipboardManager clipboard = (ClipboardManager) getContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(
                    ClipData.newPlainText("lyrics", fullText.toString()));
            Utils.showToastShort(str("morphe_music_lyrics_copied"));
        }
    }

    private void onCopyLongPressed() {
        if (lyrics == null || lyrics.isEmpty()) {
            return;
        }

        TrackInfo track = LyricsManager.getInstance().getCurrentTrack();
        if (track != null) {
            LyricsFileSaver.save(getContext(), track, lyrics);
        }
    }

    private void onSourceClicked() {
        if (currentSourceUrl == null || currentSourceUrl.isEmpty()) {
            return;
        }

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(currentSourceUrl));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not open source URL: " + currentSourceUrl, ex);
        }
    }

    private void resetScrollAndExitBrowsing() {
        scrollEngine.reset();
        isBrowsing = false;
        userScrollUntilUptimeMs = 0;
        isDraggingScroll = false;
        isUserTouchingScroll = false;
        lastScrollTarget = -1;
        handler.removeCallbacks(resumeFollowRunnable);
        hideJumpToCurrentButton(true);
        if (scrollView != null) {
            stopScrollViewFling(scrollView);
            scrollView.stopNestedScroll();
        }
    }

    private void clearLines() {
        resetScrollAndExitBrowsing();
        isProgrammaticScrolling = true;
        for (TextView lineView : lineViews) {
            lineView.animate().cancel();
            if (lineView instanceof LyricsLineView llv) {
                llv.cancelScaleAnimation();
            }
        }
        for (View lineRow : lineRows) {
            lineRow.setTranslationY(0f);
        }
        for (GapLineView gv : gapLineViews) {
            gv.destroy();
            linesContainer.removeView(gv);
        }
        gapLineViews.clear();
        gapLineViewsByLine.clear();
        for (View lineRow : new LinkedHashSet<>(lineRows)) {
            linesContainer.removeView(lineRow);
        }
        gapIntervals.clear();
        lineViews.clear();
        lineRows.clear();
        lineWordSpans.clear();
        lineOriginalStarts.clear();
        lineUnsungSpans.clear();
        if (creditLookAnimator != null) {
            creditLookAnimator.cancel();
            creditLookAnimator = null;
        }
        if (creditBlurAnimator != null) {
            creditBlurAnimator.cancel();
            creditBlurAnimator = null;
        }
        creditBlockContainer.setTranslationY(0f);
        if (linesContainer.indexOfChild(creditBlockContainer) < 0) {
            linesContainer.addView(creditBlockContainer);
        }
        if (linesContainer.indexOfChild(footerContainer) < 0) {
            linesContainer.addView(footerContainer);
        }
        creditLineActive = false;
        creditLookToAlpha = -1f;
        creditLookToScale = -1f;
        highlightedIndex = -1;
        primaryActiveIndex = -1;
        previousPrimaryActiveIndex = -1;
        activeIndicesSet.clear();
        previousActiveSet.clear();
        lastWordLineIndex = -1;
        lastOverlayIndex = -1;
        pendingOldWordLineIndex = -1;
        lastScrollTarget = -1;
        lastLivePosMs = 0L;
        seekPending = false;
        pendingSeekPosMs = -1L;
        pendingSeekUptimeMs = 0L;
        pendingSeekMainIndex = -1;
        retimedLyrics = Collections.emptyList();
        retimedAdjustedEndMax = new long[0];
    }

    private Set<Integer> computeActiveLines(Lyrics lyrics, long currentTimeMs) {
        if (retimedLyrics == null || retimedLyrics.isEmpty()) {
            return Collections.emptySet();
        }
        long scanTime = currentTimeMs + LyricsRetiming.HIGHLIGHT_LOOK_AHEAD_MS;
        List<Integer> scanned = LyricsRetiming.findActiveRetimedLineIndices(
                retimedLyrics, retimedAdjustedEndMax, scanTime, 0L);
        Set<Integer> set = new TreeSet<>(scanned);
        for (int i : scanned) {
            int mainIdx = i;
            TextView tv = (i >= 0 && i < lineViews.size()) ? lineViews.get(i) : null;
            if (tv instanceof LyricsLineView llv && llv.isSecondary()) {
                int pIdx = llv.getParentLineIndex();
                if (pIdx >= 0) mainIdx = pIdx;
            }
            while (mainIdx > 0 && isSecondaryLineView(mainIdx)) {
                mainIdx--;
            }
            if (mainIdx >= 0) {
                set.add(mainIdx);
            }
        }

        for (int i = 0; i < lineViews.size(); i++) {
            TextView tv = lineViews.get(i);
            if (tv instanceof LyricsLineView llv && llv.isSecondary()) {
                int pIdx = llv.getParentLineIndex();
                if (pIdx >= 0 && set.contains(pIdx)) {
                    boolean shouldActivateSec = true;
                    if (i < retimedLyrics.size() && pIdx < retimedLyrics.size()) {
                        LyricsRetiming.RetimedEntry secEntry = retimedLyrics.get(i);
                        LyricsRetiming.RetimedEntry parEntry = retimedLyrics.get(pIdx);
                        if (secEntry.startMs > parEntry.startMs + 550L && scanTime < secEntry.startMs - 250L) {
                            shouldActivateSec = false;
                        } else if (secEntry.actualEndTimeMs > secEntry.startMs
                                && secEntry.actualEndTimeMs < parEntry.actualEndTimeMs - 600L
                                && currentTimeMs > secEntry.actualEndTimeMs + 300L) {
                            shouldActivateSec = false;
                        }
                    }
                    if (shouldActivateSec) {
                        set.add(i);
                    } else {
                        set.remove(i);
                    }
                }
            }
        }

        return set;
    }

    private int computePrimaryActiveIndex(Lyrics lyrics, Set<Integer> activeSet, long currentTimeMs) {
        if (retimedLyrics == null || retimedLyrics.isEmpty()) {
            return -1;
        }
        List<Integer> activeList = new ArrayList<>(activeSet);
        Collections.sort(activeList);
        List<Integer> mainActiveList = new ArrayList<>(activeList.size());
        for (int idx : activeList) {
            int mainAct = idx;
            if (mainAct >= 0 && mainAct < lineViews.size()) {
                TextView tv = lineViews.get(mainAct);
                if (tv instanceof LyricsLineView llv && llv.isSecondary()) {
                    int p = llv.getParentLineIndex();
                    if (p >= 0) mainAct = p;
                }
            }
            while (mainAct > 0 && isSecondaryLineView(mainAct)) {
                mainAct--;
            }
            if (mainAct >= 0 && mainAct < retimedLyrics.size() && !mainActiveList.contains(mainAct)) {
                mainActiveList.add(mainAct);
            }
        }
        int idx = LyricsRetiming.calculatePrimaryRetimedLineIndex(
                retimedLyrics, mainActiveList, currentTimeMs, previousPrimaryActiveIndex);
        while (idx > 0 && isSecondaryLineView(idx)) {
            idx--;
        }
        if (idx < 0 && !retimedLyrics.isEmpty()) {
            idx = 0;
        }
        return idx;
    }

    private void handleSeekOrRewind(long targetPosMs) {
        if (lyrics == null || lineViews.isEmpty()) return;
        final int lineCount = lineViews.size();
        for (int i = 0; i < lineCount; i++) {
            LyricsLine line = (i < lyrics.lines().size()) ? lyrics.lines().get(i) : null;
            if (line == null) continue;
            long start = line.startTimeMs() - 190L;
            TextView tv = lineViews.get(i);
            long end = line.endTimeMs();
            if (tv instanceof LyricsLineView llv) {
                long wordEnd = llv.getLastWordEndMs();
                if (wordEnd != LyricsLine.NO_TIME) {
                    end = (end == LyricsLine.NO_TIME) ? wordEnd : Math.max(end, wordEnd);
                }
            }
            if (end == LyricsLine.NO_TIME) {
                end = (i + 1 < lyrics.lines().size())
                        ? lyrics.lines().get(i + 1).startTimeMs() : Long.MAX_VALUE;
            }
            if (targetPosMs < start) {
                clearWordHighlight(i);
                if (tv instanceof LyricsLineView llv) {
                    llv.setLineActive(false);
                    llv.resetClockPos();
                }
            } else if (targetPosMs >= end) {
                applyWordColors(i, 0, true);
                applyBgWordColors(i, 0, true);
                if (tv instanceof LyricsLineView llv) {
                    llv.setLineActive(false);
                    llv.resetClockPos();
                }
            } else {
                applyWordColors(i, targetPosMs, false);
                applyBgWordColors(i, targetPosMs, false);
                if (tv instanceof LyricsLineView llv) {
                    llv.setLineActive(true);
                    llv.resetClockPos();
                }
            }
        }
        previousActiveSet.clear();
    }

    private long computeCurrentInterLineGapMs(long pos, int refIdx) {
        if (retimedLyrics == null || retimedLyrics.isEmpty()) {
            return 0L;
        }
        List<Integer> curActive = LyricsRetiming.findActiveRetimedLineIndices(
                retimedLyrics, retimedAdjustedEndMax, pos, 0L);
        int currentIdx = LyricsRetiming.calculatePrimaryRetimedLineIndex(
                retimedLyrics, curActive, pos, Math.max(0, refIdx));
        while (currentIdx > 0 && isSecondaryLineView(currentIdx)) {
            currentIdx--;
        }
        if (currentIdx >= 0 && currentIdx < retimedLyrics.size()) {
            int clusterEndIdx = currentIdx;
            while (clusterEndIdx + 1 < retimedLyrics.size() && isSecondaryLineView(clusterEndIdx + 1)) {
                clusterEndIdx++;
            }
            long rawEnd = retimedLyrics.get(currentIdx).actualEndTimeMs;
            for (int k = currentIdx; k <= clusterEndIdx && k < retimedLyrics.size(); k++) {
                rawEnd = Math.max(rawEnd, retimedLyrics.get(k).actualEndTimeMs);
            }
            int nextMainIdx = clusterEndIdx + 1;
            if (nextMainIdx < retimedLyrics.size()) {
                return Math.max(0L, retimedLyrics.get(nextMainIdx).startMs - rawEnd);
            }
        }
        return 0L;
    }

    private long computeCurrentScrollLookAheadMs(long pos, int refIdx) {
        if (retimedLyrics == null || retimedLyrics.isEmpty()) {
            return LyricsRetiming.SCROLL_LOOK_AHEAD_MIN_MS;
        }
        List<Integer> curActive = LyricsRetiming.findActiveRetimedLineIndices(
                retimedLyrics, retimedAdjustedEndMax, pos, 0L);
        int currentIdx = LyricsRetiming.calculatePrimaryRetimedLineIndex(
                retimedLyrics, curActive, pos, Math.max(0, refIdx));
        while (currentIdx > 0 && isSecondaryLineView(currentIdx)) {
            currentIdx--;
        }
        if (currentIdx >= 0 && currentIdx < retimedLyrics.size()) {
            int clusterEndIdx = currentIdx;
            while (clusterEndIdx + 1 < retimedLyrics.size() && isSecondaryLineView(clusterEndIdx + 1)) {
                clusterEndIdx++;
            }
            long rawEnd = retimedLyrics.get(currentIdx).actualEndTimeMs;
            for (int k = currentIdx; k <= clusterEndIdx && k < retimedLyrics.size(); k++) {
                rawEnd = Math.max(rawEnd, retimedLyrics.get(k).actualEndTimeMs);
            }
            int nextMainIdx = clusterEndIdx + 1;
            if (nextMainIdx < retimedLyrics.size()) {
                long gap = retimedLyrics.get(nextMainIdx).startMs - rawEnd;
                return Math.max(LyricsRetiming.SCROLL_LOOK_AHEAD_MIN_MS,
                        Math.min(LyricsRetiming.SCROLL_LOOK_AHEAD_MAX_MS, gap));
            }
        }
        return LyricsRetiming.SCROLL_LOOK_AHEAD_MIN_MS;
    }

    private void updateHighlight() {
        try {
            Lyrics current = lyrics;
            if (current == null || !current.synced() || lineViews.isEmpty() || !initialBuildComplete) {
                return;
            }

            LyricsManager manager = LyricsManager.getInstance();
            final long rawPos = manager.getPositionMs();
            final boolean hasPendingSeek = (pendingSeekPosMs >= 0);
            final long pos;
            if (hasPendingSeek) {
                long elapsed = SystemClock.uptimeMillis() - pendingSeekUptimeMs;
                boolean reached = elapsed >= 150L && (rawPos >= pendingSeekPosMs - 200L && rawPos <= pendingSeekPosMs + 2500L);
                if (reached || elapsed > 1500L) {
                    pendingSeekPosMs = -1L;
                    pendingSeekMainIndex = -1;
                    pos = rawPos;
                } else {
                    pos = pendingSeekPosMs;
                }
            } else {
                pos = rawPos;
            }
            final long prevPos = lastLivePosMs;
            lastLivePosMs = pos;

            final boolean isInitial = (prevPos == 0 && pos > 0);
            final boolean isBackward = (prevPos > 0 && (pos < prevPos - 600L || (pos < 200L && prevPos >= 600L)));
            final boolean isForceJump = (prevPos > 0 && Math.abs(pos - prevPos) > 1000L);
            final boolean isSeek = seekPending || hasPendingSeek || isInitial || isBackward || isForceJump;
            seekPending = false;

            if (isInitial || isBackward || isForceJump) {
                handleSeekOrRewind(pos);
            }

            GapInterval activeGi = getActiveGapInterval(pos);
            final boolean inGapBreak = (activeGi != null && pos < activeGi.endMs());

            for (GapLineView gv : gapLineViews) {
                boolean shouldBeActive = (activeGi != null && gv.getInterval() == activeGi && pos < activeGi.endMs());
                gv.updateState(pos, shouldBeActive);
            }

            if (inGapBreak) {
                if (!isBrowsing) {
                    hideJumpToCurrentButton();
                }
            }

            Set<Integer> newActiveSet = inGapBreak
                    ? Collections.emptySet()
                    : computeActiveLines(current, pos);
            if (!inGapBreak && hasPendingSeek && pendingSeekMainIndex >= 0) {
                if (newActiveSet.isEmpty()) {
                    newActiveSet = new TreeSet<>();
                } else if (!(newActiveSet instanceof TreeSet)) {
                    newActiveSet = new TreeSet<>(newActiveSet);
                }
                newActiveSet.add(pendingSeekMainIndex);
            }

            int refIdx = primaryActiveIndex >= 0 ? primaryActiveIndex : Math.max(0, previousPrimaryActiveIndex);
            while (refIdx > 0 && isSecondaryLineView(refIdx)) {
                refIdx--;
            }
            if (refIdx < 0 && !retimedLyrics.isEmpty()) {
                refIdx = 0;
            }

            final long interLineGapMs = computeCurrentInterLineGapMs(pos, refIdx);
            final long scrollLookAheadMs = computeCurrentScrollLookAheadMs(pos, refIdx);
            final int anchorPx = (scrollView != null && scrollView.getHeight() > 0)
                    ? getScrollAnchorPx() : 0;
            final long staggerLeadMs = scrollEngine.computeStaggerLeadMs(scrollLookAheadMs, anchorPx);

            int newPrimary;
            if (inGapBreak) {
                newPrimary = -1;
            } else if (activeGi != null && pos >= activeGi.endMs() && pos < activeGi.nextStartMs()) {
                newPrimary = Math.max(0, Math.min(activeGi.beforeLineIndex(), lineViews.size() - 1));
            } else if (hasPendingSeek && pendingSeekMainIndex >= 0) {
                newPrimary = pendingSeekMainIndex;
            } else if (!retimedLyrics.isEmpty() && (pos + LyricsRetiming.SCROLL_LOOK_AHEAD_MIN_MS) < retimedLyrics.get(0).startMs) {
                newPrimary = 0;
            } else {
                long predictiveTime = pos + scrollLookAheadMs + staggerLeadMs;
                List<Integer> predActiveList = LyricsRetiming.findActiveRetimedLineIndices(
                        retimedLyrics, retimedAdjustedEndMax, predictiveTime, 50L);
                List<Integer> mainPredActiveList = new ArrayList<>(predActiveList.size());
                for (int idx : predActiveList) {
                    int mainAct = idx;
                    while (mainAct > 0 && isSecondaryLineView(mainAct)) {
                        mainAct--;
                    }
                    if (mainAct >= 0 && mainAct < retimedLyrics.size() && !mainPredActiveList.contains(mainAct)) {
                        mainPredActiveList.add(mainAct);
                    }
                }
                newPrimary = LyricsRetiming.calculatePrimaryRetimedLineIndex(
                        retimedLyrics, mainPredActiveList, predictiveTime, refIdx);
                while (newPrimary > 0 && isSecondaryLineView(newPrimary)) {
                    newPrimary--;
                }
                if (newPrimary < 0 && !retimedLyrics.isEmpty()) {
                    newPrimary = 0;
                }
                if (newPrimary < 0) {
                    newPrimary = Math.max(0, refIdx);
                }
                if (!isSeek && manager.isPlaying() && refIdx >= 0 && newPrimary < refIdx) {
                    if (pos >= prevPos - 1000L) {
                        newPrimary = refIdx;
                    }
                }
            }
            long maxSongEndMs = Long.MIN_VALUE;
            if (retimedAdjustedEndMax != null && retimedAdjustedEndMax.length > 0) {
                maxSongEndMs = retimedAdjustedEndMax[retimedAdjustedEndMax.length - 1];
            }
            final boolean isAfterAllLyrics = !inGapBreak
                    && maxSongEndMs != Long.MIN_VALUE
                    && pos > maxSongEndMs
                    && newActiveSet.isEmpty()
                    && creditBlockContainer != null
                    && creditBlockContainer.getVisibility() == VISIBLE;

            if (!inGapBreak && !isAfterAllLyrics && newPrimary >= 0 && newActiveSet.isEmpty()) {
                boolean primaryStillSinging = true;
                if (retimedLyrics != null && newPrimary < retimedLyrics.size()) {
                    if (pos > retimedLyrics.get(newPrimary).adjustedEndTimeMs) {
                        primaryStillSinging = false;
                    }
                }
                if (primaryStillSinging) {
                    newActiveSet = Collections.singleton(newPrimary);
                }
            }

            boolean creditActiveChanged = (isAfterAllLyrics != creditLineActive);
            creditLineActive = isAfterAllLyrics;

            boolean activeSetChanged = !newActiveSet.equals(activeIndicesSet);
            boolean primaryChanged = (newPrimary != primaryActiveIndex);

            if (activeSetChanged) {
                activeIndicesSet.clear();
                activeIndicesSet.addAll(newActiveSet);
            }
            if (primaryChanged) {
                if (primaryActiveIndex >= 0) {
                    previousPrimaryActiveIndex = primaryActiveIndex;
                }
                primaryActiveIndex = newPrimary;
                highlightedIndex = newPrimary;
            }

            if (isSeek || activeSetChanged || primaryChanged || creditActiveChanged) {
                updateLinesDepthOfField(activeIndicesSet, primaryActiveIndex, inGapBreak && activeGi != null ? activeGi.beforeLineIndex() : -1);
            }

            if (isBrowsing || SystemClock.uptimeMillis() < userScrollUntilUptimeMs) {
                return;
            }

            if (inGapBreak && scrollView != null && scrollView.getHeight() > 0) {
                int targetIdx = activeGi.beforeLineIndex();
                GapLineView gv = gapLineViewsByLine.get(targetIdx);
                final int viewPos;
                if (gv != null && gv.getTop() > 0) {
                    viewPos = gv.getTop();
                } else if (targetIdx == 0) {
                    viewPos = 0;
                } else if (targetIdx >= 0 && targetIdx < lineRows.size() && lineRows.get(targetIdx).getTop() > 0) {
                    viewPos = lineRows.get(targetIdx).getTop() - Dim.dp(GapLineView.TARGET_HEIGHT_DP);
                } else {
                    viewPos = -1;
                }
                if (viewPos >= 0) {
                    final int target = (targetIdx == 0) ? 0 : (viewPos - anchorPx);
                    final int clamped = Math.max(0, target);
                    if (isSeek || clamped != lastScrollTarget) {
                        lastScrollTarget = clamped;
                        View targetView = gv != null ? gv : (targetIdx >= 0 && targetIdx < lineRows.size() ? lineRows.get(targetIdx) : null);
                        int prevRef = previousPrimaryActiveIndex >= 0 ? previousPrimaryActiveIndex : targetIdx;
                        View refView = (prevRef >= 0 && prevRef < lineRows.size()) ? lineRows.get(prevRef) : targetView;
                        scrollEngine.scrollTo(clamped, targetView, targetIdx, refView, prevRef, scrollLookAheadMs, interLineGapMs, false);
                    }
                    return;
                }
            }

            if (creditLineActive) {
                final int clamped = computeBlockScrollTargetY(lineRows.size());
                if (clamped >= 0 && (isSeek || creditActiveChanged || lastScrollTarget < 0)) {
                    lastScrollTarget = clamped;
                    int prevRef = primaryActiveIndex >= 0 ? primaryActiveIndex : Math.max(0, lineRows.size() - 1);
                    View refView = (prevRef >= 0 && prevRef < lineRows.size()) ? lineRows.get(prevRef) : creditBlockContainer;
                    scrollEngine.scrollTo(clamped, creditBlockContainer, lineRows.size(), refView, prevRef, scrollLookAheadMs, interLineGapMs, false);
                }
                return;
            }

            if (primaryActiveIndex >= 0 && primaryActiveIndex < lineRows.size()) {
                final int clamped = computeBlockScrollTargetY(primaryActiveIndex);
                if (clamped >= 0 && (isSeek || primaryChanged || creditActiveChanged || lastScrollTarget < 0)) {
                    lastScrollTarget = clamped;
                    View targetView = lineRows.get(primaryActiveIndex);
                    int endIdx = computeActiveGroupEndIndex(primaryActiveIndex);
                    View endView = (endIdx >= 0 && endIdx < lineRows.size()) ? lineRows.get(endIdx) : targetView;
                    int prevRef = previousPrimaryActiveIndex >= 0 ? previousPrimaryActiveIndex : primaryActiveIndex;
                    View refView = (prevRef >= 0 && prevRef < lineRows.size()) ? lineRows.get(prevRef) : targetView;
                    scrollEngine.scrollTo(clamped, targetView, primaryActiveIndex, endView, endIdx, refView, prevRef, scrollLookAheadMs, interLineGapMs, false);
                }
            }
        } catch (Exception t) {
            Logger.printDebug(() -> "updateHighlight failed", t);
        }
    }

    private void updateLinesDepthOfField(Set<Integer> activeSet, int primaryIndex) {
        updateLinesDepthOfField(activeSet, primaryIndex, -1);
    }

    private void updateLinesDepthOfField(Set<Integer> activeSet, int primaryIndex, int gapFocusIndex) {
        final boolean blurInactive = Settings.LYRICS_BLUR_INACTIVE.get();
        final boolean isAutoScrollEnabled = !isBrowsing;
        final boolean isLrcSynced = (lyrics != null && lyrics.synced());
        final boolean applyBlur = blurInactive && isAutoScrollEnabled;
        final float delayIncrementMs = LyricsRetiming.calculateScrollDelayIncrementFloat(scrollEngine.getScrollDurationMs(), Settings.LYRICS_RELAX_SCROLL.get());
        final int count = lineViews.size();

        final int[] mainOrd = new int[count];
        int curOrd = 0;
        for (int i = 0; i < count; i++) {
            TextView lv = lineViews.get(i);
            if (lv instanceof LyricsLineView slv && slv.isSecondary() && slv.getParentLineIndex() >= 0) {
                int pIdx = slv.getParentLineIndex();
                mainOrd[i] = (pIdx >= 0 && pIdx < i) ? mainOrd[pIdx] : Math.max(0, curOrd - 1);
            } else {
                mainOrd[i] = curOrd++;
            }
        }

        int anchorLineIdx = primaryIndex;
        if (anchorLineIdx < 0 && gapFocusIndex >= 0) {
            anchorLineIdx = gapFocusIndex;
        }
        if (anchorLineIdx < 0 && highlightedIndex >= 0) {
            anchorLineIdx = highlightedIndex;
        }
        if (anchorLineIdx < 0 && !activeSet.isEmpty()) {
            for (int act : activeSet) {
                anchorLineIdx = act;
                break;
            }
        }
        final int creditOrd = curOrd;
        final int anchorOrd = creditLineActive
                ? creditOrd
                : ((anchorLineIdx >= 0 && anchorLineIdx < count) ? mainOrd[anchorLineIdx] : -1);

        Set<Integer> activeParentsFromSecondary = new HashSet<>();
        for (int i = 0; i < count; i++) {
            TextView v = lineViews.get(i);
            if (v instanceof LyricsLineView slv && slv.isSecondary() && slv.getParentLineIndex() >= 0) {
                if (activeSet.contains(i)) {
                    activeParentsFromSecondary.add(slv.getParentLineIndex());
                }
            }
        }

        for (int i = 0; i < count; i++) {
            final TextView lv = lineViews.get(i);
            if (lv == null) continue;

            if (lv instanceof LyricsLineView slv && slv.isSecondary() && slv.getParentLineIndex() >= 0) {
                int pIdx = slv.getParentLineIndex();
                boolean parentActive = (pIdx >= 0 && (activeSet.contains(pIdx) || activeParentsFromSecondary.contains(pIdx)));
                boolean isBgActive = activeSet.contains(i) || parentActive;
                slv.setLineActive(isBgActive);
                slv.setPrewarmMasks(isBgActive);
                slv.updateLineLook(isBgActive, isBgActive ? 0 : 1, 0L, isAutoScrollEnabled, isLrcSynced, false);
                slv.animateSecondaryExpansion(isBgActive);
                slv.animateBlur(0f, 300L);
                continue;
            }

            final boolean hasActiveSec = activeParentsFromSecondary.contains(i);
            final boolean isActive = activeSet.contains(i) || hasActiveSec;
            final int signedDistance;
        // ponytail: any line in activeSet is fully focused with distance 0
        if (!isLrcSynced || isActive) {
            signedDistance = 0;
            } else if (anchorOrd >= 0) {
                signedDistance = mainOrd[i] - anchorOrd;
            } else {
                signedDistance = 0;
            }
            final int dist = Math.abs(signedDistance);

            final long staggerDelayMs = Math.min(200L, Math.round(dist * delayIncrementMs));

            if (lv instanceof LyricsLineView llv) {
                llv.setLineActive(isActive);
                llv.setPrewarmMasks(isActive || (signedDistance >= -1 && signedDistance <= 1));
                llv.updateLineLook(isActive, dist, staggerDelayMs, isAutoScrollEnabled, isLrcSynced, false);
            } else {
                final float targetAlpha = isAutoScrollEnabled ? (isActive ? 1.0f : 0.12f) : 0.6f;
                fadeTo(lv, targetAlpha);
            }

            float targetBlurPx = 0f;
            if (applyBlur && !isActive) {
                float em = lv.getTextSize();
                targetBlurPx = (dist == 1) ? (em * 0.035f) : (em * 0.070f);
            }
            if (lv instanceof LyricsLineView llv) {
                llv.animateBlur(targetBlurPx, 300L);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                RenderEffect effect = getCachedBlurEffect(targetBlurPx);
                applyBlurEffect(lv, effect);
            }
        }

        for (GapLineView gv : gapLineViews) {
            if (gv == null) continue;
            boolean isGapActive = gv.isGapActive();
            float targetBlurPx = 0f;
            if (applyBlur && !isGapActive) {
                targetBlurPx = Dim.dp(16f) * 0.070f;
            }
            gv.animateBlur(targetBlurPx, 300L);
        }

        if (creditBlockContainer != null && creditBlockContainer.getVisibility() == VISIBLE) {
            final int creditDist = (!isLrcSynced || anchorOrd < 0) ? 0 : Math.abs(creditOrd - anchorOrd);
            final long creditStaggerMs = Math.min(200L, Math.round(creditDist * delayIncrementMs));
            animateCreditBlockLook(creditLineActive, creditDist, creditStaggerMs,
                    isAutoScrollEnabled, isLrcSynced, applyBlur, false);
        }
    }

    private void animateCreditBlockLook(
            boolean isActive,
            int distance,
            long staggerDelayMs,
            boolean isAutoScrollEnabled,
            boolean isLrcSynced,
            boolean applyBlur,
            boolean instant) {
        if (creditBlockContainer == null) return;

        final float targetAlpha;
        if (!isAutoScrollEnabled || !isLrcSynced) {
            targetAlpha = 0.60f;
        } else if (isActive) {
            targetAlpha = 1.0f;
        } else if (distance == 0) {
            targetAlpha = 0.15f;
        } else if (distance == 1 || distance == 2) {
            targetAlpha = 0.12f;
        } else if (distance == 3) {
            targetAlpha = 0.09f;
        } else {
            targetAlpha = 0.06f;
        }

        final float targetScale;
        if (isActive || !isAutoScrollEnabled || !isLrcSynced || Settings.LYRICS_RELAX_SCROLL.get()) {
            targetScale = 1.0f;
        } else if (distance == 0 || distance == 1) {
            targetScale = 0.97f;
        } else if (distance == 2 || distance == 3) {
            targetScale = 0.93f;
        } else if (distance == 4) {
            targetScale = 0.90f;
        } else {
            targetScale = 0.85f;
        }

        float targetBlurPx = 0f;
        if (applyBlur && !isActive && isLrcSynced) {
            float em = footerView.getTextSize();
            targetBlurPx = (distance == 1) ? (em * 0.035f) : (em * 0.070f);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (creditBlurAnimator != null) {
                creditBlurAnimator.cancel();
                creditBlurAnimator = null;
            }
            if (instant || Math.abs(creditBlurRadius - targetBlurPx) < 0.1f) {
                creditBlurRadius = targetBlurPx;
                creditBlockContainer.setRenderEffect(getCachedBlurEffect(targetBlurPx));
            } else {
                final float fromBlur = creditBlurRadius;
                final float toBlur = targetBlurPx;
                creditBlurAnimator = ValueAnimator.ofFloat(fromBlur, toBlur);
                creditBlurAnimator.setDuration(300L);
                creditBlurAnimator.setInterpolator(TRANSITION_INTERPOLATOR);
                creditBlurAnimator.addUpdateListener(anim -> {
                    creditBlurRadius = (Float) anim.getAnimatedValue();
                    creditBlockContainer.setRenderEffect(getCachedBlurEffect(creditBlurRadius));
                });
                creditBlurAnimator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        creditBlurAnimator = null;
                        creditBlurRadius = toBlur;
                        creditBlockContainer.setRenderEffect(getCachedBlurEffect(toBlur));
                    }
                });
                creditBlurAnimator.start();
            }
        }

        if (instant || creditLookToAlpha < 0f) {
            if (creditLookAnimator != null) {
                creditLookAnimator.cancel();
                creditLookAnimator = null;
            }
            creditLookCurAlpha = creditLookToAlpha = targetAlpha;
            creditLookCurScale = creditLookToScale = targetScale;
            creditBlockContainer.setPivotX(creditBlockContainer.getPaddingLeft());
            creditBlockContainer.setPivotY(creditBlockContainer.getHeight() * 0.5f);
            creditBlockContainer.setAlpha(creditLookCurAlpha);
            creditBlockContainer.setScaleX(creditLookCurScale);
            creditBlockContainer.setScaleY(creditLookCurScale);
            return;
        }

        if (Math.abs(creditLookToAlpha - targetAlpha) < 0.001f
                && Math.abs(creditLookToScale - targetScale) < 0.001f) {
            return;
        }

        if (creditLookAnimator != null) {
            creditLookAnimator.cancel();
            creditLookAnimator = null;
        }

        final float fromAlpha = creditLookCurAlpha;
        final float fromScale = creditLookCurScale;
        creditLookToAlpha = targetAlpha;
        creditLookToScale = targetScale;

        final int alphaMs = Math.max(1, (int) (750L + staggerDelayMs));
        final int scaleMs = Math.max(1, (int) (800L + staggerDelayMs * 2L));
        final int totalMs = Math.max(alphaMs, scaleMs);

        creditLookAnimator = ValueAnimator.ofFloat(0f, (float) totalMs);
        creditLookAnimator.setDuration(totalMs);
        creditLookAnimator.setInterpolator(new LinearInterpolator());
        creditLookAnimator.addUpdateListener(anim -> {
            float clockMs = (Float) anim.getAnimatedValue();
            float fAlpha = TRANSITION_INTERPOLATOR.getInterpolation(Math.max(0f, Math.min(1f, clockMs / alphaMs)));
            float fScale = TRANSITION_INTERPOLATOR.getInterpolation(Math.max(0f, Math.min(1f, clockMs / scaleMs)));
            creditLookCurAlpha = fromAlpha + (targetAlpha - fromAlpha) * fAlpha;
            creditLookCurScale = fromScale + (targetScale - fromScale) * fScale;
            creditBlockContainer.setPivotX(creditBlockContainer.getPaddingLeft());
            creditBlockContainer.setPivotY(creditBlockContainer.getHeight() * 0.5f);
            creditBlockContainer.setAlpha(creditLookCurAlpha);
            creditBlockContainer.setScaleX(creditLookCurScale);
            creditBlockContainer.setScaleY(creditLookCurScale);
        });
        creditLookAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                creditLookAnimator = null;
                creditLookCurAlpha = targetAlpha;
                creditLookCurScale = targetScale;
                creditBlockContainer.setAlpha(targetAlpha);
                creditBlockContainer.setScaleX(targetScale);
                creditBlockContainer.setScaleY(targetScale);
            }
        });
        creditLookAnimator.start();
    }

    private void updateLinesDepthOfField(int activeIndex) {
        Set<Integer> set = new HashSet<>();
        if (activeIndex >= 0) set.add(activeIndex);
        updateLinesDepthOfField(set, activeIndex);
    }

    private void scrollToActiveLine(int index) {
        scrollToActiveLine(index, false);
    }

    private void scrollToActiveLine(int index, boolean forceInstant) {
        if (index >= 0 && index < lineRows.size() && scrollView.getHeight() > 0) {
            View row = lineRows.get(index);
            if (row.getHeight() == 0 && row.getTop() == 0 && index > 0) {
                row.post(() -> scrollToActiveLine(index, forceInstant));
                return;
            }
            final int clamped = computeBlockScrollTargetY(index);
            if (clamped < 0) {
                row.post(() -> scrollToActiveLine(index, forceInstant));
                return;
            }
            lastScrollTarget = clamped;
            int refIdx = previousPrimaryActiveIndex >= 0 ? previousPrimaryActiveIndex : index;
            View refView = (refIdx >= 0 && refIdx < lineRows.size()) ? lineRows.get(refIdx) : row;
            scrollEngine.scrollTo(clamped, row, index, refView, refIdx, 350L, forceInstant);
        }
    }

    private void updateWordSync(long positionMs) {
        boolean enabled = Settings.LYRICS_WORD_SYNC.get();
        if (enabled != wordSyncWasEnabled) {
            if (!enabled) {
                int count = Math.min(lineWordSpans.size(), lineViews.size());
                for (int i = 0; i < count; i++) {
                    lineViews.get(i).setTextColor(lineTextColor());
                    ForegroundColorSpan cached = i < lineUnsungSpans.size()
                            ? lineUnsungSpans.get(i) : null;
                    if (cached != null && lineViews.get(i).getText() instanceof Spannable) {
                        ((Spannable) lineViews.get(i).getText()).removeSpan(cached);
                        lineUnsungSpans.set(i, null);
                    }
                    if (lineViews.get(i) instanceof LyricsLineView) {
                        ((LyricsLineView) lineViews.get(i)).setHighlight(
                                Collections.emptyList(), 0, false, 0, 0, -1);
                    }
                }
                previousActiveSet.clear();
                wordSyncWasEnabled = enabled;
                return;
            }
            wordSyncWasEnabled = enabled;
        }
        if (!enabled) {
            return;
        }

        for (int prevIdx : previousActiveSet) {
            if (!activeIndicesSet.contains(prevIdx)) {
                LyricsLine prevLine = (lyrics != null && prevIdx < lyrics.lines().size())
                        ? lyrics.lines().get(prevIdx) : null;
                long lineEnd = prevLine != null ? prevLine.endTimeMs() : LyricsLine.NO_TIME;
                if (prevIdx >= 0 && prevIdx < lineViews.size() && lineViews.get(prevIdx) instanceof LyricsLineView llv) {
                    long wordEnd = llv.getLastWordEndMs();
                    if (wordEnd != LyricsLine.NO_TIME) {
                        lineEnd = (lineEnd == LyricsLine.NO_TIME) ? wordEnd : Math.max(lineEnd, wordEnd);
                    }
                }
                if (lineEnd == LyricsLine.NO_TIME && lyrics != null && prevIdx + 1 < lyrics.lines().size()) {
                    lineEnd = lyrics.lines().get(prevIdx + 1).startTimeMs();
                }
                if (prevLine != null && positionMs < prevLine.startTimeMs()) {
                    clearWordHighlight(prevIdx);
                } else if (lineEnd != LyricsLine.NO_TIME && positionMs >= lineEnd - 250L) {
                    applyWordColors(prevIdx, 0, true);
                    applyBgWordColors(prevIdx, 0, true);
                } else {
                    applyWordColors(prevIdx, 0, true);
                    applyBgWordColors(prevIdx, 0, true);
                }
            }
        }
        previousActiveSet.clear();
        previousActiveSet.addAll(activeIndicesSet);

        // highlight words for all active lines together
        for (int activeIdx : activeIndicesSet) {
            if (activeIdx >= 0 && activeIdx < lineViews.size()) {
                TextView tv = lineViews.get(activeIdx);
                boolean hasWords = (tv instanceof LyricsLineView)
                        ? ((LyricsLineView) tv).hasWordTimings()
                        : (activeIdx < lineWordSpans.size() && !lineWordSpans.get(activeIdx).isEmpty());
                if (!hasWords) {
                    applyWordColors(activeIdx, 0, true);
                } else {
                    applyWordColors(activeIdx, positionMs, false);
                }
                applyBgWordColors(activeIdx, positionMs, false);
            }
        }
    }

    private void applyBgWordColors(int parentIndex, long positionMs, boolean allSung) {
        if (lyrics == null || parentIndex < 0) return;
        List<LyricsLine> lines = lyrics.lines();
        for (int i = parentIndex + 1; i < lines.size() && isSecondaryLineView(i); i++) {
            applyWordColors(i, positionMs, allSung);
        }
    }

    private void resetBgWordColors(int parentIndex) {
        if (lyrics == null || parentIndex < 0) return;
        List<LyricsLine> lines = lyrics.lines();
        for (int i = parentIndex + 1; i < lines.size() && isSecondaryLineView(i); i++) {
            applyWordColors(i, Long.MIN_VALUE, false);
        }
    }

    private void clearWordHighlight(int lineIndex) {
        applyWordColors(lineIndex, Long.MIN_VALUE, false);
        resetBgWordColors(lineIndex);
    }

    private int computeOriginalTextStart(LyricsLine line, int index, boolean hasWordUnits) {
        return 0;
    }

    private void applyWordColors(int index, long positionMs, boolean allSung) {
        if (index < 0 || index >= lineViews.size()) {
            return;
        }

        List<WordTiming> timings = (index < lineWordSpans.size()) ? lineWordSpans.get(index) : Collections.emptyList();
        int origStart = index < lineOriginalStarts.size() ? lineOriginalStarts.get(index) : 0;
        TextView lineView = lineViews.get(index);

        if (lineView instanceof LyricsLineView && ((LyricsLineView) lineView).hasWordUnits()) {
            ((LyricsLineView) lineView).setHighlight(
                    timings, positionMs, allSung, unsungWordColor(), lineTextColor(), origStart);
            return;
        }

        if (positionMs == Long.MIN_VALUE && lineView.getText() instanceof Spannable) {
            ForegroundColorSpan cached = index < lineUnsungSpans.size()
                    ? lineUnsungSpans.get(index) : null;
            if (cached != null) {
                ((Spannable) lineView.getText()).removeSpan(cached);
                lineUnsungSpans.set(index, null);
            }
        }

        if (lineView instanceof LyricsLineView) {
            ((LyricsLineView) lineView).setHighlight(
                    timings, positionMs, allSung, unsungWordColor(), lineTextColor(), origStart);
        }
    }

    /** smooth fade between lines */
    private static void fadeTo(TextView lineView, float alpha) {
        if (lineView == null) {
            return;
        }
        Object tag = lineView.getTag();
        if (tag instanceof Float && Math.abs(((Float) tag) - alpha) < 0.001f) {
            return;
        }
        lineView.setTag(alpha);
        if (Math.abs(lineView.getAlpha() - alpha) < 0.01f) {
            return;
        }
        lineView.animate().cancel();
        lineView.animate()
                .alpha(alpha)
                .setInterpolator(TRANSITION_INTERPOLATOR)
                .setDuration(HIGHLIGHT_FADE_DURATION_MILLISECONDS)
                .start();
    }


    private static void applyBlurEffect(View view, @Nullable RenderEffect effect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (view instanceof LyricsLineView) {
                ((LyricsLineView) view).setRenderEffectCompat(effect);
            } else if (view != null) {
                view.setRenderEffect(effect);
            }
        }
    }

    private static void applyFooterStyle(TextView footer) {
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, FOOTER_TEXT_SIZE_SP);
        footer.setTextColor(lineTextColor());
        footer.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        footer.setIncludeFontPadding(false);
    }

    @NonNull
    private static SpannableString formatSongwritersSpannable(@NonNull List<String> songwriters) {
        Set<String> writerNames = new LinkedHashSet<>();
        List<String> otherLines = new ArrayList<>();

        for (String raw : songwriters) {
            if (raw == null) continue;
            for (String partLine : raw.split("\\r?\\n")) {
                String trimmed = partLine.trim();
                if (trimmed.isEmpty()) continue;
                String lower = trimmed.toLowerCase(Locale.ROOT);
                if (lower.startsWith("written by:") || lower.startsWith("written by ")
                        || lower.startsWith("songwriter:") || lower.startsWith("songwriters:")) {
                    int colonIdx = trimmed.indexOf(':');
                    String namesPart = (colonIdx >= 0)
                            ? trimmed.substring(colonIdx + 1).trim()
                            : trimmed.substring("written by".length()).trim();
                    for (String name : namesPart.split(",")) {
                        String n = name.trim();
                        if (!n.isEmpty()) {
                            writerNames.add(n);
                        }
                    }
                } else if (trimmed.indexOf(':') < 0 && trimmed.indexOf('：') < 0
                        && !lower.startsWith("lyrics ") && !lower.startsWith("©") && !lower.startsWith("(c)")) {
                    for (String name : trimmed.split(",")) {
                        String n = name.trim();
                        if (!n.isEmpty()) {
                            writerNames.add(n);
                        }
                    }
                } else {
                    if (!otherLines.contains(trimmed)) {
                        otherLines.add(trimmed);
                    }
                }
            }
        }

        List<String> finalLines = new ArrayList<>();
        if (!writerNames.isEmpty()) {
            finalLines.add("Written by: " + String.join(", ", writerNames));
        }
        finalLines.addAll(otherLines);

        StringBuilder fullText = new StringBuilder();
        List<int[]> boldRanges = new ArrayList<>();

        for (int i = 0; i < finalLines.size(); i++) {
            if (i > 0) fullText.append('\n');
            String line = finalLines.get(i);
            int lineStart = fullText.length();
            fullText.append(line);
            int colonIdx = line.indexOf(':');
            if (colonIdx < 0) colonIdx = line.indexOf('：');
            if (colonIdx >= 0 && colonIdx + 1 < line.length()) {
                int valStart = colonIdx + 1;
                while (valStart < line.length() && line.charAt(valStart) == ' ') {
                    valStart++;
                }
                if (valStart < line.length()) {
                    boldRanges.add(new int[]{lineStart + valStart, lineStart + line.length()});
                }
            }
        }

        SpannableString spannable = new SpannableString(fullText.toString());
        for (int[] range : boldRanges) {
            spannable.setSpan(new LyricsBoldSpan(), range[0], range[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return spannable;
    }

    private static void suppressContainerLayout(ViewGroup vg, boolean suppress) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vg.suppressLayout(suppress);
        }
    }

    /**
     * Styles the button as a pill, the shape the app uses for the buttons under its
     * own lyrics, with the background taken from the app palette so it follows the theme.
     *
     * @param iconName Drawable name for the button icon, or {@code null} for a text only button.
     */
    private void applyButtonStyle(TextView button, @Nullable String iconName) {
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON_TEXT_SIZE_SP);
        button.setTextColor(lineTextColor());
        button.setTypeface(null, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(Dim.dp16, Dim.dp8, Dim.dp16, Dim.dp8);

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(Dim.dp20);
        background.setColor(ResourceUtils.getColor(APP_BUTTON_BACKGROUND_COLOR, 0x1AFFFFFF));
        background.setStroke(Dim.dp(1), 0x2EFFFFFF);
        button.setBackground(background);

        ViewAnimations.applyPressEffect(button);

        if (iconName == null || iconName.isEmpty()) {
            return;
        }

        Drawable icon = ResourceUtils.getDrawable(iconName);
        if (icon != null) {
            icon = icon.mutate();
            icon.setTint(lineTextColor());
            final int iconSize = Dim.dp(18);
            icon.setBounds(0, 0, iconSize, iconSize);
            button.setCompoundDrawables(icon, null, null, null);
            button.setCompoundDrawablePadding(Dim.dp(6));
        }
    }

    private void setButtonLabel(TextView button, @Nullable String text, boolean active) {
        if (button == null) {
            return;
        }

        final int targetBg = active
                ? ACTIVE_BUTTON_BG_COLOR
                : ResourceUtils.getColor(APP_BUTTON_BACKGROUND_COLOR, 0x1AFFFFFF);
        final int targetFg = active ? ThemeUtils.getAppBackgroundColor() : lineTextColor();
        final Drawable bgDrawable = button.getBackground();

        int currentBg = 0;
        if (bgDrawable instanceof GradientDrawable) {
            ColorStateList csl = ((GradientDrawable) bgDrawable).getColor();
            if (csl != null) {
                currentBg = csl.getDefaultColor();
            }
        }
        final int fromBg = (currentBg != 0) ? currentBg : targetBg;
        final int fromFg = button.getCurrentTextColor();

        ValueAnimator colorAnim = ValueAnimator.ofFloat(0f, 1f);
        colorAnim.setDuration(BUTTON_STATE_FADE_MILLISECONDS);
        colorAnim.addUpdateListener(anim -> {
            float fraction = (float) anim.getAnimatedValue();
            int cBg = (int) BUTTON_COLOR_EVALUATOR.evaluate(fraction, fromBg, targetBg);
            int cFg = (int) BUTTON_COLOR_EVALUATOR.evaluate(fraction, fromFg, targetFg);
            if (bgDrawable instanceof GradientDrawable) {
                ((GradientDrawable) bgDrawable).setColor(cBg);
            }
            button.setTextColor(cFg);
            Drawable[] compoundDrawables = button.getCompoundDrawables();
            if (compoundDrawables[0] != null) {
                compoundDrawables[0].setTint(cFg);
            }
        });
        colorAnim.start();

        if (text != null && !text.isEmpty()) {
            button.setText(text);
        } else {
            button.setText("");
            button.setCompoundDrawablePadding(0);
        }
    }

    private static String sourceText(String provider, boolean translated, boolean fromGoogleTrans,
                                     boolean fromAITrans, boolean fromGoogleRoma, boolean fromAIRoma,
                                     @Nullable String aiModel, @Nullable String localDictionary) {
        StringBuilder sb = new StringBuilder();
        String baseLabel = str(LYRICS_SOURCE_KEY, provider);
        if (baseLabel != null && provider != null && !provider.isEmpty()
                && !baseLabel.contains(":") && !baseLabel.contains("：")
                && baseLabel.endsWith(provider) && baseLabel.length() > provider.length()) {
            String prefix = baseLabel.substring(0, baseLabel.length() - provider.length()).trim();
            baseLabel = prefix + ": " + provider;
        }
        sb.append(baseLabel);

        if (translated) {
            if (fromAITrans) {
                sb.append(" • AI Translated");
                if (aiModel != null && !aiModel.isEmpty()) {
                    sb.append(" (").append(aiModel).append(")");
                }
            } else if (fromGoogleTrans) {
                sb.append(" • Google Translated");
            }
        }

        if (fromAIRoma) {
            sb.append(" • AI Romanized");
            if (aiModel != null && !aiModel.isEmpty()) {
                sb.append(" (").append(aiModel).append(")");
            }
        } else if (fromGoogleRoma) {
            sb.append(" • Google Romanized");
        } else if (localDictionary != null && !localDictionary.isEmpty()) {
            sb.append(" • ").append(localDictionary);
        }

        return sb.toString();
    }

    static int lineTextColor() {
        return ResourceUtils.getColor(APP_PRIMARY_TEXT_COLOR, Color.WHITE);
    }

    static int secondaryTextColor() {
        return ResourceUtils.getColor(APP_SECONDARY_TEXT_COLOR, 0xB3FFFFFF);
    }

    static int auxiliaryTextColor() {
        return 0xD9FFFFFF;
    }

    static int unsungWordColor() {
        int color = lineTextColor();
        return Color.argb(
                UNSUNG_ALPHA,
                Color.red(color),
                Color.green(color),
                Color.blue(color));
    }

}
