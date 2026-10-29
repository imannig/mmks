/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.settings.preference;

import android.app.Dialog;
import android.content.Context;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.widget.Toolbar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.music.settings.MusicActivityHook;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.patches.GmsCoreSupportPatch;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.settings.preference.ToolbarPreferenceFragment;

/**
 * Preference fragment for Morphe settings.
 */
@SuppressWarnings("deprecation")
public class MusicPreferenceFragment extends ToolbarPreferenceFragment {
    /**
     * The main PreferenceScreen used to display the current set of preferences.
     */
    private PreferenceScreen preferenceScreen;

    private record LyricsSwitchSpec(
            BooleanSetting setting,
            String fallbackTitle,
            String fallbackSummary) {}

    private static final LyricsSwitchSpec[] LYRICS_OVERLAY_SWITCH_SPECS = new LyricsSwitchSpec[]{
            new LyricsSwitchSpec(
                    Settings.LYRICS_DYNAMIC_BG_VOCAL_POS,
                    "Dynamic Background Vocal Positioning",
                    "Background lyrics that start before main lyrics will be displayed on top."),
            new LyricsSwitchSpec(
                    Settings.LYRICS_BLUR_INACTIVE,
                    "Blur Inactive Lyrics",
                    "Applies a blur effect to lyrics not currently being sung, similar to Apple Music."),
            new LyricsSwitchSpec(
                    Settings.LYRICS_LIGHTWEIGHT_MODE,
                    "Lightweight Mode",
                    "Reduces animations and visual effects for better performance on older devices."),
            new LyricsSwitchSpec(
                    Settings.LYRICS_HIDE_OFFSCREEN,
                    "Hide Off-screen Lyrics Line",
                    "Hides & remove all animation lyrics lines that are not currently visible on the screen. Recommended for low-end devices."),
            new LyricsSwitchSpec(
                    Settings.LYRICS_DYNAMIC_BACKGROUND,
                    "Enable Dynamic Background (Player View)",
                    "Applies animated album backgrounds when the player is expanded. This setting has no effect in fullscreen mode, where dynamic background is always enabled."),
            new LyricsSwitchSpec(
                    Settings.LYRICS_AUDIO_BEAT_SYNC,
                    "Audio-based Beat Pulse for Dynamic Background",
                    "Makes the dynamic background layers pulse to the beat of the audio. If you use Better Lyrics Shaders or another Visualizer, you should disable this setting."),
            new LyricsSwitchSpec(
                    Settings.LYRICS_RELAX_SCROLL,
                    "Relax Scrolling",
                    "Slower scrolling animation & disable text resizing animation for the lyrics.")
    };

    /**
     * Initializes the preference fragment.
     */
    @Override
    protected void initialize() {
        // Touch Music Settings class before super.initialize() so all BooleanSetting keys are registered.
        Settings.LYRICS_ENABLED.get();

        super.initialize();

        try {
            preferenceScreen = getPreferenceScreen();
            if (preferenceScreen != null) {
                ensureLyricsOverlayPreferences(preferenceScreen);
            }
            sortPreferenceGroups(preferenceScreen);
            setPreferenceScreenToolbar(preferenceScreen);

            // Clunky work around until preferences are custom classes that manage themselves.
            // A mount (root) install has its branding applied while patching and cannot change
            // it at runtime. But the preferences must be added during patching because of
            // difficulties detecting during patching if it's a root installation. So instead
            // the non-functional preferences are removed during runtime.
            if (GmsCoreSupportPatch.isPackageNameOriginal()) {
                removePreferences(
                        SharedYouTubeSettings.CUSTOM_BRANDING_ICON.key,
                        SharedYouTubeSettings.CUSTOM_BRANDING_NOTIFICATION_ICON.key,
                        SharedYouTubeSettings.CUSTOM_BRANDING_NAME.key);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "initialize failure", ex);
        }
    }

    private void ensureLyricsOverlayPreferences(@NonNull PreferenceScreen root) {
        Context context = getContext();
        if (context == null) return;

        PreferenceGroup overlayGroup = findGroupByKeyPrefix(root, "morphe_music_lyrics_section_overlay");
        if (overlayGroup == null) {
            overlayGroup = findGroupByKeyPrefix(root, "morphe_settings_music_screen_5_lyrics");
        }
        if (overlayGroup == null) {
            return;
        }

        for (LyricsSwitchSpec spec : LYRICS_OVERLAY_SWITCH_SPECS) {
            String key = spec.setting().key;
            String titleResKey = key + "_title";
            String summaryResKey = key + "_summary";
            String resolvedTitle = resolveStringOrFallback(titleResKey, spec.fallbackTitle());
            String resolvedSummary = resolveStringOrFallback(summaryResKey, spec.fallbackSummary());

            Preference existing = findPreferenceInGroup(root, key);
            if (existing instanceof SwitchPreference sw) {
                CharSequence curTitle = sw.getTitle();
                if (curTitle == null || curTitle.length() == 0 || curTitle.toString().equals(titleResKey) || curTitle.toString().startsWith("@")) {
                    sw.setTitle(resolvedTitle);
                }
                CharSequence curSummary = sw.getSummary();
                if (curSummary == null || curSummary.length() == 0 || curSummary.toString().equals(summaryResKey) || curSummary.toString().startsWith("@")) {
                    sw.setSummary(resolvedSummary);
                }
                sw.setChecked(spec.setting().get());
            } else if (existing == null) {
                SwitchPreference created = new SwitchPreference(context);
                created.setKey(key);
                created.setTitle(resolvedTitle);
                created.setSummary(resolvedSummary);
                created.setDefaultValue(spec.setting().defaultValue);
                created.setChecked(spec.setting().get());
                overlayGroup.addPreference(created);
                if (findPreferenceInGroup(root, Settings.LYRICS_ENABLED.key) != null) {
                    try {
                        created.setDependency(Settings.LYRICS_ENABLED.key);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    @NonNull
    private static String resolveStringOrFallback(@NonNull String resourceName, @NonNull String fallback) {
        try {
            if (ResourceUtils.getIdentifier(ResourceType.STRING, resourceName) != 0) {
                String val = ResourceUtils.getString(resourceName);
                if (val != null && !val.isEmpty() && !val.equals(resourceName)) {
                    return val;
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    @Nullable
    private static PreferenceGroup findGroupByKeyPrefix(@NonNull PreferenceGroup group, @NonNull String prefix) {
        String gKey = group.getKey();
        if (gKey != null && (gKey.equals(prefix) || gKey.startsWith(prefix))) {
            return group;
        }
        for (int i = 0, n = group.getPreferenceCount(); i < n; i++) {
            Preference p = group.getPreference(i);
            if (p instanceof PreferenceGroup sub) {
                PreferenceGroup found = findGroupByKeyPrefix(sub, prefix);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Nullable
    private static Preference findPreferenceInGroup(@NonNull PreferenceGroup group, @NonNull String key) {
        if (key.equals(group.getKey())) {
            return group;
        }
        for (int i = 0, n = group.getPreferenceCount(); i < n; i++) {
            Preference p = group.getPreference(i);
            if (key.equals(p.getKey())) {
                return p;
            }
            if (p instanceof PreferenceGroup sub) {
                Preference found = findPreferenceInGroup(sub, key);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Called when the fragment starts.
     */
    @Override
    public void onStart() {
        super.onStart();
        try {
            // Initialize search controller if needed
            if (MusicActivityHook.searchViewController != null) {
                // Trigger search data collection after fragment is ready.
                MusicActivityHook.searchViewController.initializeSearchData();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onStart failure", ex);
        }
    }

    /**
     * Sets toolbar for all nested preference screens.
     */
    @Override
    protected void customizeToolbar(Toolbar toolbar) {
        MusicActivityHook.setToolbarLayoutParams(toolbar);
    }

    /**
     * Perform actions after toolbar setup.
     */
    @Override
    protected void onPostToolbarSetup(Toolbar toolbar, Dialog preferenceScreenDialog) {
        if (MusicActivityHook.searchViewController != null
                && MusicActivityHook.searchViewController.isSearchActive()) {
            toolbar.post(() -> MusicActivityHook.searchViewController.closeSearch());
        }
    }

    /**
     * Returns the preference screen for external access by SearchViewController.
     */
    public PreferenceScreen getPreferenceScreenForSearch() {
        return preferenceScreen;
    }
}
