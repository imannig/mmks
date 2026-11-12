/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.AlignedSyllable;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.ReconstructedWord;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.VisualCluster;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.WipeResult;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.WordTimestamp;

public class WordAnimationUtilsTest {

    @Test
    public void testBidiStrippingAndScriptDetection() {
        String dirty = "\u200BHello\u200F \u202AWorld\uFEFF";
        Assert.assertEquals("Hello World", WordAnimationUtils.stripBidiControls(dirty));
        Assert.assertTrue(WordAnimationUtils.isCJK("東京"));
        Assert.assertTrue(WordAnimationUtils.isCJK("사랑"));
        Assert.assertFalse(WordAnimationUtils.isCJK("Hello"));
        Assert.assertTrue(WordAnimationUtils.isNeedSplitOnly("東京"));
        Assert.assertTrue(WordAnimationUtils.isNeedSplitOnly("사랑해!"));
        Assert.assertFalse(WordAnimationUtils.isNeedSplitOnly("Hello"));
        Assert.assertFalse(WordAnimationUtils.isNeedSplitOnly("Tokyo東京"));
        Assert.assertTrue(WordAnimationUtils.isRtlText("مرحبا"));
        Assert.assertTrue(WordAnimationUtils.isJoiningScript("مرحبا"));
        Assert.assertFalse(WordAnimationUtils.isRtlText("Hello"));
    }

    @Test
    public void testSafeBreakOffsetAvoidsProhibitedStart() {
        String text = "君のことが好きだ。本当に";
        int rawBreak = text.indexOf('。');
        int safe = WordAnimationUtils.findSafeBreakOffset(text, rawBreak, 0);
        Assert.assertNotEquals("Should not break right before prohibited start '。'", rawBreak, safe);
    }

    @Test
    public void testSyllableAlignmentAndReconstructedWords() {
        List<WordTimestamp> words = List.of(
                new WordTimestamp("for", 1.0, 1.4, false, false, null),
                new WordTimestamp("ev", 1.4, 1.8, false, false, null),
                new WordTimestamp("er", 1.8, 2.5, true, false, null),
                new WordTimestamp("more", 2.5, 3.8, false, false, null)
        );
        String mainText = "forever more";
        List<AlignedSyllable> aligned = WordAnimationUtils.alignSyllablesForFormatting(words, mainText);
        Assert.assertEquals(4, aligned.size());
        Assert.assertEquals(0, aligned.get(0).startOffset);
        Assert.assertEquals(3, aligned.get(0).endOffset);
        Assert.assertEquals(3, aligned.get(1).startOffset);
        Assert.assertEquals(5, aligned.get(1).endOffset);
        Assert.assertEquals(5, aligned.get(2).startOffset);
        Assert.assertEquals(7, aligned.get(2).endOffset);
        Assert.assertEquals(8, aligned.get(3).startOffset);
        Assert.assertEquals(12, aligned.get(3).endOffset);

        List<ReconstructedWord> rec = WordAnimationUtils.buildReconstructedWords(aligned, mainText);
        Assert.assertEquals(2, rec.size());
        Assert.assertEquals(3, rec.get(0).syllables.size());
        Assert.assertEquals(1000L, rec.get(0).wordStartTimeMs);
        Assert.assertEquals(2500L, rec.get(0).wordEndTimeMs);
        Assert.assertEquals(1, rec.get(1).syllables.size());
    }

    @Test
    public void testComputeWipeLeadingEdgeGrowAndCrossSyllable() {
        VisualCluster vc0 = new VisualCluster(
                0, 4, 0, 0f, 0f, 50f,
                false, false, false, false,
                0L, 0L, 0f, 0f, -1f, null,
                1f, 0f, 0f, 0L, 0);
        WipeResult wipe = new WipeResult();
        // Leading edge grow ramp when localX < halfGradientWidth (gradientWidth = 20f, half = 10f, localX = 5f + brushShift 0.25f = 5.25f -> growWidth = 10.5f)
        WordAnimationUtils.computeWipe(
                vc0, false, 0f, 200f, 0f, 50f,
                0, 0, 5f, false, 0.25f, 20f, wipe);
        Assert.assertEquals(WordAnimationUtils.WIPE_BAND, wipe.mode);
        Assert.assertEquals(0f, wipe.bandStart, 0.001f);
        Assert.assertEquals(10.5f, wipe.bandEnd, 0.001f);
    }

    @Test
    public void testAppleEmphasisTableAndGlowCurve() {
        float[] table = AppleWordAnimator.buildEmphasisTable(1500L, 600L);
        Assert.assertEquals(AppleWordAnimator.EMPHASIS_TABLE_FRAMES * 2, table.length);

        float[] sample = new float[2];
        AppleWordAnimator.sampleEmphasisCached(table, 3600f, 0f, sample);
        Assert.assertEquals(0f, sample[0], 0.0001f);
        Assert.assertEquals(0f, sample[1], 0.0001f);

        AppleWordAnimator.sampleEmphasisCached(table, 3600f, 3600f, sample);
        Assert.assertEquals(1f, sample[0], 0.0001f);
        Assert.assertEquals(0f, sample[1], 0.0001f);

        Assert.assertEquals(0f, AppleWordAnimator.emphasisGlowOpacity(0f), 0.001f);
        Assert.assertEquals(0.8f, AppleWordAnimator.emphasisGlowOpacity(0.15f), 0.001f);
        Assert.assertEquals(1.0f, AppleWordAnimator.emphasisGlowOpacity(0.30f), 0.001f);
        Assert.assertEquals(0.3f, AppleWordAnimator.emphasisGlowOpacity(0.60f), 0.001f);
        Assert.assertEquals(0f, AppleWordAnimator.emphasisGlowOpacity(1.0f), 0.001f);
    }

    @Test
    public void testSeparableBlur24x48() {
        int w = 24;
        int h = 48;

        int[] src = new int[w * h];
        int[] temp = new int[w * h];
        int[] dst = new int[w * h];
        for (int i = 0; i < src.length; i++) {
            src[i] = (i % 2 == 0) ? 0xFFFF0000 : 0xFF0000FF;
        }
        BoxBlur.blur(src, temp, dst, w, h, 2, 4);
        int mid = dst[(h / 2) * w + (w / 2)];
        int r = (mid >> 16) & 0xFF;
        int b = mid & 0xFF;
        Assert.assertTrue("Red channel should be blended around 127", r > 100 && r < 155);
        Assert.assertTrue("Blue channel should be blended around 127", b > 100 && b < 155);
    }
}