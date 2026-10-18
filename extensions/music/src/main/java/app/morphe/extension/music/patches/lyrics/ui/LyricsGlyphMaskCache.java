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
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.Layout;
import android.text.TextPaint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.TintFilterCache;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.WipeResult;

public final class LyricsGlyphMaskCache {

    private LyricsGlyphMaskCache() {
    }

    public static final float MASK_RASTER_SCALE = 1.25f;
    public static final float GLOW_RASTER_SCALE = 0.5f;
    public static final float MAX_MASK_SIDE = 2048f;
    public static final long PREWARM_BUDGET_NS = 3_000_000L;

    public static final class GlowShadowTable {
        public final float blurRadius;
        public final int steps;
        private final int[] shadowColors;

        public GlowShadowTable(float blurRadius, int steps) {
            this.blurRadius = blurRadius;
            this.steps = Math.max(1, steps);
            this.shadowColors = new int[this.steps + 1];
            for (int i = 0; i <= this.steps; i++) {
                float a = (float) i / (float) this.steps;
                this.shadowColors[i] = Color.argb(Math.round(a * 255f), 255, 255, 255);
            }
        }

        public GlowShadowTable(float blurRadius) {
            this(blurRadius, 100);
        }

        public int colorFor(float alpha) {
            int idx = Math.max(0, Math.min(steps, (int) (alpha * steps)));
            return shadowColors[idx];
        }

        public float radiusFor(float alpha) {
            return blurRadius;
        }
    }

    public static final class ClusterMaskCache {
        public final float padPx;
        private final Bitmap[] bitmaps;
        private final float[] scales;
        private final Bitmap[] glowBitmaps;
        private final float[] glowScales;
        private int prewarmCursor = 0;

        public final TintFilterCache pastTint = new TintFilterCache();
        public final TintFilterCache unlitTint = new TintFilterCache();
        public final TintFilterCache inactiveTint = new TintFilterCache();
        public final TintFilterCache glowTint = new TintFilterCache();
        public final ColorFilter glowFilter = new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);

        public final Paint layerPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint bandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF layerBounds = new RectF();

        private final int[] bandColors = new int[4];
        private int bandLit = Color.TRANSPARENT;
        private int bandUnlit = Color.TRANSPARENT;
        private float bandDx = Float.NaN;
        @Nullable
        private LinearGradient bandBrushCached = null;

        public ClusterMaskCache(int count, float padPx) {
            this.padPx = padPx;
            this.bitmaps = new Bitmap[count];
            this.scales = new float[count];
            this.glowBitmaps = new Bitmap[count];
            this.glowScales = new float[count];
            for (int i = 0; i < count; i++) {
                this.scales[i] = MASK_RASTER_SCALE;
                this.glowScales[i] = GLOW_RASTER_SCALE;
            }
            this.bandPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        }

        @Nullable
        public Bitmap peek(int idx) {
            return (idx >= 0 && idx < bitmaps.length) ? bitmaps[idx] : null;
        }

        public float scaleOf(int idx) {
            return (idx >= 0 && idx < scales.length) ? scales[idx] : MASK_RASTER_SCALE;
        }

        @Nullable
        public Bitmap peekGlow(int idx) {
            return (idx >= 0 && idx < glowBitmaps.length) ? glowBitmaps[idx] : null;
        }

        public boolean hasGlow(int idx) {
            return idx >= 0 && idx < glowBitmaps.length && glowBitmaps[idx] != null;
        }

        public float glowScaleOf(int idx) {
            return (idx >= 0 && idx < glowScales.length) ? glowScales[idx] : GLOW_RASTER_SCALE;
        }

        @NonNull
        public Bitmap obtain(int idx, @NonNull Layout layout) {
            Bitmap existing = bitmaps[idx];
            if (existing != null) return existing;
            RasterResult res = rasterize(layout, padPx, MASK_RASTER_SCALE);
            bitmaps[idx] = res.bitmap;
            scales[idx] = res.scale;
            return res.bitmap;
        }

        @NonNull
        public Bitmap obtainGlow(int idx, @NonNull Layout layout, float blurRadius) {
            Bitmap existing = glowBitmaps[idx];
            if (existing != null) return existing;
            RasterResult res = rasterizeGlow(layout, padPx, blurRadius, GLOW_RASTER_SCALE);
            glowBitmaps[idx] = res.bitmap;
            glowScales[idx] = res.scale;
            return res.bitmap;
        }

        public interface LayoutProvider {
            Layout get(int index);
        }

        public interface GlowBlurProvider {
            float get(int index);
        }

        public void prewarmAll(int count, @NonNull LayoutProvider layoutOf, @NonNull GlowBlurProvider glowBlurOf) {
            int limit = Math.min(count, bitmaps.length);
            for (int i = 0; i < limit; i++) {
                Layout layout = layoutOf.get(i);
                obtain(i, layout);
                float blur = glowBlurOf.get(i);
                if (blur > 0f && !hasGlow(i)) {
                    obtainGlow(i, layout, blur);
                }
            }
            prewarmCursor = limit;
        }

        public void prewarmStep(
                int count,
                long budgetNs,
                @NonNull LayoutProvider layoutOf,
                @NonNull GlowBlurProvider glowBlurOf) {
            int limit = Math.min(count, bitmaps.length);
            if (prewarmCursor >= limit) return;
            long startNs = SystemClock.elapsedRealtimeNanos();
            while (prewarmCursor < limit) {
                int i = prewarmCursor;
                Layout layout = layoutOf.get(i);
                obtain(i, layout);
                float blur = glowBlurOf.get(i);
                if (blur > 0f && !hasGlow(i)) {
                    obtainGlow(i, layout, blur);
                }
                prewarmCursor++;
                if (SystemClock.elapsedRealtimeNanos() - startNs >= budgetNs) {
                    break;
                }
            }
        }

        public void evictAndClear() {
            for (int i = 0; i < bitmaps.length; i++) {
                if (bitmaps[i] != null && !bitmaps[i].isRecycled()) {
                    bitmaps[i].recycle();
                }
                bitmaps[i] = null;
                if (glowBitmaps[i] != null && !glowBitmaps[i].isRecycled()) {
                    glowBitmaps[i].recycle();
                }
                glowBitmaps[i] = null;
            }
            prewarmCursor = 0;
        }

        @NonNull
        public LinearGradient bandBrush(int lit, int unlit, float dx) {
            LinearGradient cached = bandBrushCached;
            if (cached != null && lit == bandLit && unlit == bandUnlit && dx == bandDx) {
                return cached;
            }
            bandLit = lit;
            bandUnlit = unlit;
            bandDx = dx;
            bandColors[0] = lit;
            bandColors[1] = lit;
            bandColors[2] = unlit;
            bandColors[3] = unlit;
            float safeDx = (Math.abs(dx) < 0.01f) ? 0.01f : dx;
            LinearGradient created = new LinearGradient(
                    0f, 0f, safeDx, 0f,
                    bandColors,
                    WordAnimationUtils.GRADIENT_STOPS,
                    Shader.TileMode.CLAMP);
            bandBrushCached = created;
            return created;
        }

        public void drawMaskTinted(
                @NonNull Canvas canvas,
                @NonNull Bitmap mask,
                float maskScale,
                @NonNull ColorFilter filter,
                float alpha,
                @Nullable PorterDuff.Mode blendMode) {
            maskPaint.setColorFilter(filter);
            maskPaint.setAlpha(Math.max(0, Math.min(255, Math.round(alpha * 255f))));
            maskPaint.setXfermode(blendMode != null ? new PorterDuffXfermode(blendMode) : null);

            float inv = 1f / maskScale;
            float padM = padPx * maskScale;
            canvas.save();
            canvas.scale(inv, inv);
            canvas.translate(-padM, -padM);
            canvas.drawBitmap(mask, 0f, 0f, maskPaint);
            canvas.restore();

            maskPaint.setXfermode(null);
            maskPaint.setAlpha(255);
        }

        public void drawMaskBand(
                @NonNull Canvas canvas,
                @NonNull Bitmap mask,
                float maskScale,
                @NonNull WipeResult wipe,
                int litColor,
                int unlitColor) {
            float inv = 1f / maskScale;
            float padM = padPx * maskScale;
            float w = mask.getWidth();
            float h = mask.getHeight();
            float bandX = wipe.bandStart * maskScale + padM;
            float dx = (wipe.bandEnd - wipe.bandStart) * maskScale;

            LinearGradient shader = bandBrush(litColor, unlitColor, dx);
            bandPaint.setShader(shader);

            canvas.save();
            canvas.scale(inv, inv);
            canvas.translate(-padM, -padM);

            layerBounds.set(0f, 0f, w, h);
            int saveCount = canvas.saveLayer(layerBounds, layerPaint);
            maskPaint.setColorFilter(null);
            maskPaint.setAlpha(255);
            maskPaint.setXfermode(null);
            canvas.drawBitmap(mask, 0f, 0f, maskPaint);

            canvas.save();
            canvas.translate(bandX, 0f);
            canvas.drawRect(-bandX, 0f, w - bandX, h, bandPaint);
            canvas.restore();

            canvas.restoreToCount(saveCount);
            canvas.restore();
        }
    }

    public static final class RasterResult {
        public final Bitmap bitmap;
        public final float scale;

        public RasterResult(Bitmap bitmap, float scale) {
            this.bitmap = bitmap;
            this.scale = scale;
        }
    }

    @NonNull
    public static RasterResult rasterize(@NonNull Layout subLayout, float padPx, float scale) {
        float lw = Math.max(1f, subLayout.getWidth() + padPx * 2f);
        float lh = Math.max(1f, subLayout.getHeight() + padPx * 2f);
        float s = Math.min(scale, MAX_MASK_SIDE / Math.max(lw, lh));
        int w = Math.max(1, Math.min((int) MAX_MASK_SIDE, (int) Math.ceil(lw * s)));
        int h = Math.max(1, Math.min((int) MAX_MASK_SIDE, (int) Math.ceil(lh * s)));

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.scale(s, s);
        c.translate(padPx, padPx);

        TextPaint paint = subLayout.getPaint();
        int prevColor = paint.getColor();
        Shader prevShader = paint.getShader();
        paint.setColor(Color.WHITE);
        paint.setShader(null);
        paint.clearShadowLayer();
        subLayout.draw(c);
        paint.setColor(prevColor);
        paint.setShader(prevShader);

        return new RasterResult(bmp, s);
    }

    @NonNull
    public static RasterResult rasterizeGlow(@NonNull Layout subLayout, float padPx, float blurRadius, float scale) {
        float lw = Math.max(1f, subLayout.getWidth() + padPx * 2f);
        float lh = Math.max(1f, subLayout.getHeight() + padPx * 2f);
        float s = Math.min(scale, MAX_MASK_SIDE / Math.max(lw, lh));
        int w = Math.max(1, Math.min((int) MAX_MASK_SIDE, (int) Math.ceil(lw * s)));
        int h = Math.max(1, Math.min((int) MAX_MASK_SIDE, (int) Math.ceil(lh * s)));

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.scale(s, s);
        c.translate(padPx, padPx);

        TextPaint paint = subLayout.getPaint();
        int prevColor = paint.getColor();
        Shader prevShader = paint.getShader();
        paint.setColor(0x00FFFFFF);
        paint.setShader(null);
        paint.setShadowLayer(Math.max(0.5f, blurRadius), 0f, 0f, Color.WHITE);
        subLayout.draw(c);
        paint.clearShadowLayer();
        paint.setColor(prevColor);
        paint.setShader(prevShader);

        return new RasterResult(bmp, s);
    }
}