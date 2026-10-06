/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.preference.SwitchPreference;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Pair;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.GeminiClient;
import app.morphe.extension.music.patches.lyrics.OpenAIClient;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

@SuppressWarnings({"unused", "deprecation"})
public class LyricsAiConfigPreference extends SwitchPreference
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String[] TRANS_LABELS = {
            "Google (Default, Free)",
            "Gemini API",
            "OpenRouter",
            "OpenAI API",
            "OpenAI Compatible (Custom)",
            "DeepL API"
    };
    private static final String[] TRANS_VALUES = {
            "google",
            "gemini",
            "openrouter",
            "openai",
            "openai_compatible",
            "deepl"
    };

    private static final String[] ROMA_LABELS = {
            "Google (Default)",
            "Gemini API",
            "OpenRouter",
            "OpenAI API",
            "OpenAI Compatible (Custom)"
    };
    private static final String[] ROMA_VALUES = {
            "google",
            "gemini",
            "openrouter",
            "openai",
            "openai_compatible"
    };

    private boolean dialogShowing = false;
    private boolean settingFromCode = false;
    private boolean isCurrentlyVisible = true;
    private android.preference.PreferenceGroup cachedParent;

    public interface OnItemSelectedListener {
        void onItemSelected(String value);
    }

    public LyricsAiConfigPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public LyricsAiConfigPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public LyricsAiConfigPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LyricsAiConfigPreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        updateSummaryText();
        updateVisibility();
    }

    private void updateSummaryText() {
        String trans = Settings.LYRICS_TRANSLATION_PROVIDER.get();
        String roma = Settings.LYRICS_ROMANIZATION_PROVIDER.get();
        String target = Settings.LYRICS_TRANSLATE_TARGET_LANG.get();

        StringBuilder sb = new StringBuilder();
        sb.append("Trans: ").append(formatProviderName(trans));
        sb.append(" | Roma: ").append(formatProviderName(roma));
        if (target != null && !target.isEmpty() && !"app".equalsIgnoreCase(target)) {
            sb.append(" [").append(target).append("]");
        }
        setSummary(sb.toString());
    }

    private static String formatProviderName(String value) {
        if (value == null) return "Google";
        switch (value.toLowerCase()) {
            case "gemini": return "Gemini";
            case "openrouter": return "OpenRouter";
            case "openai": return "OpenAI";
            case "openai_compatible": return "Custom API";
            case "deepl": return "DeepL";
            default: return "Google";
        }
    }

    @Override
    protected void onAttachedToHierarchy(PreferenceManager preferenceManager) {
        super.onAttachedToHierarchy(preferenceManager);
        cachedParent = getParent();
        try {
            SharedPreferences prefs = preferenceManager.getSharedPreferences();
            if (prefs != null) {
                prefs.registerOnSharedPreferenceChangeListener(this);
                updateVisibility();
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "onAttachedToHierarchy failure", ex);
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if ("morphe_music_lyrics_show_translate_button".equals(key)
                || "morphe_music_lyrics_show_romanize_button".equals(key)
                || "morphe_music_lyrics_translation_provider".equals(key)
                || "morphe_music_lyrics_romanization_provider".equals(key)
                || "morphe_music_lyrics_translate_target_lang".equals(key)) {
            new Handler(Looper.getMainLooper()).post(() -> {
                updateVisibility();
                updateSummaryText();
            });
        }
    }

    private void updateVisibility() {
        boolean shouldBeVisible = Settings.LYRICS_SHOW_TRANSLATE_BUTTON.get()
                || Settings.LYRICS_SHOW_ROMANIZE_BUTTON.get();
        if (shouldBeVisible == isCurrentlyVisible) return;
        try {
            if (cachedParent == null) {
                cachedParent = getParent();
            }
            if (cachedParent == null) return;
            if (shouldBeVisible) {
                cachedParent.addPreference(this);
                isCurrentlyVisible = true;
            } else {
                cachedParent.removePreference(this);
                isCurrentlyVisible = false;
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "updateVisibility failure", ex);
        }
    }

    @Override
    protected void onClick() {
        showDialog();
    }

    @Override
    protected boolean callChangeListener(Object newValue) {
        if (settingFromCode) {
            return super.callChangeListener(newValue);
        }
        showDialog();
        return false;
    }

    private void showDialog() {
        if (dialogShowing) return;
        dialogShowing = true;

        Context context = getContext();

        ScrollView scrollView = new ScrollView(context);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Instruction
        TextView instruction = new TextView(context);
        instruction.setText(str("morphe_music_lyrics_ai_config_dialog_instruction"));
        instruction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        instruction.setTextColor(ThemeUtils.getAppForegroundColor());
        LinearLayout.LayoutParams instrParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        instrParams.bottomMargin = Dim.dp12;
        content.addView(instruction, instrParams);

        // Target language override
        content.addView(createLabel(context, str("morphe_music_lyrics_ai_config_target_lang_label")));
        EditText targetLangInput = createThemedEditText(context);
        targetLangInput.setHint(str("morphe_music_lyrics_ai_config_target_lang_hint"));
        String curTargetLang = Settings.LYRICS_TRANSLATE_TARGET_LANG.get();
        targetLangInput.setText(curTargetLang);
        targetLangInput.setSelection(curTargetLang.length());
        content.addView(targetLangInput);

        // Translation Provider Selector
        content.addView(createLabel(context, str("morphe_music_lyrics_ai_config_translation_provider_label")));
        Button transBtn = createThemedButton(context, "");
        final String[] selectedTrans = {Settings.LYRICS_TRANSLATION_PROVIDER.get()};
        updateButtonLabel(transBtn, TRANS_LABELS, TRANS_VALUES, selectedTrans[0]);
        content.addView(transBtn);

        // Romanization Provider Selector
        content.addView(createLabel(context, str("morphe_music_lyrics_ai_config_romanization_provider_label")));
        Button romaBtn = createThemedButton(context, "");
        final String[] selectedRoma = {Settings.LYRICS_ROMANIZATION_PROVIDER.get()};
        updateButtonLabel(romaBtn, ROMA_LABELS, ROMA_VALUES, selectedRoma[0]);
        content.addView(romaBtn);

        // Section Containers
        LinearLayout geminiSection = new LinearLayout(context);
        geminiSection.setOrientation(LinearLayout.VERTICAL);
        geminiSection.addView(createSectionHeader(context, str("morphe_music_lyrics_ai_config_gemini_section")));
        geminiSection.addView(createLabel(context, "API Key"));
        EditText geminiKeyInput = createThemedEditText(context);
        geminiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        geminiKeyInput.setText(Settings.LYRICS_GEMINI_API_KEY.get());
        geminiSection.addView(geminiKeyInput);
        geminiSection.addView(createLabel(context, "Model"));
        EditText geminiModelInput = createThemedEditText(context);
        geminiModelInput.setText(Settings.LYRICS_GEMINI_MODEL.get());
        Button geminiFetchBtn = createThemedButton(context, str("morphe_music_lyrics_ai_config_fetch_models"));
        geminiSection.addView(createInputWithButton(geminiModelInput, geminiFetchBtn));
        content.addView(geminiSection);

        geminiFetchBtn.setOnClickListener(v -> {
            String apiKey = geminiKeyInput.getText().toString().trim();
            if (apiKey.isEmpty()) {
                Utils.showToastShort(str("morphe_music_lyrics_ai_config_fetch_models_failed"));
                return;
            }
            Utils.showToastShort(str("morphe_music_lyrics_ai_config_fetching_models"));
            Utils.runOnBackgroundThread(() -> {
                List<String> models = GeminiClient.fetchModels(apiKey);
                Utils.runOnMainThread(() -> showModelPicker(context, models, geminiModelInput));
            });
        });

        // OpenRouter Section
        LinearLayout openrouterSection = new LinearLayout(context);
        openrouterSection.setOrientation(LinearLayout.VERTICAL);
        openrouterSection.addView(createSectionHeader(context, str("morphe_music_lyrics_ai_config_openrouter_section")));
        openrouterSection.addView(createLabel(context, "API Key"));
        EditText openrouterKeyInput = createThemedEditText(context);
        openrouterKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        openrouterKeyInput.setText(Settings.LYRICS_OPENROUTER_API_KEY.get());
        openrouterSection.addView(openrouterKeyInput);
        openrouterSection.addView(createLabel(context, "Model"));
        EditText openrouterModelInput = createThemedEditText(context);
        openrouterModelInput.setText(Settings.LYRICS_OPENROUTER_MODEL.get());
        Button openrouterFetchBtn = createThemedButton(context, str("morphe_music_lyrics_ai_config_fetch_models"));
        openrouterSection.addView(createInputWithButton(openrouterModelInput, openrouterFetchBtn));
        content.addView(openrouterSection);

        openrouterFetchBtn.setOnClickListener(v -> {
            String apiKey = openrouterKeyInput.getText().toString().trim();
            if (apiKey.isEmpty()) {
                Utils.showToastShort(str("morphe_music_lyrics_ai_config_fetch_models_failed"));
                return;
            }
            Utils.showToastShort(str("morphe_music_lyrics_ai_config_fetching_models"));
            Utils.runOnBackgroundThread(() -> {
                List<String> models = OpenAIClient.fetchModels("https://openrouter.ai/api/v1/models", apiKey);
                Utils.runOnMainThread(() -> showModelPicker(context, models, openrouterModelInput));
            });
        });

        // OpenAI Section
        LinearLayout openaiSection = new LinearLayout(context);
        openaiSection.setOrientation(LinearLayout.VERTICAL);
        openaiSection.addView(createSectionHeader(context, str("morphe_music_lyrics_ai_config_openai_section")));
        openaiSection.addView(createLabel(context, "API Key"));
        EditText openaiKeyInput = createThemedEditText(context);
        openaiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        openaiKeyInput.setText(Settings.LYRICS_OPENAI_API_KEY.get());
        openaiSection.addView(openaiKeyInput);
        openaiSection.addView(createLabel(context, "Model"));
        EditText openaiModelInput = createThemedEditText(context);
        openaiModelInput.setText(Settings.LYRICS_OPENAI_MODEL.get());
        Button openaiFetchBtn = createThemedButton(context, str("morphe_music_lyrics_ai_config_fetch_models"));
        openaiSection.addView(createInputWithButton(openaiModelInput, openaiFetchBtn));
        content.addView(openaiSection);

        openaiFetchBtn.setOnClickListener(v -> {
            String apiKey = openaiKeyInput.getText().toString().trim();
            if (apiKey.isEmpty()) {
                Utils.showToastShort(str("morphe_music_lyrics_ai_config_fetch_models_failed"));
                return;
            }
            Utils.showToastShort(str("morphe_music_lyrics_ai_config_fetching_models"));
            Utils.runOnBackgroundThread(() -> {
                List<String> models = OpenAIClient.fetchModels("https://api.openai.com/v1/models", apiKey);
                Utils.runOnMainThread(() -> showModelPicker(context, models, openaiModelInput));
            });
        });

        // OpenAI Compatible Section
        LinearLayout openaiCompatSection = new LinearLayout(context);
        openaiCompatSection.setOrientation(LinearLayout.VERTICAL);
        openaiCompatSection.addView(createSectionHeader(context, str("morphe_music_lyrics_ai_config_openai_compat_section")));
        openaiCompatSection.addView(createLabel(context, "Base URL"));
        EditText compatUrlInput = createThemedEditText(context);
        compatUrlInput.setHint(str("morphe_music_lyrics_ai_config_base_url_hint"));
        compatUrlInput.setText(Settings.LYRICS_AI_BASE_URL.get());
        openaiCompatSection.addView(compatUrlInput);
        openaiCompatSection.addView(createLabel(context, "API Token (Optional)"));
        EditText compatTokenInput = createThemedEditText(context);
        compatTokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        compatTokenInput.setText(Settings.LYRICS_AI_API_TOKEN.get());
        openaiCompatSection.addView(compatTokenInput);
        openaiCompatSection.addView(createLabel(context, "Model"));
        EditText compatModelInput = createThemedEditText(context);
        compatModelInput.setText(Settings.LYRICS_AI_MODEL.get());
        Button compatFetchBtn = createThemedButton(context, str("morphe_music_lyrics_ai_config_fetch_models"));
        openaiCompatSection.addView(createInputWithButton(compatModelInput, compatFetchBtn));
        content.addView(openaiCompatSection);

        compatFetchBtn.setOnClickListener(v -> {
            String baseUrl = compatUrlInput.getText().toString().trim();
            String token = compatTokenInput.getText().toString().trim();
            if (baseUrl.isEmpty()) {
                Utils.showToastShort(str("morphe_music_lyrics_ai_config_status_empty_url"));
                return;
            }
            Utils.showToastShort(str("morphe_music_lyrics_ai_config_fetching_models"));
            Utils.runOnBackgroundThread(() -> {
                List<String> models = OpenAIClient.fetchModels(baseUrl, token);
                Utils.runOnMainThread(() -> showModelPicker(context, models, compatModelInput));
            });
        });

        // DeepL Section
        LinearLayout deeplSection = new LinearLayout(context);
        deeplSection.setOrientation(LinearLayout.VERTICAL);
        deeplSection.addView(createSectionHeader(context, str("morphe_music_lyrics_ai_config_deepl_section")));
        deeplSection.addView(createLabel(context, "Auth Key (Free or Pro)"));
        EditText deeplKeyInput = createThemedEditText(context);
        deeplKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        deeplKeyInput.setText(Settings.LYRICS_DEEPL_API_KEY.get());
        deeplSection.addView(deeplKeyInput);
        content.addView(deeplSection);

        // Visibility controller for sections
        Runnable updateVisibilityRunnable = () -> {
            boolean needGemini = "gemini".equals(selectedTrans[0]) || "gemini".equals(selectedRoma[0]);
            boolean needOpenRouter = "openrouter".equals(selectedTrans[0]) || "openrouter".equals(selectedRoma[0]);
            boolean needOpenAi = "openai".equals(selectedTrans[0]) || "openai".equals(selectedRoma[0]);
            boolean needCompat = "openai_compatible".equals(selectedTrans[0]) || "openai_compatible".equals(selectedRoma[0]);
            boolean needDeepL = "deepl".equals(selectedTrans[0]);

            geminiSection.setVisibility(needGemini ? View.VISIBLE : View.GONE);
            openrouterSection.setVisibility(needOpenRouter ? View.VISIBLE : View.GONE);
            openaiSection.setVisibility(needOpenAi ? View.VISIBLE : View.GONE);
            openaiCompatSection.setVisibility(needCompat ? View.VISIBLE : View.GONE);
            deeplSection.setVisibility(needDeepL ? View.VISIBLE : View.GONE);
        };
        updateVisibilityRunnable.run();

        transBtn.setOnClickListener(v -> showItemPickerDialog(
                context,
                str("morphe_music_lyrics_ai_config_translation_provider_label"),
                TRANS_LABELS,
                TRANS_VALUES,
                selectedTrans[0],
                val -> {
                    selectedTrans[0] = val;
                    updateButtonLabel(transBtn, TRANS_LABELS, TRANS_VALUES, selectedTrans[0]);
                    updateVisibilityRunnable.run();
                }
        ));

        romaBtn.setOnClickListener(v -> showItemPickerDialog(
                context,
                str("morphe_music_lyrics_ai_config_romanization_provider_label"),
                ROMA_LABELS,
                ROMA_VALUES,
                selectedRoma[0],
                val -> {
                    selectedRoma[0] = val;
                    updateButtonLabel(romaBtn, ROMA_LABELS, ROMA_VALUES, selectedRoma[0]);
                    updateVisibilityRunnable.run();
                }
        ));

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                str("morphe_music_lyrics_ai_config_dialog_title"),
                null,
                null,
                str("morphe_settings_save"),
                () -> {
                    String targetLang = targetLangInput.getText().toString().trim();
                    Settings.LYRICS_TRANSLATE_TARGET_LANG.save(targetLang.isEmpty() ? "app" : targetLang);
                    Settings.LYRICS_TRANSLATION_PROVIDER.save(selectedTrans[0]);
                    Settings.LYRICS_ROMANIZATION_PROVIDER.save(selectedRoma[0]);

                    Settings.LYRICS_GEMINI_API_KEY.save(geminiKeyInput.getText().toString().trim());
                    Settings.LYRICS_GEMINI_MODEL.save(geminiModelInput.getText().toString().trim());

                    Settings.LYRICS_OPENROUTER_API_KEY.save(openrouterKeyInput.getText().toString().trim());
                    Settings.LYRICS_OPENROUTER_MODEL.save(openrouterModelInput.getText().toString().trim());

                    Settings.LYRICS_OPENAI_API_KEY.save(openaiKeyInput.getText().toString().trim());
                    Settings.LYRICS_OPENAI_MODEL.save(openaiModelInput.getText().toString().trim());

                    Settings.LYRICS_AI_BASE_URL.save(compatUrlInput.getText().toString().trim());
                    Settings.LYRICS_AI_API_TOKEN.save(compatTokenInput.getText().toString().trim());
                    Settings.LYRICS_AI_MODEL.save(compatModelInput.getText().toString().trim());

                    Settings.LYRICS_DEEPL_API_KEY.save(deeplKeyInput.getText().toString().trim());

                    boolean hasAiProvider = !"google".equals(selectedTrans[0]) || !"google".equals(selectedRoma[0]);
                    Settings.LYRICS_USE_AI_TRANSLATION.save(hasAiProvider);

                    settingFromCode = true;
                    setChecked(hasAiProvider);
                    settingFromCode = false;
                    updateSummaryText();
                    Utils.showToastShort(str("morphe_music_lyrics_ai_config_toast_saved"));
                },
                null,
                str("morphe_settings_reset"),
                () -> {
                    Settings.LYRICS_TRANSLATION_PROVIDER.resetToDefault();
                    Settings.LYRICS_ROMANIZATION_PROVIDER.resetToDefault();
                    Settings.LYRICS_TRANSLATE_TARGET_LANG.resetToDefault();
                    Settings.LYRICS_GEMINI_API_KEY.resetToDefault();
                    Settings.LYRICS_GEMINI_MODEL.resetToDefault();
                    Settings.LYRICS_OPENROUTER_API_KEY.resetToDefault();
                    Settings.LYRICS_OPENROUTER_MODEL.resetToDefault();
                    Settings.LYRICS_OPENAI_API_KEY.resetToDefault();
                    Settings.LYRICS_OPENAI_MODEL.resetToDefault();
                    Settings.LYRICS_AI_BASE_URL.resetToDefault();
                    Settings.LYRICS_AI_API_TOKEN.resetToDefault();
                    Settings.LYRICS_AI_MODEL.resetToDefault();
                    Settings.LYRICS_DEEPL_API_KEY.resetToDefault();
                    Settings.LYRICS_USE_AI_TRANSLATION.resetToDefault();

                    targetLangInput.setText("app");
                    selectedTrans[0] = "google";
                    selectedRoma[0] = "google";
                    updateButtonLabel(transBtn, TRANS_LABELS, TRANS_VALUES, "google");
                    updateButtonLabel(romaBtn, ROMA_LABELS, ROMA_VALUES, "google");

                    geminiKeyInput.setText("");
                    geminiModelInput.setText(Settings.LYRICS_GEMINI_MODEL.get());

                    openrouterKeyInput.setText("");
                    openrouterModelInput.setText(Settings.LYRICS_OPENROUTER_MODEL.get());

                    openaiKeyInput.setText("");
                    openaiModelInput.setText(Settings.LYRICS_OPENAI_MODEL.get());

                    compatUrlInput.setText(Settings.LYRICS_AI_BASE_URL.get());
                    compatTokenInput.setText("");
                    compatModelInput.setText(Settings.LYRICS_AI_MODEL.get());

                    deeplKeyInput.setText("");

                    updateVisibilityRunnable.run();
                    settingFromCode = true;
                    setChecked(false);
                    settingFromCode = false;
                    updateSummaryText();
                    Utils.showToastShort(str("morphe_music_lyrics_ai_config_toast_reset"));
                },
                false
        );

        Dialog dialog = dialogPair.first;
        LinearLayout mainLayout = dialogPair.second;

        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        scrollParams.bottomMargin = Dim.dp12;
        mainLayout.addView(scrollView, mainLayout.getChildCount() - 1, scrollParams);

        dialog.setOnDismissListener(d -> {
            dialogShowing = false;
            updateSummaryText();
        });

        dialog.show();
    }

    private static void showItemPickerDialog(Context context, String title, String[] labels, String[] values,
                                             String currentSelected, OnItemSelectedListener onSelected) {
        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                title,
                null,
                null,
                null,
                null,
                () -> {},
                null,
                null,
                true
        );

        Dialog dialog = dialogPair.first;
        LinearLayout mainLayout = dialogPair.second;

        ScrollView scrollView = new ScrollView(context);
        LinearLayout listLayout = new LinearLayout(context);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(listLayout, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < labels.length; i++) {
            final String val = values[i];
            final String label = labels[i];
            final boolean isSelected = val != null && val.equalsIgnoreCase(currentSelected);

            TextView item = new TextView(context);
            item.setText(label);
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            item.setTextColor(isSelected ? Color.WHITE : 0xDDFFFFFF);
            if (isSelected) {
                item.setTypeface(Typeface.DEFAULT_BOLD);
            }
            item.setPadding(Dim.dp16, Dim.dp12, Dim.dp16, Dim.dp12);

            ShapeDrawable bg = new ShapeDrawable(new RoundRectShape(Dim.roundedCorners(10), null, null));
            bg.getPaint().setColor(isSelected ? 0x33FFFFFF : 0x00000000);
            item.setBackground(bg);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Dim.dp4;
            item.setLayoutParams(lp);

            item.setOnClickListener(v -> {
                if (onSelected != null) {
                    onSelected.onItemSelected(val);
                }
                dialog.dismiss();
            });

            listLayout.addView(item);
        }

        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollParams.bottomMargin = Dim.dp8;
        mainLayout.addView(scrollView, mainLayout.getChildCount() - 1, scrollParams);

        dialog.show();
    }

    private static void updateButtonLabel(Button btn, String[] labels, String[] values, String selectedValue) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equalsIgnoreCase(selectedValue)) {
                btn.setText(labels[i]);
                return;
            }
        }
        btn.setText(labels[0]);
    }

    private static void showModelPicker(Context context, List<String> models, EditText targetInput) {
        if (models == null || models.isEmpty()) {
            Utils.showToastShort(str("morphe_music_lyrics_ai_config_fetch_models_failed"));
            return;
        }
        String[] items = models.toArray(new String[0]);
        showItemPickerDialog(
                context,
                str("morphe_music_lyrics_ai_config_fetch_models_success"),
                items,
                items,
                targetInput.getText().toString().trim(),
                selectedModel -> {
                    targetInput.setText(selectedModel);
                    targetInput.setSelection(selectedModel.length());
                }
        );
    }

    private static View createInputWithButton(EditText input, Button button) {
        LinearLayout row = new LinearLayout(input.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        input.setLayoutParams(inputLp);
        row.addView(input);

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.leftMargin = Dim.dp8;
        button.setLayoutParams(btnLp);
        row.addView(button);
        return row;
    }

    private static TextView createSectionHeader(Context context, String title) {
        TextView tv = new TextView(context);
        tv.setText(title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(0xFF3EA6FF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Dim.dp16;
        lp.bottomMargin = Dim.dp4;
        tv.setLayoutParams(lp);
        return tv;
    }

    private static TextView createLabel(Context context, String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(0xCCFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Dim.dp8;
        lp.bottomMargin = Dim.dp4;
        tv.setLayoutParams(lp);
        return tv;
    }

    private static Button createThemedButton(Context context, String text) {
        Button btn = new Button(context);
        btn.setText(text);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setTextColor(Color.WHITE);
        ShapeDrawable bg = new ShapeDrawable(new RoundRectShape(Dim.roundedCorners(10), null, null));
        bg.getPaint().setColor(0x22FFFFFF);
        btn.setBackground(bg);
        btn.setPadding(Dim.dp12, Dim.dp8, Dim.dp12, Dim.dp8);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Dim.dp4;
        btn.setLayoutParams(lp);
        return btn;
    }

    private static EditText createThemedEditText(Context context) {
        EditText editText = new EditText(context);
        editText.setSingleLine(true);
        editText.setTextSize(14);
        editText.setTextColor(Color.WHITE);
        editText.setHintTextColor(0x77FFFFFF);
        ShapeDrawable background = new ShapeDrawable(new RoundRectShape(
                Dim.roundedCorners(10), null, null));
        background.getPaint().setColor(0x1AFFFFFF);
        editText.setPadding(Dim.dp12, Dim.dp8, Dim.dp12, Dim.dp8);
        editText.setBackground(background);
        editText.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Dim.dp4;
        editText.setLayoutParams(lp);
        return editText;
    }
}
