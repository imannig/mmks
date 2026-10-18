/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.animation.TimeInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SpringEasing {

    public static final Interpolator LY_PLUS_SCROLL_EASING =
            new PathInterpolator(0.41f, 0f, 0.12f, 0.99f);

    private static final Map<Integer, LyPlusSpring> SPRING_CACHE = new HashMap<>();

    @NonNull
    public static synchronized LyPlusSpring lyPlusSpringFor(long durationMs) {
        int key = Math.max(10, (int) (((Math.max(1L, durationMs) + 5L) / 10L) * 10L));
        LyPlusSpring cached = SPRING_CACHE.get(key);
        if (cached == null) {
            cached = new LyPlusSpring((float) key);
            SPRING_CACHE.put(key, cached);
        }
        return cached;
    }

    public static final class Result implements TimeInterpolator {
        public final float[] samples;
        public final int durationMs;

        Result(float[] s, int d) {
            this.samples = s;
            this.durationMs = d;
        }

        @Override
        public float getInterpolation(float input) {
            return interpolate(input);
        }

        public float interpolate(float input) {
            if (input <= 0f) return samples[0];
            if (input >= 1f) return samples[samples.length - 1];
            float x = input * (samples.length - 1);
            int i = (int) x;
            float f = x - i;
            return samples[i] + (samples[i + 1] - samples[i]) * f;
        }
    }

    public static final class LyPlusSpring {
        public final float peakMs;
        public final float zeta;
        public final float settleRatio;
        public final float tailMs;
        public final float totalMs;
        public final float peakValue;

        public LyPlusSpring(float peakMs, float zeta, float settleRatio) {
            this.peakMs = Math.max(1f, peakMs);
            this.zeta = zeta;
            this.settleRatio = settleRatio;
            this.tailMs = this.peakMs * settleRatio;
            this.totalMs = this.peakMs + this.tailMs;

            double peakS = this.peakMs / 1000.0;
            double omegaD = Math.PI / peakS;
            double omega0 = omegaD / Math.sqrt(1.0 - zeta * zeta);
            double decayRate = zeta * omega0;
            this.peakValue = (float) (1.0 + Math.exp(-decayRate * peakS));
        }

        public LyPlusSpring(float peakMs) {
            this(peakMs, 0.78f, 1.5f);
        }

        public float value(float tMs) {
            if (tMs <= 0f) {
                return 0f;
            }
            if (tMs >= totalMs) {
                return 1f;
            }
            if (tMs < peakMs) {
                return peakValue * LY_PLUS_SCROLL_EASING.getInterpolation(tMs / peakMs);
            } else {
                float u = (tMs - peakMs) / tailMs;
                return 1f + (peakValue - 1f) * 0.5f * (1f + (float) Math.cos(Math.PI * u));
            }
        }
    }

    public static final class LyricsScrollStagger {
        public static final int MAX_STAGGER_STEPS = 12;

        public final boolean scrollingDown;
        public final int referenceIndex;
        public final long durationMs;
        public final float delayIncrementMs;
        private final int[] linesBefore;

        public LyricsScrollStagger(
                boolean scrollingDown,
                int referenceIndex,
                long durationMs,
                float delayIncrementMs,
                @NonNull int[] linesBefore) {
            this.scrollingDown = scrollingDown;
            this.referenceIndex = referenceIndex;
            this.durationMs = durationMs;
            this.delayIncrementMs = delayIncrementMs;
            this.linesBefore = linesBefore;
        }

        public LyricsScrollStagger(int anchorIndex, boolean scrollingDown, long delayIncrementMs) {
            this(scrollingDown, anchorIndex, 400L, (float) Math.max(0L, delayIncrementMs), new int[0]);
        }

        public int steps(int index) {
            if (linesBefore.length == 0) {
                int raw = scrollingDown ? (index - referenceIndex) : (referenceIndex - index);
                return Math.max(0, Math.min(MAX_STAGGER_STEPS, raw));
            }
            int last = linesBefore.length - 1;
            int steps;
            if (scrollingDown) {
                if (index <= referenceIndex) {
                    steps = 0;
                } else {
                    int iClamped = Math.max(0, Math.min(index, last));
                    int rClamped = Math.max(0, Math.min(referenceIndex, last));
                    steps = linesBefore[iClamped] - linesBefore[rClamped];
                }
            } else {
                if (index >= referenceIndex) {
                    steps = 0;
                } else {
                    int rClamped = Math.max(0, Math.min(referenceIndex + 1, last));
                    int iClamped = Math.max(0, Math.min(index + 1, last));
                    steps = linesBefore[rClamped] - linesBefore[iClamped];
                }
            }
            return Math.max(0, Math.min(MAX_STAGGER_STEPS, steps));
        }

        public int staggerOrderFor(int rowIndex) {
            return steps(rowIndex);
        }

        public long delayFor(int rowIndex) {
            return Math.round(steps(rowIndex) * delayIncrementMs);
        }
    }

    private static final Map<String, Result> CACHE = new LinkedHashMap<String, Result>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Result> e) {
            return size() > 64;
        }
    };

    static double cubicBezierY(double t, double x1, double y1, double x2, double y2) {
        double cx = 3 * x1, bx = 3 * (x2 - x1) - cx, ax = 1 - cx - bx;
        double cy = 3 * y1, by = 3 * (y2 - y1) - cy, ay = 1 - cy - by;
        double u = t;
        for (int i = 0; i < 8; i++) {
            double x = ((ax * u + bx) * u + cx) * u - t;
            double dx = (3 * ax * u + 2 * bx) * u + cx;
            if (Math.abs(dx) < 1e-6) break;
            u -= x / dx;
        }
        return ((ay * u + by) * u + cy) * u;
    }

    public static synchronized Result get(double peakTimeMs, double zeta, double settleTailMs) {
        peakTimeMs = Math.round(peakTimeMs / 10.0) * 10.0;
        settleTailMs = Math.round(settleTailMs / 10.0) * 10.0;
        String key = peakTimeMs + ":" + zeta + ":" + settleTailMs;
        Result hit = CACHE.get(key);
        if (hit != null) return hit;

        double peakS = peakTimeMs / 1000.0, tailS = settleTailMs / 1000.0;
        double omegaD = Math.PI / peakS;
        double omega0 = omegaD / Math.sqrt(1.0 - zeta * zeta);
        double decayRate = zeta * omega0;
        double peakValue = 1.0 + Math.exp(-decayRate * peakS);
        double dt = 1.0 / 120.0;

        java.util.ArrayList<Float> pts = new java.util.ArrayList<>();
        for (double t = 0; t <= peakS; t += dt) {
            pts.add((float) (peakValue * cubicBezierY(t / peakS, 0.41, 0, 0.12, 0.99)));
        }
        for (double t = dt; t <= tailS; t += dt) {
            double u = t / tailS;
            pts.add((float) (1.0 + (peakValue - 1.0) * 0.5 * (1.0 + Math.cos(Math.PI * u))));
        }
        pts.add(1f);
        float[] arr = new float[pts.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = Math.round(pts.get(i) * 10000f) / 10000f;
        Result r = new Result(arr, (int) Math.round((peakS + tailS) * 1000));
        CACHE.put(key, r);
        return r;
    }

    public static float springProgress(double time, double response) {
        if (time <= 0.0) return 0f;
        double phase = (2.0 * Math.PI * time) / Math.max(0.001, response);
        return (float) (1.0 - (1.0 + phase) * Math.exp(-phase));
    }
}