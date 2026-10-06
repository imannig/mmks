/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.shared.Logger;

/**
 * Animated multi-blob mesh gradient background.
 * Dynamically samples artwork colors and smoothly animates organic orbital gradients.
 */
public final class DynamicBackgroundView extends View {

    private static final ExecutorService COLOR_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final ArgbEvaluator COLOR_EVALUATOR = new ArgbEvaluator();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int fromColorA = 0xFF2A4278;
    private int fromColorB = 0xFF58236B;
    private int fromColorC = 0xFF255B69;
    private int fromBaseBgColor = 0xFF0D111A;

    private int targetColorA = 0xFF2A4278;
    private int targetColorB = 0xFF58236B;
    private int targetColorC = 0xFF255B69;
    private int targetBaseBgColor = 0xFF0D111A;

    private int currentColorA = targetColorA;
    private int currentColorB = targetColorB;
    private int currentColorC = targetColorC;
    private int currentBaseBgColor = targetBaseBgColor;

    private long transitionStartUptimeMs;
    private static final long TRANSITION_DURATION_MS = 1000;

    @Nullable
    private String currentVideoId;
    private boolean isAttached;

    public DynamicBackgroundView(Context context) {
        super(context);
        scrimPaint.setColor(0x32000000);
    }

    public void setVideoId(@Nullable String videoId, @Nullable String title, @Nullable String artist) {
        if (Objects.equals(videoId, currentVideoId)) {
            return;
        }
        currentVideoId = videoId;

        // Immediately update with a harmonious algorithmic fallback based on track name
        int hash = (title != null ? title.hashCode() : 0) ^ (artist != null ? artist.hashCode() : 0);
        float baseHue = Math.abs(hash % 360);
        int fallbackA = Color.HSVToColor(new float[]{baseHue, 0.85f, 0.58f});
        int fallbackB = Color.HSVToColor(new float[]{(baseHue + 55f) % 360f, 0.88f, 0.52f});
        int fallbackC = Color.HSVToColor(new float[]{(baseHue + 115f) % 360f, 0.80f, 0.55f});
        int fallbackBaseBg = Color.HSVToColor(new float[]{baseHue, 0.40f, 0.12f});
        startColorTransition(fallbackA, fallbackB, fallbackC, fallbackBaseBg);

        if (videoId == null || videoId.isEmpty()) {
            return;
        }

        final String targetVid = videoId;
        COLOR_EXECUTOR.execute(() -> {
            try {
                String urlStr = "https://i.ytimg.com/vi/" + targetVid + "/hqdefault.jpg";
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setDoInput(true);
                conn.connect();

                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 8; // Downsample heavily for fast 32-60px palette sampling
                try (InputStream is = conn.getInputStream()) {
                    Bitmap bmp = BitmapFactory.decodeStream(is, null, options);
                    if (bmp != null) {
                        extractAndApplyPalette(bmp, targetVid);
                        bmp.recycle();
                    }
                }
            } catch (Exception ex) {
                Logger.printDebug(() -> "DynamicBackground: Failed to fetch thumbnail for palette", ex);
            }
        });
    }

    private void extractAndApplyPalette(@NonNull Bitmap bmp, @NonNull String targetVid) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w <= 0 || h <= 0) return;

        int p1 = bmp.getPixel(Math.min(w - 1, w / 4), Math.min(h - 1, h / 4));
        int p2 = bmp.getPixel(Math.min(w - 1, w / 2), Math.min(h - 1, h / 2));
        int p3 = bmp.getPixel(Math.min(w - 1, (3 * w) / 4), Math.min(h - 1, (3 * h) / 4));

        int tunedA = tuneVibrancy(p1, 0.82f, 0.60f);
        int tunedB = tuneVibrancy(p2, 0.88f, 0.55f);
        int tunedC = tuneVibrancy(p3, 0.78f, 0.58f);

        float[] hsv = new float[3];
        Color.colorToHSV(tunedA, hsv);
        int baseBg = Color.HSVToColor(new float[]{hsv[0], 0.40f, 0.12f});

        mainHandler.post(() -> {
            if (Objects.equals(currentVideoId, targetVid)) {
                startColorTransition(tunedA, tunedB, tunedC, baseBg);
            }
        });
    }

    private static int tuneVibrancy(int color, float minSat, float targetVal) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        if (hsv[1] < 0.15f) {
            // Low-saturation / monochrome artwork fallback: deep elegant dark ambient tone
            hsv[0] = 220f; // Soft midnight blue hue
            hsv[1] = 0.35f;
            hsv[2] = 0.30f;
            return Color.HSVToColor(hsv);
        }
        hsv[1] = Math.max(minSat, Math.min(0.95f, Math.max(0.78f, hsv[1] * 1.35f)));
        hsv[2] = Math.max(targetVal, Math.min(0.65f, Math.max(0.52f, hsv[2] * 1.25f)));
        return Color.HSVToColor(hsv);
    }

    private void startColorTransition(int a, int b, int c, int baseBg) {
        fromColorA = currentColorA;
        fromColorB = currentColorB;
        fromColorC = currentColorC;
        fromBaseBgColor = currentBaseBgColor;

        targetColorA = a;
        targetColorB = b;
        targetColorC = c;
        targetBaseBgColor = baseBg;

        transitionStartUptimeMs = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        isAttached = true;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        isAttached = false;
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE && isAttached) {
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // Update color crossfade
        long now = SystemClock.uptimeMillis();
        if (transitionStartUptimeMs > 0) {
            float progress = (float) (now - transitionStartUptimeMs) / TRANSITION_DURATION_MS;
            if (progress >= 1f) {
                currentColorA = targetColorA;
                currentColorB = targetColorB;
                currentColorC = targetColorC;
                currentBaseBgColor = targetBaseBgColor;
                transitionStartUptimeMs = 0;
            } else {
                currentColorA = (int) COLOR_EVALUATOR.evaluate(progress, fromColorA, targetColorA);
                currentColorB = (int) COLOR_EVALUATOR.evaluate(progress, fromColorB, targetColorB);
                currentColorC = (int) COLOR_EVALUATOR.evaluate(progress, fromColorC, targetColorC);
                currentBaseBgColor = (int) COLOR_EVALUATOR.evaluate(progress, fromBaseBgColor, targetBaseBgColor);
            }
        }

        // Draw dynamic tinted base canvas
        canvas.drawColor(currentBaseBgColor);

        // Smooth orbital physics for 3 mesh blobs
        final float t = now * 0.00030f;
        final float maxDim = Math.max(w, h);

        // Blob 1: upper area, slow circular drift
        float cx1 = w * 0.5f + (float) Math.cos(t * 0.9f) * (w * 0.38f);
        float cy1 = h * 0.32f + (float) Math.sin(t * 0.7f) * (h * 0.22f);
        float r1 = maxDim * 0.90f;
        int col1 = Color.argb(0xD8, Color.red(currentColorA), Color.green(currentColorA), Color.blue(currentColorA));
        RadialGradient g1 = new RadialGradient(cx1, cy1, r1, col1, Color.TRANSPARENT, Shader.TileMode.CLAMP);
        paint.setShader(g1);
        canvas.drawCircle(cx1, cy1, r1, paint);

        // Blob 2: mid-lower area, counter orbital rotation
        float cx2 = w * 0.5f + (float) Math.sin(-t * 0.8f) * (w * 0.35f);
        float cy2 = h * 0.68f + (float) Math.cos(-t * 0.6f) * (h * 0.25f);
        float r2 = maxDim * 0.86f;
        int col2 = Color.argb(0xCC, Color.red(currentColorB), Color.green(currentColorB), Color.blue(currentColorB));
        RadialGradient g2 = new RadialGradient(cx2, cy2, r2, col2, Color.TRANSPARENT, Shader.TileMode.CLAMP);
        paint.setShader(g2);
        canvas.drawCircle(cx2, cy2, r2, paint);

        // Blob 3: central accent harmonic pulse
        float cx3 = w * 0.5f + (float) Math.cos(t * 1.3f) * (w * 0.25f);
        float cy3 = h * 0.50f + (float) Math.sin(t * 1.1f) * (h * 0.18f);
        float r3 = maxDim * 0.76f;
        int col3 = Color.argb(0xC0, Color.red(currentColorC), Color.green(currentColorC), Color.blue(currentColorC));
        RadialGradient g3 = new RadialGradient(cx3, cy3, r3, col3, Color.TRANSPARENT, Shader.TileMode.CLAMP);
        paint.setShader(g3);
        canvas.drawCircle(cx3, cy3, r3, paint);

        paint.setShader(null);

        // Dark scrim overlay for high-contrast legible typography
        canvas.drawRect(0, 0, w, h, scrimPaint);

        // Continue fluid 60/120 FPS animation loop when visible
        if (isAttached && getVisibility() == VISIBLE) {
            postInvalidateOnAnimation();
        }
    }
}
