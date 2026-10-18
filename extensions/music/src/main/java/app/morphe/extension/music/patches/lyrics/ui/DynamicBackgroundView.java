/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.music.patches.lyrics.LyricsManager;
import app.morphe.extension.shared.Logger;

public final class DynamicBackgroundView extends View {

    private static final int BUFFER_SIZE = 256;
    private static final int BLUR_RADIUS = 14;
    private static final float TWO_PI = (float) (Math.PI * 2.0);
    private static final long ARTWORK_TRANSITION_DURATION_MS = 1000L;
    private static final long ROTATION_TICK_INTERVAL_MS = 50L;

    private static final float ROTATION_POWER = 0.8f;
    private static final float[] ROTATION_SPEEDS = new float[]{-0.10f, 0.18f, 0.32f};
    private static final float[] INITIAL_ROTATIONS = new float[]{0.3f, -2.1f, 2.4f};
    private static final float[] LAYER_SCALES = new float[]{1.4f, 1.26f, 1.26f};
    private static final float[] PERIMETER_SPEEDS = new float[]{0.09f, 0.012f, 0.02f};
    private static final float[] PERIMETER_DIRECTION = new float[]{-1.0f, 1.0f, 1.0f};
    private static final float[] LAYER_BASE_POSITIONS = new float[]{0.0f, 0.0f, 0.75f, -0.75f, -0.75f, 0.75f};

    private static final float[] BEAT_ROT_BOOST = new float[]{0.28f, -0.18f, 0.02f};
    private static final float[] BEAT_SPD_BOOST = new float[]{0.8f, 0.2f, 0.5f};
    private static final float[] BEAT_SCALE_BOOST = new float[]{0.2f, 0.34f, 0.39f};
    private static final float BEAT_SCALE_DECAY = 2.0f;

    private static final LinkedHashMap<String, Bitmap> LIQUID_ART_CACHE =
            new LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return size() > 8;
                }
            };

    @Nullable
    private static volatile Bitmap lastRenderedArt = null;
    private static volatile long globalLiquidRotationTimeMs = 0L;

    @Nullable
    private static Bitmap getCachedLiquidArt(@Nullable String key) {
        if (key == null) return null;
        synchronized (LIQUID_ART_CACHE) {
            return LIQUID_ART_CACHE.get(key);
        }
    }

    private static void putCachedLiquidArt(@Nullable String key, @NonNull Bitmap bmp) {
        if (key != null && !key.isEmpty()) {
            synchronized (LIQUID_ART_CACHE) {
                LIQUID_ART_CACHE.put(key, bmp);
            }
        }
        lastRenderedArt = bmp;
    }

    private static final ExecutorService COLOR_EXECUTOR = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Bitmap bufferBitmap;
    private final Canvas bufferCanvas;
    private final Bitmap blurBitmap;
    private final int[] pixelsSrc = new int[BUFFER_SIZE * BUFFER_SIZE];
    private final int[] pixelsDst = new int[BUFFER_SIZE * BUFFER_SIZE];
    private final int[] pixelsTemp = new int[BUFFER_SIZE * BUFFER_SIZE];
    private final Bitmap defaultArtwork;

    private final Paint layerPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint postProcessPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect screenRect = new Rect();
    private final Matrix layerMatrix = new Matrix();

    private LinearGradient scrimGradient;
    private int lastGradientHeight = -1;

    private final float[] layerPerimTime = new float[3];
    private final float[] layerBeatScale = new float[3];
    private final float[] layerBeatRot = new float[3];
    private float beatEnergyBaseline = 0.0f;

    private final float[] cachedLayerRots = new float[3];
    private final float[] cachedLayerScales = new float[3];
    private final float[] cachedLayerPosX = new float[3];
    private final float[] cachedLayerPosY = new float[3];

    private float artworkTransitionProgress = 1.0f;
    private long artworkTransitionStartUptimeMs = 0L;
    private long lastDrawTimeMs = 0L;
    private long lastRotationTickUptimeMs = 0L;

    private float beatPulse = 0.0f;
    private float visualizerPulse = 0.0f;
    private Visualizer visualizer;

    @Nullable
    private Bitmap currentArtwork;
    @Nullable
    private Bitmap previousArtwork;
    @Nullable
    private String currentVideoId;
    private boolean isAttached;

    public DynamicBackgroundView(Context context) {
        super(context);
        setWillNotDraw(false);

        bufferBitmap = Bitmap.createBitmap(BUFFER_SIZE, BUFFER_SIZE, Bitmap.Config.ARGB_8888);
        bufferCanvas = new Canvas(bufferBitmap);
        blurBitmap = Bitmap.createBitmap(BUFFER_SIZE, BUFFER_SIZE, Bitmap.Config.ARGB_8888);

        defaultArtwork = Bitmap.createBitmap(BUFFER_SIZE, BUFFER_SIZE, Bitmap.Config.ARGB_8888);
        Canvas defCanvas = new Canvas(defaultArtwork);
        defCanvas.drawColor(0xFF1E1E28);

        currentArtwork = (lastRenderedArt != null && !lastRenderedArt.isRecycled())
                ? lastRenderedArt
                : defaultArtwork;

        postProcessPaint.setColorFilter(new ColorMatrixColorFilter(createPostProcessMatrix()));
    }

    private static ColorMatrix createPostProcessMatrix() {
        ColorMatrix satMatrix = new ColorMatrix();
        satMatrix.setSaturation(3.0f);

        ColorMatrix cbMatrix = new ColorMatrix();
        float contrast = 0.95f;
        float brightness = 0.70f;
        float scale = brightness * contrast;
        float translate = 0.5f * (1.0f - contrast) * 255.0f;
        cbMatrix.set(new float[]{
                scale, 0, 0, 0, translate,
                0, scale, 0, 0, translate,
                0, 0, scale, 0, translate,
                0, 0, 0, 1, 0
        });

        ColorMatrix finalMatrix = new ColorMatrix();
        finalMatrix.postConcat(satMatrix);
        finalMatrix.postConcat(cbMatrix);
        return finalMatrix;
    }

    public void setArtwork(@Nullable Bitmap bitmap, @Nullable String videoId, @Nullable String title, @Nullable String artist) {
        if (Objects.equals(videoId, currentVideoId) && bitmap == null && currentArtwork != null && currentArtwork != defaultArtwork) {
            return;
        }
        currentVideoId = videoId;

        if (bitmap != null && !bitmap.isRecycled()) {
            applyNewArtwork(bitmap, videoId);
            return;
        }

        if (videoId != null && !videoId.isEmpty()) {
            Bitmap cached = getCachedLiquidArt(videoId);
            if (cached != null && !cached.isRecycled()) {
                applyNewArtwork(cached, videoId);
                return;
            }
        }

        if (videoId == null || videoId.isEmpty()) {
            applyNewArtwork(defaultArtwork, null);
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

                try (InputStream is = conn.getInputStream()) {
                    Bitmap downloaded = BitmapFactory.decodeStream(is);
                    if (downloaded != null) {
                        Bitmap scaled = Bitmap.createScaledBitmap(downloaded, BUFFER_SIZE, BUFFER_SIZE, true);
                        if (scaled != downloaded) {
                            downloaded.recycle();
                        }
                        putCachedLiquidArt(targetVid, scaled);
                        mainHandler.post(() -> {
                            if (Objects.equals(currentVideoId, targetVid)) {
                                applyNewArtwork(scaled, targetVid);
                            }
                        });
                    }
                }
            } catch (Exception ex) {
                Logger.printDebug(() -> "DynamicBackground: Failed to fetch artwork thumbnail", ex);
            }
        });
    }

    public void setVideoId(@Nullable String videoId, @Nullable String title, @Nullable String artist) {
        setArtwork(null, videoId, title, artist);
    }

    private void applyNewArtwork(@NonNull Bitmap newBmp, @Nullable String cacheKey) {
        Bitmap normalized;
        if (newBmp == defaultArtwork) {
            normalized = defaultArtwork;
        } else if (newBmp.getWidth() == BUFFER_SIZE && newBmp.getHeight() == BUFFER_SIZE) {
            normalized = newBmp;
        } else {
            normalized = Bitmap.createScaledBitmap(newBmp, BUFFER_SIZE, BUFFER_SIZE, true);
        }

        if (normalized != defaultArtwork) {
            putCachedLiquidArt(cacheKey, normalized);
        }

        if (currentArtwork != null && currentArtwork != normalized) {
            previousArtwork = currentArtwork;
            currentArtwork = normalized;
            artworkTransitionProgress = 0.0f;
            artworkTransitionStartUptimeMs = SystemClock.uptimeMillis();
        } else {
            currentArtwork = normalized;
            artworkTransitionProgress = 1.0f;
        }
        postInvalidateOnAnimation();
    }

    public void onBeatTrigger(float strength) {
    }

    private void tryInitVisualizer() {
        if (visualizer != null) return;
        Context ctx = getContext();
        if (ctx == null) return;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }
        try {
            visualizer = new Visualizer(0);
            int[] range = Visualizer.getCaptureSizeRange();
            int captureSize = (range != null && range.length >= 2) ? Math.min(range[1], 1024) : 512;
            visualizer.setCaptureSize(captureSize);
            visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer v, byte[] waveform, int samplingRate) {
                    if (waveform != null && waveform.length > 0) {
                        int maxDiff = 0;
                        for (byte b : waveform) {
                            int val = b & 0xFF;
                            int diff = Math.abs(val - 128);
                            if (diff > maxDiff) maxDiff = diff;
                        }
                        visualizerPulse = (float) maxDiff / 128.0f;
                    }
                }

                @Override
                public void onFftDataCapture(Visualizer v, byte[] fft, int samplingRate) {
                }
            }, Visualizer.getMaxCaptureRate(), true, false);
            visualizer.setEnabled(true);
        } catch (Throwable t) {
            releaseVisualizer();
        }
    }

    private void releaseVisualizer() {
        if (visualizer != null) {
            try {
                visualizer.setEnabled(false);
                visualizer.release();
            } catch (Throwable ignored) {
            }
            visualizer = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        isAttached = true;
        tryInitVisualizer();
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        isAttached = false;
        releaseVisualizer();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE && isAttached) {
            tryInitVisualizer();
            postInvalidateOnAnimation();
        } else {
            releaseVisualizer();
        }
    }

    private void processAudioPulse() {
        boolean isPlaying = LyricsManager.getInstance().isPlaying();
        if (isPlaying && visualizerPulse > 0.001f) {
            beatPulse = visualizerPulse;
            visualizerPulse *= 0.85f;
        } else {
            beatPulse = (beatPulse > 0.0001f) ? beatPulse * 0.9f : 0.0f;
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) return;

        final long now = SystemClock.uptimeMillis();
        final float elapsedSec = (lastDrawTimeMs > 0) ? Math.min((now - lastDrawTimeMs) / 1000.0f, 0.1f) : 0.016f;
        lastDrawTimeMs = now;

        if (lastRotationTickUptimeMs == 0L) {
            lastRotationTickUptimeMs = now;
        } else if (now - lastRotationTickUptimeMs >= ROTATION_TICK_INTERVAL_MS) {
            long ticks = (now - lastRotationTickUptimeMs) / ROTATION_TICK_INTERVAL_MS;
            globalLiquidRotationTimeMs += ticks * ROTATION_TICK_INTERVAL_MS;
            lastRotationTickUptimeMs += ticks * ROTATION_TICK_INTERVAL_MS;
        }
        final float currentTime = globalLiquidRotationTimeMs / 1000.0f;

        if (artworkTransitionProgress < 1.0f) {
            if (artworkTransitionStartUptimeMs > 0L) {
                artworkTransitionProgress = Math.min(1.0f,
                        (float) (now - artworkTransitionStartUptimeMs) / (float) ARTWORK_TRANSITION_DURATION_MS);
            } else {
                artworkTransitionProgress = 1.0f;
            }
        }

        processAudioPulse();
        final float pulse = beatPulse;

        beatEnergyBaseline += (pulse - beatEnergyBaseline) * Math.min(1.0f, 0.8f * elapsedSec);
        final float relativePulse = Math.max(0.0f, pulse - beatEnergyBaseline);

        final float attackSpeed = 12.0f;
        final float decaySpeed = BEAT_SCALE_DECAY;

        for (int i = 0; i < 3; i++) {
            layerPerimTime[i] += elapsedSec * (1.0f + pulse * BEAT_SPD_BOOST[i]);

            float speed = relativePulse > layerBeatScale[i] ? attackSpeed : decaySpeed;
            float delta = Math.min(1.0f, speed * elapsedSec);
            layerBeatScale[i] += (relativePulse - layerBeatScale[i]) * delta;
            layerBeatRot[i] += (relativePulse - layerBeatRot[i]) * delta;

            float bs = Math.max(0.0f, Math.min(1.0f, layerBeatScale[i]));
            float smoothBS = bs * bs * (3.0f - 2.0f * bs);

            float br = Math.max(0.0f, Math.min(1.0f, layerBeatRot[i]));
            float smoothBR = br * br * (3.0f - 2.0f * br);

            float rot = INITIAL_ROTATIONS[i] + (ROTATION_SPEEDS[i] * currentTime * ROTATION_POWER) + smoothBR * BEAT_ROT_BOOST[i];

            float bx = LAYER_BASE_POSITIONS[i * 2];
            float by = LAYER_BASE_POSITIONS[i * 2 + 1];

            float offset = i * 0.33f;
            float t = ((offset + PERIMETER_DIRECTION[i] * PERIMETER_SPEEDS[i] * layerPerimTime[i]) % 1.0f);
            if (t < 0.0f) t += 1.0f;
            float angle = t * TWO_PI;
            float px = Math.abs(bx) * (float) Math.cos(angle);
            float py = Math.abs(by) * (float) Math.sin(angle);

            cachedLayerRots[i] = rot;
            cachedLayerScales[i] = LAYER_SCALES[i] + smoothBS * BEAT_SCALE_BOOST[i];
            cachedLayerPosX[i] = px;
            cachedLayerPosY[i] = py;
        }

        bufferCanvas.drawColor(0xFF1E1E28);

        if (artworkTransitionProgress < 1.0f && previousArtwork != null && !previousArtwork.isRecycled()) {
            renderLayers(bufferCanvas, previousArtwork, 1.0f - artworkTransitionProgress);
        }
        Bitmap activeArt = (currentArtwork != null && !currentArtwork.isRecycled()) ? currentArtwork : defaultArtwork;
        renderLayers(bufferCanvas, activeArt, (previousArtwork != null) ? artworkTransitionProgress : 1.0f);

        bufferBitmap.getPixels(pixelsSrc, 0, BUFFER_SIZE, 0, 0, BUFFER_SIZE, BUFFER_SIZE);
        fastBoxBlur(pixelsSrc, pixelsTemp, pixelsDst, BUFFER_SIZE, BUFFER_SIZE, BLUR_RADIUS);
        blurBitmap.setPixels(pixelsDst, 0, BUFFER_SIZE, 0, 0, BUFFER_SIZE, BUFFER_SIZE);

        int maxDim = Math.max(w, h);
        int left = (w - maxDim) / 2;
        int top = (h - maxDim) / 2;
        screenRect.set(left, top, left + maxDim, top + maxDim);
        canvas.drawBitmap(blurBitmap, null, screenRect, postProcessPaint);

        if (scrimGradient == null || lastGradientHeight != h) {
            lastGradientHeight = h;
            scrimGradient = new LinearGradient(
                    0f, 0f, 0f, (float) h,
                    new int[]{0xCC000000, 0x88000000, 0xCC000000},
                    new float[]{0.0f, 0.5f, 1.0f},
                    Shader.TileMode.CLAMP
            );
            scrimPaint.setShader(scrimGradient);
        }
        canvas.drawRect(0, 0, w, h, scrimPaint);
        canvas.drawColor(0x27505050);

        if (isAttached && getVisibility() == VISIBLE) {
            postInvalidateOnAnimation();
        }
    }

    private void renderLayers(Canvas c, Bitmap bmp, float alpha) {
        if (bmp == null || bmp.isRecycled() || alpha <= 0.001f) return;
        layerPaint.setAlpha((int) (255 * Math.min(1.0f, Math.max(0.0f, alpha))));
        float baseScale = (float) BUFFER_SIZE / Math.min(bmp.getWidth(), bmp.getHeight());

        for (int i = 0; i < 3; i++) {
            layerMatrix.reset();
            layerMatrix.postTranslate(-bmp.getWidth() * 0.5f, -bmp.getHeight() * 0.5f);
            layerMatrix.postScale(baseScale * cachedLayerScales[i], baseScale * cachedLayerScales[i]);
            layerMatrix.postRotate((float) -Math.toDegrees(cachedLayerRots[i]));

            float transX = (BUFFER_SIZE * 0.5f) + cachedLayerPosX[i] * (BUFFER_SIZE * 0.5f);
            float transY = (BUFFER_SIZE * 0.5f) - cachedLayerPosY[i] * (BUFFER_SIZE * 0.5f);
            layerMatrix.postTranslate(transX, transY);

            c.drawBitmap(bmp, layerMatrix, layerPaint);
        }
    }

    private static void fastBoxBlur(int[] src, int[] temp, int[] dst, int w, int h, int radius) {
        if (radius < 1) return;
        boxBlurH(src, temp, w, h, radius);
        boxBlurV(temp, dst, w, h, radius);
        boxBlurH(dst, temp, w, h, radius);
        boxBlurV(temp, dst, w, h, radius);
    }

    private static void boxBlurH(int[] src, int[] dst, int w, int h, int radius) {
        final int div = 2 * radius + 1;
        for (int y = 0; y < h; y++) {
            final int row = y * w;
            final int firstPixel = src[row];
            final int fr = (firstPixel >> 16) & 0xFF;
            final int fg = (firstPixel >> 8) & 0xFF;
            final int fb = firstPixel & 0xFF;

            int rSum = fr * (radius + 1);
            int gSum = fg * (radius + 1);
            int bSum = fb * (radius + 1);

            for (int i = 1; i <= radius; i++) {
                int p = src[row + Math.min(i, w - 1)];
                rSum += (p >> 16) & 0xFF;
                gSum += (p >> 8) & 0xFF;
                bSum += p & 0xFF;
            }

            for (int x = 0; x < w; x++) {
                dst[row + x] = 0xFF000000
                        | ((rSum / div) << 16)
                        | ((gSum / div) << 8)
                        | (bSum / div);

                int pIn = src[row + Math.min(x + radius + 1, w - 1)];
                int pOut = src[row + Math.max(x - radius, 0)];

                rSum += ((pIn >> 16) & 0xFF) - ((pOut >> 16) & 0xFF);
                gSum += ((pIn >> 8) & 0xFF) - ((pOut >> 8) & 0xFF);
                bSum += (pIn & 0xFF) - (pOut & 0xFF);
            }
        }
    }

    private static void boxBlurV(int[] src, int[] dst, int w, int h, int radius) {
        final int div = 2 * radius + 1;
        for (int x = 0; x < w; x++) {
            final int firstPixel = src[x];
            final int fr = (firstPixel >> 16) & 0xFF;
            final int fg = (firstPixel >> 8) & 0xFF;
            final int fb = firstPixel & 0xFF;

            int rSum = fr * (radius + 1);
            int gSum = fg * (radius + 1);
            int bSum = fb * (radius + 1);

            for (int i = 1; i <= radius; i++) {
                int p = src[Math.min(i, h - 1) * w + x];
                rSum += (p >> 16) & 0xFF;
                gSum += (p >> 8) & 0xFF;
                bSum += p & 0xFF;
            }

            for (int y = 0; y < h; y++) {
                dst[y * w + x] = 0xFF000000
                        | ((rSum / div) << 16)
                        | ((gSum / div) << 8)
                        | (bSum / div);

                int pIn = src[Math.min(y + radius + 1, h - 1) * w + x];
                int pOut = src[Math.max(y - radius, 0) * w + x];

                rSum += ((pIn >> 16) & 0xFF) - ((pOut >> 16) & 0xFF);
                gSum += ((pIn >> 8) & 0xFF) - ((pOut >> 8) & 0xFF);
                bSum += (pIn & 0xFF) - (pOut & 0xFF);
            }
        }
    }
}