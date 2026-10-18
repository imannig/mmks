/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.music.patches.lyrics.LyricsManager;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.ui.Dim;

final class OffsetRulerView extends View {
    private static final int RANGE_MS = 20000;
    public static final int OFFSET_STEP_MS = 50;

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

    @NonNull
    public static String formatOffsetLabel(int offsetMs) {
        return (offsetMs >= 0 ? "+" : "") + offsetMs + "ms";
    }

    public static void showOffsetEditorDialog(
            @NonNull Context context,
            @Nullable Runnable onOffsetChanged) {
        final LyricsManager manager = LyricsManager.getInstance();
        final int[] currentOffset = new int[]{
                Settings.LYRICS_OFFSET_MS.get() + manager.getTemporaryOffsetMs()
        };

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Dim.dp(20);
        root.setPadding(pad, pad, pad, pad);
        GradientDrawable rootBg = new GradientDrawable();
        rootBg.setColor(0xFF1E1E24);
        rootBg.setCornerRadius(Dim.dp(20));
        root.setBackground(rootBg);

        TextView titleView = new TextView(context);
        titleView.setText("Lyrics Offset");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setGravity(Gravity.CENTER);
        root.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout controlsRow = new LinearLayout(context);
        controlsRow.setOrientation(LinearLayout.HORIZONTAL);
        controlsRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = Dim.dp(16);
        rowLp.bottomMargin = Dim.dp(16);

        TextView minusBtn = createPillButton(context, "-50ms", 0x26FFFFFF, Color.WHITE);
        TextView valueLabel = createPillButton(context, formatOffsetLabel(currentOffset[0]), 0x1AFFFFFF, Color.WHITE);
        valueLabel.setMinWidth(Dim.dp(96));
        TextView plusBtn = createPillButton(context, "+50ms", 0x26FFFFFF, Color.WHITE);

        Runnable applyUpdate = () -> {
            currentOffset[0] = Math.max(-RANGE_MS, Math.min(RANGE_MS, currentOffset[0]));
            valueLabel.setText(formatOffsetLabel(currentOffset[0]));
            int baseSetting = Settings.LYRICS_OFFSET_MS.get();
            manager.setTemporaryOffsetMs(currentOffset[0] - baseSetting);
            if (onOffsetChanged != null) {
                onOffsetChanged.run();
            }
        };

        minusBtn.setOnClickListener(v -> {
            currentOffset[0] -= OFFSET_STEP_MS;
            applyUpdate.run();
        });

        plusBtn.setOnClickListener(v -> {
            currentOffset[0] += OFFSET_STEP_MS;
            applyUpdate.run();
        });

        valueLabel.setOnClickListener(v -> showNumericInputDialog(context, currentOffset[0], newVal -> {
            currentOffset[0] = newVal;
            applyUpdate.run();
        }));

        LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        itemLp.setMargins(Dim.dp(6), 0, Dim.dp(6), 0);
        controlsRow.addView(minusBtn, itemLp);
        controlsRow.addView(valueLabel, itemLp);
        controlsRow.addView(plusBtn, itemLp);
        root.addView(controlsRow, rowLp);

        LinearLayout actionsRow = new LinearLayout(context);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setGravity(Gravity.END);

        TextView resetBtn = createPillButton(context, "Reset", Color.TRANSPARENT, 0xFFB0B0B8);
        TextView doneBtn = createPillButton(context, "Done", 0xFF3A82F7, Color.WHITE);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(root)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        resetBtn.setOnClickListener(v -> {
            currentOffset[0] = 0;
            Settings.LYRICS_OFFSET_MS.save(0);
            manager.resetTemporaryOffsetMs();
            valueLabel.setText(formatOffsetLabel(0));
            if (onOffsetChanged != null) {
                onOffsetChanged.run();
            }
        });

        doneBtn.setOnClickListener(v -> {
            Settings.LYRICS_OFFSET_MS.save(currentOffset[0]);
            manager.resetTemporaryOffsetMs();
            if (onOffsetChanged != null) {
                onOffsetChanged.run();
            }
            dialog.dismiss();
        });

        actionsRow.addView(resetBtn, itemLp);
        actionsRow.addView(doneBtn, itemLp);
        root.addView(actionsRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.show();
    }

    private interface IntConsumer {
        void accept(int value);
    }

    private static void showNumericInputDialog(
            @NonNull Context context,
            int initialMs,
            @NonNull IntConsumer onConfirm) {
        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setText(String.valueOf(initialMs));
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(context)
                .setTitle("Enter Offset (ms)")
                .setView(input)
                .setPositiveButton("OK", (d, w) -> {
                    try {
                        int parsed = Integer.parseInt(input.getText().toString().trim());
                        onConfirm.accept(Math.max(-RANGE_MS, Math.min(RANGE_MS, parsed)));
                    } catch (NumberFormatException ignored) {
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @NonNull
    private static TextView createPillButton(
            @NonNull Context context,
            @NonNull String text,
            int bgColor,
            int textColor) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        int hPad = Dim.dp(14);
        int vPad = Dim.dp(8);
        tv.setPadding(hPad, vPad, hPad, vPad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(Dim.dp(999));
        tv.setBackground(bg);
        return tv;
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

        String offsetText = formatOffsetLabel(currentOffsetMs);
        String text = recordTimeMs == null
                ? offsetText
                : SponsorBlockMusicSubmitDialog.formatTimeMs(recordTimeMs) + " " + offsetText;
        valuePaint.setTextSize(13 * density);
        canvas.drawText(text, w / 2f, h / 2f + 5 * density, valuePaint);
    }
}