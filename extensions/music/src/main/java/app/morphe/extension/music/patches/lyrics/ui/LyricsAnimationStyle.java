/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

public enum LyricsAnimationStyle {
    APPLE("Apple", "Apple"),
    EXPRESSIVE("Expressive", "Expressive"),
    SIMPLE("Simple", "Simple"),
    NONE("None", "None");

    public final String id;
    public final String label;

    LyricsAnimationStyle(String id, String label) {
        this.id = id;
        this.label = label;
    }

    @NonNull
    public static LyricsAnimationStyle fromString(@Nullable String value) {
        if (value == null) return APPLE;
        switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "APPLE":
                return APPLE;
            case "EXPRESSIVE":
            case "GLOW":
            case "SLIDE":
                return EXPRESSIVE;
            case "SIMPLE":
            case "FADE":
            case "KARAOKE":
                return SIMPLE;
            case "NONE":
                return NONE;
            default:
                return APPLE;
        }
    }
}