/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static app.morphe.extension.music.patches.lyrics.ui.LyricsPanelView.TRANSLATION_RELATIVE_SIZE;
import static app.morphe.extension.music.patches.lyrics.ui.LyricsPanelView.TRANSITION_INTERPOLATOR;
import static app.morphe.extension.music.patches.lyrics.ui.LyricsPanelView.lineTextColor;
import static app.morphe.extension.music.patches.lyrics.ui.LyricsPanelView.unsungWordColor;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import app.morphe.extension.music.patches.lyrics.LyricsManager;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.patches.lyrics.ui.SpringEasing.LyPlusSpring;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.WordTimestamp;
import app.morphe.extension.shared.ui.Dim;

class LyricsLineView extends TextView {
    private static final Interpolator FAST_OUT_SLOW_IN = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    private static final Interpolator LINEAR_OUT_SLOW_IN = new PathInterpolator(0f, 0f, 0.2f, 1f);
    public static final int LINE_VISIBILITY_MS = 250;

    public static record WordTiming(int start, int end, long startMs, long endMs,
            @Nullable String romaji) {
    }

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
    private LyricsLine rawLyricsLine = null;
    @Nullable
    private LyricsLine rawRomanizedLine = null;
    @Nullable
    private List<WordUnit> wordUnits = null;
    @Nullable
    private AppleWordAnimator appleAnimator = null;
    private LyricsAnimationStyle animationStyle = LyricsAnimationStyle.APPLE;
    @Nullable
    private Long adjustedEndTimeMs = null;
    @Nullable
    private LyricsScrollEngine scrollEngine = null;

    @Nullable
    private String translationText = null;
    private final TextPaint transPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private StaticLayout transLayout = null;
    private int transLayoutWidth = -1;

    private final TextPaint romajiFallbackPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    @Nullable
    private StaticLayout romajiFallbackLayout = null;

    private final LyricsScrollEngine.LyricsItemFlip translationFlip = new LyricsScrollEngine.LyricsItemFlip();
    private float translationFlipOffsetY = 0f;

    private final TextPaint romajiPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint unsungKanjiPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint unsungRomajiPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint sungKanjiPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint sungRomajiPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private int cachedLayoutWidth = -1;
    private int totalMeasuredHeight = 0;
    private int lyricsContentHeight = 0;

    private List<WordTiming> wordTimings = Collections.emptyList();
    private long positionMs = Long.MIN_VALUE;
    private boolean allSung = false;
    private int unsungColor;
    private int sungColor;
    private int originalTextStart;
    private float[] lineMaxSungX;
    private boolean isLineActive = false;
    private boolean cachedHasWordTimings = false;
    private boolean prewarmMasks = false;

    public static final class PrebuiltWordLayout {
        public final int wrapWidth;
        @Nullable
        public final AppleWordAnimator appleAnimator;
        @Nullable
        public final StaticLayout romajiFallbackLayout;
        @Nullable
        public final StaticLayout transLayout;
        public final int lyricsContentHeight;
        public final int totalMeasuredHeight;

        public PrebuiltWordLayout(
                int wrapWidth,
                @Nullable AppleWordAnimator appleAnimator,
                @Nullable StaticLayout romajiFallbackLayout,
                @Nullable StaticLayout transLayout,
                int lyricsContentHeight,
                int totalMeasuredHeight) {
            this.wrapWidth = wrapWidth;
            this.appleAnimator = appleAnimator;
            this.romajiFallbackLayout = romajiFallbackLayout;
            this.transLayout = transLayout;
            this.lyricsContentHeight = lyricsContentHeight;
            this.totalMeasuredHeight = totalMeasuredHeight;
        }
    }

    public void setPrewarmMasks(boolean prewarm) {
        this.prewarmMasks = prewarm;
        if (this.appleAnimator != null) {
            this.appleAnimator.setPrewarmMasks(prewarm);
        }
    }

    private float lookFromAlpha = 0.15f;
    private float lookToAlpha = 0.15f;
    private float lookCurAlpha = 0.15f;
    private float lookFromTransAlpha = 0.15f;
    private float lookToTransAlpha = 0.15f;
    private float lookCurTransAlpha = 0.15f;
    private float lookFromFocusedAlpha = 0.15f;
    private float lookToFocusedAlpha = 0.15f;
    private float lookCurFocusedAlpha = 0.15f;
    private float lookFromVisibility = 0f;
    private float lookToVisibility = 0f;
    private float lookCurVisibility = 0f;
    private float lookFromScale = 0.93f;
    private float lookToScale = 0.93f;
    private float lookCurScale = 0.93f;
    private boolean lineLookInitialized = false;
    @Nullable
    private ValueAnimator lineLookAnimator = null;

    public void setScrollEngine(@Nullable LyricsScrollEngine engine) {
        this.scrollEngine = engine;
    }

    public void setAnimationStyle(@NonNull LyricsAnimationStyle style) {
        if (this.animationStyle != style) {
            this.animationStyle = style;
            releaseAppleAnimator();
            cachedLayoutWidth = -1;
            requestLayout();
            invalidate();
        }
    }

    @NonNull
    public LyricsAnimationStyle getAnimationStyle() {
        return animationStyle;
    }

    public void setAdjustedEndTimeMs(@Nullable Long adjustedEndMs) {
        this.adjustedEndTimeMs = adjustedEndMs;
    }

    public void setRawLyricsLine(@Nullable LyricsLine mainLine, @Nullable LyricsLine romanizedLine) {
        this.rawLyricsLine = mainLine;
        this.rawRomanizedLine = romanizedLine;
        this.cachedHasWordTimings = computeHasWordTimings();
    }

    private void releaseAppleAnimator() {
        if (appleAnimator != null) {
            appleAnimator.release();
            appleAnimator = null;
        }
    }

    private boolean wordUnitsHaveTimings() {
        if (wordUnits != null && !wordUnits.isEmpty()) {
            for (int i = 0; i < wordUnits.size(); i++) {
                if (wordUnits.get(i).startMs != LyricsLine.NO_TIME) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean wordUnitsHaveRomaji() {
        if (wordUnits != null && !wordUnits.isEmpty()) {
            for (int i = 0; i < wordUnits.size(); i++) {
                String r = wordUnits.get(i).romaji;
                if (r != null && !r.trim().isEmpty() && !WordAnimationUtils.isCJK(r)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean computeHasWordTimings() {
        if (rawLyricsLine != null && rawLyricsLine.hasWords()) {
            return true;
        }
        if (wordUnitsHaveTimings()) {
            return true;
        }
        return wordTimings != null && !wordTimings.isEmpty();
    }

    public boolean hasWordTimings() {
        return cachedHasWordTimings;
    }

    public void setLineActive(boolean active) {
        if (this.isLineActive != active) {
            this.isLineActive = active;
            if (active) {
                this.allSung = false;
                this.lastDrawnClockPos = Long.MIN_VALUE;
                if (appleAnimator != null) {
                    appleAnimator.reset();
                }
            }
            postInvalidateOnAnimation();
        }
    }

    public float getLineLookAlpha() {
        return lookCurAlpha;
    }

    public float getLineLookTranslationAlpha() {
        return lookCurTransAlpha;
    }

    public float getLineLookFocusedAlpha() {
        return lookCurFocusedAlpha;
    }

    public float getLineLookScale() {
        return lookCurScale;
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(255f * alpha)));
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    public void updateLineLook(
            boolean isActive,
            int distance,
            long staggerDelayMs,
            boolean isAutoScrollEnabled,
            boolean isLrcSynced,
            boolean instant) {
        final float targetFocusedAlpha = !isAutoScrollEnabled ? 0.6f : 0.15f;
        final float targetInactiveAlpha;
        if (!isAutoScrollEnabled) {
            targetInactiveAlpha = 0.6f;
        } else if (isLrcSynced) {
            if (distance == 0) {
                targetInactiveAlpha = targetFocusedAlpha;
            } else if (distance == 1 || distance == 2) {
                targetInactiveAlpha = 0.12f;
            } else if (distance == 3) {
                targetInactiveAlpha = 0.09f;
            } else {
                targetInactiveAlpha = 0.06f;
            }
        } else {
            targetInactiveAlpha = 0.6f;
        }

        final float targetAlpha = isActive ? 1.0f : targetInactiveAlpha;
        final float targetTranslationAlpha;
        if (!isAutoScrollEnabled) {
            targetTranslationAlpha = 0.6f;
        } else if (isLrcSynced) {
            targetTranslationAlpha = isActive ? 0.45f : targetInactiveAlpha;
        } else {
            targetTranslationAlpha = 0.6f;
        }

        final float targetVisibility = isActive ? 1.0f : 0.0f;
        final float targetScale;
        if (isActive || !isAutoScrollEnabled) {
            targetScale = 1.0f;
        } else if (isLrcSynced) {
            if (distance == 0 || distance == 1) {
                targetScale = 0.97f;
            } else if (distance == 2 || distance == 3) {
                targetScale = 0.93f;
            } else if (distance == 4) {
                targetScale = 0.90f;
            } else {
                targetScale = 0.85f;
            }
        } else {
            targetScale = 0.92f;
        }

        if (instant || !lineLookInitialized) {
            lineLookInitialized = true;
            if (lineLookAnimator != null) {
                lineLookAnimator.cancel();
                lineLookAnimator = null;
            }
            lookFromAlpha = lookToAlpha = lookCurAlpha = targetAlpha;
            lookFromTransAlpha = lookToTransAlpha = lookCurTransAlpha = targetTranslationAlpha;
            lookFromFocusedAlpha = lookToFocusedAlpha = lookCurFocusedAlpha = targetFocusedAlpha;
            lookFromVisibility = lookToVisibility = lookCurVisibility = targetVisibility;
            lookFromScale = lookToScale = lookCurScale = targetScale;
            applyCurrentLineLookToView();
            return;
        }

        if (Math.abs(lookToAlpha - targetAlpha) < 0.001f
                && Math.abs(lookToTransAlpha - targetTranslationAlpha) < 0.001f
                && Math.abs(lookToFocusedAlpha - targetFocusedAlpha) < 0.001f
                && Math.abs(lookToVisibility - targetVisibility) < 0.001f
                && Math.abs(lookToScale - targetScale) < 0.001f) {
            return;
        }

        if (lineLookAnimator != null) {
            lineLookAnimator.cancel();
            lineLookAnimator = null;
        }

        lookFromAlpha = lookCurAlpha;
        lookFromTransAlpha = lookCurTransAlpha;
        lookFromFocusedAlpha = lookCurFocusedAlpha;
        lookFromVisibility = lookCurVisibility;
        lookFromScale = lookCurScale;

        lookToAlpha = targetAlpha;
        lookToTransAlpha = targetTranslationAlpha;
        lookToFocusedAlpha = targetFocusedAlpha;
        lookToVisibility = targetVisibility;
        lookToScale = targetScale;

        final int alphaMs = Math.max(1, (int) (750L + staggerDelayMs));
        final int scaleMs = Math.max(1, (int) (800L + staggerDelayMs * 2L));
        final int totalMs = Math.max(Math.max(alphaMs, scaleMs), LINE_VISIBILITY_MS);

        lineLookAnimator = ValueAnimator.ofFloat(0f, (float) totalMs);
        lineLookAnimator.setDuration(totalMs);
        lineLookAnimator.setInterpolator(new LinearInterpolator());
        lineLookAnimator.addUpdateListener(anim -> {
            float clockMs = (Float) anim.getAnimatedValue();
            float fAlpha = FAST_OUT_SLOW_IN.getInterpolation(Math.max(0f, Math.min(1f, clockMs / alphaMs)));
            float fVis = FAST_OUT_SLOW_IN.getInterpolation(Math.max(0f, Math.min(1f, clockMs / LINE_VISIBILITY_MS)));
            float fScale = FAST_OUT_SLOW_IN.getInterpolation(Math.max(0f, Math.min(1f, clockMs / scaleMs)));

            lookCurAlpha = lookFromAlpha + (lookToAlpha - lookFromAlpha) * fAlpha;
            lookCurTransAlpha = lookFromTransAlpha + (lookToTransAlpha - lookFromTransAlpha) * fAlpha;
            lookCurFocusedAlpha = lookFromFocusedAlpha + (lookToFocusedAlpha - lookFromFocusedAlpha) * fAlpha;
            lookCurVisibility = lookFromVisibility + (lookToVisibility - lookFromVisibility) * fVis;
            lookCurScale = lookFromScale + (lookToScale - lookFromScale) * fScale;

            applyCurrentLineLookToView();
        });
        lineLookAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                lineLookAnimator = null;
                lookCurAlpha = lookToAlpha;
                lookCurTransAlpha = lookToTransAlpha;
                lookCurFocusedAlpha = lookToFocusedAlpha;
                lookCurVisibility = lookToVisibility;
                lookCurScale = lookToScale;
                applyCurrentLineLookToView();
            }
        });
        lineLookAnimator.start();
    }

    // align lyric scale pivot with line text alignment (left / right).
    // if forced at center (0.5w), inactive lyric when scaling down get pulled toward center and look crooked
    private float computeLinePivotX(int w) {
        if (isRightAligned) {
            return w - getPaddingRight();
        } else {
            return getPaddingLeft();
        }
    }

    private void applyCurrentLineLookToView() {
        if (!isSecondary) {
            if (getWidth() > 0 && getHeight() > 0) {
                setPivotX(computeLinePivotX(getWidth()));
                setPivotY(getHeight() * 0.5f);
            }
            setScaleX(lookCurScale);
            setScaleY(lookCurScale);
            if (getAlpha() != 1.0f) {
                setAlpha(1.0f);
            }
            if (!hasWordUnits()) {
                int baseCol = lineTextColor();
                setTextColor(withAlpha(baseCol, lookCurAlpha));
                updateSpanColorsForLineLook(baseCol);
            }
        }
        invalidate();
    }

    public void resetInnerFlips() {
        translationFlip.reset(Float.NaN);
        translationFlipOffsetY = 0f;
    }

    public boolean syncInnerFlip(
            @NonNull LyricsScrollEngine engine,
            int rowIndex,
            boolean enabled,
            float now) {
        if (transLayout != null) {
            float transTop = totalMeasuredHeight - transLayout.getHeight();
            engine.onInnerPlaced(translationFlip, rowIndex, transTop, enabled);
            translationFlipOffsetY = enabled ? translationFlip.translation(now) : 0f;
            boolean active = translationFlip.hasActiveLayers(now);
            if (active || Math.abs(translationFlipOffsetY) > 0.1f) {
                invalidate();
            }
            return active;
        } else {
            translationFlip.reset(Float.NaN);
            translationFlipOffsetY = 0f;
            return false;
        }
    }

    public boolean updateInnerFlipTranslation(float now) {
        if (transLayout == null) {
            translationFlipOffsetY = 0f;
            return false;
        }
        float newOff = translationFlip.translation(now);
        if (Math.abs(newOff - translationFlipOffsetY) > 0.1f) {
            translationFlipOffsetY = newOff;
            invalidate();
        }
        return translationFlip.hasActiveLayers(now);
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
    private boolean secondaryLayoutVisible = true;
    private int lastSecondaryReportedH = -1;
    @Nullable
    private ValueAnimator secondaryExpandAnimator;

    public void setSecondaryExpandFraction(float fraction) {
        float clamped = Math.max(0f, Math.min(1f, fraction));
        this.secondaryExpandFraction = clamped;
        boolean shouldShowLayout = clamped > 0.001f;
        float revealScale = 0.8f + 0.2f * clamped;
        setPivotX(isGravityRightAligned() ? getWidth() : 0f);
        setPivotY(0f);
        setScaleX(revealScale);
        setScaleY(revealScale);
        if (this.secondaryLayoutVisible != shouldShowLayout) {
            this.secondaryLayoutVisible = shouldShowLayout;
            requestLayout();
        }
        invalidate();
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
        if (Math.abs(secondaryExpandFraction - target) < 0.001f && secondaryLayoutVisible == expand) {
            if (!expand) {
                if (getVisibility() != View.GONE) {
                    setVisibility(View.GONE);
                }
                setAlpha(0f);
            } else {
                if (getVisibility() != View.VISIBLE) {
                    setVisibility(View.VISIBLE);
                }
                setAlpha(0.8f);
                setScaleX(1f);
                setScaleY(1f);
            }
            return;
        }

        setPivotX(isGravityRightAligned() ? getWidth() : 0f);
        setPivotY(0f);
        float baseDuration = (scrollEngine != null)
                ? Math.max(1f, (float) scrollEngine.getScrollDurationMs())
                : 400f;

        if (expand) {
            secondaryLayoutVisible = true;
            if (getVisibility() != View.VISIBLE) {
                setVisibility(View.VISIBLE);
            }
            requestLayout();

            LyPlusSpring spring = new LyPlusSpring(baseDuration);
            long inScaleMs = Math.max(1L, Math.round(spring.totalMs));
            long inAlphaMs = Math.max(300L, Math.min(450L, Math.round(baseDuration * 0.95f)));
            final float startProgress = secondaryExpandFraction;
            final float startAlpha = getAlpha();

            secondaryExpandAnimator = ValueAnimator.ofFloat(0f, (float) inScaleMs);
            secondaryExpandAnimator.setDuration(inScaleMs);
            secondaryExpandAnimator.setInterpolator(new LinearInterpolator());
            secondaryExpandAnimator.addUpdateListener(animation -> {
                float tMs = (Float) animation.getAnimatedValue();
                float scaleProgress = startProgress + (1f - startProgress) * spring.value(tMs);
                secondaryExpandFraction = Math.max(0f, Math.min(1f, scaleProgress));
                float scale = Math.max(0f, 0.8f + 0.2f * scaleProgress);
                setPivotX(isGravityRightAligned() ? getWidth() : 0f);
                setPivotY(0f);
                setScaleX(scale);
                setScaleY(scale);

                float alphaFrac = Math.max(0f, Math.min(1f, tMs / (float) inAlphaMs));
                float easedAlpha = LINEAR_OUT_SLOW_IN.getInterpolation(alphaFrac);
                setAlpha(startAlpha + (0.8f - startAlpha) * easedAlpha);
                invalidate();
            });
            secondaryExpandAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    secondaryExpandAnimator = null;
                    secondaryExpandFraction = 1f;
                    setAlpha(0.8f);
                    setScaleX(1f);
                    setScaleY(1f);
                }
            });
            secondaryExpandAnimator.start();
        } else {
            secondaryLayoutVisible = false;
            requestLayout();

            long outMs = Math.max(260L, Math.min(360L, Math.round(baseDuration * 0.8f)));
            final float startProgress = secondaryExpandFraction;
            final float startAlpha = getAlpha();

            secondaryExpandAnimator = ValueAnimator.ofFloat(0f, 1f);
            secondaryExpandAnimator.setDuration(outMs);
            secondaryExpandAnimator.setInterpolator(FAST_OUT_SLOW_IN);
            secondaryExpandAnimator.addUpdateListener(animation -> {
                float f = (Float) animation.getAnimatedValue();
                float val = startProgress * (1f - f);
                secondaryExpandFraction = val;
                float scale = Math.max(0f, 0.8f + 0.2f * val);
                setPivotX(isGravityRightAligned() ? getWidth() : 0f);
                setPivotY(0f);
                setScaleX(scale);
                setScaleY(scale);
                setAlpha(startAlpha * (1f - f));
                invalidate();
            });
            secondaryExpandAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    secondaryExpandAnimator = null;
                    secondaryExpandFraction = 0f;
                    setVisibility(View.GONE);
                    setAlpha(0f);
                }
            });
            secondaryExpandAnimator.start();
        }
    }

    private boolean isRightAligned = false;

    public void setIsRightAligned(boolean rightAligned) {
        this.isRightAligned = rightAligned;
    }

    public boolean isRightAligned() {
        return isRightAligned;
    }

    boolean isGravityRightAligned() {
        return isRightAligned;
    }

    public static Layout.Alignment resolveAlignment(boolean isRtl, boolean isRightAligned) {
        if (isRightAligned) {
            return isRtl ? Layout.Alignment.ALIGN_NORMAL : Layout.Alignment.ALIGN_OPPOSITE;
        } else {
            return isRtl ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;
        }
    }

    @Nullable
    private ValueAnimator scaleAnimator;

    public void animateScale(float targetScale, long duration, float pivotX, float pivotY) {
        setPivotX(pivotX);
        setPivotY(pivotY);
        if (scaleAnimator != null) {
            scaleAnimator.cancel();
            scaleAnimator = null;
        }
        if (Math.abs(getScaleX() - targetScale) < 0.001f) {
            setScaleX(targetScale);
            setScaleY(targetScale);
            return;
        }
        scaleAnimator = ValueAnimator.ofFloat(getScaleX(), targetScale);
        scaleAnimator.setDuration(duration);
        scaleAnimator.setInterpolator(FAST_OUT_SLOW_IN);
        scaleAnimator.addUpdateListener(anim -> {
            float s = (Float) anim.getAnimatedValue();
            setScaleX(s);
            setScaleY(s);
        });
        scaleAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                scaleAnimator = null;
            }
        });
        scaleAnimator.start();
    }

    public void cancelScaleAnimation() {
        if (scaleAnimator != null) {
            scaleAnimator.cancel();
            scaleAnimator = null;
        }
        if (lineLookAnimator != null) {
            lineLookAnimator.cancel();
            lineLookAnimator = null;
        }
    }

    private RenderEffect currentRenderEffect;
    private float currentBlurRadius = 0f;
    @Nullable
    private ValueAnimator blurAnimator;

    public void setRenderEffectCompat(@Nullable RenderEffect effect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (this.currentRenderEffect == effect) {
                return;
            }
            this.currentRenderEffect = effect;
            super.setRenderEffect(effect);
        }
    }

    public void animateBlur(float targetRadius, long duration) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        if (blurAnimator != null) {
            blurAnimator.cancel();
            blurAnimator = null;
        }
        if (Math.abs(currentBlurRadius - targetRadius) < 0.1f) {
            setBlurRadius(targetRadius);
            return;
        }
        blurAnimator = ValueAnimator.ofFloat(currentBlurRadius, targetRadius);
        blurAnimator.setDuration(duration);
        blurAnimator.setInterpolator(TRANSITION_INTERPOLATOR);
        blurAnimator.addUpdateListener(anim -> {
            float r = (Float) anim.getAnimatedValue();
            setBlurRadius(r);
        });
        blurAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                blurAnimator = null;
                setBlurRadius(targetRadius);
            }
        });
        blurAnimator.start();
    }

    public void setBlurRadius(float radius) {
        this.currentBlurRadius = radius;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (radius < 0.5f) {
                setRenderEffectCompat(null);
            } else {
                setRenderEffectCompat(LyricsPanelView.getCachedBlurEffect(radius));
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (scaleAnimator != null) {
            scaleAnimator.cancel();
            scaleAnimator = null;
        }
        if (lineLookAnimator != null) {
            lineLookAnimator.cancel();
            lineLookAnimator = null;
        }
        if (blurAnimator != null) {
            blurAnimator.cancel();
            blurAnimator = null;
        }
        if (secondaryExpandAnimator != null) {
            secondaryExpandAnimator.cancel();
            secondaryExpandAnimator = null;
        }
        releaseAppleAnimator();
    }

    private Layout cachedLayout;
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
    float lastTouchY;

    LyricsLineView(Context context) {
        super(context);
    }

    private static boolean sameWordUnits(@Nullable List<WordUnit> a, @Nullable List<WordUnit> b) {
        if (a == b) return true;
        boolean aEmpty = (a == null || a.isEmpty());
        boolean bEmpty = (b == null || b.isEmpty());
        if (aEmpty && bEmpty) return true;
        if (aEmpty != bEmpty || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            WordUnit ua = a.get(i);
            WordUnit ub = b.get(i);
            if (ua.startMs != ub.startMs || ua.endMs != ub.endMs || ua.endsWithSpace != ub.endsWithSpace) {
                return false;
            }
            if (!Objects.equals(ua.kanji, ub.kanji) || !Objects.equals(ua.romaji, ub.romaji)) {
                return false;
            }
        }
        return true;
    }

    void setWordUnits(@Nullable List<WordUnit> units) {
        List<WordUnit> normalized = (units != null && !units.isEmpty()) ? units : null;
        if (sameWordUnits(this.wordUnits, normalized) && this.appleAnimator != null && this.cachedLayoutWidth > 0) {
            this.wordUnits = normalized;
            this.cachedHasWordTimings = computeHasWordTimings();
            invalidate();
            return;
        }
        releaseAppleAnimator();
        this.wordUnits = normalized;
        this.cachedHasWordTimings = computeHasWordTimings();
        this.cachedLayoutWidth = -1;
        requestLayout();
        invalidate();
    }

    // when translate button toggled, only rebuild translation text layout.
    // no need rebuild word animator from scratch so zero lag/frame drop while enjoying music.
    private void rebuildTranslationLayoutOnly(int wrapWidth) {
        if (translationText != null && !translationText.isEmpty() && wrapWidth > 0) {
            TextPaint mainP = getPaint();
            int accentColor = (sungColor != 0) ? sungColor : lineTextColor();
            transPaint.setTextSize(mainP.getTextSize() * 0.7f);
            transPaint.setColor(accentColor);
            WordAnimationUtils.applyLyricsBoldPaint(transPaint);

            boolean isTransRtl = WordAnimationUtils.isRtlText(translationText);
            Layout.Alignment align = resolveAlignment(isTransRtl, isRightAligned());

            StaticLayout.Builder tb = StaticLayout.Builder
                    .obtain(translationText, 0, translationText.length(), transPaint, wrapWidth)
                    .setAlignment(align)
                    .setLineSpacing(Dim.dp(3), 1.15f)
                    .setIncludePad(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                tb.setUseLineSpacingFromFallbacks(true);
            }
            transLayout = tb.build();
            transLayoutWidth = wrapWidth;
            totalMeasuredHeight = Math.round(lyricsContentHeight + Dim.dp(4f) + transLayout.getHeight());
        } else {
            transLayout = null;
            totalMeasuredHeight = Math.round(lyricsContentHeight);
        }
    }

    void setTranslationText(@Nullable String text) {
        String normalized = (text != null && !text.trim().isEmpty()) ? text.trim() : null;
        if (Objects.equals(this.translationText, normalized)) {
            return;
        }
        this.translationText = normalized;
        this.transLayout = null;
        if (this.cachedLayoutWidth > 0 && (this.appleAnimator != null || (this.wordUnits != null && !this.wordUnits.isEmpty()))) {
            rebuildTranslationLayoutOnly(this.cachedLayoutWidth);
            requestLayout();
            invalidate();
            return;
        }
        this.cachedLayoutWidth = -1;
        requestLayout();
        invalidate();
    }

    @Nullable
    public static PrebuiltWordLayout buildPrebuiltWordLayout(
            int availableWidth,
            @NonNull TextPaint baseMainPaint,
            boolean isRightAligned,
            boolean isSecondary,
            @NonNull LyricsAnimationStyle animationStyle,
            int accentColor,
            @Nullable Long adjustedEndTimeMs,
            @Nullable LyricsLine rawLyricsLine,
            @Nullable LyricsLine rawRomanizedLine,
            @Nullable List<WordUnit> wordUnits,
            @Nullable String translationText,
            boolean prewarmMasks) {
        if (availableWidth <= 0 || animationStyle == LyricsAnimationStyle.NONE) {
            return null;
        }
        boolean hasUnitsTimings = false;
        boolean hasUnitsRomaji = false;
        if (wordUnits != null) {
            for (int i = 0; i < wordUnits.size(); i++) {
                WordUnit u = wordUnits.get(i);
                if (u.startMs != LyricsLine.NO_TIME) hasUnitsTimings = true;
                if (u.romaji != null && !u.romaji.trim().isEmpty() && !WordAnimationUtils.isCJK(u.romaji)) {
                    hasUnitsRomaji = true;
                }
            }
        }
        boolean hasTimings = (rawLyricsLine != null && rawLyricsLine.hasWords()) || hasUnitsTimings;
        if (!hasTimings) {
            return null;
        }

        TextPaint mainP = new TextPaint(baseMainPaint);
        WordAnimationUtils.applyLyricsBoldPaint(mainP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mainP.setFontFeatureSettings("\"liga\" 0, \"clig\" 0, \"dlig\" 0");
        }

        boolean hasRomaji = hasUnitsRomaji;
        if (!hasRomaji && rawRomanizedLine != null && rawRomanizedLine.text() != null
                && !rawRomanizedLine.text().trim().isEmpty()
                && !WordAnimationUtils.isCJK(rawRomanizedLine.text())) {
            hasRomaji = true;
        }

        int wrapWidth = Math.max(1, availableWidth);
        String builtMainText;
        List<WordTimestamp> rawWords;
        List<WordTimestamp> rawRomWords;
        String romLineText = null;

        if (rawRomanizedLine != null && rawRomanizedLine.text() != null) {
            String candidateRom = isSecondary
                    ? LyricsPanelView.stripParentheses(rawRomanizedLine.text()).trim()
                    : rawRomanizedLine.text().trim();
            if (!candidateRom.isEmpty() && !WordAnimationUtils.isCJK(candidateRom)) {
                romLineText = candidateRom;
            }
        }
        if ((romLineText == null || romLineText.isEmpty()) && hasUnitsRomaji && wordUnits != null) {
            StringBuilder rSb = new StringBuilder();
            for (int i = 0; i < wordUnits.size(); i++) {
                String ur = wordUnits.get(i).romaji;
                if (ur != null && !ur.trim().isEmpty() && !WordAnimationUtils.isCJK(ur)) {
                    if (rSb.length() > 0) rSb.append(' ');
                    rSb.append(ur.trim());
                }
            }
            if (rSb.length() > 0) {
                romLineText = rSb.toString();
            }
        }

        boolean preferSegmentedWordUnits = hasRomaji
                && wordUnits != null
                && !wordUnits.isEmpty()
                && hasUnitsTimings
                && hasUnitsRomaji
                && rawLyricsLine != null
                && (!rawLyricsLine.hasWords() || rawLyricsLine.words().size() <= 1)
                && WordAnimationUtils.isCJK(rawLyricsLine.text());

        if (preferSegmentedWordUnits) {
            String rawText = rawLyricsLine.text() != null ? rawLyricsLine.text() : "";
            rawText = isSecondary ? LyricsPanelView.stripParentheses(rawText).trim() : rawText.trim();
            if (rawText.isEmpty()) {
                StringBuilder mainSb = new StringBuilder();
                for (int i = 0; i < wordUnits.size(); i++) {
                    WordUnit u = wordUnits.get(i);
                    mainSb.append(u.kanji);
                    if (u.endsWithSpace && i < wordUnits.size() - 1) {
                        mainSb.append(' ');
                    }
                }
                rawText = mainSb.toString();
            }
            builtMainText = rawText;
            rawWords = WordTimestamp.fromWordUnits(wordUnits, isSecondary);
            rawRomWords = WordTimestamp.buildRomanizedWords(wordUnits, isSecondary);
        } else if (rawLyricsLine != null && rawLyricsLine.hasWords()) {
            String rawText = rawLyricsLine.text() != null ? rawLyricsLine.text() : "";
            rawText = isSecondary ? LyricsPanelView.stripParentheses(rawText).trim() : rawText.trim();
            rawWords = WordTimestamp.fromLyricsLine(rawLyricsLine, isSecondary);
            if (rawText.isEmpty() && !rawWords.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < rawWords.size(); i++) {
                    WordTimestamp wt = rawWords.get(i);
                    sb.append(wt.text);
                    if (wt.hasTrailingSpace && i < rawWords.size() - 1) {
                        sb.append(' ');
                    }
                }
                rawText = sb.toString();
            }
            builtMainText = rawText;
            rawRomWords = hasRomaji
                    ? WordTimestamp.buildRomanizedWordsFromLines(rawLyricsLine, rawRomanizedLine, wordUnits, isSecondary)
                    : null;
        } else {
            StringBuilder mainSb = new StringBuilder();
            if (wordUnits != null) {
                for (int i = 0; i < wordUnits.size(); i++) {
                    WordUnit u = wordUnits.get(i);
                    mainSb.append(u.kanji);
                    if (u.endsWithSpace && i < wordUnits.size() - 1) {
                        mainSb.append(' ');
                    }
                }
            }
            builtMainText = mainSb.toString();
            rawWords = WordTimestamp.fromWordUnits(wordUnits, isSecondary);
            rawRomWords = hasRomaji ? WordTimestamp.buildRomanizedWords(wordUnits, isSecondary) : null;
        }

        boolean isMainRtl = WordAnimationUtils.isRtlText(builtMainText);
        Layout.Alignment mainAlign = resolveAlignment(isMainRtl, isRightAligned);
        Layout.Alignment subAlign = resolveAlignment(false, isRightAligned);

        boolean showSylRom = hasRomaji
                && (rawRomWords != null || (romLineText != null && !romLineText.isEmpty()));

        AppleWordAnimator builtAnimator = new AppleWordAnimator(
                animationStyle,
                builtMainText,
                rawWords,
                rawRomWords,
                romLineText,
                showSylRom,
                isSecondary,
                adjustedEndTimeMs,
                mainP,
                mainAlign,
                wrapWidth,
                accentColor,
                prewarmMasks);
        int contentH = builtAnimator.measuredHeight;

        StaticLayout builtRomFallback = null;
        if (hasRomaji && !builtAnimator.hasSyllableRomanization
                && romLineText != null && !romLineText.isEmpty()
                && !romLineText.equalsIgnoreCase(builtMainText.trim())) {
            TextPaint rfPaint = new TextPaint(mainP);
            rfPaint.setTextSize(mainP.getTextSize() * 0.7f);
            WordAnimationUtils.applyLyricsBoldPaint(rfPaint);
            rfPaint.setColor(accentColor);

            StaticLayout.Builder rb = StaticLayout.Builder
                    .obtain(romLineText, 0, romLineText.length(), rfPaint, wrapWidth)
                    .setAlignment(subAlign)
                    .setLineSpacing(Dim.dp(2), 1.15f)
                    .setIncludePad(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                rb.setUseLineSpacingFromFallbacks(true);
            }
            builtRomFallback = rb.build();
            contentH += Dim.dp(4f) + builtRomFallback.getHeight();
        }

        StaticLayout builtTrans = null;
        int totalH = contentH;
        String cleanTrans = (translationText != null && !translationText.trim().isEmpty())
                ? translationText.trim() : null;
        if (cleanTrans != null) {
            TextPaint tPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            tPaint.setTextSize(mainP.getTextSize() * 0.7f);
            tPaint.setColor(accentColor);
            WordAnimationUtils.applyLyricsBoldPaint(tPaint);

            boolean isTransRtl = WordAnimationUtils.isRtlText(cleanTrans);
            Layout.Alignment transAlign = resolveAlignment(isTransRtl, isRightAligned);

            StaticLayout.Builder tb = StaticLayout.Builder
                    .obtain(cleanTrans, 0, cleanTrans.length(), tPaint, wrapWidth)
                    .setAlignment(transAlign)
                    .setLineSpacing(Dim.dp(3), 1.15f)
                    .setIncludePad(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                tb.setUseLineSpacingFromFallbacks(true);
            }
            builtTrans = tb.build();
            totalH = Math.round(contentH + Dim.dp(4f) + builtTrans.getHeight());
        }

        return new PrebuiltWordLayout(
                wrapWidth,
                builtAnimator,
                builtRomFallback,
                builtTrans,
                contentH,
                totalH);
    }

    void applyPrebuiltWordLayout(
            @Nullable LyricsLine mainLine,
            @Nullable LyricsLine romanizedLine,
            @Nullable List<WordUnit> units,
            @Nullable String transText,
            @NonNull PrebuiltWordLayout prebuilt) {
        releaseAppleAnimator();
        this.rawLyricsLine = mainLine;
        this.rawRomanizedLine = romanizedLine;
        this.wordUnits = (units != null && !units.isEmpty()) ? units : null;
        this.cachedHasWordTimings = computeHasWordTimings();
        this.translationText = (transText != null && !transText.trim().isEmpty()) ? transText.trim() : null;
        this.appleAnimator = prebuilt.appleAnimator;
        if (this.appleAnimator != null) {
            this.appleAnimator.setPrewarmMasks(this.prewarmMasks);
        }
        this.romajiFallbackLayout = prebuilt.romajiFallbackLayout;
        if (this.romajiFallbackLayout != null) {
            TextPaint mainP = getPaint();
            romajiFallbackPaint.set(mainP);
            romajiFallbackPaint.setTextSize(mainP.getTextSize() * 0.7f);
            WordAnimationUtils.applyLyricsBoldPaint(romajiFallbackPaint);
        }
        this.transLayout = prebuilt.transLayout;
        if (this.transLayout != null) {
            TextPaint mainP = getPaint();
            transPaint.setTextSize(mainP.getTextSize() * 0.7f);
            WordAnimationUtils.applyLyricsBoldPaint(transPaint);
            this.transLayoutWidth = prebuilt.wrapWidth;
        }
        this.lyricsContentHeight = prebuilt.lyricsContentHeight;
        this.totalMeasuredHeight = prebuilt.totalMeasuredHeight;
        this.cachedLayoutWidth = prebuilt.wrapWidth;
        this.lastDrawnClockPos = Long.MIN_VALUE;
        this.allSung = false;
        requestLayout();
        invalidate();
    }

    public boolean hasWordUnits() {
        return (wordUnits != null && !wordUnits.isEmpty())
                || (rawLyricsLine != null && rawLyricsLine.hasWords());
    }

    public long getLastWordEndMs() {
        if (appleAnimator != null && appleAnimator.effectiveEndTimeMs > 0L) {
            return appleAnimator.effectiveEndTimeMs;
        }
        if (rawLyricsLine != null && rawLyricsLine.hasWords()) {
            long maxEnd = LyricsLine.NO_TIME;
            List<Word> words = rawLyricsLine.words();
            for (int i = 0; i < words.size(); i++) {
                long e = words.get(i).endMs();
                if (e != LyricsLine.NO_TIME) {
                    maxEnd = (maxEnd == LyricsLine.NO_TIME) ? e : Math.max(maxEnd, e);
                }
            }
            if (maxEnd != LyricsLine.NO_TIME) return maxEnd;
        }
        if (adjustedEndTimeMs != null && adjustedEndTimeMs > 0L) {
            return adjustedEndTimeMs;
        }
        if (wordUnits == null || wordUnits.isEmpty()) {
            return LyricsLine.NO_TIME;
        }
        long maxEnd = LyricsLine.NO_TIME;
        for (int i = 0; i < wordUnits.size(); i++) {
            WordUnit u = wordUnits.get(i);
            if (u.endMs != LyricsLine.NO_TIME) {
                if (maxEnd == LyricsLine.NO_TIME || u.endMs > maxEnd) {
                    maxEnd = u.endMs;
                }
            }
        }
        return maxEnd;
    }

    private void layoutWordUnits(int availableWidth) {
        if (!hasWordUnits() || availableWidth <= 0) {
            return;
        }
        if (availableWidth == cachedLayoutWidth) {
            return;
        }
        cachedLayoutWidth = availableWidth;

        TextPaint mainP = getPaint();
        WordAnimationUtils.applyLyricsBoldPaint(mainP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mainP.setFontFeatureSettings("\"liga\" 0, \"clig\" 0, \"dlig\" 0");
        }
        romajiPaint.setTextSize(mainP.getTextSize() * 0.7f);
        WordAnimationUtils.applyLyricsBoldPaint(romajiPaint);

        unsungKanjiPaint.set(mainP);
        unsungKanjiPaint.clearShadowLayer();
        WordAnimationUtils.applyLyricsBoldPaint(unsungKanjiPaint);
        sungKanjiPaint.set(mainP);
        sungKanjiPaint.clearShadowLayer();
        WordAnimationUtils.applyLyricsBoldPaint(sungKanjiPaint);

        unsungRomajiPaint.set(romajiPaint);
        unsungRomajiPaint.clearShadowLayer();
        WordAnimationUtils.applyLyricsBoldPaint(unsungRomajiPaint);
        sungRomajiPaint.set(romajiPaint);
        sungRomajiPaint.clearShadowLayer();
        WordAnimationUtils.applyLyricsBoldPaint(sungRomajiPaint);

        boolean hasRomaji = wordUnitsHaveRomaji();
        if (!hasRomaji && rawRomanizedLine != null && rawRomanizedLine.text() != null
                && !rawRomanizedLine.text().trim().isEmpty()
                && !WordAnimationUtils.isCJK(rawRomanizedLine.text())) {
            hasRomaji = true;
        }

        final boolean isRightAligned = isGravityRightAligned();
        int wrapWidth = Math.max(1, availableWidth);

        releaseAppleAnimator();
        romajiFallbackLayout = null;

        if (cachedHasWordTimings && animationStyle != LyricsAnimationStyle.NONE) {
            String builtMainText;
            List<WordTimestamp> rawWords;
            List<WordTimestamp> rawRomWords;
            String romLineText = null;

            if (rawRomanizedLine != null && rawRomanizedLine.text() != null) {
                String candidateRom = isSecondary
                        ? LyricsPanelView.stripParentheses(rawRomanizedLine.text()).trim()
                        : rawRomanizedLine.text().trim();
                if (!candidateRom.isEmpty() && !WordAnimationUtils.isCJK(candidateRom)) {
                    romLineText = candidateRom;
                }
            }
            if ((romLineText == null || romLineText.isEmpty()) && wordUnitsHaveRomaji() && wordUnits != null) {
                StringBuilder rSb = new StringBuilder();
                for (int i = 0; i < wordUnits.size(); i++) {
                    String ur = wordUnits.get(i).romaji;
                    if (ur != null && !ur.trim().isEmpty() && !WordAnimationUtils.isCJK(ur)) {
                        if (rSb.length() > 0) rSb.append(' ');
                        rSb.append(ur.trim());
                    }
                }
                if (rSb.length() > 0) {
                    romLineText = rSb.toString();
                }
            }

            boolean preferSegmentedWordUnits = hasRomaji
                    && wordUnits != null
                    && !wordUnits.isEmpty()
                    && wordUnitsHaveTimings()
                    && wordUnitsHaveRomaji()
                    && rawLyricsLine != null
                    && (!rawLyricsLine.hasWords() || rawLyricsLine.words().size() <= 1)
                    && WordAnimationUtils.isCJK(rawLyricsLine.text());

            if (preferSegmentedWordUnits) {
                String rawText = rawLyricsLine.text() != null ? rawLyricsLine.text() : "";
                rawText = isSecondary ? LyricsPanelView.stripParentheses(rawText).trim() : rawText.trim();
                if (rawText.isEmpty()) {
                    StringBuilder mainSb = new StringBuilder();
                    for (int i = 0; i < wordUnits.size(); i++) {
                        WordUnit u = wordUnits.get(i);
                        mainSb.append(u.kanji);
                        if (u.endsWithSpace && i < wordUnits.size() - 1) {
                            mainSb.append(' ');
                        }
                    }
                    rawText = mainSb.toString();
                }
                builtMainText = rawText;
                rawWords = WordTimestamp.fromWordUnits(wordUnits, isSecondary);
                rawRomWords = WordTimestamp.buildRomanizedWords(wordUnits, isSecondary);
            } else if (rawLyricsLine != null && rawLyricsLine.hasWords()) {
                String rawText = rawLyricsLine.text() != null ? rawLyricsLine.text() : "";
                if (isSecondary) {
                    rawText = LyricsPanelView.stripParentheses(rawText).trim();
                } else {
                    rawText = rawText.trim();
                }
                rawWords = WordTimestamp.fromLyricsLine(rawLyricsLine, isSecondary);
                if (rawText.isEmpty() && !rawWords.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < rawWords.size(); i++) {
                        WordTimestamp wt = rawWords.get(i);
                        sb.append(wt.text);
                        if (wt.hasTrailingSpace && i < rawWords.size() - 1) {
                            sb.append(' ');
                        }
                    }
                    rawText = sb.toString();
                }
                builtMainText = rawText;
                rawRomWords = hasRomaji
                        ? WordTimestamp.buildRomanizedWordsFromLines(rawLyricsLine, rawRomanizedLine, wordUnits, isSecondary)
                        : null;
            } else {
                StringBuilder mainSb = new StringBuilder();
                if (wordUnits != null) {
                    for (int i = 0; i < wordUnits.size(); i++) {
                        WordUnit u = wordUnits.get(i);
                        mainSb.append(u.kanji);
                        if (u.endsWithSpace && i < wordUnits.size() - 1) {
                            mainSb.append(' ');
                        }
                    }
                }
                builtMainText = mainSb.toString();
                rawWords = WordTimestamp.fromWordUnits(wordUnits, isSecondary);
                rawRomWords = hasRomaji ? WordTimestamp.buildRomanizedWords(wordUnits, isSecondary) : null;
            }

            boolean isRtl = WordAnimationUtils.isRtlText(builtMainText);
            Layout.Alignment mainAlign = resolveAlignment(isRtl, isRightAligned);
            Layout.Alignment subAlign = resolveAlignment(false, isRightAligned);
            boolean isTransRtl = translationText != null && WordAnimationUtils.isRtlText(translationText);
            Layout.Alignment transAlign = resolveAlignment(isTransRtl, isRightAligned);

            int accentColor = (sungColor != 0) ? sungColor : lineTextColor();
            Long adjEnd = adjustedEndTimeMs;
            if (adjEnd == null) {
                long lastEnd = getLastWordEndMs();
                if (lastEnd != LyricsLine.NO_TIME) {
                    adjEnd = lastEnd;
                }
            }
            boolean showSylRom = hasRomaji
                    && (rawRomWords != null || (romLineText != null && !romLineText.isEmpty()));

            appleAnimator = new AppleWordAnimator(
                    animationStyle,
                    builtMainText,
                    rawWords,
                    rawRomWords,
                    romLineText,
                    showSylRom,
                    isSecondary,
                    adjEnd,
                    mainP,
                    mainAlign,
                    wrapWidth,
                    accentColor,
                    prewarmMasks);
            lyricsContentHeight = appleAnimator.measuredHeight;

            if (hasRomaji && !appleAnimator.hasSyllableRomanization
                    && romLineText != null && !romLineText.isEmpty()
                    && !romLineText.equalsIgnoreCase(builtMainText.trim())) {
                romajiFallbackPaint.set(mainP);
                romajiFallbackPaint.setTextSize(mainP.getTextSize() * 0.7f);
                WordAnimationUtils.applyLyricsBoldPaint(romajiFallbackPaint);
                romajiFallbackPaint.setColor(accentColor);

                StaticLayout.Builder rb = StaticLayout.Builder
                        .obtain(romLineText, 0, romLineText.length(), romajiFallbackPaint, wrapWidth)
                        .setAlignment(subAlign)
                        .setLineSpacing(Dim.dp(2), 1.15f)
                        .setIncludePad(false);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    rb.setUseLineSpacingFromFallbacks(true);
                }
                romajiFallbackLayout = rb.build();
                lyricsContentHeight += Dim.dp(4f) + romajiFallbackLayout.getHeight();
            }

            if (translationText != null && !translationText.isEmpty()) {
                transPaint.setTextSize(mainP.getTextSize() * 0.7f);
                transPaint.setColor(accentColor);
                WordAnimationUtils.applyLyricsBoldPaint(transPaint);

                StaticLayout.Builder tb = StaticLayout.Builder
                        .obtain(translationText, 0, translationText.length(), transPaint, wrapWidth)
                        .setAlignment(transAlign)
                        .setLineSpacing(Dim.dp(3), 1.15f)
                        .setIncludePad(false);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    tb.setUseLineSpacingFromFallbacks(true);
                }
                transLayout = tb.build();
                transLayoutWidth = wrapWidth;

                totalMeasuredHeight = Math.round(lyricsContentHeight + Dim.dp(4f) + transLayout.getHeight());
            } else {
                transLayout = null;
                totalMeasuredHeight = Math.round(lyricsContentHeight);
            }
            return;
        }

        if (wordUnits == null || wordUnits.isEmpty()) {
            return;
        }

        Paint.FontMetrics kanjiFm = mainP.getFontMetrics();
        float kanjiHeight = kanjiFm.descent - kanjiFm.ascent;
        float kanjiBaseline = -kanjiFm.ascent;

        Paint.FontMetrics romajiFm = romajiPaint.getFontMetrics();
        float romajiHeight = romajiFm.descent - romajiFm.ascent;
        float romajiBaseline = -romajiFm.ascent;

        float romajiMargin = hasRomaji ? Dim.dp(4f) : 0f;
        float effectiveRomajiHeight = hasRomaji ? romajiHeight : 0f;
        float singleLineHeight = kanjiHeight + romajiMargin + effectiveRomajiHeight;
        float lineSpacing = hasRomaji
                ? (singleLineHeight + Dim.dp(6f))
                : (singleLineHeight * 1.15f + Dim.dp(4f));
        float baseSpaceWidth = mainP.measureText(" ");

        for (int i = 0; i < wordUnits.size(); i++) {
            WordUnit u = wordUnits.get(i);
            u.kanjiWidth = mainP.measureText(u.kanji);
            u.romajiWidth = (!u.romaji.isEmpty() && !WordAnimationUtils.isCJK(u.romaji))
                    ? romajiPaint.measureText(u.romaji) : 0f;
        }

        float curX = 0f;
        float curY = 0f;
        int currentLine = 0;
        for (int i = 0; i < wordUnits.size(); i++) {
            WordUnit u = wordUnits.get(i);
            float pillarW = Math.max(u.kanjiWidth, u.romajiWidth);
            if (curX > 0f && curX + pillarW > wrapWidth) {
                curX = 0f;
                currentLine++;
                curY += lineSpacing;
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
            curX += pillarW + (u.endsWithSpace ? baseSpaceWidth : 0f);
        }

        lyricsContentHeight = Math.round(curY + singleLineHeight);

        if (translationText != null && !translationText.isEmpty()) {
            transPaint.setTextSize(mainP.getTextSize() * 0.7f);
            transPaint.setColor(lineTextColor());
            WordAnimationUtils.applyLyricsBoldPaint(transPaint);

            Layout.Alignment align = isRightAligned
                    ? Layout.Alignment.ALIGN_OPPOSITE
                    : Layout.Alignment.ALIGN_NORMAL;

            StaticLayout.Builder tb = StaticLayout.Builder
                    .obtain(translationText, 0, translationText.length(), transPaint, wrapWidth)
                    .setAlignment(align)
                    .setLineSpacing(Dim.dp(3), 1.15f)
                    .setIncludePad(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                tb.setUseLineSpacingFromFallbacks(true);
            }
            transLayout = tb.build();
            transLayoutWidth = wrapWidth;

            totalMeasuredHeight = Math.round(curY + singleLineHeight + Dim.dp(4f) + transLayout.getHeight());
        } else {
            transLayout = null;
            totalMeasuredHeight = Math.round(curY + singleLineHeight);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (hasWordUnits() && ((cachedHasWordTimings && animationStyle != LyricsAnimationStyle.NONE)
                || (wordUnits != null && !wordUnits.isEmpty()))) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int padH = getCompoundPaddingLeft() + getCompoundPaddingRight();
            int availableW = Math.max(0, width - padH);
            if (availableW > 0) {
                layoutWordUnits(availableW);
            }
            int padV = getExtendedPaddingTop() + getExtendedPaddingBottom();
            int fullH = Math.max(totalMeasuredHeight + padV, getSuggestedMinimumHeight());
            int reportedH = (isSecondary && !secondaryLayoutVisible) ? 0 : fullH;
            if (isSecondary) {
                if (lastSecondaryReportedH >= 0 && lastSecondaryReportedH != reportedH && scrollEngine != null) {
                    scrollEngine.recordLayoutShift(this, reportedH - lastSecondaryReportedH);
                }
                lastSecondaryReportedH = reportedH;
            }
            setMeasuredDimension(width, reportedH);
            return;
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (isSecondary) {
            int fullH = getMeasuredHeight();
            int reportedH = secondaryLayoutVisible ? fullH : 0;
            if (lastSecondaryReportedH >= 0 && lastSecondaryReportedH != reportedH && scrollEngine != null) {
                scrollEngine.recordLayoutShift(this, reportedH - lastSecondaryReportedH);
            }
            lastSecondaryReportedH = reportedH;
            if (!secondaryLayoutVisible) {
                setMeasuredDimension(getMeasuredWidth(), 0);
            }
        }
    }

    private long lastDrawnClockPos = Long.MIN_VALUE;

    public void resetClockPos() {
        this.lastDrawnClockPos = Long.MIN_VALUE;
        this.allSung = false;
        if (appleAnimator != null) {
            appleAnimator.reset();
        }
        invalidate();
    }

    // monotonic clock guard: keep drawing clock position from ever going backwards suddenly.
    // if small jitter (< 600ms) on play or pause, clamp to last drawn position so words dont glitch jump.
    private long getLiveClockPositionMs() {
        long rawPos = LyricsManager.getInstance().getPositionMs();
        boolean isPlaying = LyricsManager.getInstance().isPlaying();
        if (lastDrawnClockPos != Long.MIN_VALUE) {
            if (!isPlaying) {
                rawPos = Math.max(lastDrawnClockPos, rawPos);
            } else if (rawPos < lastDrawnClockPos - 600L || (rawPos < 200L && lastDrawnClockPos >= 600L)) {
                lastDrawnClockPos = rawPos;
            } else {
                rawPos = Math.max(lastDrawnClockPos, rawPos);
            }
        }
        lastDrawnClockPos = rawPos;
        return rawPos;
    }

    private void onDrawWordUnits(Canvas canvas) {
        if (isSecondary && secondaryExpandFraction <= 0.001f && !secondaryLayoutVisible) {
            return;
        }

        final boolean active = isLineActive;

        canvas.save();
        canvas.translate(getCompoundPaddingLeft(), getExtendedPaddingTop());

        int baseSung = (sungColor != 0) ? sungColor : lineTextColor();
        int dynamicLineColor = isSecondary
                ? baseSung
                : withAlpha(baseSung, lookCurAlpha);
        float dynamicFocusedAlpha = lookCurFocusedAlpha;

        if (appleAnimator != null && cachedHasWordTimings && animationStyle != LyricsAnimationStyle.NONE) {
            long livePos = getLiveClockPositionMs();
            float speed = LyricsManager.getInstance().getPlaybackSpeed();
            boolean playing = LyricsManager.getInstance().isPlaying();

            if (isGravityRightAligned() && getWidth() > 0) {
                int availW = Math.max(0, getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight());
                float rightShift = Math.max(0f, availW - appleAnimator.measuredWidth);
                if (rightShift > 0f) {
                    canvas.save();
                    canvas.translate(rightShift, 0f);
                    appleAnimator.draw(canvas, livePos, active, allSung, baseSung, dynamicLineColor, dynamicFocusedAlpha, speed, playing);
                    canvas.restore();
                } else {
                    appleAnimator.draw(canvas, livePos, active, allSung, baseSung, dynamicLineColor, dynamicFocusedAlpha, speed, playing);
                }
            } else {
                appleAnimator.draw(canvas, livePos, active, allSung, baseSung, dynamicLineColor, dynamicFocusedAlpha, speed, playing);
            }

            drawRomajiFallbackLayout(canvas, dynamicLineColor);
            drawTranslationLayout(canvas, baseSung);
            canvas.restore();

            if ((active || appleAnimator.isStillAnimating()) && LyricsManager.getInstance().isPlaying()) {
                postInvalidateOnAnimation();
            }
            return;
        }

        if (wordUnits == null || wordUnits.isEmpty()) {
            canvas.restore();
            return;
        }

        sungKanjiPaint.setColor(dynamicLineColor);
        sungRomajiPaint.setColor(withAlpha(baseSung, lookCurAlpha * 0.85f));
        unsungKanjiPaint.setColor(dynamicLineColor);
        unsungRomajiPaint.setColor(withAlpha(baseSung, lookCurAlpha * 0.85f));

        final int wordCount = wordUnits.size();
        for (int i = 0; i < wordCount; i++) {
            WordUnit u = wordUnits.get(i);
            canvas.drawText(u.kanji, u.kanjiX, u.kanjiY, sungKanjiPaint);
            if (!u.romaji.isEmpty() && u.romajiWidth > 0f) {
                canvas.drawText(u.romaji, u.romajiX, u.romajiY, sungRomajiPaint);
            }
        }

        drawTranslationLayout(canvas, baseSung);
        canvas.restore();
    }

    private void drawRomajiFallbackLayout(Canvas canvas, int lineCol) {
        if (romajiFallbackLayout != null && appleAnimator != null) {
            float top = appleAnimator.measuredHeight + Dim.dp(4f);
            romajiFallbackPaint.setColor(lineCol);
            romajiFallbackLayout.getPaint().setColor(lineCol);
            int availW = Math.max(0, getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight());
            float rightShift = (isGravityRightAligned() && availW > romajiFallbackLayout.getWidth())
                    ? (availW - romajiFallbackLayout.getWidth()) : 0f;
            canvas.save();
            canvas.translate(rightShift, top);
            romajiFallbackLayout.draw(canvas);
            canvas.restore();
        }
    }

    private void drawTranslationLayout(Canvas canvas, int baseTextColor) {
        if (transLayout != null) {
            float transTop = totalMeasuredHeight - transLayout.getHeight() + translationFlipOffsetY;
            float tAlpha = isSecondary ? (0.45f * secondaryExpandFraction) : lookCurTransAlpha;
            int transCol = withAlpha(baseTextColor, tAlpha);
            transPaint.setColor(transCol);
            transLayout.getPaint().setColor(transCol);
            int availW = Math.max(0, getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight());
            float rightShift = (isGravityRightAligned() && availW > transLayout.getWidth())
                    ? (availW - transLayout.getWidth()) : 0f;
            canvas.save();
            canvas.translate(rightShift, transTop);
            transLayout.draw(canvas);
            canvas.restore();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (isSecondary) {
            setPivotX(isGravityRightAligned() ? w : 0f);
            setPivotY(0f);
        } else {
            setPivotX(computeLinePivotX(w));
            setPivotY(h * 0.5f);
        }
    }

    private void updateSpanColorsForLineLook(int baseCol) {
        CharSequence text = getText();
        if (!(text instanceof android.text.Spannable sp)) {
            return;
        }
        int len = sp.length();
        if (romaStart >= 0 && romaEnd > romaStart && romaEnd <= len) {
            ForegroundColorSpan[] romSpans = sp.getSpans(romaStart, romaEnd, ForegroundColorSpan.class);
            for (ForegroundColorSpan s : romSpans) {
                if (sp.getSpanStart(s) == romaStart && sp.getSpanEnd(s) == romaEnd) {
                    sp.removeSpan(s);
                }
            }
            sp.setSpan(new ForegroundColorSpan(withAlpha(baseCol, lookCurAlpha * 0.85f)),
                    romaStart, romaEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (transStart >= 0 && transEnd > transStart && transEnd <= len) {
            ForegroundColorSpan[] transSpans = sp.getSpans(transStart, transEnd, ForegroundColorSpan.class);
            for (ForegroundColorSpan s : transSpans) {
                if (sp.getSpanStart(s) == transStart && sp.getSpanEnd(s) == transEnd) {
                    sp.removeSpan(s);
                }
            }
            sp.setSpan(new ForegroundColorSpan(withAlpha(baseCol, lookCurTransAlpha)),
                    transStart, transEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    void setTranslationBounds(int start, int end) {
        transStart = start;
        transEnd = end;
    }

    void setRomanizationBounds(int start, int end) {
        romaStart = start;
        romaEnd = end;
        if (!hasWordUnits()) {
            updateSpanColorsForLineLook(lineTextColor());
        }
    }

    @Nullable
    String getCopyTextForTouch(float touchY) {
        if (transLayout != null && touchY >= lyricsContentHeight) {
            return translationText;
        }
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
        final boolean backwardSeek = (this.positionMs != Long.MIN_VALUE && posMs != Long.MIN_VALUE
                && (posMs < this.positionMs - 600L || (posMs < 200L && this.positionMs >= 600L)));

        this.wordTimings = timings != null ? timings : Collections.emptyList();
        this.cachedHasWordTimings = computeHasWordTimings();
        this.positionMs = posMs;
        if (posMs == Long.MIN_VALUE || backwardSeek) {
            this.lastDrawnClockPos = Long.MIN_VALUE;
            if (backwardSeek) {
                this.allSung = false;
                if (appleAnimator != null) {
                    appleAnimator.reset();
                }
            }
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
        } else if (!hasWordUnits()) {
            invalidate();
        } else if (sungChanged || colorChanged || (posMs != Long.MIN_VALUE)
                || (appleAnimator != null && appleAnimator.isStillAnimating())) {
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
        if (isSecondary && secondaryExpandFraction <= 0.001f && !secondaryLayoutVisible) {
            return;
        }
        if (hasWordUnits() && ((cachedHasWordTimings && animationStyle != LyricsAnimationStyle.NONE)
                || (wordUnits != null && !wordUnits.isEmpty()))) {
            onDrawWordUnits(canvas);
            return;
        }

        canvas.save();

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
        canvas.restore();
        canvas.restore();

        if (firstSungLine >= 0 && !allSung && LyricsManager.getInstance().isPlaying()) {
            postInvalidateOnAnimation();
        }
    }

    private static boolean isRightToLeftLine(Layout layout, int line) {
        return layout.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT;
    }

    private static float extendSung(float current, float edge, boolean rightToLeft) {
        if (Float.isNaN(current)) {
            return edge;
        }
        return rightToLeft ? Math.min(current, edge) : Math.max(current, edge);
    }
}