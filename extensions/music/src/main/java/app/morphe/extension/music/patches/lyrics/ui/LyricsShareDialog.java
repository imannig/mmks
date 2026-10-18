/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import app.morphe.extension.music.patches.lyrics.LyricsManager;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;

public final class LyricsShareDialog {

    public static final int MAX_SHARE_LYRICS_LINES = 5;

    private LyricsShareDialog() {
    }

    public static final class LyricsSelectionState {
        private boolean isSelectionMode = false;
        private final TreeSet<Integer> selectedIndices = new TreeSet<>();

        public boolean isSelectionMode() {
            return isSelectionMode;
        }

        @NonNull
        public Set<Integer> getSelectedIndices() {
            return Collections.unmodifiableSet(selectedIndices);
        }

        public void enter(int initialIndex) {
            isSelectionMode = true;
            selectedIndices.clear();
            selectedIndices.add(initialIndex);
        }

        public boolean toggle(int index) {
            if (selectedIndices.contains(index)) {
                selectedIndices.remove(index);
                return true;
            } else if (selectedIndices.size() < MAX_SHARE_LYRICS_LINES) {
                selectedIndices.add(index);
                return true;
            }
            return false;
        }

        public void clear() {
            isSelectionMode = false;
            selectedIndices.clear();
        }
    }

    public enum LyricsAspectRatio {
        SQUARE("1:1", 1f),
        STORY("9:16", 9f / 16f),
        WIDE("16:9", 16f / 9f),
        AUTO("Auto", 0f);

        public final String label;
        public final float ratio;

        LyricsAspectRatio(String label, float ratio) {
            this.label = label;
            this.ratio = ratio;
        }
    }

    public enum LyricsFontStyle {
        REGULAR("Regular"),
        BOLD("Bold"),
        SERIF("Serif"),
        MONO("Mono");

        public final String label;

        LyricsFontStyle(String label) {
            this.label = label;
        }

        @NonNull
        public Typeface toTypeface(boolean forceBold) {
            int style = forceBold ? Typeface.BOLD : Typeface.NORMAL;
            switch (this) {
                case BOLD:
                    return Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
                case SERIF:
                    return Typeface.create(Typeface.SERIF, style);
                case MONO:
                    return Typeface.create(Typeface.MONOSPACE, style);
                case REGULAR:
                default:
                    return Typeface.create(Typeface.SANS_SERIF, style);
            }
        }
    }

    public enum LyricsFontSize {
        SMALL("S", 0.8f),
        MEDIUM("M", 1.0f),
        LARGE("L", 1.25f);

        public final String label;
        public final float scale;

        LyricsFontSize(String label, float scale) {
            this.label = label;
            this.scale = scale;
        }
    }

    public enum LyricsAlignment {
        LEFT("Left", Layout.Alignment.ALIGN_NORMAL),
        CENTER("Center", Layout.Alignment.ALIGN_CENTER),
        RIGHT("Right", Layout.Alignment.ALIGN_OPPOSITE);

        public final String label;
        public final Layout.Alignment layoutAlignment;

        LyricsAlignment(String label, Layout.Alignment layoutAlignment) {
            this.label = label;
            this.layoutAlignment = layoutAlignment;
        }
    }

    public static final class LyricsShareTheme {
        public final String name;
        public final int backgroundColor;
        public final int backgroundGradientEnd;
        public final int textColor;
        public final int secondaryTextColor;
        public final int accentColor;
        public final boolean useAlbumArtBlur;

        public LyricsShareTheme(
                String name,
                int backgroundColor,
                int backgroundGradientEnd,
                int textColor,
                int secondaryTextColor,
                int accentColor,
                boolean useAlbumArtBlur) {
            this.name = name;
            this.backgroundColor = backgroundColor;
            this.backgroundGradientEnd = backgroundGradientEnd;
            this.textColor = textColor;
            this.secondaryTextColor = secondaryTextColor;
            this.accentColor = accentColor;
            this.useAlbumArtBlur = useAlbumArtBlur;
        }
    }

    public static final List<LyricsShareTheme> SHARE_THEMES = List.of(
            new LyricsShareTheme("Classic Dark", 0xFF121212, 0xFF1E1E2E, 0xFFFFFFFF, 0xFFB0B0B0, 0xFFBB86FC, false),
            new LyricsShareTheme("Vibrant", 0xFF1A0533, 0xFF0D2137, 0xFFFFFFFF, 0xFFD0C4DF, 0xFFFF6EC7, false),
            new LyricsShareTheme("Minimal Light", 0xFFF8F6F4, 0xFFEDE8E3, 0xFF1A1A1A, 0xFF666666, 0xFF6200EE, false),
            new LyricsShareTheme("Spotify Green", 0xFF0A1F0A, 0xFF121212, 0xFFFFFFFF, 0xFFA8D5A8, 0xFF1DB954, false),
            new LyricsShareTheme("Sunset", 0xFF2D1B2E, 0xFF1F1638, 0xFFFFF5E4, 0xFFD4A5A5, 0xFFFF6B6B, false),
            new LyricsShareTheme("Ocean", 0xFF0B1929, 0xFF0D2847, 0xFFE8F1F5, 0xFF8BB8D0, 0xFF00B4D8, false),
            new LyricsShareTheme("Neon Purple", 0xFF0F051D, 0xFF1A0A30, 0xFFF0E6FF, 0xFFB8A0D8, 0xFFA855F7, false),
            new LyricsShareTheme("Warm Sepia", 0xFF2C221E, 0xFF1E1714, 0xFFF5E6D3, 0xFFBFA890, 0xFFD4A574, false),
            new LyricsShareTheme("Rose Gold", 0xFF2A1F24, 0xFF1E1519, 0xFFFFF0F3, 0xFFD4A0AC, 0xFFF48FB1, false),
            new LyricsShareTheme("Midnight Blue", 0xFF0A0E1A, 0xFF141E30, 0xFFE2E8F0, 0xFF94A3B8, 0xFF38BDF8, false),
            new LyricsShareTheme("Forest", 0xFF0D1F14, 0xFF162A1E, 0xFFE6F4EA, 0xFF8FBF9F, 0xFF34D399, false),
            new LyricsShareTheme("Crimson", 0xFF2A0A0A, 0xFF1A0606, 0xFFFFF0F0, 0xFFD4A0A0, 0xFFEF4444, false),
            new LyricsShareTheme("Album Art", 0xFF121212, 0xFF1E1E2E, 0xFFFFFFFF, 0xFFD0D0D0, 0xFFFFFFFF, true)
    );

    public static final class LyricsCardLine {
        public final String main;
        @Nullable
        public final String romanized;
        @Nullable
        public final String translated;

        public LyricsCardLine(@NonNull String main, @Nullable String romanized, @Nullable String translated) {
            this.main = main;
            this.romanized = romanized;
            this.translated = translated;
        }
    }

    public static final class LyricsCardConfig {
        public final List<LyricsCardLine> lines;
        public final String songTitle;
        public final String artistName;
        @Nullable
        public final Bitmap coverBitmap;
        public LyricsShareTheme theme = SHARE_THEMES.get(0);
        public LyricsAspectRatio aspectRatio = LyricsAspectRatio.AUTO;
        public LyricsFontStyle fontStyle = LyricsFontStyle.BOLD;
        public LyricsFontSize fontSize = LyricsFontSize.MEDIUM;
        public LyricsAlignment alignment = LyricsAlignment.LEFT;
        public boolean showCoverArt = true;
        public boolean showSongInfo = true;
        public boolean showBranding = true;
        public boolean showQuoteMark = true;
        public boolean showRomanization = true;
        public boolean showTranslation = true;

        public LyricsCardConfig(
                @NonNull List<LyricsCardLine> lines,
                @NonNull String songTitle,
                @NonNull String artistName,
                @Nullable Bitmap coverBitmap) {
            this.lines = lines;
            this.songTitle = songTitle;
            this.artistName = artistName;
            this.coverBitmap = coverBitmap;
        }
    }

    @NonNull
    public static Bitmap renderToBitmap(@NonNull LyricsCardConfig config) {
        final int cardWidth = 1080;
        final float padding = 80f;
        final float contentWidth = cardWidth - padding * 2f;
        final float cornerRadius = 40f;

        boolean isBold = (config.fontStyle == LyricsFontStyle.BOLD || config.fontStyle == LyricsFontStyle.REGULAR);
        Typeface lyricsTf = config.fontStyle.toTypeface(isBold);
        Typeface secondaryTf = config.fontStyle.toTypeface(false);
        Typeface brandingTf = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);

        float baseLyricsSizePx = 46f * config.fontSize.scale;
        float baseSecondarySizePx = baseLyricsSizePx * 0.62f;
        float lineSpacingPx = 28f * config.fontSize.scale;
        float subLineSpacingPx = 8f * config.fontSize.scale;

        TextPaint mainPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        mainPaint.setColor(config.theme.textColor);
        mainPaint.setTextSize(baseLyricsSizePx);
        mainPaint.setTypeface(lyricsTf);

        TextPaint romPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        romPaint.setColor(withAlpha(config.theme.secondaryTextColor, 0.85f));
        romPaint.setTextSize(baseSecondarySizePx);
        romPaint.setTypeface(secondaryTf);

        TextPaint transPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        transPaint.setColor(withAlpha(config.theme.secondaryTextColor, 0.7f));
        transPaint.setTextSize(baseSecondarySizePx);
        transPaint.setTypeface(secondaryTf);

        int maxLineWidth = Math.max(1, (int) contentWidth);
        List<StaticLayout> mainLayouts = new ArrayList<>(config.lines.size());
        List<StaticLayout> romLayouts = new ArrayList<>(config.lines.size());
        List<StaticLayout> transLayouts = new ArrayList<>(config.lines.size());

        float totalLyricsHeight = 0f;
        for (int i = 0; i < config.lines.size(); i++) {
            LyricsCardLine line = config.lines.get(i);
            StaticLayout ml = buildStaticLayout(line.main, mainPaint, maxLineWidth, config.alignment.layoutAlignment, 1.3f);
            mainLayouts.add(ml);
            totalLyricsHeight += ml.getHeight();

            if (config.showRomanization && line.romanized != null && !line.romanized.trim().isEmpty()) {
                StaticLayout rl = buildStaticLayout(line.romanized, romPaint, maxLineWidth, config.alignment.layoutAlignment, 1.25f);
                romLayouts.add(rl);
                totalLyricsHeight += subLineSpacingPx + rl.getHeight();
            } else {
                romLayouts.add(null);
            }

            if (config.showTranslation && line.translated != null && !line.translated.trim().isEmpty()) {
                StaticLayout tl = buildStaticLayout(line.translated, transPaint, maxLineWidth, config.alignment.layoutAlignment, 1.25f);
                transLayouts.add(tl);
                totalLyricsHeight += subLineSpacingPx + tl.getHeight();
            } else {
                transLayouts.add(null);
            }

            if (i < config.lines.size() - 1) {
                totalLyricsHeight += lineSpacingPx;
            }
        }

        boolean hasHeader = config.showCoverArt || config.showSongInfo;
        float headerHeight = hasHeader ? (config.showCoverArt ? 120f : 80f) : 0f;
        float headerBottomGap = hasHeader ? 48f : 0f;
        float quoteHeight = config.showQuoteMark ? 60f : 0f;
        float footerTopGap = config.showBranding ? 48f : 0f;
        float footerHeight = config.showBranding ? 50f : 0f;

        int calculatedHeight = (int) (padding * 2f + headerHeight + headerBottomGap + quoteHeight
                + totalLyricsHeight + footerTopGap + footerHeight);

        int cardHeight;
        switch (config.aspectRatio) {
            case SQUARE:
                cardHeight = Math.max(cardWidth, calculatedHeight);
                break;
            case STORY:
                cardHeight = Math.max((int) (cardWidth * 16f / 9f), calculatedHeight);
                break;
            case WIDE:
                cardHeight = Math.max((int) (cardWidth * 9f / 16f), calculatedHeight);
                break;
            case AUTO:
            default:
                cardHeight = Math.max(600, calculatedHeight);
                break;
        }

        Bitmap outBitmap = Bitmap.createBitmap(cardWidth, cardHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(outBitmap);

        Path clipPath = new Path();
        clipPath.addRoundRect(new RectF(0, 0, cardWidth, cardHeight), cornerRadius, cornerRadius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clipPath);

        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (config.theme.useAlbumArtBlur && config.coverBitmap != null && !config.coverBitmap.isRecycled()) {
            Bitmap blurred = createBlurredArt(config.coverBitmap, 128, 14);
            canvas.drawBitmap(blurred, null, new Rect(0, 0, cardWidth, cardHeight), new Paint(Paint.FILTER_BITMAP_FLAG));
            blurred.recycle();
            bgPaint.setColor(0x99000000);
            canvas.drawRect(0, 0, cardWidth, cardHeight, bgPaint);
        } else {
            LinearGradient grad = new LinearGradient(
                    0f, 0f, cardWidth, cardHeight,
                    config.theme.backgroundColor,
                    config.theme.backgroundGradientEnd,
                    Shader.TileMode.CLAMP);
            bgPaint.setShader(grad);
            canvas.drawRect(0, 0, cardWidth, cardHeight, bgPaint);
        }

        Paint glowCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glowCirclePaint.setColor(withAlpha(config.theme.accentColor, 0.08f));
        canvas.drawCircle(cardWidth * 0.85f, cardHeight * 0.15f, cardWidth * 0.35f, glowCirclePaint);

        float currentY = padding;

        if (hasHeader) {
            float coverSize = 110f;
            float textStartX = padding;
            if (config.showCoverArt && config.coverBitmap != null && !config.coverBitmap.isRecycled()) {
                canvas.save();
                Path coverClip = new Path();
                coverClip.addRoundRect(new RectF(padding, currentY, padding + coverSize, currentY + coverSize),
                        20f, 20f, Path.Direction.CW);
                canvas.clipPath(coverClip);
                canvas.drawBitmap(config.coverBitmap, null,
                        new RectF(padding, currentY, padding + coverSize, currentY + coverSize),
                        new Paint(Paint.FILTER_BITMAP_FLAG));
                canvas.restore();
                textStartX = padding + coverSize + 28f;
            }

            if (config.showSongInfo) {
                TextPaint titleP = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                titleP.setColor(config.theme.textColor);
                titleP.setTextSize(34f);
                titleP.setTypeface(brandingTf);

                TextPaint artistP = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                artistP.setColor(config.theme.secondaryTextColor);
                artistP.setTextSize(26f);
                artistP.setTypeface(secondaryTf);

                float textBlockY = currentY + (config.showCoverArt ? 38f : 28f);
                canvas.drawText(ellipsize(config.songTitle, titleP, cardWidth - padding - textStartX),
                        textStartX, textBlockY, titleP);
                canvas.drawText(ellipsize(config.artistName, artistP, cardWidth - padding - textStartX),
                        textStartX, textBlockY + 40f, artistP);
            }

            currentY += headerHeight + 24f;
            Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            dividerPaint.setColor(withAlpha(config.theme.secondaryTextColor, 0.2f));
            dividerPaint.setStrokeWidth(2f);
            canvas.drawLine(padding, currentY, cardWidth - padding, currentY, dividerPaint);
            currentY += headerBottomGap - 24f;
        }

        if (config.showQuoteMark) {
            TextPaint quoteP = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            quoteP.setColor(withAlpha(config.theme.accentColor, 0.3f));
            quoteP.setTextSize(90f);
            quoteP.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
            float qX = padding;
            if (config.alignment == LyricsAlignment.CENTER) {
                qX = (cardWidth - quoteP.measureText("\u201C")) / 2f;
            } else if (config.alignment == LyricsAlignment.RIGHT) {
                qX = cardWidth - padding - quoteP.measureText("\u201C");
            }
            canvas.drawText("\u201C", qX, currentY + 54f, quoteP);
            currentY += quoteHeight;
        }

        float availableMiddle = cardHeight - padding - footerTopGap - footerHeight - currentY;
        float lyricsStartY = currentY + Math.max(0f, (availableMiddle - totalLyricsHeight) / 2f);

        float ly = lyricsStartY;
        for (int i = 0; i < config.lines.size(); i++) {
            StaticLayout ml = mainLayouts.get(i);
            canvas.save();
            canvas.translate(padding, ly);
            ml.draw(canvas);
            canvas.restore();
            ly += ml.getHeight();

            StaticLayout rl = romLayouts.get(i);
            if (rl != null) {
                ly += subLineSpacingPx;
                canvas.save();
                canvas.translate(padding, ly);
                rl.draw(canvas);
                canvas.restore();
                ly += rl.getHeight();
            }

            StaticLayout tl = transLayouts.get(i);
            if (tl != null) {
                ly += subLineSpacingPx;
                canvas.save();
                canvas.translate(padding, ly);
                tl.draw(canvas);
                canvas.restore();
                ly += tl.getHeight();
            }

            if (i < config.lines.size() - 1) {
                ly += lineSpacingPx;
            }
        }

        if (config.showBranding) {
            float footerY = cardHeight - padding - footerHeight;
            Paint dotP = new Paint(Paint.ANTI_ALIAS_FLAG);
            dotP.setColor(config.theme.accentColor);
            canvas.drawCircle(padding + 14f, footerY + 25f, 14f, dotP);

            TextPaint brandP = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            brandP.setColor(withAlpha(config.theme.secondaryTextColor, 0.7f));
            brandP.setTextSize(24f);
            brandP.setTypeface(brandingTf);
            canvas.drawText("YouTube Music", padding + 42f, footerY + 33f, brandP);
        }

        canvas.restore();
        return outBitmap;
    }

    private static int withAlpha(int color, float alphaFactor) {
        int a = Math.max(0, Math.min(255, Math.round(Color.alpha(color) * alphaFactor)));
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    @NonNull
    private static String ellipsize(@NonNull String text, @NonNull TextPaint paint, float maxWidth) {
        if (maxWidth <= 0f || paint.measureText(text) <= maxWidth) return text;
        String ellipsis = "...";
        float ellW = paint.measureText(ellipsis);
        int end = text.length();
        while (end > 0 && paint.measureText(text, 0, end) + ellW > maxWidth) {
            end--;
        }
        return text.substring(0, Math.max(0, end)) + ellipsis;
    }

    @NonNull
    private static StaticLayout buildStaticLayout(
            @NonNull String text,
            @NonNull TextPaint paint,
            int width,
            @NonNull Layout.Alignment align,
            float spacingMult) {
        StaticLayout.Builder b = StaticLayout.Builder
                .obtain(text, 0, text.length(), paint, Math.max(1, width))
                .setAlignment(align)
                .setLineSpacing(0f, spacingMult)
                .setIncludePad(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            b.setUseLineSpacingFromFallbacks(true);
        }
        return b.build();
    }

    @NonNull
    private static Bitmap createBlurredArt(@NonNull Bitmap src, int size, int radius) {
        Bitmap scaled = Bitmap.createScaledBitmap(src, size, size, true);
        int[] pix = new int[size * size];
        int[] tmp = new int[size * size];
        scaled.getPixels(pix, 0, size, 0, 0, size, size);
        int div = 2 * radius + 1;
        for (int y = 0; y < size; y++) {
            int row = y * size;
            for (int x = 0; x < size; x++) {
                int r = 0, g = 0, b = 0;
                for (int k = -radius; k <= radius; k++) {
                    int p = pix[row + Math.max(0, Math.min(size - 1, x + k))];
                    r += (p >> 16) & 0xFF;
                    g += (p >> 8) & 0xFF;
                    b += p & 0xFF;
                }
                tmp[row + x] = 0xFF000000 | ((r / div) << 16) | ((g / div) << 8) | (b / div);
            }
        }
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                int r = 0, g = 0, b = 0;
                for (int k = -radius; k <= radius; k++) {
                    int p = tmp[Math.max(0, Math.min(size - 1, y + k)) * size + x];
                    r += (p >> 16) & 0xFF;
                    g += (p >> 8) & 0xFF;
                    b += p & 0xFF;
                }
                pix[y * size + x] = 0xFF000000 | ((r / div) << 16) | ((g / div) << 8) | (b / div);
            }
        }
        scaled.setPixels(pix, 0, size, 0, 0, size, size);
        return scaled;
    }

    public static void show(
            @NonNull Context context,
            @NonNull List<LyricsLine> allLines,
            @Nullable List<LyricsLine> romanizedLines,
            @Nullable List<String> translatedLines,
            int initialLineIndex) {
        if (allLines.isEmpty()) return;

        LyricsManager manager = LyricsManager.getInstance();
        TrackInfo track = manager.getCurrentTrack();
        String songTitle = (track != null && track.title() != null) ? track.title() : "";
        String artistName = (track != null && track.artist() != null) ? track.artist() : "";
        Bitmap coverBitmap = manager.getCurrentArtworkBitmap();

        final LyricsSelectionState selection = new LyricsSelectionState();
        int safeInit = Math.max(0, Math.min(allLines.size() - 1, initialLineIndex));
        selection.enter(safeInit);

        final LyricsCardConfig config = new LyricsCardConfig(
                buildCardLines(allLines, romanizedLines, translatedLines, selection.getSelectedIndices()),
                songTitle,
                artistName,
                coverBitmap);

        ScrollView scrollRoot = new ScrollView(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Dim.dp(16);
        root.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF181820);
        bg.setCornerRadius(Dim.dp(20));
        root.setBackground(bg);
        scrollRoot.addView(root);

        TextView headerTitle = new TextView(context);
        headerTitle.setText("Share Lyrics (up to " + MAX_SHARE_LYRICS_LINES + " lines)");
        headerTitle.setTextColor(Color.WHITE);
        headerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        headerTitle.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(headerTitle);

        final ImageView previewImage = new ImageView(context);
        previewImage.setAdjustViewBounds(true);
        previewImage.setMaxHeight(Dim.dp(280));
        LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        prevLp.topMargin = Dim.dp(12);
        prevLp.bottomMargin = Dim.dp(12);
        root.addView(previewImage, prevLp);

        final Bitmap[] currentRendered = new Bitmap[]{null};
        Runnable refreshPreview = () -> {
            List<LyricsCardLine> updatedLines = buildCardLines(
                    allLines, romanizedLines, translatedLines, selection.getSelectedIndices());
            config.lines.clear();
            config.lines.addAll(updatedLines);
            if (currentRendered[0] != null && !currentRendered[0].isRecycled()) {
                currentRendered[0].recycle();
            }
            currentRendered[0] = renderToBitmap(config);
            previewImage.setImageBitmap(currentRendered[0]);
        };

        HorizontalScrollView themeScroll = new HorizontalScrollView(context);
        themeScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout themeRow = new LinearLayout(context);
        themeRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int t = 0; t < SHARE_THEMES.size(); t++) {
            final LyricsShareTheme theme = SHARE_THEMES.get(t);
            TextView chip = createChip(context, theme.name, theme.backgroundColor, theme.textColor);
            chip.setOnClickListener(v -> {
                config.theme = theme;
                refreshPreview.run();
            });
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chipLp.rightMargin = Dim.dp(8);
            themeRow.addView(chip, chipLp);
        }
        themeScroll.addView(themeRow);
        root.addView(themeScroll);

        HorizontalScrollView styleScroll = new HorizontalScrollView(context);
        styleScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout styleRow = new LinearLayout(context);
        styleRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams styleRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        styleRowLp.topMargin = Dim.dp(8);

        for (LyricsAspectRatio ratio : LyricsAspectRatio.values()) {
            TextView chip = createChip(context, ratio.label, 0xFF2A2A36, Color.WHITE);
            chip.setOnClickListener(v -> {
                config.aspectRatio = ratio;
                refreshPreview.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Dim.dp(6);
            styleRow.addView(chip, lp);
        }
        for (LyricsAlignment align : LyricsAlignment.values()) {
            TextView chip = createChip(context, align.label, 0xFF2A2A36, Color.WHITE);
            chip.setOnClickListener(v -> {
                config.alignment = align;
                refreshPreview.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Dim.dp(6);
            styleRow.addView(chip, lp);
        }
        for (LyricsFontSize fSize : LyricsFontSize.values()) {
            TextView chip = createChip(context, "Size " + fSize.label, 0xFF2A2A36, Color.WHITE);
            chip.setOnClickListener(v -> {
                config.fontSize = fSize;
                refreshPreview.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Dim.dp(6);
            styleRow.addView(chip, lp);
        }
        styleScroll.addView(styleRow);
        root.addView(styleScroll, styleRowLp);

        LinearLayout linesList = new LinearLayout(context);
        linesList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        listLp.topMargin = Dim.dp(10);

        int startIdx = Math.max(0, safeInit - 2);
        int endIdx = Math.min(allLines.size() - 1, safeInit + 6);
        for (int i = startIdx; i <= endIdx; i++) {
            final int lineIdx = i;
            String text = allLines.get(i).text();
            if (text == null || text.trim().isEmpty()) continue;
            final TextView lineItem = new TextView(context);
            lineItem.setText(text.trim());
            lineItem.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            int itemPad = Dim.dp(8);
            lineItem.setPadding(itemPad, itemPad, itemPad, itemPad);
            styleLineSelectionItem(lineItem, selection.getSelectedIndices().contains(lineIdx));

            lineItem.setOnClickListener(v -> {
                if (selection.toggle(lineIdx)) {
                    if (selection.getSelectedIndices().isEmpty()) {
                        selection.enter(lineIdx);
                    }
                    styleLineSelectionItem(lineItem, selection.getSelectedIndices().contains(lineIdx));
                    refreshPreview.run();
                }
            });
            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            itemLp.bottomMargin = Dim.dp(4);
            linesList.addView(lineItem, itemLp);
        }
        root.addView(linesList, listLp);

        LinearLayout actionRow = new LinearLayout(context);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.END);
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionLp.topMargin = Dim.dp(14);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(scrollRoot)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        TextView copyTextBtn = createChip(context, "Copy Text", 0xFF2E2E3A, Color.WHITE);
        TextView saveImgBtn = createChip(context, "Save Image", 0xFF3A82F7, Color.WHITE);

        copyTextBtn.setOnClickListener(v -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < config.lines.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(config.lines.get(i).main);
            }
            ClipboardManager cb = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cb != null) {
                cb.setPrimaryClip(ClipData.newPlainText("lyrics", sb.toString()));
                Utils.showToastShort("Lyrics copied");
            }
            dialog.dismiss();
        });

        saveImgBtn.setOnClickListener(v -> {
            Bitmap bmp = currentRendered[0];
            if (bmp != null && !bmp.isRecycled()) {
                saveBitmapToGallery(context, bmp, config.songTitle);
            }
            dialog.dismiss();
        });

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.leftMargin = Dim.dp(8);
        actionRow.addView(copyTextBtn, btnLp);
        actionRow.addView(saveImgBtn, btnLp);
        root.addView(actionRow, actionLp);

        refreshPreview.run();
        dialog.show();
    }

    private static void styleLineSelectionItem(@NonNull TextView tv, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Dim.dp(10));
        if (selected) {
            bg.setColor(0x333A82F7);
            bg.setStroke(Dim.dp(1), 0xFF3A82F7);
            tv.setTextColor(Color.WHITE);
        } else {
            bg.setColor(0x14FFFFFF);
            tv.setTextColor(0xAAFFFFFF);
        }
        tv.setBackground(bg);
    }

    @NonNull
    private static TextView createChip(@NonNull Context context, @NonNull String label, int bgColor, int textColor) {
        TextView tv = new TextView(context);
        tv.setText(label);
        tv.setTextColor(textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        int hPad = Dim.dp(12);
        int vPad = Dim.dp(6);
        tv.setPadding(hPad, vPad, hPad, vPad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(Dim.dp(999));
        bg.setStroke(Dim.dp(1), 0x33FFFFFF);
        tv.setBackground(bg);
        return tv;
    }

    @NonNull
    private static List<LyricsCardLine> buildCardLines(
            @NonNull List<LyricsLine> allLines,
            @Nullable List<LyricsLine> romanizedLines,
            @Nullable List<String> translatedLines,
            @NonNull Set<Integer> selectedIndices) {
        List<LyricsCardLine> out = new ArrayList<>();
        for (Integer idx : selectedIndices) {
            if (idx == null || idx < 0 || idx >= allLines.size()) continue;
            String main = allLines.get(idx).text();
            if (main == null || main.trim().isEmpty()) continue;
            String rom = null;
            if (romanizedLines != null && idx < romanizedLines.size() && romanizedLines.get(idx) != null) {
                rom = romanizedLines.get(idx).text();
            }
            String trans = null;
            if (translatedLines != null && idx < translatedLines.size()) {
                trans = translatedLines.get(idx);
            }
            out.add(new LyricsCardLine(main.trim(), rom, trans));
        }
        return out;
    }

    private static void saveBitmapToGallery(@NonNull Context context, @NonNull Bitmap bitmap, @NonNull String title) {
        String safeTitle = title.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (safeTitle.isEmpty()) safeTitle = "lyrics";
        String filename = "Lyrics_" + safeTitle + "_" + System.currentTimeMillis() + ".png";

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Lyrics");
                Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                        if (os != null) {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
                        }
                    }
                    Utils.showToastShort("Saved to Pictures/Lyrics/" + filename);
                    return;
                }
            }
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Lyrics");
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            File file = new File(dir, filename);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            Utils.showToastShort("Saved to " + file.getAbsolutePath());
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to save lyrics share card", ex);
        }
    }
}