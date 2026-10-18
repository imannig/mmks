/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import org.junit.Assert;
import org.junit.Test;

public class SpringEasingTest {

    @Test
    public void testSpringEasing350() {
        SpringEasing.Result r = SpringEasing.get(350, 0.78, 350 * 1.5);
        Assert.assertEquals(880, r.durationMs);
        Assert.assertEquals(106, r.samples.length);

        float[][] expected = {
                {0.00f, 0.0000f},
                {0.05f, 0.0443f},
                {0.10f, 0.2754f},
                {0.20f, 0.8258f},
                {0.30f, 0.9828f},
                {0.40f, 1.0198f},
                {0.50f, 1.0182f},
                {0.70f, 1.0095f},
                {1.00f, 1.0000f}
        };

        for (float[] exp : expected) {
            float u = exp[0];
            float expectedVal = exp[1];
            float actualVal = r.interpolate(u);
            Assert.assertEquals("Mismatch at u=" + u, expectedVal, actualVal, 0.0005f);
        }
    }

    @Test
    public void testSpringEasing450() {
        SpringEasing.Result r = SpringEasing.get(450, 0.78, 450 * 1.5);
        Assert.assertEquals(1130, r.durationMs);
        Assert.assertEquals(136, r.samples.length);

        float[][] expected = {
                {0.00f, 0.0000f},
                {0.05f, 0.0441f},
                {0.10f, 0.2750f},
                {0.20f, 0.8259f},
                {0.30f, 0.9829f},
                {0.40f, 1.0198f},
                {0.50f, 1.0183f},
                {0.70f, 1.0097f},
                {1.00f, 1.0000f}
        };

        for (float[] exp : expected) {
            float u = exp[0];
            float expectedVal = exp[1];
            float actualVal = r.interpolate(u);
            Assert.assertEquals("Mismatch at u=" + u, expectedVal, actualVal, 0.0005f);
        }
    }

    @Test
    public void testCacheCapacity() {
        for (int i = 0; i < 100; i++) {
            SpringEasing.get(300 + i * 10, 0.78, 400);
        }
        // make sure cache working and memory safe
        SpringEasing.Result r = SpringEasing.get(350, 0.78, 525);
        Assert.assertNotNull(r);
    }
}
