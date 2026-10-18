/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.WeakHashMap;

import app.morphe.extension.music.patches.lyrics.model.LyricsRetiming;
import app.morphe.extension.music.patches.lyrics.ui.SpringEasing.LyPlusSpring;
import app.morphe.extension.music.patches.lyrics.ui.SpringEasing.LyricsScrollStagger;

public final class LyricsScrollEngine {

    public interface Callback {
        boolean isUserScrolling();
        void setProgrammaticScrolling(boolean isProgrammatic);
        void onRecomputeAndScroll(int referenceIndex);
        default int computeReflowScrollTarget(@Nullable View targetRow) {
            return -1;
        }
    }

    public static final long LYRICS_SCROLL_LOCK_MS = 400L;
    public static final int LYRICS_FORCED_SCROLL_MS = 500;
    public static final float LYRICS_FAR_JUMP_VIEWPORTS = 2.0f;

    private static final long SUPPRESS_TAIL_MS = 250L;
    private static final long DEFAULT_FLIP_DURATION_MS = 400L;
    private static final float MIN_FLIP_DISTANCE_PX = 0.5f;
    private static final int SHIFT_RING = 32;

    private static final class FlipLayer {
        final float offset;
        final float startMs;
        final float waitMs;
        final float remainingMs;
        final float skipMs;
        final LyPlusSpring spring;

        FlipLayer(float offset, float startMs, float waitMs, float remainingMs, float skipMs, LyPlusSpring spring) {
            this.offset = offset;
            this.startMs = startMs;
            this.waitMs = waitMs;
            this.remainingMs = remainingMs;
            this.skipMs = skipMs;
            this.spring = spring;
        }

        float progress(float nowMs) {
            float elapsed = nowMs - startMs;
            if (elapsed <= waitMs) return 0f;
            float active = elapsed - waitMs;
            if (active >= remainingMs) return 1f;
            float f = active / Math.max(1f, remainingMs);
            return spring.value(skipMs + f * remainingMs);
        }

        boolean isDone(float nowMs) {
            return (nowMs - startMs) >= (waitMs + remainingMs);
        }

        float translation(float nowMs) {
            return offset * (1f - progress(nowMs));
        }
    }

    public static final class LyricsItemFlip {
        float lastTop = Float.NaN;
        long seenShiftSeq = 0L;
        final List<FlipLayer> layers = new ArrayList<>(4);

        public float translation(float nowMs) {
            if (layers.isEmpty()) return 0f;
            float sum = 0f;
            Iterator<FlipLayer> it = layers.iterator();
            while (it.hasNext()) {
                FlipLayer layer = it.next();
                if (layer.isDone(nowMs)) {
                    it.remove();
                } else {
                    sum += layer.translation(nowMs);
                }
            }
            return sum;
        }

        public boolean hasActiveLayers(float nowMs) {
            if (layers.isEmpty()) return false;
            Iterator<FlipLayer> it = layers.iterator();
            while (it.hasNext()) {
                if (it.next().isDone(nowMs)) {
                    it.remove();
                }
            }
            return !layers.isEmpty();
        }

        public void reset(float top) {
            lastTop = top;
            layers.clear();
        }

        public void add(float nowMs, float offset, float delayMs, @NonNull LyPlusSpring spring, float elapsedMs) {
            if (Math.abs(offset) < MIN_FLIP_DISTANCE_PX) return;
            float skip = Math.max(0f, elapsedMs - delayMs);
            float wait = Math.max(0f, delayMs - elapsedMs);
            if (skip >= spring.totalMs) return;
            float remaining = Math.max(1f, spring.totalMs - skip);
            layers.add(new FlipLayer(offset, nowMs, wait, remaining, skip, spring));
        }
    }

    private final ScrollView scrollView;
    private final LinearLayout linesContainer;
    private final View footerContainer;
    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final WeakHashMap<View, LyricsItemFlip> itemFlips = new WeakHashMap<>();

    private final long originNano = SystemClock.elapsedRealtimeNanos();
    private float suppressedUntilMs = 0f;
    @Nullable
    private LyricsScrollStagger currentStagger = null;
    private float txStartMs = 0f;
    private float txEndMs = 0f;
    private float txDeltaPx = 0f;
    private long lastDurationMs = DEFAULT_FLIP_DURATION_MS;

    private final int[] shiftIndex = new int[SHIFT_RING];
    private final float[] shiftDelta = new float[SHIFT_RING];
    private final long[] shiftSeqs = new long[SHIFT_RING];
    private long shiftSeq = 0L;

    private int lastTotalRowsHeight = -1;
    private int lastViewportHeight = -1;
    private long lastReflowShiftSeq = 0L;

    private boolean isLocked = false;
    private boolean hasPendingUpdate = false;
    private int pendingTargetIndex = -1;
    private int lastTargetRowIdx = -1;
    @Nullable
    private WeakReference<View> lastTargetRowRef = null;
    private boolean frameCallbackScheduled = false;

    @Nullable
    private ValueAnimator forcedScrollAnimator = null;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            frameCallbackScheduled = false;
            updateFlipTranslations();
        }
    };

    private final ViewTreeObserver.OnPreDrawListener preDrawListener = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            handlePreDrawReflowAndSync();
            return true;
        }
    };

    private final Runnable unlockRunnable = new Runnable() {
        @Override
        public void run() {
            isLocked = false;
            if (hasPendingUpdate) {
                hasPendingUpdate = false;
                final int targetIdx = pendingTargetIndex;
                pendingTargetIndex = -1;
                if (!callback.isUserScrolling()) {
                    callback.onRecomputeAndScroll(targetIdx);
                }
            }
        }
    };

    public LyricsScrollEngine(@NonNull ScrollView scrollView,
                              @NonNull LinearLayout linesContainer,
                              @NonNull View footerContainer,
                              @NonNull Callback callback) {
        this.scrollView = scrollView;
        this.linesContainer = linesContainer;
        this.footerContainer = footerContainer;
        this.callback = callback;
        this.linesContainer.getViewTreeObserver().addOnPreDrawListener(preDrawListener);
    }

    public float nowMs() {
        return (SystemClock.elapsedRealtimeNanos() - originNano) / 1_000_000f;
    }

    public boolean isAnimating() {
        return isLocked || (forcedScrollAnimator != null && forcedScrollAnimator.isRunning());
    }

    public long getScrollDurationMs() {
        return lastDurationMs;
    }

    public int getLastTargetRowIdx() {
        return lastTargetRowIdx;
    }

    public long computeStaggerLeadMs(long scrollLookAheadMs, int anchorPx) {
        if (scrollView.getHeight() <= 0 || linesContainer.getChildCount() <= 0) return 0L;
        int visibleTop = scrollView.getScrollY();
        List<View> validRows = collectValidRows();
        int stepsAboveAnchor = 0;
        for (int i = 0; i < validRows.size(); i++) {
            View row = validRows.get(i);
            if (row instanceof GapLineView) continue;
            int top = row.getTop();
            int size = row.getHeight();
            if (top + size > visibleTop && top + size / 2 < visibleTop + anchorPx) {
                stepsAboveAnchor++;
            }
        }
        int clampedSteps = Math.min(LyricsScrollStagger.MAX_STAGGER_STEPS, stepsAboveAnchor);
        long durationMs = LyricsRetiming.calculateScrollDuration(scrollLookAheadMs);
        float delayInc = LyricsRetiming.calculateScrollDelayIncrementFloat(durationMs);
        return (long) (clampedSteps * delayInc);
    }

    public void suppress(long forMs) {
        suppressedUntilMs = nowMs() + forMs + SUPPRESS_TAIL_MS;
        currentStagger = null;
        txDeltaPx = 0f;
    }

    public void recordLayoutShift(@Nullable View sourceView, float delta) {
        if (Math.abs(delta) < MIN_FLIP_DISTANCE_PX || sourceView == null) return;
        List<View> validRows = collectValidRows();
        int rowIdx = -1;
        for (int i = 0; i < validRows.size(); i++) {
            View r = validRows.get(i);
            if (r == sourceView || (r instanceof ViewGroup && ((ViewGroup) r).indexOfChild(sourceView) >= 0)) {
                rowIdx = i;
                break;
            }
        }
        if (rowIdx >= 0) {
            recordLayoutShift(rowIdx, delta);
        }
    }

    public void recordLayoutShift(int index, float delta) {
        if (Math.abs(delta) < MIN_FLIP_DISTANCE_PX) return;
        long seq = ++shiftSeq;
        int slot = (int) (seq % SHIFT_RING);
        shiftIndex[slot] = index;
        shiftDelta[slot] = delta;
        shiftSeqs[slot] = seq;
    }

    private float consumeLayoutShift(@NonNull LyricsItemFlip flip, int index) {
        float sum = 0f;
        for (int i = 0; i < SHIFT_RING; i++) {
            if (shiftSeqs[i] > flip.seenShiftSeq && shiftIndex[i] < index) {
                sum += shiftDelta[i];
            }
        }
        flip.seenShiftSeq = shiftSeq;
        return sum;
    }

    private float remainingStaggerMs(int index, float now) {
        LyricsScrollStagger st = currentStagger;
        if (st == null || now >= txEndMs) return 0f;
        return Math.max(0f, st.steps(index) * st.delayIncrementMs - (now - txStartMs));
    }

    public void onInnerPlaced(@NonNull LyricsItemFlip flip, int rowIndex, float topInParent, boolean enabled) {
        float now = nowMs();
        if (!enabled || now < suppressedUntilMs) {
            flip.reset(topInParent);
            flip.seenShiftSeq = shiftSeq;
            return;
        }
        float last = flip.lastTop;
        flip.lastTop = topInParent;
        if (Float.isNaN(last)) {
            flip.seenShiftSeq = shiftSeq;
            return;
        }
        float delta = last - topInParent;
        if (Math.abs(delta) < MIN_FLIP_DISTANCE_PX) return;
        LyricsScrollStagger running = (currentStagger != null && now < txEndMs) ? currentStagger : null;
        long dur = (running != null) ? running.durationMs : lastDurationMs;
        flip.add(now, delta, remainingStaggerMs(rowIndex, now), SpringEasing.lyPlusSpringFor(dur), 0f);
    }

    public void arm(@NonNull LyricsScrollStagger stagger, float deltaPx) {
        lastDurationMs = stagger.durationMs;
        float now = nowMs();
        if (now < suppressedUntilMs) return;
        this.currentStagger = stagger;
        this.txStartMs = now;
        this.txDeltaPx = deltaPx;
        this.txEndMs = now
                + LyricsScrollStagger.MAX_STAGGER_STEPS * stagger.delayIncrementMs
                + SpringEasing.lyPlusSpringFor(stagger.durationMs).totalMs;
    }

    public void reset() {
        if (forcedScrollAnimator != null) {
            forcedScrollAnimator.cancel();
            forcedScrollAnimator = null;
        }
        if (frameCallbackScheduled) {
            Choreographer.getInstance().removeFrameCallback(frameCallback);
            frameCallbackScheduled = false;
        }
        handler.removeCallbacks(unlockRunnable);
        isLocked = false;
        hasPendingUpdate = false;
        pendingTargetIndex = -1;
        lastTargetRowIdx = -1;
        lastTargetRowRef = null;
        lastTotalRowsHeight = -1;
        lastViewportHeight = -1;
        currentStagger = null;
        txDeltaPx = 0f;

        int scrollY = scrollView.getScrollY();
        int childCount = linesContainer.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = linesContainer.getChildAt(i);
            if (child == footerContainer) continue;
            child.setTranslationY(0f);
            LyricsItemFlip flip = itemFlips.get(child);
            if (flip != null) {
                flip.reset(child.getTop() - scrollY);
                flip.seenShiftSeq = shiftSeq;
            }
            resetInnerFlips(child);
        }
    }

    private void resetInnerFlips(@NonNull View row) {
        if (row instanceof LyricsLineView llv) {
            llv.resetInnerFlips();
        } else if (row instanceof ViewGroup vg) {
            for (int c = 0; c < vg.getChildCount(); c++) {
                View child = vg.getChildAt(c);
                if (child instanceof LyricsLineView llv) {
                    llv.resetInnerFlips();
                }
            }
        }
    }

    public void cancelCascade() {
        reset();
    }

    @NonNull
    private LyricsItemFlip flipFor(@NonNull View view) {
        LyricsItemFlip flip = itemFlips.get(view);
        if (flip == null) {
            flip = new LyricsItemFlip();
            itemFlips.put(view, flip);
        }
        return flip;
    }

    @NonNull
    private List<View> collectValidRows() {
        int childCount = linesContainer.getChildCount();
        List<View> validRows = new ArrayList<>(childCount);
        for (int i = 0; i < childCount; i++) {
            View child = linesContainer.getChildAt(i);
            if (child == footerContainer) continue;
            if (child.getVisibility() == View.GONE) continue;
            if (child instanceof GapLineView && child.getHeight() <= 0) continue;
            validRows.add(child);
        }
        return validRows;
    }

    @NonNull
    private int[] buildLinesBefore(@NonNull List<View> validRows) {
        int[] out = new int[validRows.size() + 1];
        for (int i = 0; i < validRows.size(); i++) {
            boolean isLine = !(validRows.get(i) instanceof GapLineView);
            out[i + 1] = out[i] + (isLine ? 1 : 0);
        }
        return out;
    }

    private void handlePreDrawReflowAndSync() {
        if (scrollView.getHeight() <= 0 || linesContainer.getChildCount() <= 0) return;

        List<View> validRows = collectValidRows();
        int totalH = 0;
        for (int i = 0; i < validRows.size(); i++) {
            totalH += validRows.get(i).getHeight();
        }
        int viewportH = scrollView.getHeight();
        boolean sizeChanged = (lastTotalRowsHeight >= 0 && totalH != lastTotalRowsHeight)
                || (lastViewportHeight >= 0 && viewportH != lastViewportHeight)
                || (shiftSeq != lastReflowShiftSeq);
        boolean layoutDriven = (shiftSeq != lastReflowShiftSeq);
        lastTotalRowsHeight = totalH;
        lastViewportHeight = viewportH;
        lastReflowShiftSeq = shiftSeq;

        boolean userScrolling = callback.isUserScrolling();
        if (sizeChanged && !userScrolling && (forcedScrollAnimator == null || !forcedScrollAnimator.isRunning())) {
            View targetRow = (lastTargetRowRef != null) ? lastTargetRowRef.get() : null;
            int resolvedRowIdx = -1;
            if (targetRow != null) {
                for (int i = 0; i < validRows.size(); i++) {
                    View r = validRows.get(i);
                    if (r == targetRow || (r instanceof ViewGroup && ((ViewGroup) r).indexOfChild(targetRow) >= 0)) {
                        resolvedRowIdx = i;
                        targetRow = r;
                        break;
                    }
                }
            }
            if (resolvedRowIdx < 0 && lastTargetRowIdx >= 0 && lastTargetRowIdx < validRows.size()) {
                resolvedRowIdx = lastTargetRowIdx;
                targetRow = validRows.get(resolvedRowIdx);
            }
            if (resolvedRowIdx >= 0 && targetRow != null) {
                lastTargetRowIdx = resolvedRowIdx;
                int desiredTargetY = callback.computeReflowScrollTarget(targetRow);
                if (desiredTargetY >= 0) {
                    int currentScrollY = scrollView.getScrollY();
                    float offset = (float) (desiredTargetY - currentScrollY);
                    if (Math.abs(offset) > MIN_FLIP_DISTANCE_PX) {
                        if (!layoutDriven) {
                            boolean scrollingDown = offset >= 0f;
                            long durationMs = lastDurationMs;
                            float delayIncrementMs = LyricsRetiming.calculateScrollDelayIncrementFloat(durationMs);
                            int[] linesBefore = buildLinesBefore(validRows);
                            arm(
                                    new LyricsScrollStagger(
                                            scrollingDown,
                                            resolvedRowIdx,
                                            durationMs,
                                            delayIncrementMs,
                                            linesBefore
                                    ),
                                    offset
                            );
                        }
                        callback.setProgrammaticScrolling(true);
                        scrollView.scrollTo(0, desiredTargetY);
                        callback.setProgrammaticScrolling(false);
                    } else if (!layoutDriven) {
                        long durationMs = lastDurationMs;
                        float delayIncrementMs = LyricsRetiming.calculateScrollDelayIncrementFloat(durationMs);
                        int[] linesBefore = buildLinesBefore(validRows);
                        arm(
                                new LyricsScrollStagger(
                                        true,
                                        resolvedRowIdx,
                                        durationMs,
                                        delayIncrementMs,
                                        linesBefore
                                ),
                                0f
                        );
                    }
                }
            }
        }

        syncPlacedPositions(!userScrolling, validRows);
    }

    private void syncPlacedPositions(boolean enabled, @NonNull List<View> validRows) {
        if (scrollView.getHeight() <= 0 || validRows.isEmpty()) return;
        float now = nowMs();
        int scrollY = scrollView.getScrollY();
        boolean anyActive = false;

        for (int i = 0; i < validRows.size(); i++) {
            View row = validRows.get(i);
            LyricsItemFlip flip = flipFor(row);
            float visualTop = row.getTop() - scrollY;

            if (!enabled) {
                boolean wasUnplaced = Float.isNaN(flip.lastTop);
                flip.lastTop = visualTop;
                if (wasUnplaced) {
                    flip.seenShiftSeq = shiftSeq;
                } else {
                    float shift = consumeLayoutShift(flip, i);
                    if (Math.abs(shift) >= MIN_FLIP_DISTANCE_PX) {
                        flip.add(now, -shift, 0f, SpringEasing.lyPlusSpringFor(lastDurationMs), 0f);
                    }
                }
            } else if (now < suppressedUntilMs) {
                flip.reset(visualTop);
                flip.seenShiftSeq = shiftSeq;
            } else {
                float last = flip.lastTop;
                flip.lastTop = visualTop;
                if (Float.isNaN(last)) {
                    flip.seenShiftSeq = shiftSeq;
                } else {
                    float delta = last - visualTop;
                    if (Math.abs(delta) >= MIN_FLIP_DISTANCE_PX) {
                        LyricsScrollStagger running = (currentStagger != null && now < txEndMs) ? currentStagger : null;
                        long dur = (running != null) ? running.durationMs : lastDurationMs;
                        flip.add(now, delta, remainingStaggerMs(i, now), SpringEasing.lyPlusSpringFor(dur), 0f);
                    }
                }
            }

            float trans = flip.translation(now);
            row.setTranslationY(trans);
            if (flip.hasActiveLayers(now)) {
                anyActive = true;
            }
            if (syncInnerFlips(row, i, enabled, now)) {
                anyActive = true;
            }
        }

        if (anyActive && !frameCallbackScheduled) {
            frameCallbackScheduled = true;
            Choreographer.getInstance().postFrameCallback(frameCallback);
        }
    }

    private boolean syncInnerFlips(@NonNull View row, int rowIndex, boolean enabled, float now) {
        boolean active = false;
        if (row instanceof LyricsLineView llv) {
            if (llv.syncInnerFlip(this, rowIndex, enabled, now)) {
                active = true;
            }
        } else if (row instanceof ViewGroup vg) {
            for (int c = 0; c < vg.getChildCount(); c++) {
                View child = vg.getChildAt(c);
                if (child instanceof LyricsLineView llv) {
                    if (llv.syncInnerFlip(this, rowIndex, enabled, now)) {
                        active = true;
                    }
                }
            }
        }
        return active;
    }

    private void updateFlipTranslations() {
        float now = nowMs();
        boolean anyActive = false;
        int childCount = linesContainer.getChildCount();

        for (int i = 0; i < childCount; i++) {
            View child = linesContainer.getChildAt(i);
            if (child == footerContainer) continue;
            LyricsItemFlip flip = itemFlips.get(child);
            if (child.getVisibility() == View.GONE) {
                if (flip != null) flip.layers.clear();
                child.setTranslationY(0f);
                continue;
            }
            if (flip != null) {
                float trans = flip.translation(now);
                child.setTranslationY(trans);
                if (flip.hasActiveLayers(now)) {
                    anyActive = true;
                }
            }
            if (updateInnerFlipTranslations(child, now)) {
                anyActive = true;
            }
        }

        if (anyActive && !frameCallbackScheduled) {
            frameCallbackScheduled = true;
            Choreographer.getInstance().postFrameCallback(frameCallback);
        }
    }

    private boolean updateInnerFlipTranslations(@NonNull View row, float now) {
        boolean active = false;
        if (row instanceof LyricsLineView llv) {
            if (llv.updateInnerFlipTranslation(now)) {
                active = true;
            }
        } else if (row instanceof ViewGroup vg) {
            for (int c = 0; c < vg.getChildCount(); c++) {
                View child = vg.getChildAt(c);
                if (child instanceof LyricsLineView llv && child.getVisibility() == View.VISIBLE) {
                    if (llv.updateInnerFlipTranslation(now)) {
                        active = true;
                    }
                }
            }
        }
        return active;
    }

    public void scrollTo(int targetY,
                         @Nullable View referenceView,
                         int referenceIndex,
                         long lookAheadMs,
                         boolean forceInstant) {
        scrollTo(targetY, referenceView, referenceIndex, referenceView, referenceIndex, lookAheadMs, forceInstant);
    }

    public void scrollTo(int targetY,
                         @Nullable View targetView,
                         int targetIndex,
                         @Nullable View referenceView,
                         int referenceIndex,
                         long lookAheadMs,
                         boolean forceInstant) {
        if (scrollView.getHeight() <= 0 || linesContainer.getChildCount() <= 0) {
            return;
        }
        if (callback.isUserScrolling()) {
            return;
        }

        final int target = Math.max(0, targetY);
        final int currentScrollY = scrollView.getScrollY();
        final float requestedDelta = (float) (target - currentScrollY);

        List<View> validRows = collectValidRows();
        int totalRows = validRows.size();
        int resolvedTargetRowIdx = -1;
        if (targetView != null) {
            for (int i = 0; i < totalRows; i++) {
                View row = validRows.get(i);
                if (row == targetView || (row instanceof ViewGroup && ((ViewGroup) row).indexOfChild(targetView) >= 0)) {
                    resolvedTargetRowIdx = i;
                    targetView = row;
                    break;
                }
            }
        }
        if (resolvedTargetRowIdx < 0 && totalRows > 0) {
            resolvedTargetRowIdx = Math.max(0, Math.min(targetIndex, totalRows - 1));
            targetView = validRows.get(resolvedTargetRowIdx);
        }

        if (forceInstant) {
            suppress(0L);
            reset();
            if (resolvedTargetRowIdx >= 0) {
                lastTargetRowIdx = resolvedTargetRowIdx;
                lastTargetRowRef = (targetView != null) ? new WeakReference<>(targetView) : null;
            }
            callback.setProgrammaticScrolling(true);
            scrollView.scrollTo(0, target);
            callback.setProgrammaticScrolling(false);
            syncPlacedPositions(false, validRows);
            return;
        }

        if (Math.abs(requestedDelta) <= 1.0f) {
            if (resolvedTargetRowIdx >= 0) {
                lastTargetRowIdx = resolvedTargetRowIdx;
                lastTargetRowRef = (targetView != null) ? new WeakReference<>(targetView) : null;
            }
            return;
        }

        if (isLocked) {
            pendingTargetIndex = targetIndex;
            hasPendingUpdate = true;
            return;
        }

        final int viewportHeight = scrollView.getHeight();
        final boolean isFirstPass = (lastTargetRowIdx < 0);

        if (isFirstPass || Math.abs(requestedDelta) > viewportHeight * LYRICS_FAR_JUMP_VIEWPORTS) {
            if (forcedScrollAnimator != null) {
                forcedScrollAnimator.cancel();
                forcedScrollAnimator = null;
            }
            suppress(LYRICS_FORCED_SCROLL_MS);
            reset();
            if (resolvedTargetRowIdx >= 0) {
                lastTargetRowIdx = resolvedTargetRowIdx;
                lastTargetRowRef = (targetView != null) ? new WeakReference<>(targetView) : null;
            }
            isLocked = true;
            handler.removeCallbacks(unlockRunnable);
            handler.postDelayed(unlockRunnable, LYRICS_FORCED_SCROLL_MS + 50L);

            ValueAnimator scrollAnimator = ValueAnimator.ofInt(currentScrollY, target);
            forcedScrollAnimator = scrollAnimator;
            scrollAnimator.setDuration(LYRICS_FORCED_SCROLL_MS);
            scrollAnimator.setInterpolator(SpringEasing.LY_PLUS_SCROLL_EASING);
            scrollAnimator.addUpdateListener(animation -> {
                int y = (Integer) animation.getAnimatedValue();
                callback.setProgrammaticScrolling(true);
                scrollView.scrollTo(0, y);
                callback.setProgrammaticScrolling(false);
            });
            scrollAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    forcedScrollAnimator = null;
                    isLocked = false;
                    syncPlacedPositions(false, collectValidRows());
                    if (hasPendingUpdate) {
                        hasPendingUpdate = false;
                        final int targetIdx = pendingTargetIndex;
                        pendingTargetIndex = -1;
                        if (!callback.isUserScrolling()) {
                            callback.onRecomputeAndScroll(targetIdx);
                        }
                    }
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    forcedScrollAnimator = null;
                    isLocked = false;
                }
            });
            scrollAnimator.start();
            return;
        }

        if (totalRows == 0) {
            return;
        }

        for (int i = 0; i < totalRows; i++) {
            View row = validRows.get(i);
            LyricsItemFlip flip = flipFor(row);
            if (Float.isNaN(flip.lastTop)) {
                flip.lastTop = row.getTop() - currentScrollY;
                flip.seenShiftSeq = shiftSeq;
            }
        }

        int targetRowIdx = resolvedTargetRowIdx;
        int refRowIdx = (lastTargetRowIdx >= 0)
                ? Math.max(0, Math.min(lastTargetRowIdx, totalRows - 1))
                : targetRowIdx;

        long durationMs = LyricsRetiming.calculateScrollDuration(lookAheadMs);
        float delayIncrementMs = LyricsRetiming.calculateScrollDelayIncrementFloat(durationMs);
        boolean scrollingDown;
        if (targetRowIdx > refRowIdx) {
            scrollingDown = true;
        } else if (targetRowIdx < refRowIdx) {
            scrollingDown = false;
        } else {
            scrollingDown = (requestedDelta >= 0f);
        }

        int[] linesBefore = buildLinesBefore(validRows);
        LyricsScrollStagger stagger = new LyricsScrollStagger(
                scrollingDown,
                refRowIdx,
                durationMs,
                delayIncrementMs,
                linesBefore
        );

        arm(stagger, requestedDelta);

        int beforeY = scrollView.getScrollY();
        callback.setProgrammaticScrolling(true);
        scrollView.scrollTo(0, target);
        int afterY = scrollView.getScrollY();
        callback.setProgrammaticScrolling(false);

        float consumed = (float) (afterY - beforeY);
        lastTargetRowIdx = targetRowIdx;
        lastTargetRowRef = (targetView != null) ? new WeakReference<>(targetView) : null;

        if (Math.abs(consumed) > MIN_FLIP_DISTANCE_PX) {
            isLocked = true;
            handler.removeCallbacks(unlockRunnable);
            handler.postDelayed(unlockRunnable, LYRICS_SCROLL_LOCK_MS);
            syncPlacedPositions(true, validRows);
        }
    }
}