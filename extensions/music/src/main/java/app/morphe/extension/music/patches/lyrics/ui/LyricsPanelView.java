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
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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

import app.morphe.extension.music.patches.lyrics.LyricsManager;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.LyricsMerge;
import app.morphe.extension.music.patches.lyrics.model.LyricsRetiming;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.patches.lyrics.parsers.LRCParser;
import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests;
import app.morphe.extension.music.patches.lyrics.storage.LyricsFileSaver;
import app.morphe.extension.music.patches.lyrics.translate.LyricsRomanizer;
import app.morphe.extension.music.patches.lyrics.translate.LyricsTranslator;
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

    private static final float INACTIVE_LINE_ALPHA = 0.35f;
    private static final float ACTIVE_LINE_ALPHA = 1.0f;
    private static final float BROWSING_ALPHA = 0.65f;
    private static final float INACTIVE_LINE_SCALE = 1.0f;
    private static final float ACTIVE_LINE_SCALE = 1.0f;
    private static final float PRESSED_LINE_SCALE = 1.0f;

    private static final float[] LINE_FALLOFF_ALPHA = { 1.0f, 0.45f, 0.35f, 0.35f, 0.35f };
    private static final float[] LINE_FALLOFF_SCALE = { 1.0f, 1.0f, 1.0f, 1.0f, 1.0f };

    private static final Interpolator TRANSITION_INTERPOLATOR = new PathInterpolator(0.25f, 0.1f, 0.25f, 1.0f);
    private static final float SCROLL_ANCHOR_RATIO = 0.20f;
    private static final float SCROLL_DAMPING_FACTOR = 0.10f;

    private static final Typeface LYRICS_TYPEFACE = Typeface.create("sans-serif", Typeface.BOLD);

    private static RenderEffect[] BLUR_EFFECTS;

    private static void ensureBlurEffects() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && BLUR_EFFECTS == null) {
            BLUR_EFFECTS = new RenderEffect[5];
            BLUR_EFFECTS[0] = null;
            BLUR_EFFECTS[1] = RenderEffect.createBlurEffect(Dim.dp(1.2f), Dim.dp(1.2f), Shader.TileMode.CLAMP);
            BLUR_EFFECTS[2] = RenderEffect.createBlurEffect(Dim.dp(1.8f), Dim.dp(1.8f), Shader.TileMode.CLAMP);
            BLUR_EFFECTS[3] = RenderEffect.createBlurEffect(Dim.dp(2.4f), Dim.dp(2.4f), Shader.TileMode.CLAMP);
            BLUR_EFFECTS[4] = RenderEffect.createBlurEffect(Dim.dp(3.0f), Dim.dp(3.0f), Shader.TileMode.CLAMP);
        }
    }

    /** Applied on top of the secondary color, which alone is brighter than the app draws it. */
    private static final float FOOTER_ALPHA = 0.6f;

    /** Fade length when the highlight moves from one line to the next (200-250ms). */
    private static final long HIGHLIGHT_FADE_DURATION_MILLISECONDS = 220;

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

    /** Alpha channel for the unsung (not-yet-sung) word color (0.35f alpha = 0x59). */
    private static final int UNSUNG_ALPHA = 0x59;

    /** Icons of the buttons the app draws under its own lyrics. */
    private static final String APP_TRANSLATE_ICON = "yt_outline_experimental_translate_vd_theme_24";

    /** Icon for the romanize button, showing the pronunciation above each line. */
    private static final String APP_ROMANIZE_ICON = "yt_outline_experimental_waveform_vd_theme_24";

    private static final String REFRESH_ICON = "ic_mtrl_arrow_circle";

    /** Own icon, because the app ships no copy icon of its own. */
    private static final String COPY_ICON = "morphe_yt_copy_bold";

    /** Translation size relative to the lyrics line it belongs to (~55%). */
    private static final float TRANSLATION_RELATIVE_SIZE = 0.55f;
    /** Per-word romanization size relative to the lyrics line it belongs to (~55%). */
    private static final float ROMAJI_RELATIVE_SIZE = 0.55f;

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
    private final LinearLayout footerContainer;
    private final LinearLayout buttonRow;
    private final ProgressBar progressBar;

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

    private record GapInterval(long startMs, long endMs, long nextStartMs, boolean isDuet, int beforeLineIndex) {}
    private final List<GapInterval> gapIntervals = new ArrayList<>();

    private static final long INTERVAL_MIN_GAP_MS = 7000L;
    private static final long GAP_EXIT_DURATION_MS = 600L;
    private static final float INTERVAL_DOT_RADIUS_DP = 4.0f;
    private static final float INTERVAL_DOT_GAP_DP = 7.0f;
    private static final int INTERVAL_SECONDARY_ALPHA = 0x55;
    private static final int INTERVAL_PRIMARY_ALPHA = 0xFF;

    private final List<GapLineView> gapLineViews = new ArrayList<>();
    private final SparseArray<GapLineView> gapLineViewsByLine = new SparseArray<>();

    private float currentSmoothScrollY = -1f;
    private int targetScrollY = 0;
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

    private void onResumeFollow() {
        isBrowsing = false;
        userScrollUntilUptimeMs = 0;
        isDraggingScroll = false;
        isUserTouchingScroll = false;
        hideJumpToCurrentButton();
        if (scrollView != null) {
            scrollView.fling(0);
            scrollView.stopNestedScroll();
        }
        currentSmoothScrollY = (scrollView != null) ? scrollView.getScrollY() : 0f;
        long currentPos = LyricsManager.getInstance().getPositionMs();
        GapInterval activeGi = getActiveGapInterval(currentPos);
        boolean inGapBreak = (activeGi != null && currentPos < activeGi.endMs());
        if (lyrics != null && lyrics.synced()) {
            Set<Integer> activeSet = inGapBreak ? Collections.emptySet() : computeActiveLines(lyrics, currentPos);
            int active = inGapBreak ? -1 : computePrimaryActiveIndex(lyrics, activeSet, currentPos);
            if (active < 0 && activeGi != null) {
                active = activeGi.beforeLineIndex();
            }
            if (active < 0 && !lyrics.lines().isEmpty() && currentPos < lyrics.lines().get(0).startTimeMs()) {
                active = 0;
            }
            if (active >= 0) {
                activeIndicesSet.clear();
                activeIndicesSet.addAll(activeSet);
                highlightedIndex = active;
                primaryActiveIndex = active;
                updateLinesDepthOfField(activeIndicesSet, inGapBreak ? -1 : active, inGapBreak && activeGi != null ? activeGi.beforeLineIndex() : -1);
                GapLineView gv = inGapBreak && activeGi != null ? gapLineViewsByLine.get(activeGi.beforeLineIndex()) : null;
                if (gv != null && gv.isGapActive()) {
                    scrollToGapView(gv);
                } else {
                    scrollToActiveLine(active);
                }
            } else {
                targetScrollY = 0;
            }
        } else if (highlightedIndex >= 0) {
            updateLinesDepthOfField(highlightedIndex);
            scrollToActiveLine(highlightedIndex);
        } else {
            targetScrollY = 0;
        }
    }

    private void showJumpToCurrentButton() {
        if (jumpToCurrentView == null) return;
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

    private int lastWordLineIndex = -1;

    private int lastOverlayIndex = -1;

    private int pendingOldWordLineIndex = -1;

    private boolean seekPending;

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

    private record WordTiming(int start, int end, long startMs, long endMs,
            @Nullable String romaji) {
    }

    private record BuildResult(Spannable text, @Nullable ForegroundColorSpan unsungSpan,
            int transStart, int transEnd, int romaStart, int romaEnd) {
    }

    private static final class LyricsLineView extends TextView {
        public static final class WordUnit {
            public final String kanji;
            public String romaji;
            public long startMs;
            public long endMs;
            public boolean endsWithSpace;
            public float kanjiWidth;
            public float romajiWidth;
            public float unitLeft;
            public float unitRight;
            public float kanjiX;
            public float romajiX;
            public float kanjiY;
            public float romajiY;
            public float lineTop;
            public float lineBottom;
            public int lineIndex;

            public float[] charWidths;
            public float[] charOffsets;
            public float[] romajiCharWidths;
            public float[] romajiCharOffsets;

            public long wordStartMs = LyricsLine.NO_TIME;
            public long wordEndMs = LyricsLine.NO_TIME;
            public float wordCenterX;
            public float wordCenterY;
            public float kanjiCenterX;
            public float kanjiCenterY;
            public float romajiCenterX;
            public float romajiCenterY;

            public WordUnit(String kanji, String romaji, long startMs, long endMs, boolean endsWithSpace) {
                this.kanji = kanji != null ? kanji : "";
                this.romaji = romaji != null ? romaji : "";
                this.startMs = startMs;
                this.endMs = endMs;
                this.endsWithSpace = endsWithSpace;
                this.wordStartMs = startMs;
                this.wordEndMs = endMs;
            }

            public WordUnit(String kanji, String romaji, long startMs, long endMs) {
                this(kanji, romaji, startMs, endMs, true);
            }
        }

        @Nullable
        private List<WordUnit> wordUnits = null;
        @Nullable
        private String translationText = null;
        private final TextPaint transPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private StaticLayout transLayout = null;
        private int transLayoutWidth = -1;

        private final TextPaint romajiPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint unsungKanjiPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint unsungRomajiPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint sungKanjiPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint sungRomajiPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint glowPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private int cachedLayoutWidth = -1;
        private int totalMeasuredHeight = 0;

        private List<WordTiming> wordTimings = Collections.emptyList();
        private long positionMs = Long.MIN_VALUE;
        private boolean allSung = false;
        private int unsungColor;
        private int sungColor;
        private int originalTextStart;
        private float[] lineMaxSungX;
        private float currentScale = 0.95f;
        private boolean isLineActive = false;
        private boolean cachedHasWordTimings = false;

        private boolean computeHasWordTimings() {
            if (wordUnits != null && !wordUnits.isEmpty()) {
                for (int i = 0; i < wordUnits.size(); i++) {
                    if (wordUnits.get(i).startMs != LyricsLine.NO_TIME) {
                        return true;
                    }
                }
                return false;
            }
            return wordTimings != null && !wordTimings.isEmpty();
        }

        public boolean hasWordTimings() {
            return cachedHasWordTimings;
        }

        public void setLineActive(boolean active) {
            if (this.isLineActive != active) {
                this.isLineActive = active;
                postInvalidateOnAnimation();
            }
        }

        private boolean isSecondary = false;
        private int parentLineIndex = -1;

        public void setSecondary(boolean secondary) {
            this.isSecondary = secondary;
        }

        public boolean isSecondary() {
            return isSecondary;
        }

        public void setParentLineIndex(int idx) {
            this.parentLineIndex = idx;
        }

        public int getParentLineIndex() {
            return parentLineIndex;
        }

        private float secondaryExpandFraction = 1f;
        @Nullable
        private ValueAnimator secondaryExpandAnimator;

        public void setSecondaryExpandFraction(float fraction) {
            float clamped = Math.max(0f, Math.min(1f, fraction));
            if (Math.abs(this.secondaryExpandFraction - clamped) > 0.001f) {
                this.secondaryExpandFraction = clamped;
                requestLayout();
                invalidate();
            }
        }

        public float getSecondaryExpandFraction() {
            return secondaryExpandFraction;
        }

        public void animateSecondaryExpansion(boolean expand) {
            float target = expand ? 1f : 0f;
            if (secondaryExpandAnimator != null) {
                secondaryExpandAnimator.cancel();
                secondaryExpandAnimator = null;
            }
            if (Math.abs(secondaryExpandFraction - target) < 0.001f) {
                if (!expand) {
                    if (getVisibility() != View.GONE) {
                        setVisibility(View.GONE);
                    }
                    setAlpha(0f);
                } else {
                    if (getVisibility() != View.VISIBLE) {
                        setVisibility(View.VISIBLE);
                    }
                    setAlpha(0.90f);
                }
                return;
            }
            if (expand && getVisibility() != View.VISIBLE) {
                setVisibility(View.VISIBLE);
            }
            long duration = expand ? 450L : 350L;
            secondaryExpandAnimator = ValueAnimator.ofFloat(secondaryExpandFraction, target);
            secondaryExpandAnimator.setDuration(duration);
            secondaryExpandAnimator.setInterpolator(TRANSITION_INTERPOLATOR);
            secondaryExpandAnimator.addUpdateListener(animation -> {
                float val = (Float) animation.getAnimatedValue();
                setSecondaryExpandFraction(val);
                setAlpha(val * 0.90f);
            });
            secondaryExpandAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    secondaryExpandAnimator = null;
                    if (!expand) {
                        setVisibility(View.GONE);
                        setAlpha(0f);
                    } else {
                        setAlpha(0.90f);
                    }
                }
            });
            secondaryExpandAnimator.start();
        }

        private boolean isGravityRightAligned() {
            int absGravity = Gravity.getAbsoluteGravity(getGravity(), getLayoutDirection());
            return (absGravity & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.RIGHT;
        }

        private RenderEffect currentRenderEffect;

        public void setRenderEffectCompat(@Nullable RenderEffect effect) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (this.currentRenderEffect == effect) {
                    return;
                }
                this.currentRenderEffect = effect;
                super.setRenderEffect(effect);
            }
        }

        private Layout cachedLayout;
        /** Where the word starts and ends being sung, in the direction its script runs. */
        private float[] cachedWordLeadX;
        private float[] cachedWordTrailX;
        private int[] cachedWordLine;
        private int[] cachedWordWrappedLine;
        private int cachedWordCount;
        private int cachedLineCount;
        private int[] cachedLineTop;
        private int[] cachedLineBottom;
        private float[] cachedLineLeft;
        private float[] cachedLineRight;
        private int cachedOrigStart;

        private CharSequence cachedText;
        private String cachedTextStr;

        private Layout sungLayout;
        private CharSequence sungLayoutText;
        private int sungLayoutWidth = -1;
        private int sungLayoutColor;
        private int sungLayoutOrigStart = -1;

        private int transStart = -1;
        private int transEnd = -1;
        private int romaStart = -1;
        private int romaEnd = -1;
        private float lastTouchY;

        LyricsLineView(Context context) {
            super(context);
        }

        void setWordUnits(@Nullable List<WordUnit> units) {
            this.wordUnits = (units != null && !units.isEmpty()) ? units : null;
            this.cachedHasWordTimings = computeHasWordTimings();
            this.cachedLayoutWidth = -1;
            requestLayout();
            invalidate();
        }

        void setTranslationText(@Nullable String text) {
            this.translationText = (text != null && !text.trim().isEmpty()) ? text.trim() : null;
            this.transLayout = null;
            this.cachedLayoutWidth = -1;
            requestLayout();
            invalidate();
        }

        public boolean hasWordUnits() {
            return wordUnits != null && !wordUnits.isEmpty();
        }

        private void layoutWordUnits(int availableWidth) {
            if (wordUnits == null || wordUnits.isEmpty() || availableWidth <= 0) {
                return;
            }
            if (availableWidth == cachedLayoutWidth) {
                return;
            }
            cachedLayoutWidth = availableWidth;

            Paint mainP = getPaint();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mainP.setFontFeatureSettings("\"liga\" 0, \"clig\" 0, \"dlig\" 0");
            }
            romajiPaint.setTextSize(mainP.getTextSize() * 0.54f);
            romajiPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));

            unsungKanjiPaint.set(mainP);
            unsungKanjiPaint.clearShadowLayer();
            unsungKanjiPaint.setFakeBoldText(false);
            sungKanjiPaint.set(mainP);
            sungKanjiPaint.clearShadowLayer();
            sungKanjiPaint.setFakeBoldText(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                unsungKanjiPaint.setFontFeatureSettings("\"liga\" 0, \"clig\" 0, \"dlig\" 0");
                sungKanjiPaint.setFontFeatureSettings("\"liga\" 0, \"clig\" 0, \"dlig\" 0");
            }

            unsungRomajiPaint.set(romajiPaint);
            unsungRomajiPaint.clearShadowLayer();
            unsungRomajiPaint.setFakeBoldText(false);
            sungRomajiPaint.set(romajiPaint);
            sungRomajiPaint.clearShadowLayer();
            sungRomajiPaint.setFakeBoldText(false);

            Paint.FontMetrics kanjiFm = mainP.getFontMetrics();
            float kanjiHeight = kanjiFm.descent - kanjiFm.ascent;
            float kanjiBaseline = -kanjiFm.ascent;

            Paint.FontMetrics romajiFm = romajiPaint.getFontMetrics();
            float romajiHeight = romajiFm.descent - romajiFm.ascent;
            float romajiBaseline = -romajiFm.ascent;

            boolean hasRomaji = false;
            for (int i = 0; i < wordUnits.size(); i++) {
                if (!wordUnits.get(i).romaji.isEmpty()) {
                    hasRomaji = true;
                    break;
                }
            }

            float romajiMargin = hasRomaji ? Dim.dp(4f) : 0f;
            float effectiveRomajiHeight = hasRomaji ? romajiHeight : 0f;
            float singleLineHeight = kanjiHeight + romajiMargin + effectiveRomajiHeight;
            float lineSpacing = hasRomaji
                    ? (singleLineHeight + Dim.dp(6f))
                    : (singleLineHeight * 1.15f + Dim.dp(4f));
            float baseSpaceWidth = mainP.measureText(" ");

            // 1. Pre-measure all word units upfront so lookahead and word widths are always exact
            for (int i = 0; i < wordUnits.size(); i++) {
                WordUnit u = wordUnits.get(i);
                u.kanjiWidth = mainP.measureText(u.kanji);
                u.romajiWidth = (!u.romaji.isEmpty()) ? romajiPaint.measureText(u.romaji) : 0f;
                int kLen = u.kanji.length();
                if (kLen > 0) {
                    u.charWidths = new float[kLen];
                    u.charOffsets = new float[kLen];
                    mainP.getTextWidths(u.kanji, u.charWidths);
                    boolean hasLigature = false;
                    for (int c = 0; c < kLen; c++) {
                        if (u.charWidths[c] <= 0f) {
                            hasLigature = true;
                            break;
                        }
                    }
                    if (hasLigature) {
                        for (int c = 0; c < kLen; ) {
                            int cp = u.kanji.codePointAt(c);
                            int cc = Character.charCount(cp);
                            float cw = mainP.measureText(u.kanji, c, c + cc);
                            for (int k = 0; k < cc; k++) {
                                u.charWidths[c + k] = (k == 0) ? cw : 0f;
                            }
                            c += cc;
                        }
                    }
                    float off = 0f;
                    for (int c = 0; c < kLen; c++) {
                        u.charOffsets[c] = off;
                        off += u.charWidths[c];
                    }
                    u.kanjiWidth = Math.max(u.kanjiWidth, off);
                } else {
                    u.charWidths = null;
                    u.charOffsets = null;
                }

                int rLen = u.romaji.length();
                if (rLen > 0) {
                    u.romajiCharWidths = new float[rLen];
                    u.romajiCharOffsets = new float[rLen];
                    romajiPaint.getTextWidths(u.romaji, u.romajiCharWidths);
                    boolean hasLigature = false;
                    for (int c = 0; c < rLen; c++) {
                        if (u.romajiCharWidths[c] <= 0f) {
                            hasLigature = true;
                            break;
                        }
                    }
                    if (hasLigature) {
                        for (int c = 0; c < rLen; ) {
                            int cp = u.romaji.codePointAt(c);
                            int cc = Character.charCount(cp);
                            float cw = romajiPaint.measureText(u.romaji, c, c + cc);
                            for (int k = 0; k < cc; k++) {
                                u.romajiCharWidths[c + k] = (k == 0) ? cw : 0f;
                            }
                            c += cc;
                        }
                    }
                    float rOff = 0f;
                    for (int c = 0; c < rLen; c++) {
                        u.romajiCharOffsets[c] = rOff;
                        rOff += u.romajiCharWidths[c];
                    }
                    u.romajiWidth = Math.max(u.romajiWidth, rOff);
                } else {
                    u.romajiCharWidths = null;
                    u.romajiCharOffsets = null;
                }
            }

            boolean isRtlLine = false;
            for (int i = 0; i < wordUnits.size(); i++) {
                WordUnit u = wordUnits.get(i);
                if (LyricsRomanizer.containsArabic(u.kanji) || LyricsRomanizer.containsHebrew(u.kanji)) {
                    isRtlLine = true;
                    break;
                }
            }

            if (isRtlLine) {
                layoutRtlWordUnits(availableWidth, mainP, kanjiHeight, kanjiBaseline,
                        romajiHeight, romajiBaseline, baseSpaceWidth, hasRomaji);
                return;
            }

            float curX = 0f;
            float curY = 0f;
            int currentLine = 0;
            float lastRomajiEndX = 0f;

            int i = 0;
            while (i < wordUnits.size()) {
                WordUnit firstInGroup = wordUnits.get(i);
                boolean isContinuousScript = LyricsRomanizer.containsJapanese(firstInGroup.kanji)
                        || containsKanji(firstInGroup.kanji)
                        || LyricsRomanizer.containsHanzi(firstInGroup.kanji)
                        || LyricsRomanizer.isSongJapanese();

                if (isContinuousScript) {
                    WordUnit u = firstInGroup;
                    float pillarW = Math.max(u.kanjiWidth, u.romajiWidth);
                    if (curX > 0f && curX + pillarW > availableWidth) {
                        curX = 0f;
                        currentLine++;
                        curY += lineSpacing;
                        lastRomajiEndX = 0f;
                    }
                    u.unitLeft = curX;
                    u.unitRight = curX + pillarW;
                    u.lineIndex = currentLine;
                    u.lineTop = curY;
                    u.lineBottom = curY + singleLineHeight;

                    u.kanjiX = curX;
                    u.romajiX = curX;
                    u.kanjiY = curY + kanjiBaseline;
                    u.romajiY = curY + kanjiHeight + romajiMargin + romajiBaseline;

                    if (!u.romaji.isEmpty()) {
                        lastRomajiEndX = u.romajiX + u.romajiWidth;
                    }
                    float spacing = u.endsWithSpace ? (baseSpaceWidth * 0.6f) : 0f;
                    curX += pillarW + spacing;

                    u.wordStartMs = u.startMs;
                    u.wordEndMs = u.endMs;
                    u.wordCenterX = (u.unitLeft + u.unitRight) / 2f;
                    u.wordCenterY = curY + singleLineHeight / 2f;
                    u.kanjiCenterX = u.kanjiX + u.kanjiWidth / 2f;
                    u.kanjiCenterY = u.wordCenterY;
                    u.romajiCenterX = u.romajiX + u.romajiWidth / 2f;
                    u.romajiCenterY = u.wordCenterY;

                    i++;
                } else {
                    // Non-CJK script (Latin, Indonesian, English, Korean, Cyrillic, etc.)
                    // Group all syllables belonging to the same word:
                    int groupEnd = i;
                    float wordTotalW = 0f;
                    while (groupEnd < wordUnits.size()) {
                        WordUnit wu = wordUnits.get(groupEnd);
                        wordTotalW += Math.max(wu.kanjiWidth, wu.romajiWidth);
                        if (wu.endsWithSpace || groupEnd == wordUnits.size() - 1) {
                            break;
                        }
                        groupEnd++;
                    }

                    // If the entire word does not fit on the current line, wrap BEFORE placing any syllable
                    if (curX > 0f && curX + wordTotalW > availableWidth) {
                        curX = 0f;
                        currentLine++;
                        curY += lineSpacing;
                        lastRomajiEndX = 0f;
                    }

                    int wordStartIdx = i;
                    float wordGroupLeft = curX;
                    long wordStartMs = Long.MAX_VALUE;
                    long wordEndMs = Long.MIN_VALUE;

                    for (int wi = i; wi <= groupEnd; wi++) {
                        WordUnit u = wordUnits.get(wi);
                        float pillarW = Math.max(u.kanjiWidth, u.romajiWidth);

                        // Fallback only if single word itself is wider than the entire screen
                        if (curX > 0f && curX + pillarW > availableWidth) {
                            curX = 0f;
                            currentLine++;
                            curY += lineSpacing;
                            lastRomajiEndX = 0f;
                        }

                        final float spacing;
                        if (LyricsRomanizer.containsHangul(u.kanji)) {
                            spacing = u.endsWithSpace ? baseSpaceWidth : 0f;
                        } else if (LyricsRomanizer.containsCyrillic(u.kanji) || LyricsRomanizer.containsArabic(u.kanji)
                                || LyricsRomanizer.containsHebrew(u.kanji) || LyricsRomanizer.containsGreek(u.kanji)
                                || LyricsRomanizer.containsDevanagari(u.kanji) || LyricsRomanizer.containsThai(u.kanji)) {
                            spacing = u.endsWithSpace ? (hasRomaji ? (baseSpaceWidth * 0.75f) : baseSpaceWidth) : 0f;
                        } else {
                            spacing = u.endsWithSpace ? baseSpaceWidth : 0f;
                        }

                        u.unitLeft = curX;
                        u.unitRight = curX + pillarW;
                        u.lineIndex = currentLine;
                        u.lineTop = curY;
                        u.lineBottom = curY + singleLineHeight;

                        u.kanjiX = curX;
                        u.romajiX = curX;
                        u.kanjiY = curY + kanjiBaseline;
                        u.romajiY = curY + kanjiHeight + romajiMargin + romajiBaseline;

                        u.kanjiCenterX = curX + u.kanjiWidth / 2f;
                        u.kanjiCenterY = curY + singleLineHeight / 2f;
                        u.romajiCenterX = curX + u.romajiWidth / 2f;
                        u.romajiCenterY = curY + singleLineHeight / 2f;

                        if (!u.romaji.isEmpty()) {
                            lastRomajiEndX = u.romajiX + u.romajiWidth;
                        }
                        curX += pillarW + spacing;

                        if (u.startMs != LyricsLine.NO_TIME) {
                            wordStartMs = Math.min(wordStartMs, u.startMs);
                        }
                        if (u.endMs != LyricsLine.NO_TIME) {
                            wordEndMs = Math.max(wordEndMs, u.endMs);
                        }
                    }

                    float wordGroupRight = wordUnits.get(groupEnd).unitRight;
                    float wordCenterX = (wordGroupLeft + wordGroupRight) / 2f;
                    float wordCenterY = (wordUnits.get(wordStartIdx).lineTop + wordUnits.get(wordStartIdx).lineBottom) / 2f;
                    if (wordStartMs == Long.MAX_VALUE) wordStartMs = LyricsLine.NO_TIME;
                    if (wordEndMs == Long.MIN_VALUE) wordEndMs = LyricsLine.NO_TIME;

                    for (int wi = i; wi <= groupEnd; wi++) {
                        WordUnit u = wordUnits.get(wi);
                        u.wordStartMs = wordStartMs;
                        u.wordEndMs = wordEndMs;
                        u.wordCenterX = wordCenterX;
                        u.wordCenterY = wordCenterY;
                    }

                    i = groupEnd + 1;
                }
            }

            final boolean isRightAligned = isGravityRightAligned();
            if (isRightAligned && currentLine >= 0) {
                float[] lineWidths = new float[currentLine + 1];
                for (int j = 0; j < wordUnits.size(); j++) {
                    WordUnit u = wordUnits.get(j);
                    if (u.lineIndex <= currentLine) {
                        lineWidths[u.lineIndex] = Math.max(lineWidths[u.lineIndex], u.unitRight);
                    }
                }
                for (int j = 0; j < wordUnits.size(); j++) {
                    WordUnit u = wordUnits.get(j);
                    float xShift = Math.max(0f, availableWidth - lineWidths[u.lineIndex]);
                    u.unitLeft += xShift;
                    u.unitRight += xShift;
                    u.kanjiX += xShift;
                    u.romajiX += xShift;
                    u.wordCenterX += xShift;
                    u.kanjiCenterX += xShift;
                    u.romajiCenterX += xShift;
                }
            }

            if (translationText != null && !translationText.isEmpty()) {
                transPaint.setTextSize(mainP.getTextSize() * TRANSLATION_RELATIVE_SIZE);
                transPaint.setColor(auxiliaryTextColor());
                transPaint.setAlpha(Math.round(255 * 0.58f));
                transPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));

                Layout.Alignment align = isRightAligned
                        ? Layout.Alignment.ALIGN_OPPOSITE
                        : Layout.Alignment.ALIGN_NORMAL;

                StaticLayout.Builder tb = StaticLayout.Builder
                        .obtain(translationText, 0, translationText.length(), transPaint, availableWidth)
                        .setAlignment(align)
                        .setLineSpacing(Dim.dp(3), 1.15f)
                        .setIncludePad(false);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    tb.setUseLineSpacingFromFallbacks(true);
                }
                transLayout = tb.build();
                transLayoutWidth = availableWidth;

                totalMeasuredHeight = Math.round(curY + singleLineHeight + Dim.dp(4f) + transLayout.getHeight());
            } else {
                transLayout = null;
                totalMeasuredHeight = Math.round(curY + singleLineHeight);
            }
        }

        private void layoutRtlWordUnits(int availableWidth, Paint mainP,
                                        float kanjiHeight, float kanjiBaseline,
                                        float romajiHeight, float romajiBaseline,
                                        float baseSpaceWidth, boolean hasRomaji) {
            final boolean isRightAligned = isGravityRightAligned();

            // 1. Line-wrap Arabic words
            float arabicLineSpacing = kanjiHeight * 1.25f;
            List<List<WordUnit>> arabicLines = new ArrayList<>();
            List<WordUnit> curArabicLine = new ArrayList<>();
            float curLineWidth = 0f;

            for (int i = 0; i < wordUnits.size(); i++) {
                WordUnit u = wordUnits.get(i);
                float wordW = u.kanjiWidth;
                float spaceW = u.endsWithSpace ? baseSpaceWidth : 0f;

                if (!curArabicLine.isEmpty() && curLineWidth + wordW > availableWidth) {
                    arabicLines.add(curArabicLine);
                    curArabicLine = new ArrayList<>();
                    curLineWidth = 0f;
                }
                curArabicLine.add(u);
                curLineWidth += wordW + spaceW;
            }
            if (!curArabicLine.isEmpty()) {
                arabicLines.add(curArabicLine);
            }

            // Position Arabic words (RTL: Word 0 is on the right, Word N is on the left)
            for (int ln = 0; ln < arabicLines.size(); ln++) {
                List<WordUnit> lineWords = arabicLines.get(ln);
                float lineY = ln * arabicLineSpacing;
                float kanjiY = lineY + kanjiBaseline;

                float totalW = 0f;
                for (int w = 0; w < lineWords.size(); w++) {
                    WordUnit u = lineWords.get(w);
                    totalW += u.kanjiWidth;
                    if (w < lineWords.size() - 1 && u.endsWithSpace) {
                        totalW += baseSpaceWidth;
                    }
                }

                float currentRight = isRightAligned ? availableWidth : Math.min(availableWidth, totalW);

                for (int w = 0; w < lineWords.size(); w++) {
                    WordUnit u = lineWords.get(w);
                    u.kanjiX = currentRight - u.kanjiWidth;
                    u.kanjiY = kanjiY;
                    u.kanjiCenterX = u.kanjiX + u.kanjiWidth / 2f;
                    u.kanjiCenterY = lineY + kanjiHeight / 2f;
                    u.wordCenterX = u.kanjiCenterX;
                    u.wordCenterY = u.kanjiCenterY;
                    u.unitLeft = u.kanjiX;
                    u.unitRight = currentRight;
                    u.lineIndex = ln;
                    u.lineTop = lineY;
                    u.lineBottom = lineY + kanjiHeight;

                    float spaceW = u.endsWithSpace ? baseSpaceWidth : 0f;
                    currentRight = u.kanjiX - spaceW;
                }
            }

            float arabicSectionHeight = arabicLines.size() * arabicLineSpacing;
            float bottomOfLyrics = arabicSectionHeight;

            // 2. Line-wrap Romaji words (if any)
            if (hasRomaji) {
                float romajiMargin = Dim.dp(6f);
                float romajiSectionTop = arabicSectionHeight + romajiMargin;
                float romajiLineSpacing = romajiHeight * 1.2f;

                List<List<WordUnit>> romajiLines = new ArrayList<>();
                List<WordUnit> curRomajiLine = new ArrayList<>();
                float curRLineWidth = 0f;

                for (int i = 0; i < wordUnits.size(); i++) {
                    WordUnit u = wordUnits.get(i);
                    if (u.romaji.isEmpty()) continue;
                    float wordW = u.romajiWidth;
                    float spaceW = u.endsWithSpace ? baseSpaceWidth : 0f;

                    if (!curRomajiLine.isEmpty() && curRLineWidth + wordW > availableWidth) {
                        romajiLines.add(curRomajiLine);
                        curRomajiLine = new ArrayList<>();
                        curRLineWidth = 0f;
                    }
                    curRomajiLine.add(u);
                    curRLineWidth += wordW + spaceW;
                }
                if (!curRomajiLine.isEmpty()) {
                    romajiLines.add(curRomajiLine);
                }

                // Position Romaji words (LTR: Word 0 is on the left, Word N is on the right)
                for (int rln = 0; rln < romajiLines.size(); rln++) {
                    List<WordUnit> rWords = romajiLines.get(rln);
                    float rLineY = romajiSectionTop + rln * romajiLineSpacing;
                    float romajiY = rLineY + romajiBaseline;

                    float totalRW = 0f;
                    for (int w = 0; w < rWords.size(); w++) {
                        WordUnit u = rWords.get(w);
                        totalRW += u.romajiWidth;
                        if (w < rWords.size() - 1 && u.endsWithSpace) {
                            totalRW += baseSpaceWidth;
                        }
                    }

                    float currentLeft = isRightAligned ? Math.max(0f, availableWidth - totalRW) : 0f;

                    for (int w = 0; w < rWords.size(); w++) {
                        WordUnit u = rWords.get(w);
                        u.romajiX = currentLeft;
                        u.romajiY = romajiY;
                        u.romajiCenterX = currentLeft + u.romajiWidth / 2f;
                        u.romajiCenterY = rLineY + romajiHeight / 2f;

                        float spaceW = u.endsWithSpace ? baseSpaceWidth : 0f;
                        currentLeft += u.romajiWidth + spaceW;
                    }
                }

                if (!romajiLines.isEmpty()) {
                    bottomOfLyrics = romajiSectionTop + romajiLines.size() * romajiLineSpacing;
                }
            }

            // 3. Translation layout
            if (translationText != null && !translationText.isEmpty()) {
                transPaint.setTextSize(mainP.getTextSize() * TRANSLATION_RELATIVE_SIZE);
                transPaint.setColor(auxiliaryTextColor());
                transPaint.setAlpha(Math.round(255 * 0.58f));
                transPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));

                Layout.Alignment align = isRightAligned
                        ? Layout.Alignment.ALIGN_OPPOSITE
                        : Layout.Alignment.ALIGN_NORMAL;

                StaticLayout.Builder tb = StaticLayout.Builder
                        .obtain(translationText, 0, translationText.length(), transPaint, availableWidth)
                        .setAlignment(align)
                        .setLineSpacing(Dim.dp(3), 1.15f)
                        .setIncludePad(false);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    tb.setUseLineSpacingFromFallbacks(true);
                }
                transLayout = tb.build();
                transLayoutWidth = availableWidth;

                totalMeasuredHeight = Math.round(bottomOfLyrics + Dim.dp(6f) + transLayout.getHeight());
            } else {
                transLayout = null;
                totalMeasuredHeight = Math.round(bottomOfLyrics);
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            if (wordUnits != null && !wordUnits.isEmpty()) {
                int width = MeasureSpec.getSize(widthMeasureSpec);
                int padH = getCompoundPaddingLeft() + getCompoundPaddingRight();
                int availableW = Math.max(0, width - padH);
                if (availableW > 0) {
                    layoutWordUnits(availableW);
                }
                int padV = getExtendedPaddingTop() + getExtendedPaddingBottom();
                int measuredH = Math.max(totalMeasuredHeight + padV, getSuggestedMinimumHeight());
                if (isSecondary) {
                    measuredH = Math.round(measuredH * secondaryExpandFraction);
                }
                setMeasuredDimension(width, measuredH);
                return;
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            if (isSecondary) {
                int measuredH = Math.round(getMeasuredHeight() * secondaryExpandFraction);
                setMeasuredDimension(getMeasuredWidth(), measuredH);
            }
        }

        private long lastDrawnClockPos = Long.MIN_VALUE;

        private long getLiveClockPositionMs() {
            if (positionMs == Long.MIN_VALUE) {
                lastDrawnClockPos = Long.MIN_VALUE;
                return Long.MIN_VALUE;
            }
            long rawPos = LyricsManager.getInstance().getPositionMs();
            if (LyricsManager.getInstance().isPlaying() && lastDrawnClockPos != Long.MIN_VALUE) {
                if (rawPos >= lastDrawnClockPos - 1000L) {
                    rawPos = Math.max(lastDrawnClockPos, rawPos);
                }
            }
            lastDrawnClockPos = rawPos;
            return rawPos;
        }

        private void onDrawWordUnits(Canvas canvas) {
            if (wordUnits == null || wordUnits.isEmpty()) {
                return;
            }
            if (isSecondary && secondaryExpandFraction <= 0.001f) {
                return;
            }

            final boolean active = isLineActive || (positionMs != Long.MIN_VALUE && !allSung);
            float target = active ? 1.0f : 0.95f;
            if (Math.abs(currentScale - target) > 0.001f) {
                currentScale += (target - currentScale) * 0.15f;
                postInvalidateOnAnimation();
            } else {
                currentScale = target;
            }

            canvas.save();
            if (isSecondary) {
                canvas.clipRect(0, 0, getWidth(), getHeight());
            }
            float pivotX = isGravityRightAligned()
                    ? (getWidth() - getCompoundPaddingRight())
                    : getCompoundPaddingLeft();
            float pivotY = getHeight() / 2f;
            canvas.scale(currentScale, currentScale, pivotX, pivotY);
            canvas.translate(getCompoundPaddingLeft(), getExtendedPaddingTop());

            TextPaint mainP = getPaint();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mainP.setFontFeatureSettings("\"liga\" 0, \"clig\" 0, \"dlig\" 0");
                unsungKanjiPaint.setFontFeatureSettings("\"liga\" 0, \"clig\" 0, \"dlig\" 0");
                sungKanjiPaint.setFontFeatureSettings("\"liga\" 0, \"clig\" 0, \"dlig\" 0");
                glowPaint.setFontFeatureSettings("\"liga\" 0, \"clig\" 0, \"dlig\" 0");
            }

            final boolean lineHasTimings = hasWordTimings();

            // Setup colors
            int baseSung = (sungColor != 0) ? sungColor : lineTextColor();
            sungKanjiPaint.setColor(baseSung);
            sungKanjiPaint.setFakeBoldText(false);

            final int sungRomajiColor = Color.argb(
                    Math.round(255 * 0.85f),
                    Color.red(baseSung),
                    Color.green(baseSung),
                    Color.blue(baseSung));
            sungRomajiPaint.setColor(sungRomajiColor);
            sungRomajiPaint.setFakeBoldText(false);

            glowPaint.set(sungKanjiPaint);
            glowPaint.setShader(null);
            glowPaint.clearShadowLayer();

            int baseUnsung = (active && lineHasTimings)
                    ? ((unsungColor != 0) ? unsungColor : unsungWordColor())
                    : baseSung;
            unsungKanjiPaint.setColor(baseUnsung);
            unsungKanjiPaint.setFakeBoldText(false);

            int rAlpha = Color.alpha(baseUnsung);
            final int unsungRomajiColor = Color.argb(
                    Math.round(rAlpha * 0.85f),
                    Color.red(baseUnsung),
                    Color.green(baseUnsung),
                    Color.blue(baseUnsung));
            unsungRomajiPaint.setColor(unsungRomajiColor);
            unsungRomajiPaint.setFakeBoldText(false);

            final int wordCount = wordUnits.size();

            // Layer 1 (Inactive Base): Draw base text
            final long baseLivePos = (active && lineHasTimings && !allSung) ? getLiveClockPositionMs() : Long.MIN_VALUE;
            final float restingLift = 0f;

            for (int i = 0; i < wordCount; i++) {
                WordUnit u = wordUnits.get(i);
                final boolean timed = baseLivePos != Long.MIN_VALUE && u.startMs != LyricsLine.NO_TIME;
                // Past words stay solid white: drawn by layer 2 only, never dimmed again.
                if (timed && baseLivePos >= u.endMs) {
                    continue;
                }
                final boolean isKanjiRtl = LyricsRomanizer.containsArabic(u.kanji) || LyricsRomanizer.containsHebrew(u.kanji);
                int numChars = u.kanji.length();

                if (!isKanjiRtl && numChars > 0 && u.charWidths != null && u.charOffsets != null) {
                    for (int c = 0; c < numChars; ) {
                        int cp = u.kanji.codePointAt(c);
                        int cc = Character.charCount(cp);
                        EmphasisState state = timed
                                ? computeCharEmphasisState(u, c, numChars, baseLivePos, restingLift, mainP.getTextSize(), isSecondary)
                                : null;
                        float lift = state != null ? state.lift : 0f;
                        float scale = state != null ? state.scale : 1f;
                        float chX = u.kanjiX + u.charOffsets[c];
                        float chW = u.charWidths[c];
                        float chCenterX = chX + chW / 2f;
                        String chStr = u.kanji.substring(c, c + cc);

                        canvas.save();
                        if (lift > 0f) {
                            canvas.translate(0f, -lift);
                        }
                        if (scale != 1f) {
                            canvas.scale(scale, scale, chCenterX, u.kanjiCenterY);
                        }
                        canvas.drawText(chStr, chX, u.kanjiY, unsungKanjiPaint);
                        canvas.restore();
                        c += cc;
                    }
                } else {
                    EmphasisState state = timed ? computeEmphasisState(u, baseLivePos, restingLift, mainP.getTextSize(), isSecondary) : null;
                    float lift = state != null ? state.lift : 0f;
                    float scale = state != null ? state.scale : 1f;

                    canvas.save();
                    if (lift > 0f) {
                        canvas.translate(0f, -lift);
                    }
                    if (scale != 1f) {
                        canvas.scale(scale, scale, u.wordCenterX, u.wordCenterY);
                    }
                    canvas.drawText(u.kanji, u.kanjiX, u.kanjiY, unsungKanjiPaint);
                    canvas.restore();
                }

                if (!u.romaji.isEmpty() && u.romajiWidth > 0f) {
                    int rLen = u.romaji.length();
                    if (rLen > 0 && u.romajiCharWidths != null && u.romajiCharOffsets != null) {
                        for (int c = 0; c < rLen; ) {
                            int cp = u.romaji.codePointAt(c);
                            int cc = Character.charCount(cp);
                            EmphasisState state = timed
                                    ? computeCharEmphasisState(u, c, rLen, baseLivePos, restingLift, romajiPaint.getTextSize(), isSecondary)
                                    : null;
                            float lift = state != null ? state.lift : 0f;
                            float scale = state != null ? state.scale : 1f;
                            float chX = u.romajiX + u.romajiCharOffsets[c];
                            float chW = u.romajiCharWidths[c];
                            float chCenterX = chX + chW / 2f;
                            String chStr = u.romaji.substring(c, c + cc);

                            canvas.save();
                            if (lift > 0f) canvas.translate(0f, -lift);
                            if (scale != 1f) canvas.scale(scale, scale, chCenterX, u.romajiCenterY);
                            canvas.drawText(chStr, chX, u.romajiY, unsungRomajiPaint);
                            canvas.restore();
                            c += cc;
                        }
                    } else {
                        canvas.save();
                        EmphasisState state = timed ? computeEmphasisState(u, baseLivePos, restingLift, romajiPaint.getTextSize(), isSecondary) : null;
                        float lift = state != null ? state.lift : 0f;
                        float scale = state != null ? state.scale : 1f;
                        if (lift > 0f) {
                            canvas.translate(0f, -lift);
                        }
                        if (scale != 1f) {
                            canvas.scale(scale, scale, u.romajiCenterX, u.romajiCenterY);
                        }
                        canvas.drawText(u.romaji, u.romajiX, u.romajiY, unsungRomajiPaint);
                        canvas.restore();
                    }
                }
            }

            // If line is not active, base layer is sufficient (view alpha handles depth-of-field)
            if (!active) {
                drawTranslationLayout(canvas);
                canvas.restore();
                return;
            }

            // Layer 2: Draw active highlighted text
            if (allSung || !lineHasTimings) {
                for (int i = 0; i < wordCount; i++) {
                    WordUnit u = wordUnits.get(i);
                    canvas.drawText(u.kanji, u.kanjiX, u.kanjiY, sungKanjiPaint);
                    if (!u.romaji.isEmpty() && u.romajiWidth > 0f) {
                        canvas.drawText(u.romaji, u.romajiX, u.romajiY, sungRomajiPaint);
                    }
                }
            } else {
                final long livePosMs = getLiveClockPositionMs();

                // Find the single active syllable currently being sung (first word where livePosMs < u.endMs)
                int activeWordIndex = -1;
                for (int i = 0; i < wordCount; i++) {
                    WordUnit u = wordUnits.get(i);
                    if (u.startMs != LyricsLine.NO_TIME && livePosMs < u.endMs) {
                        if (livePosMs >= u.startMs) {
                            activeWordIndex = i;
                        }
                        break;
                    }
                }

                for (int i = 0; i < wordCount; i++) {
                    WordUnit u = wordUnits.get(i);
                    if (u.startMs == LyricsLine.NO_TIME) {
                        continue;
                    }
                    if (activeWordIndex != -1 && i > activeWordIndex) {
                        continue;
                    }
                    if (activeWordIndex == -1 && livePosMs < u.startMs) {
                        continue;
                    }

                    final boolean isKanjiRtl = LyricsRomanizer.containsArabic(u.kanji) || LyricsRomanizer.containsHebrew(u.kanji);
                    int numChars = u.kanji.length();

                    if (i == activeWordIndex) {
                        final long dur = Math.max(1L, u.endMs - u.startMs);
                        final float progress = Math.max(0f, Math.min(1f, (float) (livePosMs - u.startMs) / (float) dur));

                        if (!isKanjiRtl && numChars > 0 && u.charWidths != null && u.charOffsets != null) {
                            final float wiperX = progress * u.kanjiWidth;
                            for (int c = 0; c < numChars; ) {
                                int cp = u.kanji.codePointAt(c);
                                int cc = Character.charCount(cp);
                                float chLeft = u.charOffsets[c];
                                float chW = u.charWidths[c];
                                float chRight = chLeft + chW;

                                EmphasisState state = computeCharEmphasisState(u, c, numChars, livePosMs, restingLift, mainP.getTextSize(), isSecondary);
                                float lift = state.lift;
                                float scale = state.scale;
                                float glowAlpha = state.glowAlpha;

                                float chX = u.kanjiX + chLeft;
                                float chCenterX = chX + chW / 2f;
                                String chStr = u.kanji.substring(c, c + cc);

                                canvas.save();
                                if (lift > 0f) {
                                    canvas.translate(0f, -lift);
                                }
                                if (scale != 1f) {
                                    canvas.scale(scale, scale, chCenterX, u.kanjiCenterY);
                                }

                                if (wiperX >= chRight) {
                                    if (glowAlpha > 1f) {
                                        float bloomRadius = Math.max(2f, 0.28f * mainP.getTextSize());
                                        int glowColor = Color.argb(Math.round(glowAlpha), 255, 255, 255);
                                        glowPaint.set(sungKanjiPaint);
                                        glowPaint.setShader(null);
                                        glowPaint.setShadowLayer(bloomRadius, 0f, 0f, glowColor);
                                        glowPaint.setColor(glowColor);
                                        glowPaint.setAlpha(Math.round(glowAlpha * 0.40f));
                                        canvas.drawText(chStr, chX, u.kanjiY, glowPaint);
                                        glowPaint.clearShadowLayer();
                                    }
                                    canvas.drawText(chStr, chX, u.kanjiY, sungKanjiPaint);
                                } else if (wiperX > chLeft) {
                                    float chProgress = (wiperX - chLeft) / Math.max(0.001f, chW);
                                    if (glowAlpha > 1f) {
                                        float bloomRadius = Math.max(2f, 0.28f * mainP.getTextSize());
                                        int glowColor = Color.argb(Math.round(glowAlpha * chProgress), 255, 255, 255);
                                        glowPaint.set(sungKanjiPaint);
                                        glowPaint.setShader(null);
                                        glowPaint.setShadowLayer(bloomRadius, 0f, 0f, glowColor);
                                        glowPaint.setColor(glowColor);
                                        glowPaint.setAlpha(Math.round(glowAlpha * chProgress * 0.40f));
                                        canvas.drawText(chStr, chX, u.kanjiY, glowPaint);
                                        glowPaint.clearShadowLayer();
                                    }
                                    drawFeatheredFill(canvas, chStr, chX, u.kanjiY, chW,
                                            chProgress, sungKanjiPaint, sungKanjiPaint.getColor(), false);
                                }
                                canvas.restore();
                                c += cc;
                            }
                        } else {
                            EmphasisState state = computeEmphasisState(u, livePosMs, restingLift, mainP.getTextSize(), isSecondary);
                            float lift = state.lift;
                            float scale = state.scale;
                            float glowAlpha = state.glowAlpha;

                            if (glowAlpha > 1f && progress > 0f) {
                                float bloomRadius = Math.max(2f, 0.28f * mainP.getTextSize());
                                int glowColor = Color.argb(Math.round(glowAlpha * progress), 255, 255, 255);
                                glowPaint.set(sungKanjiPaint);
                                glowPaint.setShader(null);
                                glowPaint.setShadowLayer(bloomRadius, 0f, 0f, glowColor);
                                glowPaint.setColor(glowColor);
                                glowPaint.setAlpha(Math.round(glowAlpha * progress * 0.40f));
                                canvas.save();
                                if (lift > 0f) canvas.translate(0f, -lift);
                                if (scale != 1f) canvas.scale(scale, scale, u.wordCenterX, u.wordCenterY);
                                canvas.drawText(u.kanji, u.kanjiX, u.kanjiY, glowPaint);
                                canvas.restore();
                                glowPaint.clearShadowLayer();
                            }

                            canvas.save();
                            if (lift > 0f) {
                                canvas.translate(0f, -lift);
                            }
                            if (scale != 1f) {
                                canvas.scale(scale, scale, u.wordCenterX, u.wordCenterY);
                            }
                            drawFeatheredFill(canvas, u.kanji, u.kanjiX, u.kanjiY, u.kanjiWidth,
                                    progress, sungKanjiPaint, sungKanjiPaint.getColor(), isKanjiRtl);
                            canvas.restore();
                        }

                        // Romaji
                        if (!u.romaji.isEmpty() && u.romajiWidth > 0f) {
                            int rNumChars = u.romaji.length();
                            if (rNumChars > 0 && u.romajiCharWidths != null && u.romajiCharOffsets != null) {
                                final float wiperX = progress * u.romajiWidth;
                                for (int c = 0; c < rNumChars; ) {
                                    int cp = u.romaji.codePointAt(c);
                                    int cc = Character.charCount(cp);
                                    float chLeft = u.romajiCharOffsets[c];
                                    float chW = u.romajiCharWidths[c];
                                    float chRight = chLeft + chW;

                                    EmphasisState state = computeCharEmphasisState(u, c, rNumChars, livePosMs, restingLift, romajiPaint.getTextSize(), isSecondary);
                                    float lift = state.lift;
                                    float scale = state.scale;
                                    float glowAlpha = state.glowAlpha;

                                    float chX = u.romajiX + chLeft;
                                    float chCenterX = chX + chW / 2f;
                                    String chStr = u.romaji.substring(c, c + cc);

                                    canvas.save();
                                    if (lift > 0f) {
                                        canvas.translate(0f, -lift);
                                    }
                                    if (scale != 1f) {
                                        canvas.scale(scale, scale, chCenterX, u.romajiCenterY);
                                    }

                                    if (wiperX >= chRight) {
                                        if (glowAlpha > 1f) {
                                            float bloomRadius = Math.max(2f, 0.28f * romajiPaint.getTextSize());
                                            int glowColor = Color.argb(Math.round(glowAlpha), 255, 255, 255);
                                            glowPaint.set(sungRomajiPaint);
                                            glowPaint.setShader(null);
                                            glowPaint.setShadowLayer(bloomRadius, 0f, 0f, glowColor);
                                            glowPaint.setColor(glowColor);
                                            glowPaint.setAlpha(Math.round(glowAlpha * 0.40f));
                                            canvas.drawText(chStr, chX, u.romajiY, glowPaint);
                                            glowPaint.clearShadowLayer();
                                        }
                                        canvas.drawText(chStr, chX, u.romajiY, sungRomajiPaint);
                                    } else if (wiperX > chLeft) {
                                        float chProgress = (wiperX - chLeft) / Math.max(0.001f, chW);
                                        if (glowAlpha > 1f) {
                                            float bloomRadius = Math.max(2f, 0.28f * romajiPaint.getTextSize());
                                            int glowColor = Color.argb(Math.round(glowAlpha * chProgress), 255, 255, 255);
                                            glowPaint.set(sungRomajiPaint);
                                            glowPaint.setShader(null);
                                            glowPaint.setShadowLayer(bloomRadius, 0f, 0f, glowColor);
                                            glowPaint.setColor(glowColor);
                                            glowPaint.setAlpha(Math.round(glowAlpha * chProgress * 0.40f));
                                            canvas.drawText(chStr, chX, u.romajiY, glowPaint);
                                            glowPaint.clearShadowLayer();
                                        }
                                        drawFeatheredFill(canvas, chStr, chX, u.romajiY, chW,
                                                chProgress, sungRomajiPaint, sungRomajiPaint.getColor(), false);
                                    }
                                    canvas.restore();
                                    c += cc;
                                }
                            } else {
                                EmphasisState state = computeEmphasisState(u, livePosMs, restingLift, romajiPaint.getTextSize(), isSecondary);
                                float lift = state.lift;
                                float scale = state.scale;
                                float glowAlpha = state.glowAlpha;
                                if (glowAlpha > 1f && progress > 0f) {
                                    float bloomRadius = Math.max(2f, 0.28f * romajiPaint.getTextSize());
                                    int glowColor = Color.argb(Math.round(glowAlpha * progress), 255, 255, 255);
                                    glowPaint.set(sungRomajiPaint);
                                    glowPaint.setShader(null);
                                    glowPaint.setShadowLayer(bloomRadius, 0f, 0f, glowColor);
                                    glowPaint.setColor(glowColor);
                                    glowPaint.setAlpha(Math.round(glowAlpha * progress * 0.40f));
                                    canvas.save();
                                    if (lift > 0f) canvas.translate(0f, -lift);
                                    if (scale != 1f) canvas.scale(scale, scale, u.romajiCenterX, u.romajiCenterY);
                                    canvas.drawText(u.romaji, u.romajiX, u.romajiY, glowPaint);
                                    canvas.restore();
                                    glowPaint.clearShadowLayer();
                                }
                                canvas.save();
                                if (lift > 0f) {
                                    canvas.translate(0f, -lift);
                                }
                                if (scale != 1f) {
                                    canvas.scale(scale, scale, u.romajiCenterX, u.romajiCenterY);
                                }
                                drawFeatheredFill(canvas, u.romaji, u.romajiX, u.romajiY, u.romajiWidth,
                                        progress, sungRomajiPaint, sungRomajiPaint.getColor(), false);
                                canvas.restore();
                            }
                        }
                    } else {
                        // Finished words (i < activeWordIndex):
                        if (!isKanjiRtl && numChars > 0 && u.charWidths != null && u.charOffsets != null) {
                            for (int c = 0; c < numChars; ) {
                                int cp = u.kanji.codePointAt(c);
                                int cc = Character.charCount(cp);
                                EmphasisState state = computeCharEmphasisState(u, c, numChars, livePosMs, restingLift, mainP.getTextSize(), isSecondary);
                                float lift = state.lift;
                                float scale = state.scale;
                                float glowAlpha = state.glowAlpha;

                                float chX = u.kanjiX + u.charOffsets[c];
                                float chW = u.charWidths[c];
                                float chCenterX = chX + chW / 2f;
                                String chStr = u.kanji.substring(c, c + cc);

                                canvas.save();
                                if (lift > 0f) {
                                    canvas.translate(0f, -lift);
                                }
                                if (scale != 1f) {
                                    canvas.scale(scale, scale, chCenterX, u.kanjiCenterY);
                                }

                                if (glowAlpha > 1f) {
                                    float bloomRadius = Math.max(2f, 0.28f * mainP.getTextSize());
                                    int glowColor = Color.argb(Math.round(glowAlpha), 255, 255, 255);
                                    glowPaint.set(sungKanjiPaint);
                                    glowPaint.setShader(null);
                                    glowPaint.setShadowLayer(bloomRadius, 0f, 0f, glowColor);
                                    glowPaint.setColor(glowColor);
                                    glowPaint.setAlpha(Math.round(glowAlpha * 0.40f));
                                    canvas.drawText(chStr, chX, u.kanjiY, glowPaint);
                                    glowPaint.clearShadowLayer();
                                }

                                canvas.drawText(chStr, chX, u.kanjiY, sungKanjiPaint);
                                canvas.restore();
                                c += cc;
                            }
                        } else {
                            EmphasisState state = computeEmphasisState(u, livePosMs, restingLift, mainP.getTextSize(), isSecondary);
                            float lift = state.lift;
                            float scale = state.scale;
                            float glowAlpha = state.glowAlpha;

                            if (glowAlpha > 1f) {
                                float bloomRadius = Math.max(2f, 0.28f * mainP.getTextSize());
                                int glowColor = Color.argb(Math.round(glowAlpha), 255, 255, 255);
                                glowPaint.set(sungKanjiPaint);
                                glowPaint.setShader(null);
                                glowPaint.setShadowLayer(bloomRadius, 0f, 0f, glowColor);
                                glowPaint.setColor(glowColor);
                                glowPaint.setAlpha(Math.round(glowAlpha * 0.40f));
                                canvas.save();
                                if (lift > 0f) canvas.translate(0f, -lift);
                                if (scale != 1f) canvas.scale(scale, scale, u.wordCenterX, u.wordCenterY);
                                canvas.drawText(u.kanji, u.kanjiX, u.kanjiY, glowPaint);
                                canvas.restore();
                                glowPaint.clearShadowLayer();
                            }

                            canvas.save();
                            if (lift > 0f) {
                                canvas.translate(0f, -lift);
                            }
                            if (scale != 1f) {
                                canvas.scale(scale, scale, u.wordCenterX, u.wordCenterY);
                            }
                            canvas.drawText(u.kanji, u.kanjiX, u.kanjiY, sungKanjiPaint);
                            canvas.restore();
                        }

                        if (!u.romaji.isEmpty() && u.romajiWidth > 0f) {
                            int rNumChars = u.romaji.length();
                            if (rNumChars > 0 && u.romajiCharWidths != null && u.romajiCharOffsets != null) {
                                for (int c = 0; c < rNumChars; ) {
                                    int cp = u.romaji.codePointAt(c);
                                    int cc = Character.charCount(cp);
                                    EmphasisState state = computeCharEmphasisState(u, c, rNumChars, livePosMs, restingLift, romajiPaint.getTextSize(), isSecondary);
                                    float lift = state.lift;
                                    float scale = state.scale;
                                    float glowAlpha = state.glowAlpha;

                                    float chX = u.romajiX + u.romajiCharOffsets[c];
                                    float chW = u.romajiCharWidths[c];
                                    float chCenterX = chX + chW / 2f;
                                    String chStr = u.romaji.substring(c, c + cc);

                                    canvas.save();
                                    if (lift > 0f) canvas.translate(0f, -lift);
                                    if (scale != 1f) canvas.scale(scale, scale, chCenterX, u.romajiCenterY);

                                    if (glowAlpha > 1f) {
                                        float bloomRadius = Math.max(2f, 0.28f * romajiPaint.getTextSize());
                                        int glowColor = Color.argb(Math.round(glowAlpha), 255, 255, 255);
                                        glowPaint.set(sungRomajiPaint);
                                        glowPaint.setShader(null);
                                        glowPaint.setShadowLayer(bloomRadius, 0f, 0f, glowColor);
                                        glowPaint.setColor(glowColor);
                                        glowPaint.setAlpha(Math.round(glowAlpha * 0.40f));
                                        canvas.drawText(chStr, chX, u.romajiY, glowPaint);
                                        glowPaint.clearShadowLayer();
                                    }

                                    canvas.drawText(chStr, chX, u.romajiY, sungRomajiPaint);
                                    canvas.restore();
                                    c += cc;
                                }
                            } else {
                                EmphasisState state = computeEmphasisState(u, livePosMs, restingLift, romajiPaint.getTextSize(), isSecondary);
                                float lift = state.lift;
                                float scale = state.scale;
                                canvas.save();
                                if (lift > 0f) {
                                    canvas.translate(0f, -lift);
                                }
                                if (scale != 1f) {
                                    canvas.scale(scale, scale, u.romajiCenterX, u.romajiCenterY);
                                }
                                canvas.drawText(u.romaji, u.romajiX, u.romajiY, sungRomajiPaint);
                                canvas.restore();
                            }
                        }
                    }
                }
            }

            drawTranslationLayout(canvas);
            canvas.restore();

            if (active && lineHasTimings && LyricsManager.getInstance().isPlaying()) {
                postInvalidateOnAnimation();
            }
        }

        private static class EmphasisState {
            final float scale;
            final float lift;
            final float glowAlpha;

            EmphasisState(float scale, float lift, float glowAlpha) {
                this.scale = scale;
                this.lift = lift;
                this.glowAlpha = glowAlpha;
            }
        }

        private static EmphasisState computeCharEmphasisState(
                WordUnit u, int charIndex, int numChars, long livePosMs,
                float restingLift, float textSize, boolean isSecondary) {

            long refStart = (u.wordStartMs != LyricsLine.NO_TIME) ? u.wordStartMs : u.startMs;
            long refEnd = (u.wordEndMs != LyricsLine.NO_TIME) ? u.wordEndMs : u.endMs;
            if (refStart == LyricsLine.NO_TIME) {
                return new EmphasisState(1f, 0f, 0f);
            }

            final float sungLift = Math.max(2.5f, textSize * 0.045f);
            final boolean growable = isWordGrowable(u, isSecondary);

            if (growable) {
                long finalDuration = Math.max(1L, refEnd - refStart);
                float maxTotalDelay = finalDuration * 0.60f;
                float baseDelayPerChar = Math.min(finalDuration * 0.09f, numChars > 1 ? maxTotalDelay / (numChars - 1) : 0f);
                long charDelayMs = (long) (charIndex * baseDelayPerChar);
                long charStartMs = refStart + charDelayMs;

                if (livePosMs < charStartMs) {
                    return new EmphasisState(1f, 0f, 0f);
                }

                long growDurationMs = Math.max(500L, Math.min(4500L, (long) (finalDuration * 1.5f)));
                float elapsedMs = (float) (livePosMs - charStartMs);

                // Dynamic word emphasis metrics
                long minDuration = 1000L;
                long maxDuration = 5000L;
                float progress = Math.min(1f, Math.max(0f, (float) (finalDuration - minDuration) / (float) (maxDuration - minDuration)));
                float easedProgress = progress * progress * progress;

                float penaltyFactor = 1.0f;
                float maxDecayRate = 0f;
                boolean isLongWord = numChars > 5;
                boolean isShortDuration = finalDuration < 1500L;
                if (isLongWord || isShortDuration) {
                    float decayStrength = 0f;
                    if (isLongWord) decayStrength += Math.min((numChars - 5) / 3f, 1.0f) * 0.4f;
                    if (isShortDuration) decayStrength += Math.max(0f, 1.0f - (finalDuration - 1000f) / 500f) * 0.4f;
                    maxDecayRate = Math.min(decayStrength, 0.85f);
                }

                float positionInWord = numChars > 1 ? (float) charIndex / (float) (numChars - 1) : 0f;
                float decayFactor = 1.0f - positionInWord * maxDecayRate;
                float charProgress = easedProgress * penaltyFactor * decayFactor;

                float baseGrowth = numChars <= 3 ? 0.07f : 0.05f;
                float charMaxScale = 1.0f + baseGrowth + charProgress * 0.10f;
                float charShadowIntensity = 0.40f + charProgress * 0.40f;
                float normalizedGrowth = Math.min(1.0f, Math.max(0f, (charMaxScale - 1.0f) / 0.13f));

                // Prominent floating wave for held/glow notes (~6.5px - 8.5px, ~2.5dp)
                float charPeakLift = Math.max(5.5f, (textSize * 0.080f) + (normalizedGrowth * textSize * 0.045f));

                // Sinusoidal rise, crest hold, and relaxed ease down to resting baseline:
                // 0% -> 25%: smooth sinusoidal rise to peak (scale, lift, bloom)
                // 25% -> 30%: hold at crest
                // 30% -> 100%: very relaxed, slow ease down back to sung resting level (sungLift)
                float t = elapsedMs / (float) Math.max(1L, growDurationMs);
                if (t <= 0f) {
                    return new EmphasisState(1f, 0f, 0f);
                } else if (t < 0.25f) {
                    float phase = t / 0.25f;
                    float easeUp = 0.5f * (1.0f - (float) Math.cos(phase * Math.PI));
                    float scale = 1.0f + (charMaxScale - 1.0f) * easeUp;
                    float lift = charPeakLift * easeUp;
                    float glowAlpha = 255f * charShadowIntensity * easeUp;
                    return new EmphasisState(scale, lift, glowAlpha);
                } else if (t <= 0.30f) {
                    float scale = charMaxScale;
                    float lift = charPeakLift;
                    float glowAlpha = 255f * charShadowIntensity;
                    return new EmphasisState(scale, lift, glowAlpha);
                } else if (t <= 1.0f) {
                    float phase = (t - 0.30f) / 0.70f;
                    float easeDown = 0.5f * (1.0f - (float) Math.cos(phase * Math.PI));
                    float scale = charMaxScale + (1.0f - charMaxScale) * easeDown;
                    float lift = charPeakLift - (charPeakLift - sungLift) * easeDown;
                    float glowAlpha = 255f * charShadowIntensity * (1.0f - easeDown);
                    return new EmphasisState(scale, lift, glowAlpha);
                } else {
                    return new EmphasisState(1.0f, sungLift, 0f);
                }
            } else {
                // Normal word-sync: smoothly glide up to sungLift and stay there (no overshoot, no jelly, no bounce-down)
                long sylStart = (u.startMs != LyricsLine.NO_TIME) ? u.startMs : refStart;
                long sylEnd = (u.endMs != LyricsLine.NO_TIME) ? u.endMs : refEnd;
                long sylDuration = Math.max(1L, sylEnd - sylStart);

                // Precise wipe timing for character position
                float charRatio;
                if (numChars == u.romaji.length() && !u.romaji.isEmpty() && u.romajiCharOffsets != null && u.romajiWidth > 0f) {
                    charRatio = charIndex < u.romajiCharOffsets.length ? (u.romajiCharOffsets[charIndex] / u.romajiWidth) : 0f;
                } else if (u.charOffsets != null && u.kanjiWidth > 0f && charIndex < u.charOffsets.length) {
                    charRatio = u.charOffsets[charIndex] / u.kanjiWidth;
                } else {
                    charRatio = numChars > 1 ? ((float) charIndex / (float) numChars) : 0f;
                }

                long charStartMs = sylStart + (long) (charRatio * sylDuration);
                if (livePosMs < charStartMs) {
                    return new EmphasisState(1f, 0f, 0f);
                }

                long charAnimDur = Math.max(350L, Math.min(800L, (long) (sylDuration * 1.2f)));
                float elapsed = (float) (livePosMs - charStartMs);
                float t = Math.min(1.0f, Math.max(0f, elapsed / (float) charAnimDur));

                // Smooth glide upwards to sungLift with smoothstep easing, then stay elevated
                float easeUp = t * t * (3.0f - 2.0f * t);
                float lift = sungLift * easeUp;
                return new EmphasisState(1.0f, lift, 0f);
            }
        }

        private static EmphasisState computeEmphasisState(WordUnit u, long livePosMs, float restingLift, float textSize, boolean isSecondary) {
            long refStart = (u.wordStartMs != LyricsLine.NO_TIME) ? u.wordStartMs : u.startMs;
            long refEnd = (u.wordEndMs != LyricsLine.NO_TIME) ? u.wordEndMs : u.endMs;
            if (refStart == LyricsLine.NO_TIME || livePosMs < refStart) {
                return new EmphasisState(1f, 0f, 0f);
            }

            final float sungLift = Math.max(2.5f, textSize * 0.045f);
            final boolean growable = isWordGrowable(u, isSecondary);
            if (growable) {
                long finalDuration = Math.max(1L, refEnd - refStart);
                long growDurationMs = (long) (finalDuration * 1.5f);
                float elapsedMs = (float) (livePosMs - refStart);
                float t = elapsedMs / (float) Math.max(1L, growDurationMs);

                float wordMaxScale = 1.06f;
                float charPeakLift = Math.max(5.5f, textSize * 0.080f);
                float maxGlowAlpha = 255f * 0.50f;

                if (t <= 0f) {
                    return new EmphasisState(1f, 0f, 0f);
                } else if (t < 0.25f) {
                    float phase = t / 0.25f;
                    float easeUp = 0.5f * (1.0f - (float) Math.cos(phase * Math.PI));
                    return new EmphasisState(1.0f + (wordMaxScale - 1.0f) * easeUp, charPeakLift * easeUp, maxGlowAlpha * easeUp);
                } else if (t <= 0.30f) {
                    return new EmphasisState(wordMaxScale, charPeakLift, maxGlowAlpha);
                } else if (t <= 1.0f) {
                    float phase = (t - 0.30f) / 0.70f;
                    float easeDown = 0.5f * (1.0f - (float) Math.cos(phase * Math.PI));
                    return new EmphasisState(wordMaxScale + (1.0f - wordMaxScale) * easeDown,
                            charPeakLift - (charPeakLift - sungLift) * easeDown,
                            maxGlowAlpha * (1.0f - easeDown));
                } else {
                    return new EmphasisState(1.0f, sungLift, 0f);
                }
            } else {
                long sylStart = (u.startMs != LyricsLine.NO_TIME) ? u.startMs : refStart;
                long sylEnd = (u.endMs != LyricsLine.NO_TIME) ? u.endMs : refEnd;
                long sylDuration = Math.max(1L, sylEnd - sylStart);
                if (livePosMs < sylStart) {
                    return new EmphasisState(1f, 0f, 0f);
                }

                long wordAnimDur = Math.max(350L, Math.min(800L, (long) (sylDuration * 1.2f)));
                float elapsed = (float) (livePosMs - sylStart);
                float t = Math.min(1.0f, Math.max(0f, elapsed / (float) wordAnimDur));
                float easeUp = t * t * (3.0f - 2.0f * t);
                float lift = sungLift * easeUp;
                return new EmphasisState(1.0f, lift, 0f);
            }
        }

        private static boolean isWordGrowable(WordUnit u, boolean isSecondary) {
            if (isSecondary) return false;
            long dur = (u.wordEndMs != LyricsLine.NO_TIME && u.wordStartMs != LyricsLine.NO_TIME)
                    ? (u.wordEndMs - u.wordStartMs)
                    : (u.endMs - u.startMs);
            if (dur < 1000L) return false;
            String text = u.kanji.trim();
            if (text.length() > 7) return false;
            if (LyricsRomanizer.containsArabic(text) || LyricsRomanizer.containsHebrew(text)) return false;
            return true;
        }

        private static final float WORD_FEATHER_PX = 8f;

        /**
         * Draws text with a horizontal LinearGradient: solid inside the filled region, soft feathered
         * drop-off to transparent in the unfilled region.
         */
        private void drawFeatheredFill(Canvas canvas, String text, float x, float y, float width,
                                       float progress, TextPaint paint, int color, boolean isRtl) {
            if (width <= 0f || progress <= 0f) {
                return;
            }
            final int solid = color | 0xFF000000;
            if (progress >= 1f) {
                paint.setColor(solid);
                canvas.drawText(text, x, y, paint);
                return;
            }
            final float p = Math.max(0f, Math.min(1f, progress));
            final float feather = Math.min(WORD_FEATHER_PX, width / 2f);
            final int clear = color & 0x00FFFFFF;

            final float wiperX;
            final int[] colors;
            final float[] stops;

            if (isRtl) {
                wiperX = (1f - p) * width;
                final float curFeather = Math.min(feather, Math.min(wiperX, width - wiperX));
                final float solidEnd = Math.max(0f, Math.min(1f, (wiperX - curFeather) / width));
                final float fadeEnd = Math.max(solidEnd, Math.min(1f, (wiperX + curFeather) / width));
                colors = new int[]{clear, clear, solid, solid};
                stops = new float[]{0f, solidEnd, fadeEnd, 1f};
            } else {
                wiperX = p * width;
                final float curFeather = Math.min(feather, Math.min(wiperX, width - wiperX));
                final float solidEnd = Math.max(0f, Math.min(1f, (wiperX - curFeather) / width));
                final float fadeEnd = Math.max(solidEnd, Math.min(1f, (wiperX + curFeather) / width));
                colors = new int[]{solid, solid, clear, clear};
                stops = new float[]{0f, solidEnd, fadeEnd, 1f};
            }

            final LinearGradient shader = new LinearGradient(x, 0f, x + width, 0f,
                    colors,
                    stops,
                    Shader.TileMode.CLAMP);
            paint.setShader(shader);
            canvas.drawText(text, x, y, paint);
            paint.setShader(null);
        }

        private void drawTranslationLayout(Canvas canvas) {
            if (transLayout != null) {
                float transTop = totalMeasuredHeight - transLayout.getHeight();
                canvas.save();
                canvas.translate(0, transTop);
                transLayout.draw(canvas);
                canvas.restore();
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (isGravityRightAligned()) {
                setPivotX(w);
            } else {
                setPivotX(0f);
            }
            setPivotY(h / 2f);
        }

        void setTranslationBounds(int start, int end) {
            transStart = start;
            transEnd = end;
        }

        void setRomanizationBounds(int start, int end) {
            romaStart = start;
            romaEnd = end;
        }

        @Nullable
        String getCopyTextForTouch(float touchY) {
            Layout layout = getLayout();
            if (layout == null) {
                return null;
            }
            int lineCount = layout.getLineCount();
            if (lineCount <= 1) {
                return null;
            }
            int touchedLine = layout.getLineForVertical((int) touchY);
            CharSequence text = getText();
            if (text == null) {
                return null;
            }
            for (int i = 0; i < lineCount; i++) {
                if (i != touchedLine) continue;
                int lineStart = layout.getLineStart(i);
                int lineEnd = layout.getLineEnd(i);
                if (transStart >= 0 && lineStart < transEnd && lineEnd > transStart) {
                    return text.subSequence(
                            Math.max(lineStart, transStart),
                            Math.min(lineEnd, transEnd)).toString().trim();
                }
                if (romaStart >= 0 && lineStart < romaEnd && lineEnd > romaStart) {
                    return text.subSequence(
                            Math.max(lineStart, romaStart),
                            Math.min(lineEnd, romaEnd)).toString().trim();
                }
            }
            return null;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                lastTouchY = event.getY();
            }
            return super.onTouchEvent(event);
        }

        void setHighlight(List<WordTiming> timings, long posMs, boolean sung,
                          int unsungCol, int sungCol, int origStart) {
            final boolean activeChanged = (this.positionMs == Long.MIN_VALUE) != (posMs == Long.MIN_VALUE);
            final boolean sungChanged = (this.allSung != sung);
            final boolean colorChanged = (this.unsungColor != unsungCol || this.sungColor != sungCol);
            final boolean timingChanged = (this.wordTimings != timings || this.originalTextStart != origStart);

            this.wordTimings = timings != null ? timings : Collections.emptyList();
            this.cachedHasWordTimings = computeHasWordTimings();
            this.positionMs = posMs;
            if (posMs == Long.MIN_VALUE) {
                this.lastDrawnClockPos = Long.MIN_VALUE;
            }
            this.allSung = sung;
            this.unsungColor = unsungCol;
            this.sungColor = sungCol;
            this.originalTextStart = origStart;

            if (timingChanged || colorChanged) {
                cachedLayout = null;
                sungLayout = null;
            }


            if (activeChanged) {
                postInvalidateOnAnimation();
            } else if (wordUnits == null || wordUnits.isEmpty()) {
                invalidate();
            } else if (sungChanged || colorChanged || (posMs != Long.MIN_VALUE)) {
                invalidate();
            }
        }

        private void ensureWordCache(Layout layout, CharSequence text, Paint paint,
                                     List<WordTiming> timings, int origStart) {
            if (layout == cachedLayout && timings.size() == cachedWordCount
                    && origStart == cachedOrigStart) {
                return;
            }
            cachedLayout = layout;
            cachedWordCount = timings.size();
            cachedOrigStart = origStart;
            cachedWordLeadX = new float[cachedWordCount];
            cachedWordTrailX = new float[cachedWordCount];
            cachedWordLine = new int[cachedWordCount];
            cachedWordWrappedLine = new int[cachedWordCount];
            for (int i = 0; i < cachedWordCount; i++) {
                final WordTiming timing = timings.get(i);
                final int s = timing.start() + origStart;
                final int e = Math.min(timing.end() + origStart, text.length());
                if (s >= text.length() || s >= e) {
                    cachedWordWrappedLine[i] = -1;
                    continue;
                }
                final int line = layout.getLineForOffset(s);
                final float lead = layout.getPrimaryHorizontal(s);
                float trail = layout.getPrimaryHorizontal(e);
                final int endLine = layout.getLineForOffset(e);
                if (trail == lead || endLine != line) {
                    final float width = paint.measureText(text, s, e);
                    trail = layout.isRtlCharAt(s) ? lead - width : lead + width;
                }
                cachedWordLeadX[i] = lead;
                cachedWordTrailX[i] = trail;
                cachedWordLine[i] = line;
                cachedWordWrappedLine[i] = endLine != line ? endLine : -1;
            }
            final int lineCount = layout.getLineCount();
            if (cachedLineCount != lineCount) {
                cachedLineCount = lineCount;
                cachedLineTop = new int[lineCount];
                cachedLineBottom = new int[lineCount];
                cachedLineLeft = new float[lineCount];
                cachedLineRight = new float[lineCount];
            }
            for (int ln = 0; ln < lineCount; ln++) {
                cachedLineTop[ln] = layout.getLineTop(ln);
                cachedLineBottom[ln] = layout.getLineBottom(ln);
                cachedLineLeft[ln] = layout.getLineLeft(ln);
                cachedLineRight[ln] = layout.getLineRight(ln);
            }
        }

        private Layout ensureSungLayout(Layout base, CharSequence text, int origStart) {
            if (sungLayout != null && sungLayoutText == text
                    && sungLayoutWidth == base.getWidth()
                    && sungLayoutColor == sungColor
                    && sungLayoutOrigStart == origStart) {
                return sungLayout;
            }

            SpannableString copy = new SpannableString(text);
            final int end = originalEnd(copy, origStart);
            if (origStart < end) {
                for (ForegroundColorSpan span : copy.getSpans(origStart, end, ForegroundColorSpan.class)) {
                    copy.removeSpan(span);
                }
                copy.setSpan(new ForegroundColorSpan(sungColor), origStart, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            StaticLayout.Builder b = StaticLayout.Builder
                    .obtain(copy, 0, copy.length(), getPaint(), base.getWidth())
                    .setAlignment(base.getAlignment())
                    .setLineSpacing(getLineSpacingExtra(), getLineSpacingMultiplier())
                    .setIncludePad(getIncludeFontPadding())
                    .setBreakStrategy(getBreakStrategy())
                    .setHyphenationFrequency(getHyphenationFrequency());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                b.setUseLineSpacingFromFallbacks(true);
            }
            sungLayout = b.build();
            sungLayoutText = text;
            sungLayoutWidth = base.getWidth();
            sungLayoutColor = sungColor;
            sungLayoutOrigStart = origStart;
            return sungLayout;
        }

        /** End of the lyric line itself, before any translation or romanization below it. */
        private int originalEnd(CharSequence text, int origStart) {
            if (text != cachedText) {
                cachedText = text;
                cachedTextStr = text.toString();
            }
            final int newline = cachedTextStr.indexOf('\n', origStart);
            return newline >= 0 ? newline : cachedTextStr.length();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (isSecondary && secondaryExpandFraction <= 0.001f) {
                return;
            }
            if (wordUnits != null && !wordUnits.isEmpty()) {
                onDrawWordUnits(canvas);
                return;
            }

            final boolean active = isLineActive || (positionMs != Long.MIN_VALUE && !allSung);
            float target = active ? 1.0f : 0.95f;
            if (Math.abs(currentScale - target) > 0.001f) {
                currentScale += (target - currentScale) * 0.15f;
                postInvalidateOnAnimation();
            } else {
                currentScale = target;
            }

            canvas.save();
            if (isSecondary) {
                canvas.clipRect(0, 0, getWidth(), getHeight());
            }
            float pivotX = isGravityRightAligned()
                    ? (getWidth() - getCompoundPaddingRight())
                    : getCompoundPaddingLeft();
            float pivotY = getHeight() / 2f;
            canvas.scale(currentScale, currentScale, pivotX, pivotY);

            super.onDraw(canvas);
            if (unsungColor == 0 || !hasWordTimings()) {
                canvas.restore();
                return;
            }
            Layout layout = getLayout();
            if (layout == null) {
                canvas.restore();
                return;
            }
            CharSequence text = getText();
            Paint tp = getPaint();
            final int origStart = originalTextStart;
            final int paddingLeft = getCompoundPaddingLeft();
            final int paddingTop = getExtendedPaddingTop();

            ensureWordCache(layout, text, tp, wordTimings, origStart);

            final int lineCount = cachedLineCount;
            if (lineMaxSungX == null || lineMaxSungX.length != lineCount) {
                lineMaxSungX = new float[lineCount];
            }
            Arrays.fill(lineMaxSungX, 0, lineCount, Float.NaN);
            int firstSungLine = -1;

            for (int i = 0; i < cachedWordCount; i++) {
                final WordTiming timing = wordTimings.get(i);
                final int s = timing.start() + origStart;
                final int e = Math.min(timing.end() + origStart, text.length());
                if (s >= text.length() || s >= e) {
                    continue;
                }
                final long livePosMs = (positionMs != Long.MIN_VALUE) ? getLiveClockPositionMs() : positionMs;
                float progress;
                if (allSung) {
                    progress = 1f;
                } else if (livePosMs >= timing.endMs()) {
                    progress = 1f;
                } else if (livePosMs <= timing.startMs()) {
                    progress = 0f;
                } else {
                    float raw = (float) (livePosMs - timing.startMs())
                            / (float) Math.max(1L, timing.endMs() - timing.startMs());
                    progress = Math.max(0f, Math.min(1f, raw));
                }
                if (progress <= 0f) {
                    continue;
                }
                final int lineNum = cachedWordLine[i];
                if (firstSungLine < 0) {
                    firstSungLine = lineNum;
                }
                final float lead = cachedWordLeadX[i];
                final float edge = lead + (cachedWordTrailX[i] - lead) * progress;
                lineMaxSungX[lineNum] = extendSung(lineMaxSungX[lineNum], edge,
                        isRightToLeftLine(layout, lineNum));
                final int wrappedLine = cachedWordWrappedLine[i];
                if (wrappedLine >= 0 && wrappedLine < lineCount) {
                    final int charsOnLine0 = layout.getLineEnd(lineNum) - s;
                    final int charsOnLine1 = e - layout.getLineEnd(lineNum);
                    if (charsOnLine0 + charsOnLine1 > 0) {
                        final float line1Progress = Math.max(0f,
                                (progress * (charsOnLine0 + charsOnLine1) - charsOnLine0)
                                        / (float) charsOnLine1);
                        if (line1Progress > 0f) {
                            final float line1Edge = layout.getLineLeft(wrappedLine)
                                    + (layout.getLineRight(wrappedLine)
                                            - layout.getLineLeft(wrappedLine))
                                            * line1Progress;
                            lineMaxSungX[wrappedLine] = extendSung(
                                    lineMaxSungX[wrappedLine], line1Edge,
                                    isRightToLeftLine(layout, wrappedLine));
                        }
                    }
                }
            }

            if (allSung && firstSungLine < 0 && origStart < text.length()) {
                final int lastChar = originalEnd(text, origStart) - 1;
                final int firstLine = layout.getLineForOffset(origStart);
                final int lastLine = layout.getLineForOffset(Math.max(lastChar, origStart));
                for (int ln = firstLine; ln <= lastLine && ln < lineCount; ln++) {
                    lineMaxSungX[ln] = isRightToLeftLine(layout, ln)
                            ? cachedLineLeft[ln] : cachedLineRight[ln];
                }
            }

            Layout sung = ensureSungLayout(layout, text, origStart);

            canvas.save();
            canvas.translate(paddingLeft, paddingTop);
            for (int ln = 0; ln < lineCount; ln++) {
                final float edge = lineMaxSungX[ln];
                if (Float.isNaN(edge)) {
                    continue;
                }
                final float left = cachedLineLeft[ln];
                final float right = cachedLineRight[ln];
                final float top = cachedLineTop[ln];
                final float bottom = cachedLineBottom[ln];
                final boolean rtl = isRightToLeftLine(layout, ln);

                if (rtl) {
                    if (allSung || edge <= left) {
                        canvas.save();
                        canvas.clipRect(left, top, right, bottom);
                        sung.draw(canvas);
                        canvas.restore();
                    } else if (edge < right) {
                        canvas.save();
                        canvas.clipRect(edge, top, right, bottom);
                        sung.draw(canvas);
                        canvas.restore();
                    }
                } else {
                    if (allSung || edge >= right) {
                        canvas.save();
                        canvas.clipRect(left, top, right, bottom);
                        sung.draw(canvas);
                        canvas.restore();
                    } else if (edge > left) {
                        canvas.save();
                        canvas.clipRect(left, top, edge, bottom);
                        sung.draw(canvas);
                        canvas.restore();
                    }
                }
            }
            canvas.restore(); // restores translate(paddingLeft, paddingTop)
            canvas.restore(); // restores canvas.scale(...)

            if (firstSungLine >= 0 && !allSung && LyricsManager.getInstance().isPlaying()) {
                postInvalidateOnAnimation();
            }
        }

        private static boolean isRightToLeftLine(Layout layout, int line) {
            return layout.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT;
        }

        /** Right to left lines fill leftwards, so their furthest point is the smallest one. */
        private static float extendSung(float current, float edge, boolean rightToLeft) {
            if (Float.isNaN(current)) {
                return edge;
            }
            return rightToLeft ? Math.min(current, edge) : Math.max(current, edge);
        }
    }

    private GapInterval getActiveGapInterval(long currentTime) {
        if (lyrics == null || !lyrics.synced() || gapIntervals.isEmpty()) {
            return null;
        }
        for (GapInterval gi : gapIntervals) {
            if (currentTime >= gi.startMs() && currentTime < gi.nextStartMs()) {
                return gi;
            }
        }
        return null;
    }

    private boolean shouldShowIntervalIndicator(long currentTime) {
        return getActiveGapInterval(currentTime) != null;
    }

    private void scrollToGapView(@Nullable GapLineView gv) {
        if (gv != null && scrollView != null && scrollView.getHeight() > 0) {
            final int target = gv.getTop()
                    - (int) (scrollView.getHeight() * SCROLL_ANCHOR_RATIO);
            final int clamped = Math.max(0, target);
            targetScrollY = clamped;
            if (currentSmoothScrollY < 0f) {
                currentSmoothScrollY = clamped;
                scrollView.scrollTo(0, clamped);
            }
        }
    }

    /**
     * Instrumental gap indicator line:
     * View inside linesContainer inserted before the target line that animates
     * expanding dots and collapses before the upcoming line begins.
     */
    private class GapLineView extends View {
        private final GapInterval interval;
        private boolean isActive = false;
        private float heightFraction = 0f;
        private float groupScale = 0f;
        private float groupAlpha = 0f;
        private ValueAnimator expandAnimator;
        private ValueAnimator exitAnimator;
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private static final float TARGET_HEIGHT_DP = 38f;

        public GapLineView(Context context, GapInterval interval) {
            super(context);
            this.interval = interval;
            dotPaint.setStyle(Paint.Style.FILL);
            setVisibility(View.GONE);
        }

        public GapInterval getInterval() {
            return interval;
        }

        public boolean isGapActive() {
            return isActive || (exitAnimator != null && exitAnimator.isRunning());
        }

        public void updateState(long currentPos, boolean active) {
            if (active) {
                if (exitAnimator != null && exitAnimator.isRunning()) {
                    exitAnimator.cancel();
                }
                if (!isActive) {
                    isActive = true;
                    setVisibility(View.VISIBLE);
                    startExpandAnimation();
                }
                invalidate();
            } else {
                if (isActive) {
                    isActive = false;
                    if (expandAnimator != null && expandAnimator.isRunning()) {
                        expandAnimator.cancel();
                    }
                    if (currentPos >= interval.endMs() && currentPos < interval.nextStartMs()) {
                        startExitAnimation();
                    } else {
                        resetImmediate();
                    }
                } else if (exitAnimator != null && exitAnimator.isRunning()) {
                    if (currentPos < interval.startMs() || currentPos >= interval.nextStartMs()) {
                        resetImmediate();
                    }
                } else if (currentPos >= interval.endMs() && currentPos < interval.nextStartMs() && getVisibility() == View.VISIBLE) {
                    startExitAnimation();
                }
            }
        }

        private void startExpandAnimation() {
            if (expandAnimator != null) expandAnimator.cancel();
            if (exitAnimator != null) exitAnimator.cancel();

            final float startHeight = heightFraction;
            final float startScale = groupScale;
            final float startAlpha = groupAlpha;

            expandAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
            expandAnimator.setDuration(300);
            expandAnimator.setInterpolator(new PathInterpolator(0.25f, 0.1f, 0.25f, 1.0f));
            expandAnimator.addUpdateListener(anim -> {
                float f = (float) anim.getAnimatedValue();
                heightFraction = startHeight + (1.0f - startHeight) * f;
                groupScale = startScale + (1.0f - startScale) * f;
                groupAlpha = startAlpha + (1.0f - startAlpha) * f;
                updateViewHeight();
                invalidate();
            });
            expandAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    heightFraction = 1.0f;
                    groupScale = 1.0f;
                    groupAlpha = 1.0f;
                    updateViewHeight();
                    invalidate();
                }
            });
            expandAnimator.start();
        }

        private void startExitAnimation() {
            if (expandAnimator != null) expandAnimator.cancel();
            if (exitAnimator != null) exitAnimator.cancel();

            final long remaining = Math.max(100L, interval.nextStartMs() - LyricsManager.getInstance().getPositionMs());
            final long duration = Math.min(GAP_EXIT_DURATION_MS, remaining);

            exitAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
            exitAnimator.setDuration(duration);
            exitAnimator.setInterpolator(new LinearInterpolator());
            exitAnimator.addUpdateListener(anim -> {
                float p = (float) anim.getAnimatedValue();

                if (p <= 0.35f) {
                    float k = p / 0.35f;
                    groupScale = 1.0f + 0.2f * (float) Math.sin(k * Math.PI / 2.0);
                } else {
                    float k = (p - 0.35f) / 0.65f;
                    groupScale = Math.max(0f, 1.2f * (1.0f - (float) Math.sin(k * Math.PI / 2.0)));
                }

                // Collapse height and fade opacity towards the end of the exit phase
                if (p < 0.625f) {
                    heightFraction = 1.0f;
                    groupAlpha = 1.0f;
                } else {
                    float k = (p - 0.625f) / 0.375f;
                    float collapseEase = (float) Math.sin(k * Math.PI / 2.0);
                    heightFraction = Math.max(0f, 1.0f - collapseEase);
                    groupAlpha = Math.max(0f, 1.0f - collapseEase);
                    updateViewHeight();
                }

                invalidate();
            });
            exitAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    resetImmediate();
                }
            });
            exitAnimator.start();
        }

        private void resetImmediate() {
            if (expandAnimator != null) expandAnimator.cancel();
            if (exitAnimator != null) exitAnimator.cancel();
            isActive = false;
            heightFraction = 0f;
            groupScale = 0f;
            groupAlpha = 0f;
            updateViewHeight();
            setVisibility(View.GONE);
            invalidate();
        }

        private void updateViewHeight() {
            int newHeight = Math.round(Dim.dp(TARGET_HEIGHT_DP) * heightFraction);
            ViewGroup.LayoutParams lp = getLayoutParams();
            if (lp != null && lp.height != newHeight) {
                lp.height = newHeight;
                setLayoutParams(lp);
            }
        }

        public void destroy() {
            if (expandAnimator != null) expandAnimator.cancel();
            if (exitAnimator != null) exitAnimator.cancel();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = Math.round(Dim.dp(TARGET_HEIGHT_DP) * heightFraction);
            setMeasuredDimension(width, height);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (getHeight() <= 0 || groupAlpha <= 0f || groupScale <= 0f) {
                return;
            }

            long currentTime = LyricsManager.getInstance().getPositionMs();
            long gapDuration = interval.endMs() - interval.startMs();
            if (gapDuration <= 0) return;

            float segmentDurationMs = (float) gapDuration / 3f;
            float syllableDuration = segmentDurationMs * 0.7f;
            float gapPadding = segmentDurationMs * 0.3f;

            float currentScale = groupScale;
            if (isActive) {
                long elapsedSinceStart = Math.max(0L, currentTime - interval.startMs());
                float cyclePhase = (float) ((elapsedSinceStart % 8000L) / 8000.0);
                float breathe = 1.0f + 0.1f * (float) Math.sin(2.0 * Math.PI * cyclePhase);
                currentScale = groupScale * breathe;
            }

            final float r = Dim.dp(INTERVAL_DOT_RADIUS_DP);
            final float gapPx = Dim.dp(INTERVAL_DOT_GAP_DP);
            final float step = 2f * r + gapPx;
            final float totalWidth = 2f * step + 2f * r;

            final float textMargin = Dim.dp16;
            final float startX = interval.isDuet()
                    ? (getWidth() - totalWidth - textMargin)
                    : textMargin;
            final float groupCenterX = startX + totalWidth / 2f;
            final float cy = getHeight() / 2f;

            int baseColor = lineTextColor();
            int red = Color.red(baseColor);
            int green = Color.green(baseColor);
            int blue = Color.blue(baseColor);

            canvas.save();
            canvas.scale(currentScale, currentScale, groupCenterX, cy);

            for (int i = 0; i < 3; i++) {
                float sylStart = interval.startMs() + (i * segmentDurationMs) + gapPadding;
                float sylEnd = sylStart + syllableDuration;

                float dotFillAlpha;
                if (currentTime < sylStart) {
                    dotFillAlpha = (float) INTERVAL_SECONDARY_ALPHA;
                } else if (currentTime <= sylEnd) {
                    float t = (float) (currentTime - sylStart) / syllableDuration;
                    t = Math.max(0f, Math.min(1f, t));
                    float interpolated = TRANSITION_INTERPOLATOR.getInterpolation(t);
                    dotFillAlpha = INTERVAL_SECONDARY_ALPHA + (INTERVAL_PRIMARY_ALPHA - INTERVAL_SECONDARY_ALPHA) * interpolated;
                } else {
                    dotFillAlpha = (float) INTERVAL_PRIMARY_ALPHA;
                }

                int finalAlpha = Math.max(0, Math.min(255, Math.round(dotFillAlpha * groupAlpha)));
                dotPaint.setColor(Color.argb(finalAlpha, red, green, blue));

                float cx = startX + r + i * step;
                canvas.drawCircle(cx, cy, r, dotPaint);
            }

            canvas.restore();

            if ((isActive || (exitAnimator != null && exitAnimator.isRunning())) && LyricsManager.getInstance().isPlaying()) {
                postInvalidateOnAnimation();
            }
        }
    }


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
                updateWordSync(LyricsManager.getInstance().getPositionMs());
                updateSmoothScroll();

                if (shouldShowIntervalIndicator(LyricsManager.getInstance().getPositionMs())) {
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
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        final int horizontalPadding = Dim.dp24;
        final int topPadding = Dim.dp(16);
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

        // The source line lives in a container of its own, so that lyrics lines can be
        // inserted before it without depending on how many views it holds.
        footerContainer = new LinearLayout(context);
        footerContainer.setOrientation(LinearLayout.VERTICAL);
        // The bottom padding keeps the last lines clear of the pinned buttons and aligns to focal line.
        footerContainer.setPadding(0, Dim.dp24, 0, Dim.dp(340));

        creditView = new TextView(context);
        applyFooterStyle(creditView);
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
        creditParams.bottomMargin = Dim.dp16;
        footerContainer.addView(creditView, creditParams);

        footerContainer.addView(footerView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        linesContainer.addView(footerContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setVerticalFadingEdgeEnabled(true);
        scrollView.setFadingEdgeLength(Dim.dp(80));
        scrollView.setClipChildren(false);
        scrollView.setClipToPadding(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            scrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                if (isProgrammaticScrolling) {
                    return;
                }
                if (!isDraggingScroll) {
                    return;
                }
                currentSmoothScrollY = scrollY;
                targetScrollY = scrollY;
                userScrollUntilUptimeMs = SystemClock.uptimeMillis() + MANUAL_SCROLL_PAUSE_MILLISECONDS;
                if (!isBrowsing) {
                    isBrowsing = true;
                    showJumpToCurrentButton();
                    if (highlightedIndex >= 0) {
                        updateLinesDepthOfField(highlightedIndex);
                    }
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
        jumpToCurrentView.setOnClickListener(v -> {
            isBrowsing = false;
            userScrollUntilUptimeMs = 0;
            isDraggingScroll = false;
            isUserTouchingScroll = false;
            lastScrollTarget = -1;
            handler.removeCallbacks(resumeFollowRunnable);
            hideJumpToCurrentButton(true);
            if (scrollView != null) {
                scrollView.fling(0);
                scrollView.stopNestedScroll();
            }
            long currentPos = LyricsManager.getInstance().getPositionMs();
            GapInterval activeGi = getActiveGapInterval(currentPos);
            if (lyrics != null && lyrics.synced()) {
                boolean inGapBreak = (activeGi != null && currentPos < activeGi.endMs());
                Set<Integer> activeSet = inGapBreak ? Collections.emptySet() : computeActiveLines(lyrics, currentPos);
                int active = inGapBreak ? -1 : computePrimaryActiveIndex(lyrics, activeSet, currentPos);
                if (active < 0 && activeGi != null) {
                    active = activeGi.beforeLineIndex();
                }
                if (active < 0 && !lyrics.lines().isEmpty() && currentPos < lyrics.lines().get(0).startTimeMs()) {
                    active = 0;
                }
                if (active >= 0) {
                    activeIndicesSet.clear();
                    activeIndicesSet.addAll(activeSet);
                    highlightedIndex = active;
                    primaryActiveIndex = active;
                    updateLinesDepthOfField(activeIndicesSet, inGapBreak ? -1 : active, inGapBreak && activeGi != null ? activeGi.beforeLineIndex() : -1);
                    GapLineView gv = inGapBreak && activeGi != null ? gapLineViewsByLine.get(activeGi.beforeLineIndex()) : null;
                    if (gv != null && gv.isGapActive()) {
                        scrollToGapView(gv);
                    } else {
                        scrollToActiveLine(active);
                    }
                } else {
                    targetScrollY = 0;
                }
            } else if (highlightedIndex >= 0) {
                updateLinesDepthOfField(highlightedIndex);
                scrollToActiveLine(highlightedIndex);
            } else {
                targetScrollY = 0;
            }
        });

        LayoutParams jumpParams = new LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        jumpParams.bottomMargin = Dim.dp(96);
        jumpParams.rightMargin = Dim.dp16;
        addView(jumpToCurrentView, jumpParams);

        offsetRulerView = new OffsetRulerView(context);
        LayoutParams rulerParams = new LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        rulerParams.bottomMargin = Dim.dp8;
        addView(offsetRulerView, rulerParams);
        offsetRulerView.setOnClickListener(view -> onOffsetLabelClicked());

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
        final int top = Dim.dp(16);
        final int bottom = Dim.dp(32);
        if (linesContainer.getPaddingTop() != top || linesContainer.getPaddingBottom() != bottom) {
            linesContainer.setPadding(Dim.dp24, top, Dim.dp24, bottom);
        }
        if (footerContainer != null && scrollView != null && scrollView.getHeight() > 0) {
            final int footerBottom = Math.max(Dim.dp(240), (int) (scrollView.getHeight() * (1f - SCROLL_ANCHOR_RATIO)));
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
        if (isTouchInsideView(jumpToCurrentView, event, Dim.dp8)
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
                    if (scrollView != null) {
                        currentSmoothScrollY = scrollView.getScrollY();
                        targetScrollY = scrollView.getScrollY();
                    }
                    if (!isBrowsing) {
                        isBrowsing = true;
                        showJumpToCurrentButton();
                        if (highlightedIndex >= 0) {
                            updateLinesDepthOfField(highlightedIndex);
                        }
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
        if (isTouchInsideView(jumpToCurrentView, event, Dim.dp8)
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
                    if (scrollView != null) {
                        currentSmoothScrollY = scrollView.getScrollY();
                        targetScrollY = scrollView.getScrollY();
                    }
                    if (!isBrowsing) {
                        isBrowsing = true;
                        showJumpToCurrentButton();
                        if (highlightedIndex >= 0) {
                            updateLinesDepthOfField(highlightedIndex);
                        }
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
            pendingOldWordLineIndex = -1;
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
            perWordRomaji = false;
            translateInProgress = false;
            romanizeInProgress = false;

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
                            onTranslateClicked();
                        }
                        if (Settings.LYRICS_ROMANIZE.get()) {
                            onRomanizeClicked();
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
        dynamicBgView.setVisibility(visible ? VISIBLE : GONE);

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

        // 1. Check if lines already have distinct agentId (e.g. from LRC or TTML)
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

        // 2. Check for speaker prefixes in line text (e.g. "[Adrian]", "(Adrian)", "Adrian:", "[v1]")
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

        // 3. Check if track has multiple artists and lines match artist names
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

        // Invert duet alignment if the vast majority of lines were assigned to the opposite side
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

        // Keep standard alignment if nearly all lines fall on one side
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
        TrackInfo track = LyricsManager.getInstance().getCurrentTrack();
        final Lyrics newLyrics = resolveDuetIfApplicable(rawLyrics, track);
        this.lyrics = newLyrics;
        clearLines();
        isBrowsing = false;
        userScrollUntilUptimeMs = 0;
        isDraggingScroll = false;
        isUserTouchingScroll = false;
        lastScrollTarget = -1;
        isProgrammaticScrolling = true;
        handler.removeCallbacks(resumeFollowRunnable);
        hideJumpToCurrentButton(true);
        if (scrollView != null) {
            scrollView.fling(0);
            scrollView.stopNestedScroll();
        }
        targetScrollY = 0;
        currentSmoothScrollY = 0f;
        progressBar.setVisibility(GONE);
        scrollView.setVisibility(VISIBLE);
        updateLinesContainerPadding();

        Context context = getContext();
        final int textSize = Settings.LYRICS_TEXT_SIZE.get();
        final int foregroundColor = lineTextColor();
        final boolean tapToSeek = newLyrics.synced() && Settings.LYRICS_TAP_TO_SEEK.get();
        final boolean isSynced = newLyrics.synced();
        final boolean isDuetSong = newLyrics.lines().stream().anyMatch(LyricsLine::isDuet);

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

        final int generation = ++buildGeneration;

        gapIntervals.clear();
        for (GapLineView gv : gapLineViews) {
            gv.destroy();
            linesContainer.removeView(gv);
        }
        gapLineViews.clear();
        gapLineViewsByLine.clear();
        if (isSynced) {
            for (int i = 0; i < newLyrics.lines().size(); i++) {
                LyricsLine line = newLyrics.lines().get(i);
                if (i == 0) {
                    long currentStart = line.startTimeMs();
                    if (currentStart != LyricsLine.NO_TIME
                            && currentStart >= INTERVAL_MIN_GAP_MS) {
                        long gapStart = 0L;
                        long gapEnd = Math.max(gapStart + 600L, currentStart - GAP_EXIT_DURATION_MS);
                        gapIntervals.add(new GapInterval(gapStart, gapEnd, currentStart, line.isDuet(), 0));
                    }
                } else {
                    LyricsLine prevLine = newLyrics.lines().get(i - 1);
                    long prevStart = prevLine.startTimeMs();
                    long currentStart = line.startTimeMs();
                    if (currentStart == LyricsLine.NO_TIME || prevStart == LyricsLine.NO_TIME) {
                        continue;
                    }
                    long prevEnd = prevLine.endTimeMs();
                    if (prevEnd == LyricsLine.NO_TIME && !prevLine.words().isEmpty()) {
                        prevEnd = prevLine.words().get(prevLine.words().size() - 1).endMs();
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
                        long gapStart = safeEnd;
                        long gapEnd = Math.max(gapStart + 600L, currentStart - GAP_EXIT_DURATION_MS);
                        gapIntervals.add(new GapInterval(gapStart, gapEnd, currentStart, line.isDuet(), i));
                    }
                }
            }
        }

        Set<Integer> manualGaps = new HashSet<>();
        for (GapInterval gi : gapIntervals) {
            if (gi.beforeLineIndex() > 0) {
                manualGaps.add(gi.beforeLineIndex() - 1);
            }
        }
        retimedLyrics = LyricsRetiming.retimeLyrics(newLyrics.lines(), manualGaps);
        retimedAdjustedEndMax = LyricsRetiming.buildAdjustedEndMaxByIndex(retimedLyrics);

        LinearLayout currentParentRow = null;
        int currentParentIdx = -1;

        for (int i = 0; i < newLyrics.lines().size(); i++) {
            LyricsLine line = newLyrics.lines().get(i);

            if (isSynced) {
                for (GapInterval gi : gapIntervals) {
                    if (gi.beforeLineIndex() == i) {
                        GapLineView gapView = new GapLineView(context, gi);
                        gapLineViews.add(gapView);
                        gapLineViewsByLine.put(i, gapView);
                        if (tapToSeek) {
                            final long seekTime = gi.startMs();
                            gapView.setOnClickListener(view -> {
                                isBrowsing = false;
                                userScrollUntilUptimeMs = 0;
                                handler.removeCallbacks(resumeFollowRunnable);
                                hideJumpToCurrentButton(true);
                                if (!VideoInformation.seekTo(seekTime)) {
                                    Logger.printDebug(() -> "Seek to gap failed: " + seekTime);
                                }
                                seekPending = true;
                            });
                        }
                        int footerIndex = linesContainer.indexOfChild(footerContainer);
                        int insertPos = (footerIndex >= 0) ? footerIndex : Math.max(0, linesContainer.getChildCount() - 1);
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

                int footerIndex = linesContainer.indexOfChild(footerContainer);
                int insertPos = (footerIndex >= 0) ? footerIndex : Math.max(0, linesContainer.getChildCount() - 1);
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

            // Placeholder until the background pass fills in the real timings.
            lineWordSpans.add(new ArrayList<>());
            lineOriginalStarts.add(0);

            LyricsLineView lineView = new LyricsLineView(context);
            boolean isSec = isSecondaryLyricLine(i, newLyrics.lines()) && (currentParentRow != null);
            lineView.setSecondary(isSec);
            String lineText = line.text();
            if (isSec) {
                lineText = stripParentheses(lineText).trim();
            }
            lineView.setText(lineText.isEmpty() ? "♪" : lineText);

            int effectiveTextSize = isSynced ? textSize : Math.max(textSize - 2, 22);
            if (isSec) {
                effectiveTextSize = Math.round(effectiveTextSize * 0.64f);
            }
            lineView.setTextSize(TypedValue.COMPLEX_UNIT_SP, effectiveTextSize);
            lineView.setTextColor(foregroundColor);
            lineView.setAlpha(isSynced ? INACTIVE_LINE_ALPHA : 1f);
            lineView.setScaleX(1f);
            lineView.setScaleY(1f);
            lineView.setTag(null);
            lineView.setPadding(Dim.dp16, Dim.dp(3), Dim.dp16, Dim.dp(3));
            lineView.setLineSpacing(Dim.dp(3), 1.15f);
            lineView.setIncludeFontPadding(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                lineView.getPaint().setElegantTextHeight(true);
            }
            lineView.setTypeface(LYRICS_TYPEFACE);

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
                lineView.setOnClickListener(view -> {
                    isBrowsing = false;
                    userScrollUntilUptimeMs = 0;
                    handler.removeCallbacks(resumeFollowRunnable);
                    hideJumpToCurrentButton(true);
                    if (!VideoInformation.seekTo(seekTime)) {
                        Logger.printDebug(() -> "Seek to lyrics line failed: " + seekTime);
                    }
                    seekPending = true;
                });
            }

            lineView.setOnLongClickListener(v -> {
                LyricsLineView lv = (LyricsLineView) v;
                String textToCopy = lv.getCopyTextForTouch(lv.lastTouchY);
                if (textToCopy == null || textToCopy.isEmpty()) {
                    textToCopy = line.text();
                }
                if (textToCopy.isEmpty()) {
                    return false;
                }
                ClipboardManager clipboard = (ClipboardManager) getContext()
                        .getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(
                            ClipData.newPlainText("lyric_line", textToCopy));
                    Utils.showToastShort(str("morphe_music_lyrics_copied"));
                }
                return true;
            });

            final int duetSideInset = Math.max(Dim.dp(48), (int) (context.getResources().getDisplayMetrics().widthPixels * 0.18f));
            final boolean isLineRtl = LyricsRomanizer.containsArabic(line.text()) || LyricsRomanizer.containsHebrew(line.text());
            boolean isDuetLine = line.isDuet() || (isSec && currentParentIdx >= 0 && newLyrics.lines().get(currentParentIdx).isDuet());
            if (isDuetLine || isLineRtl) {
                lineView.setGravity(Gravity.END);
                lineView.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_END);
            } else {
                lineView.setGravity(Gravity.START);
                lineView.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            }

            if (isSec && currentParentRow != null) {
                lineView.setParentLineIndex(currentParentIdx);
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
                bgParams.topMargin = Dim.dp(4);
                currentParentRow.addView(lineView, bgParams);

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

            if (line.isDuet()) {
                lineRow.setPadding(duetSideInset, Dim.dp(2), 0, Dim.dp(2));
            } else if (isLineRtl) {
                lineRow.setPadding(0, Dim.dp(2), 0, Dim.dp(2));
            } else {
                if (isDuetSong) {
                    lineRow.setPadding(0, Dim.dp(2), duetSideInset, Dim.dp(2));
                } else {
                    lineRow.setPadding(0, Dim.dp(2), 0, Dim.dp(2));
                }
            }

            lineRow.addView(lineView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = Dim.dp(26);
            int footerIndex = linesContainer.indexOfChild(footerContainer);
            int insertPos = (footerIndex >= 0) ? footerIndex : Math.max(0, linesContainer.getChildCount() - 1);
            linesContainer.addView(lineRow, insertPos, rowParams);
            lineViews.add(lineView);
            lineRows.add(lineRow);
            lineUnsungSpans.add(null);
        }

        LINE_BUILDER_EXECUTOR.execute(() -> {
            try {
                final int lineCount = newLyrics.lines().size();
                List<List<WordTiming>> allTimings = new ArrayList<>(lineCount);
                List<Integer> allOrigStarts = new ArrayList<>(lineCount);
                List<List<LyricsLineView.WordUnit>> allWordUnits = new ArrayList<>(lineCount);
                final boolean romanizeEnabled = Settings.LYRICS_ROMANIZE.get();

                for (int i = 0; i < lineCount; i++) {
                    final int lineIdx = i;
                    LyricsLine l = newLyrics.lines().get(i);
                    LyricsLine rLine = (romanizedLines != null && i < romanizedLines.size())
                            ? romanizedLines.get(i) : null;
                    List<WordTiming> timings = null;
                    try {
                        timings = computeWordTimings(l, rLine);
                    } catch (Exception t) {
                        Logger.printDebug(() -> "computeWordTimings failed for line " + lineIdx, t);
                        timings = Collections.emptyList();
                    }
                    allTimings.add(timings);

                    List<LyricsLineView.WordUnit> units = null;
                    try {
                        units = buildWordUnits(l, rLine, timings);
                    } catch (Exception t) {
                        Logger.printDebug(() -> "buildWordUnits failed for line " + lineIdx, t);
                        units = null;
                    }
                    allWordUnits.add(units);

                    int origStart = 0;
                    try {
                        origStart = computeOriginalTextStart(l, i, units != null && !units.isEmpty());
                    } catch (Exception t) {
                        origStart = 0;
                    }
                    allOrigStarts.add(origStart);
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
                                    String origText = newLyrics.lines().get(i).text();
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
                                    if (tv instanceof LyricsLineView lineView) {
                                        lineView.setWordUnits(units);
                                        lineView.setTranslationText(units != null && !units.isEmpty() ? trans : null);
                                    }
                                    BuildResult result = buildLineText(newLyrics.lines().get(i),
                                            allTimings.get(i), i, units != null && !units.isEmpty());
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
                        linesContainer.post(() -> {
                            try {
                                if (lyrics == newLyrics) {
                                    isBrowsing = false;
                                    userScrollUntilUptimeMs = 0;
                                    isDraggingScroll = false;
                                    isUserTouchingScroll = false;
                                    hideJumpToCurrentButton(true);
                                    highlightedIndex = -1;
                                    primaryActiveIndex = -1;
                                    lastScrollTarget = -1;
                                    updateHighlight();
                                    if (primaryActiveIndex >= 0) {
                                        scrollToActiveLine(primaryActiveIndex);
                                    } else if (newLyrics.synced() && !newLyrics.lines().isEmpty()) {
                                        scrollToActiveLine(0);
                                    }
                                }
                                isProgrammaticScrolling = false;
                            } catch (Exception t) {
                                Logger.printDebug(() -> "linesContainer.post error", t);
                            }
                        });
                    } catch (Exception t) {
                        Logger.printDebug(() -> "handler.post line update failed", t);
                    }
                });
            } catch (Exception t) {
                Logger.printDebug(() -> "LINE_BUILDER_EXECUTOR failed", t);
            }
        });

        currentSourceUrl = newLyrics.sourceUrl();
        footerView.setText(sourceText(newLyrics.providerName(),
                translatedLines != null, translatedFromGoogle, translatedFromAI,
                romanizedFromGoogle, romanizedFromAI, aiModelName));
        footerView.setOnClickListener(view -> onSourceClicked());
        footerView.setOnLongClickListener(view -> {
            showProviderSelectionDialog();
            return true;
        });

        List<String> songwriters = newLyrics.songwriters();
        if (songwriters != null && !songwriters.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < songwriters.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(songwriters.get(i));
            }
            creditView.setText(sb.toString());
            creditView.setVisibility(Settings.LYRICS_HIDE_INFO.get() ? GONE : VISIBLE);
        } else {
            creditView.setVisibility(GONE);
        }
        footerContainer.setVisibility(VISIBLE);
        footerView.setVisibility(VISIBLE);
        buttonRow.setVisibility(VISIBLE);
        updateTranslateLabel();
        updateRomanizeLabel();

        isProgrammaticScrolling = true;
        scrollView.scrollTo(0, 0);
        scrollView.post(() -> isProgrammaticScrolling = false);
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
            if (wText.length() > 1) {
                long wordStart = single.startMs();
                long wordEnd = single.endMs();
                if (wordEnd <= wordStart) {
                    return Collections.emptyList();
                }
                String romaji = single.romaji();
                if ((romaji == null || romaji.isEmpty()) && rLine != null && rLine.hasWords() && !rLine.words().isEmpty()) {
                    romaji = rLine.words().get(0).romaji();
                }
                int charCount = wText.length();
                long dur = wordEnd - wordStart;
                long perChar = dur / charCount;
                List<WordTiming> timings = new ArrayList<>(charCount);
                int pos = 0;
                for (int i = 0; i < charCount; i++) {
                    int next = pos + Character.charCount(wText.codePointAt(pos));
                    timings.add(new WordTiming(pos, next,
                            wordStart + i * perChar,
                            wordStart + (i + 1) * perChar, romaji));
                    pos = next;
                }
                return timings;
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
        String trimmed = text.trim();
        boolean stripped = true;
        while (stripped && trimmed.length() >= 2) {
            stripped = false;
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '(' && last == ')')
                    || (first == '（' && last == '）')
                    || (first == '[' && last == ']')
                    || (first == '【' && last == '】')) {
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
        return LRCParser.hasOuterParentheses(line.text());
    }

    public static boolean isSecondaryLyricLine(int index, @Nullable List<LyricsLine> lines) {
        if (lines == null || index <= 0 || index >= lines.size()) {
            return false;
        }
        LyricsLine line = lines.get(index);
        boolean candidate = line.isBG() || LRCParser.hasOuterParentheses(line.text());
        if (!candidate) {
            return false;
        }

        // A secondary lyric MUST accompany an existing primary/main line
        int parentIdx = index - 1;
        while (parentIdx >= 0) {
            LyricsLine p = lines.get(parentIdx);
            if (!p.isBG() && !LRCParser.hasOuterParentheses(p.text())) {
                break;
            }
            parentIdx--;
        }
        if (parentIdx < 0) {
            return false;
        }

        LyricsLine parentLine = lines.get(parentIdx);
        if (line.startTimeMs() != LyricsLine.NO_TIME && parentLine.startTimeMs() != LyricsLine.NO_TIME) {
            long timeDiff = line.startTimeMs() - parentLine.startTimeMs();
            if (timeDiff < -500L || timeDiff > 12000L) {
                return false;
            }
            if (parentLine.endTimeMs() != LyricsLine.NO_TIME && parentLine.endTimeMs() > parentLine.startTimeMs()) {
                if (line.startTimeMs() > parentLine.endTimeMs() + 4000L) {
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
                if (!r.isEmpty() && !hasCjk(r) && !LyricsRomanizer.isDegradedRomajiToken(r) && !LyricsRomanizer.isMandarinPinyinToken(r)) {
                    rawRoma = r;
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
                String rom = needsRoma ? getJapaneseRomajiForRange(rawText, mStart, mEnd, segments, readings) : "";

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

    private static boolean containsKanji(@Nullable String s) {
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

    private static String getJapaneseRomajiForRange(
            String rawText, int start, int end,
            List<LyricsRomanizer.JapaneseSegment> segments,
            List<String> readings) {
        if (start >= end || start >= rawText.length()) return "";
        StringBuilder sb = new StringBuilder();
        if (segments != null && readings != null) {
            for (int k = 0; k < segments.size(); k++) {
                LyricsRomanizer.JapaneseSegment seg = segments.get(k);
                if (seg.end > start && seg.start < end) {
                    String r = (k < readings.size()) ? readings.get(k) : null;
                    if (r == null || r.isEmpty()) {
                        r = LyricsRomanizer.romanizeJapanese(seg.text);
                    }
                    if (r != null && !r.isEmpty()) {
                        sb.append(r);
                    }
                }
            }
        }
        if (sb.length() == 0) {
            String sub = rawText.substring(start, Math.min(end, rawText.length()));
            String r = LyricsRomanizer.romanizeJapanese(sub);
            if (r != null) sb.append(r);
        }
        return normalizeRomajiText(sb.toString()).trim();
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

    private void onTranslateClicked() {
        try {
            isBrowsing = false;
            userScrollUntilUptimeMs = 0;
            handler.removeCallbacks(resumeFollowRunnable);
            hideJumpToCurrentButton(true);
            // The saved translation state outlives the button, so a track change can
            // auto translate when there is no button to drive the translation from.
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
                showLyrics(current);
                return;
            }

            Settings.LYRICS_TRANSLATE.save(true);
            translateInProgress = true;
            setButtonLabel(translateView, str("morphe_music_lyrics_translating"), true);

            LyricsTranslator.translate(track, current, current.providerName(),
                    (lines, fromGoogle, fromAI, model) -> {
                if (!translateInProgress) {
                    return;
                }
                translateInProgress = false;

                // The track may have changed while the translation was in flight.
                if (lyrics != current) {
                    return;
                }

                final boolean transOk = (lines != null && !lines.isEmpty());
                translatedLines = transOk ? lines : null;
                translatedFromGoogle = transOk && fromGoogle;
                translatedFromAI = transOk && fromAI;
                if (translatedFromAI && model != null) {
                    aiModelName = model;
                }
                if (!transOk) {
                    Utils.showToastShort(str("morphe_music_lyrics_translate_failed"));
                }
                showLyrics(current);
                if (transOk) {
                    setButtonLabel(translateView, str("morphe_music_lyrics_translate_hide"), true);
                    handler.postDelayed(this::updateTranslateLabel, 3000);
                }
            });
        } catch (Exception ex) {
            Logger.printDebug(() -> "onTranslateClicked failure", ex);
        }
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
                showLyrics(current);
                return;
            }

            Settings.LYRICS_ROMANIZE.save(true);
            romanizeInProgress = true;
            setButtonLabel(romanizeView, str("morphe_music_lyrics_romanizing"), true);

            LyricsRomanizer.romanize(track, current, current.providerName(),
                    (lines, fromGoogle, fromAI, model, perWord) -> {
                if (!romanizeInProgress) {
                    return;
                }
                romanizeInProgress = false;

                // The track may have changed while the romanization was in flight.
                if (lyrics != current) {
                    return;
                }

                final boolean romaOk = LyricsMerge.hasText(lines);
                romanizedLines = romaOk ? lines : null;
                romanizedFromGoogle = romaOk && fromGoogle;
                romanizedFromAI = romaOk && fromAI;
                if (romanizedFromAI && model != null) {
                    aiModelName = model;
                }
                perWordRomaji = romaOk && perWord;
                if (!romaOk && !perWord) {
                    Utils.showToastShort(str("morphe_music_lyrics_romanize_failed"));
                }
                showLyrics(current);
                if (romaOk) {
                    setButtonLabel(romanizeView, str("morphe_music_lyrics_romanize_hide"), true);
                    handler.postDelayed(this::updateRomanizeLabel, 3000);
                }
            });
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

        // --- Option 1: Search Lyrics with custom query ---
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
        searchDescTv.setText(str("morphe_music_lyrics_show_refresh_button_summary"));
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

        // --- Option 2: Provider Selection List ---
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

    private void clearLines() {
        isProgrammaticScrolling = true;
        isBrowsing = false;
        userScrollUntilUptimeMs = 0;
        isDraggingScroll = false;
        isUserTouchingScroll = false;
        lastScrollTarget = -1;
        targetScrollY = 0;
        currentSmoothScrollY = -1f;
        handler.removeCallbacks(resumeFollowRunnable);
        hideJumpToCurrentButton(true);
        if (scrollView != null) {
            scrollView.fling(0);
            scrollView.stopNestedScroll();
        }
        for (TextView lineView : lineViews) {
            // A running fade would otherwise keep a reference to a removed view.
            lineView.animate().cancel();
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
        if (linesContainer.indexOfChild(footerContainer) < 0) {
            linesContainer.addView(footerContainer);
        }
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
        retimedLyrics = Collections.emptyList();
        retimedAdjustedEndMax = new long[0];
    }

    private Set<Integer> computeActiveLines(Lyrics lyrics, long currentTimeMs) {
        if (retimedLyrics == null || retimedLyrics.isEmpty()) {
            return Collections.emptySet();
        }
        // Lookahead window (190ms)
        List<Integer> activeList = LyricsRetiming.findActiveRetimedLineIndices(
                retimedLyrics, retimedAdjustedEndMax, currentTimeMs + 190L, 0L);
        if (activeList.isEmpty()) {
            return Collections.emptySet();
        }

        Set<Integer> set = new TreeSet<>();
        for (int idx : activeList) {
            if (idx < 0 || idx >= lineViews.size()) continue;
            set.add(idx);
            int mainIdx = idx;
            TextView tv = lineViews.get(idx);
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

        // Include all secondary lyrics belonging to any active main lines
        for (int i = 0; i < lineViews.size(); i++) {
            TextView tv = lineViews.get(i);
            if (tv instanceof LyricsLineView llv && llv.isSecondary()) {
                int pIdx = llv.getParentLineIndex();
                if (pIdx >= 0 && set.contains(pIdx)) {
                    set.add(i);
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

    private void updateHighlight() {
        try {
            Lyrics current = lyrics;
            if (current == null || !current.synced() || lineViews.isEmpty()) {
                return;
            }

            LyricsManager manager = LyricsManager.getInstance();
            final long rawPos = manager.getPositionMs();
            final long pos;
            if (manager.isPlaying() && lastLivePosMs > 0 && rawPos < lastLivePosMs && rawPos >= lastLivePosMs - 1000L) {
                pos = lastLivePosMs;
            } else {
                pos = rawPos;
            }
            final long prevPos = lastLivePosMs;
            lastLivePosMs = pos;

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

            int newPrimary;
            if (inGapBreak) {
                newPrimary = -1;
            } else if (activeGi != null && pos >= activeGi.endMs() && pos < activeGi.nextStartMs()) {
                // In gap exit collapse phase: seamlessly focus the upcoming line
                newPrimary = Math.max(0, Math.min(activeGi.beforeLineIndex(), lineViews.size() - 1));
            } else {
                // Find all active main line indices currently playing in newActiveSet
                List<Integer> activeMainLines = new ArrayList<>();
                for (int act : newActiveSet) {
                    int mainAct = act;
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
                    if (mainAct >= 0 && !activeMainLines.contains(mainAct)) {
                        activeMainLines.add(mainAct);
                    }
                }
                Collections.sort(activeMainLines);

                int refIdx = primaryActiveIndex >= 0 ? primaryActiveIndex : Math.max(0, previousPrimaryActiveIndex);
                while (refIdx > 0 && isSecondaryLineView(refIdx)) {
                    refIdx--;
                }
                if (refIdx < 0 && !retimedLyrics.isEmpty()) {
                    refIdx = 0;
                }

                if (refIdx >= 0 && activeMainLines.contains(refIdx)) {
                    // The current line is still actively playing!
                    // Hold scroll anchored on this line until it finishes being active,
                    // even if subsequent lines below it are also active (duet / multi-line active).
                    newPrimary = refIdx;
                } else if (!activeMainLines.isEmpty()) {
                    // The previous line has finished; advance to the earliest line currently active.
                    newPrimary = activeMainLines.get(0);
                } else {
                    // No lines currently active (e.g. gap between lines); use predictive lookahead to scroll smoothly to upcoming line.
                    int clusterEndIdx = Math.max(0, refIdx);
                    while (clusterEndIdx + 1 < retimedLyrics.size() && isSecondaryLineView(clusterEndIdx + 1)) {
                        clusterEndIdx++;
                    }
                    long clusterActualEnd = (refIdx >= 0 && refIdx < retimedLyrics.size())
                            ? retimedLyrics.get(refIdx).actualEndTimeMs : Long.MIN_VALUE;
                    for (int k = Math.max(0, refIdx); k <= clusterEndIdx && k < retimedLyrics.size(); k++) {
                        clusterActualEnd = Math.max(clusterActualEnd, retimedLyrics.get(k).actualEndTimeMs);
                    }
                    int nextMainIdx = clusterEndIdx + 1;
                    long scrollLookAheadMs = 350L;
                    if (retimedLyrics != null && nextMainIdx < retimedLyrics.size() && clusterActualEnd > Long.MIN_VALUE) {
                        long gap = retimedLyrics.get(nextMainIdx).startMs - clusterActualEnd;
                        scrollLookAheadMs = Math.max(350L, Math.min(500L, gap));
                    }
                    long predictiveTime = pos + scrollLookAheadMs;
                    List<Integer> predActiveList = LyricsRetiming.findActiveRetimedLineIndices(
                            retimedLyrics, retimedAdjustedEndMax, predictiveTime, 50L);
                    newPrimary = LyricsRetiming.calculatePrimaryRetimedLineIndex(
                            retimedLyrics, predActiveList, predictiveTime, refIdx);
                    while (newPrimary > 0 && isSecondaryLineView(newPrimary)) {
                        newPrimary--;
                    }
                    if (newPrimary < 0 && !retimedLyrics.isEmpty()) {
                        newPrimary = 0;
                    }
                }

                if (newPrimary < 0) {
                    newPrimary = Math.max(0, refIdx);
                }
                // Enforce forward stability during playback so autoscroll never bounces backwards on micro-jitter
                if (manager.isPlaying() && refIdx >= 0 && newPrimary < refIdx) {
                    if (pos >= prevPos - 1000L) {
                        newPrimary = refIdx;
                    }
                }
            }

            boolean activeSetChanged = !newActiveSet.equals(activeIndicesSet);
            boolean primaryChanged = (newPrimary != primaryActiveIndex);

            if (activeSetChanged || primaryChanged) {
                activeIndicesSet.clear();
                activeIndicesSet.addAll(newActiveSet);
                previousPrimaryActiveIndex = primaryActiveIndex;
                primaryActiveIndex = newPrimary;
                highlightedIndex = newPrimary;
            }

            if (activeSetChanged || primaryChanged) {
                updateLinesDepthOfField(activeIndicesSet, primaryActiveIndex, inGapBreak && activeGi != null ? activeGi.beforeLineIndex() : -1);
            }

            if (isBrowsing || SystemClock.uptimeMillis() < userScrollUntilUptimeMs) {
                return;
            }

            if (inGapBreak && scrollView != null && scrollView.getHeight() > 0) {
                int targetIdx = activeGi.beforeLineIndex();
                GapLineView gv = gapLineViewsByLine.get(targetIdx);
                final int viewTop = (gv != null && (gv.getTop() > 0 || targetIdx == 0))
                        ? gv.getTop()
                        : (targetIdx >= 0 && targetIdx < lineRows.size() ? lineRows.get(targetIdx).getTop() : -1);
                if (viewTop >= 0) {
                    final int target = viewTop
                            - (int) (scrollView.getHeight() * SCROLL_ANCHOR_RATIO);
                    final int clamped = Math.max(0, target);
                    final int dist = Math.abs(scrollView.getScrollY() - clamped);
                    if (dist > scrollView.getHeight() * SCROLL_INSTANT_THRESHOLD_FACTOR) {
                        isProgrammaticScrolling = true;
                        scrollView.scrollTo(0, clamped);
                        currentSmoothScrollY = clamped;
                        targetScrollY = clamped;
                        scrollView.post(() -> isProgrammaticScrolling = false);
                        lastScrollTarget = clamped;
                    } else {
                        targetScrollY = clamped;
                        lastScrollTarget = clamped;
                    }
                    return;
                }
            }

            if (primaryActiveIndex >= 0 && primaryActiveIndex < lineRows.size()) {
                final int target = lineRows.get(primaryActiveIndex).getTop()
                        - (int) (scrollView.getHeight() * SCROLL_ANCHOR_RATIO);
                final int clamped = Math.max(0, target);
                final int dist = Math.abs(scrollView.getScrollY() - clamped);
                if (dist > scrollView.getHeight() * SCROLL_INSTANT_THRESHOLD_FACTOR) {
                    isProgrammaticScrolling = true;
                    scrollView.scrollTo(0, clamped);
                    currentSmoothScrollY = clamped;
                    targetScrollY = clamped;
                    scrollView.post(() -> isProgrammaticScrolling = false);
                    lastScrollTarget = clamped;
                } else {
                    targetScrollY = clamped;
                    lastScrollTarget = clamped;
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
        final boolean wordSync = Settings.LYRICS_WORD_SYNC.get();
        final boolean blurInactive = Settings.LYRICS_BLUR_INACTIVE.get();
        final boolean applyBlur = blurInactive && !isBrowsing;
        if (applyBlur) {
            ensureBlurEffects();
        }
        final int count = lineViews.size();
        for (int i = 0; i < count; i++) {
            final TextView lv = lineViews.get(i);
            if (lv == null) continue;

            if (lv instanceof LyricsLineView && ((LyricsLineView) lv).isSecondary() && ((LyricsLineView) lv).getParentLineIndex() >= 0) {
                LyricsLineView slv = (LyricsLineView) lv;
                int pIdx = slv.getParentLineIndex();
                boolean parentActive = (pIdx >= 0 && activeSet.contains(pIdx));
                boolean isBgActive = activeSet.contains(i) || parentActive;
                slv.setLineActive(isBgActive);
                slv.animateSecondaryExpansion(isBgActive);

                if (applyBlur) {
                    applyBlurEffect(slv, null);
                }

                boolean hasWordSync = wordSync
                        && (slv.hasWordTimings()
                            || (i < lineWordSpans.size() && !lineWordSpans.get(i).isEmpty()));
                if (!hasWordSync) {
                    slv.setTextColor(Color.WHITE);
                }
                continue;
            }

            final boolean isActive = activeSet.contains(i);
            if (lv instanceof LyricsLineView) {
                ((LyricsLineView) lv).setLineActive(isActive);
            }
            final int dist;
            if (isActive) {
                dist = 0;
            } else if (!activeSet.isEmpty()) {
                int minDist = Integer.MAX_VALUE;
                for (int act : activeSet) {
                    minDist = Math.min(minDist, Math.abs(i - act));
                }
                dist = minDist;
            } else if (gapFocusIndex >= 0) {
                dist = Math.max(1, Math.abs(i - gapFocusIndex));
            } else if (primaryIndex >= 0) {
                dist = Math.max(1, Math.abs(i - primaryIndex));
            } else {
                dist = 3;
            }

            final int step = Math.min(dist, LINE_FALLOFF_ALPHA.length - 1);
            final float targetAlpha;

            if (isBrowsing) {
                targetAlpha = BROWSING_ALPHA;
            } else if (isActive) {
                targetAlpha = ACTIVE_LINE_ALPHA;
            } else {
                targetAlpha = LINE_FALLOFF_ALPHA[step];
            }

            fadeTo(lv, targetAlpha);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                RenderEffect effect = null;
                if (applyBlur && !isActive && BLUR_EFFECTS != null) {
                    int blurStep = Math.min(dist, BLUR_EFFECTS.length - 1);
                    effect = BLUR_EFFECTS[blurStep];
                }
                applyBlurEffect(lv, effect);
            }

            boolean hasWordSync = wordSync
                    && ((lv instanceof LyricsLineView && ((LyricsLineView) lv).hasWordTimings())
                        || (i < lineWordSpans.size() && !lineWordSpans.get(i).isEmpty()));
            if (!hasWordSync) {
                lv.setTextColor(Color.WHITE);
            }
        }
    }

    private void updateLinesDepthOfField(int activeIndex) {
        Set<Integer> set = new HashSet<>();
        if (activeIndex >= 0) set.add(activeIndex);
        updateLinesDepthOfField(set, activeIndex);
    }

    private void scrollToActiveLine(int index) {
        if (index >= 0 && index < lineRows.size() && scrollView.getHeight() > 0) {
            View row = lineRows.get(index);
            if (row.getHeight() == 0 && row.getTop() == 0 && index > 0) {
                row.post(() -> scrollToActiveLine(index));
                return;
            }
            final int target = row.getTop()
                    - (int) (scrollView.getHeight() * SCROLL_ANCHOR_RATIO);
            final int clamped = Math.max(0, target);
            targetScrollY = clamped;
            if (currentSmoothScrollY < 0f) {
                currentSmoothScrollY = clamped;
                scrollView.scrollTo(0, clamped);
            }
        }
    }

    private void updateSmoothScroll() {
        if (scrollView == null || isDraggingScroll || isUserTouchingScroll) {
            return;
        }
        if (currentSmoothScrollY < 0f) {
            currentSmoothScrollY = scrollView.getScrollY();
        }
        float diff = targetScrollY - currentSmoothScrollY;
        if (Math.abs(diff) > 1.0f) {
            currentSmoothScrollY += diff * SCROLL_DAMPING_FACTOR;
            isProgrammaticScrolling = true;
            scrollView.scrollTo(0, Math.round(currentSmoothScrollY));
            isProgrammaticScrolling = false;
        } else if (Math.round(currentSmoothScrollY) != targetScrollY) {
            currentSmoothScrollY = targetScrollY;
            isProgrammaticScrolling = true;
            scrollView.scrollTo(0, targetScrollY);
            isProgrammaticScrolling = false;
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

        // Finalize or clear word highlights for lines that are no longer active
        for (int prevIdx : previousActiveSet) {
            if (!activeIndicesSet.contains(prevIdx)) {
                LyricsLine prevLine = (lyrics != null && prevIdx < lyrics.lines().size())
                        ? lyrics.lines().get(prevIdx) : null;
                if (prevLine != null && positionMs >= prevLine.endTimeMs()) {
                    applyWordColors(prevIdx, 0, true);
                    applyBgWordColors(prevIdx, 0, true);
                } else if (prevLine != null && positionMs < prevLine.startTimeMs()) {
                    clearWordHighlight(prevIdx);
                } else {
                    applyWordColors(prevIdx, 0, true);
                    applyBgWordColors(prevIdx, 0, true);
                }
            }
        }
        previousActiveSet.clear();
        previousActiveSet.addAll(activeIndicesSet);

        // Apply word colors simultaneously to all active lines (Multi-Line Active State)
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

    /** Eases the highlight between lines with fluid opacity. */
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
        footer.setTextColor(secondaryTextColor());
        // The secondary color alone is brighter than the app draws this line, which
        // sits dimmer than even the inactive lyrics above it.
        footer.setAlpha(FOOTER_ALPHA);
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
                                     @Nullable String aiModel) {
        StringBuilder sb = new StringBuilder();
        sb.append(str(LYRICS_SOURCE_KEY, provider));

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
        }

        return sb.toString();
    }

    private static int lineTextColor() {
        return ResourceUtils.getColor(APP_PRIMARY_TEXT_COLOR, Color.WHITE);
    }

    private static int secondaryTextColor() {
        return ResourceUtils.getColor(APP_SECONDARY_TEXT_COLOR, 0xB3FFFFFF);
    }

    private static int auxiliaryTextColor() {
        return 0xD9FFFFFF;
    }

    private static int unsungWordColor() {
        int color = lineTextColor();
        return Color.argb(
                UNSUNG_ALPHA,
                Color.red(color),
                Color.green(color),
                Color.blue(color));
    }

    private final class OffsetRulerView extends View {
        private static final int RANGE_MS = 20000;

        private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private int currentOffsetMs;
        @Nullable
        private Long recordTimeMs;

        OffsetRulerView(Context context) {
            super(context);
            valuePaint.setColor(Color.WHITE);
            valuePaint.setTextAlign(Paint.Align.CENTER);
            valuePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            setVisibility(GONE);
        }

        void setOffsetMs(int ms) {
            currentOffsetMs = Math.max(-RANGE_MS, Math.min(RANGE_MS, ms));
            invalidate();
        }

        int getOffsetMs() {
            return currentOffsetMs;
        }

        void setRecordTimeMs(@Nullable Long ms) {
            recordTimeMs = ms;
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            int h = (int) (28 * getResources().getDisplayMetrics().density);
            setMeasuredDimension(w, h);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            float density = getResources().getDisplayMetrics().density;

            String offsetText = (currentOffsetMs >= 0 ? "+" : "") + currentOffsetMs + "ms";
            String text = recordTimeMs == null
                    ? offsetText
                    : SponsorBlockMusicSubmitDialog.formatTimeMs(recordTimeMs) + " " + offsetText;
            valuePaint.setTextSize(13 * density);
            canvas.drawText(text, w / 2f, h / 2f + 5 * density, valuePaint);
        }
    }
}
