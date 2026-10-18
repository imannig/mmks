/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class LyricsRetiming {
    public static final long RETIMING_OVERLAP_THRESHOLD_MS = 5L;
    public static final long RETIMING_GAP_THRESHOLD_MS = 1L;
    public static final long RETIMING_MAX_EXTENSION_MS = 1300L;

    public static final long SCROLL_LOOK_AHEAD_MIN_MS = 350L;
    public static final long SCROLL_LOOK_AHEAD_MAX_MS = 500L;
    public static final long SCROLL_ANIMATION_MAX_DURATION_MS = 450L;
    public static final float SCROLL_DELAY_INCREMENT_RATIO = 0.1f;
    public static final long HIGHLIGHT_LOOK_AHEAD_MS = 190L;

    public static final class RetimedEntry {
        public final LyricsLine line;
        public final long startMs;
        public final long actualEndTimeMs;
        public final long adjustedEndTimeMs;
        public final boolean isSecondary;

        public RetimedEntry(LyricsLine line, long startMs, long actualEndTimeMs, long adjustedEndTimeMs) {
            this(line, startMs, actualEndTimeMs, adjustedEndTimeMs, line != null && line.isBG());
        }

        public RetimedEntry(LyricsLine line, long startMs, long actualEndTimeMs, long adjustedEndTimeMs, boolean isSecondary) {
            this.line = line;
            this.startMs = startMs;
            this.actualEndTimeMs = actualEndTimeMs;
            this.adjustedEndTimeMs = adjustedEndTimeMs;
            this.isSecondary = isSecondary;
        }
    }

    public static final class ActiveLineScanner {
        private int hint = 0;
        private List<Integer> previous = Collections.emptyList();

        public void reset() {
            hint = 0;
            previous = Collections.emptyList();
        }

        @NonNull
        public List<Integer> update(
                @Nullable List<RetimedEntry> retimedLyrics,
                @Nullable long[] adjustedEndMaxByIndex,
                long positionMs,
                long endToleranceMs) {
            if (retimedLyrics == null || retimedLyrics.isEmpty() || adjustedEndMaxByIndex == null) {
                reset();
                return Collections.emptyList();
            }
            int lastStarted = lastStartedIndex(retimedLyrics, positionMs);
            List<Integer> before = previous;
            int count = 0;
            boolean unchanged = true;
            int index = lastStarted;

            while (index >= 0 && index < adjustedEndMaxByIndex.length
                    && adjustedEndMaxByIndex[index] + endToleranceMs >= positionMs) {
                if (positionMs <= retimedLyrics.get(index).adjustedEndTimeMs + endToleranceMs) {
                    if (unchanged && (count >= before.size() || before.get(before.size() - 1 - count) != index)) {
                        unchanged = false;
                    }
                    count++;
                }
                index--;
            }

            if (unchanged && count == before.size()) {
                return before;
            }
            if (count == 0) {
                previous = Collections.emptyList();
                return previous;
            }

            ArrayList<Integer> result = new ArrayList<>(count);
            index = lastStarted;
            while (index >= 0 && index < adjustedEndMaxByIndex.length
                    && adjustedEndMaxByIndex[index] + endToleranceMs >= positionMs) {
                if (positionMs <= retimedLyrics.get(index).adjustedEndTimeMs + endToleranceMs) {
                    result.add(index);
                }
                index--;
            }
            Collections.reverse(result);
            previous = result;
            return result;
        }

        private int lastStartedIndex(@NonNull List<RetimedEntry> retimedLyrics, long positionMs) {
            int last = retimedLyrics.size() - 1;
            if (last < 0) return -1;
            int h = hint;
            if (h >= 0 && h <= last) {
                if (retimedLyrics.get(h).startMs <= positionMs) {
                    if (h == last || retimedLyrics.get(h + 1).startMs > positionMs) return h;
                    if (h + 1 == last || retimedLyrics.get(h + 2).startMs > positionMs) {
                        hint = h + 1;
                        return h + 1;
                    }
                } else if (h > 0 && retimedLyrics.get(h - 1).startMs <= positionMs) {
                    hint = h - 1;
                    return h - 1;
                }
            }
            int found = findLastStartedLineIndex(retimedLyrics, positionMs);
            if (found >= 0) hint = found;
            return found;
        }
    }

    private LyricsRetiming() {
    }

    public static long calculateScrollDuration(long scrollLookAheadMs) {
        return Math.min(SCROLL_ANIMATION_MAX_DURATION_MS, Math.max(1L, scrollLookAheadMs));
    }

    public static float calculateScrollDelayIncrementFloat(long scrollDurationMs) {
        return scrollDurationMs * SCROLL_DELAY_INCREMENT_RATIO;
    }

    public static long calculateScrollDelayIncrement(long scrollDurationMs) {
        return Math.round(scrollDurationMs * SCROLL_DELAY_INCREMENT_RATIO);
    }

    public static long[] buildAdjustedEndMaxByIndex(@Nullable List<RetimedEntry> retimedLyrics) {
        if (retimedLyrics == null || retimedLyrics.isEmpty()) {
            return new long[0];
        }
        final int size = retimedLyrics.size();
        long[] maxEnd = new long[size];
        long currentMax = Long.MIN_VALUE;
        for (int i = 0; i < size; i++) {
            currentMax = Math.max(currentMax, retimedLyrics.get(i).adjustedEndTimeMs);
            maxEnd[i] = currentMax;
        }
        return maxEnd;
    }

    public static int findLastStartedLineIndex(@Nullable List<RetimedEntry> retimedLyrics, long positionMs) {
        if (retimedLyrics == null || retimedLyrics.isEmpty()) {
            return -1;
        }
        int low = 0;
        int high = retimedLyrics.size() - 1;
        int result = -1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (retimedLyrics.get(mid).startMs <= positionMs) {
                result = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return result;
    }

    @NonNull
    public static List<Integer> findActiveRetimedLineIndices(
            @Nullable List<RetimedEntry> retimedLyrics,
            @Nullable long[] adjustedEndMaxByIndex,
            long positionMs,
            long endToleranceMs) {
        if (retimedLyrics == null || retimedLyrics.isEmpty() || adjustedEndMaxByIndex == null) {
            return Collections.emptyList();
        }
        int lastStartedIndex = findLastStartedLineIndex(retimedLyrics, positionMs);
        if (lastStartedIndex < 0) {
            return Collections.emptyList();
        }

        List<Integer> activeIndices = new ArrayList<>();
        int index = lastStartedIndex;
        while (index >= 0 && index < adjustedEndMaxByIndex.length
                && adjustedEndMaxByIndex[index] + endToleranceMs >= positionMs) {
            RetimedEntry retimed = retimedLyrics.get(index);
            if (positionMs <= retimed.adjustedEndTimeMs + endToleranceMs) {
                activeIndices.add(index);
            }
            index--;
        }

        Collections.reverse(activeIndices);
        return activeIndices;
    }

    private static boolean areConsecutiveMainLines(
            @NonNull List<RetimedEntry> retimedLyrics,
            int prevIdx,
            int currIdx) {
        if (currIdx <= prevIdx) return false;
        if (currIdx - prevIdx == 1) return true;
        for (int m = prevIdx + 1; m < currIdx && m < retimedLyrics.size(); m++) {
            if (!retimedLyrics.get(m).isSecondary) {
                return false;
            }
        }
        return true;
    }

    public static int calculatePrimaryRetimedLineIndex(
            @Nullable List<RetimedEntry> retimedLyrics,
            @Nullable List<Integer> activeIndices,
            long positionMs,
            int lastActiveIndex) {
        if (retimedLyrics == null || retimedLyrics.isEmpty()) {
            return -1;
        }

        int primaryIndex = (activeIndices != null && !activeIndices.isEmpty())
                ? activeIndices.get(activeIndices.size() - 1) : -1;
        if (primaryIndex != -1 && positionMs > retimedLyrics.get(primaryIndex).adjustedEndTimeMs + 10L) {
            primaryIndex = -1;
        }

        if (primaryIndex != -1 && activeIndices != null && !activeIndices.isEmpty()) {
            int groupEnd = activeIndices.size() - 1;
            int groupStart = groupEnd;
            while (groupStart > 0 && areConsecutiveMainLines(
                    retimedLyrics, activeIndices.get(groupStart - 1), activeIndices.get(groupStart))) {
                groupStart--;
            }

            int candidateIndex = activeIndices.get(Math.max(groupStart, groupEnd - 2));
            boolean lastPrimaryStillActive = activeIndices.contains(lastActiveIndex);
            primaryIndex = (candidateIndex < lastActiveIndex && lastPrimaryStillActive)
                    ? lastActiveIndex : candidateIndex;
        } else {
            long firstStart = retimedLyrics.get(0).startMs;
            if (positionMs < firstStart) {
                primaryIndex = 0;
            } else {
                int lastStarted = findLastStartedLineIndex(retimedLyrics, positionMs);
                primaryIndex = Math.max(0, Math.min(retimedLyrics.size() - 1, lastStarted));
            }
        }

        RetimedEntry currentPrimaryRetimed = (lastActiveIndex >= 0 && lastActiveIndex < retimedLyrics.size())
                ? retimedLyrics.get(lastActiveIndex) : null;
        RetimedEntry candidateRetimed = (primaryIndex >= 0 && primaryIndex < retimedLyrics.size())
                ? retimedLyrics.get(primaryIndex) : null;

        if (primaryIndex > lastActiveIndex
                && candidateRetimed != null
                && currentPrimaryRetimed != null
                && candidateRetimed.adjustedEndTimeMs == currentPrimaryRetimed.adjustedEndTimeMs
                && activeIndices != null && activeIndices.size() <= 3) {
            return lastActiveIndex;
        } else {
            return primaryIndex;
        }
    }

    @NonNull
    public static List<RetimedEntry> retimeLyrics(
            @Nullable List<LyricsLine> lyrics,
            @Nullable Set<Integer> gapAfterIndex) {
        return retimeLyrics(lyrics, gapAfterIndex, null);
    }

    @NonNull
    public static List<RetimedEntry> retimeLyrics(
            @Nullable List<LyricsLine> lyrics,
            @Nullable Set<Integer> gapAfterIndex,
            @Nullable boolean[] isSecondaryLine) {
        if (lyrics == null || lyrics.isEmpty()) {
            return Collections.emptyList();
        }

        final int len = lyrics.size();
        long[] originalEndMs = new long[len];
        long[] newEndMs = new long[len];
        long[] startMs = new long[len];
        boolean[] secFlags = new boolean[len];

        for (int i = 0; i < len; i++) {
            LyricsLine e = lyrics.get(i);
            secFlags[i] = (isSecondaryLine != null && i < isSecondaryLine.length && isSecondaryLine[i])
                    || e.isBG();
            startMs[i] = e.startTimeMs();
            long end = LyricsLine.NO_TIME;
            if (!e.words().isEmpty()) {
                Word lastWord = e.words().get(e.words().size() - 1);
                if (lastWord.endMs() != LyricsLine.NO_TIME) {
                    end = lastWord.endMs();
                }
            }
            if (end == LyricsLine.NO_TIME) {
                end = e.endTimeMs();
            }
            if (end == LyricsLine.NO_TIME) {
                if (i + 1 < len) {
                    long nextStart = lyrics.get(i + 1).startTimeMs();
                    long gap = nextStart - startMs[i];
                    if (gap < 11000L) {
                        end = nextStart;
                    } else {
                        long vocalHold = Math.max(5000L, (e.text() != null ? e.text().length() : 10) * 180L);
                        end = Math.min(startMs[i] + vocalHold, nextStart - 7000L);
                    }
                } else {
                    end = startMs[i] + Math.max(5000L, (e.text() != null ? e.text().length() : 10) * 180L);
                }
            }
            originalEndMs[i] = end;
            newEndMs[i] = end;
        }

        int i = 0;
        while (i < len) {
            int clusterEnd = i;
            long maxMainEndInRange = originalEndMs[i];

            while (clusterEnd < len - 1) {
                int nextIdx = clusterEnd + 1;
                // secondary vocal line (backing vocal) just skip it when clustering overlap, so main lyric dont get cut off
                if (secFlags[nextIdx]) {
                    clusterEnd++;
                    continue;
                }
                LyricsLine next = lyrics.get(nextIdx);
                long nextStart = next.startTimeMs();
                long overlap = maxMainEndInRange - nextStart;
                if (overlap > RETIMING_OVERLAP_THRESHOLD_MS) {
                    clusterEnd++;
                    maxMainEndInRange = Math.max(maxMainEndInRange, originalEndMs[clusterEnd]);
                } else {
                    break;
                }
            }

            long clusterBaseEnd = originalEndMs[i];
            for (int k = i; k <= clusterEnd; k++) {
                if (!secFlags[k]) {
                    clusterBaseEnd = Math.max(clusterBaseEnd, originalEndMs[k]);
                }
            }
            long clusterFinalEnd = clusterBaseEnd;

            if (clusterEnd + 1 < len) {
                long nextStart = lyrics.get(clusterEnd + 1).startTimeMs();
                long gap = nextStart - clusterBaseEnd;
                boolean hasManualGap = (gapAfterIndex != null && gapAfterIndex.contains(clusterEnd));
                if (gap > RETIMING_GAP_THRESHOLD_MS && !hasManualGap) {
                    clusterFinalEnd += Math.min(RETIMING_MAX_EXTENSION_MS, gap);
                }
            } else {
                clusterFinalEnd += 600L;
            }

            int lastCompletedMainIdx = i;
            for (int j = i; j <= clusterEnd; j++) {
                if (secFlags[j]) {
                    newEndMs[j] = originalEndMs[j];
                    continue;
                }
                Long cutoff = null;
                int prevMainIdx = j;
                // search cutoff only between main lines, secondary vocal cannot cut main lyric active life
                for (int k = j + 1; k <= clusterEnd; k++) {
                    if (secFlags[k]) {
                        continue;
                    }
                    boolean jClearsK = (originalEndMs[j] - startMs[k] <= RETIMING_OVERLAP_THRESHOLD_MS);
                    boolean chainBrokenAtK = (originalEndMs[prevMainIdx] - startMs[k] <= RETIMING_OVERLAP_THRESHOLD_MS);
                    if (jClearsK || chainBrokenAtK) {
                        cutoff = startMs[k];
                        break;
                    }
                    prevMainIdx = k;
                }
                newEndMs[j] = (cutoff != null) ? cutoff : clusterFinalEnd;
                lastCompletedMainIdx = j;
            }

            i = clusterEnd + 1;
        }

        List<RetimedEntry> result = new ArrayList<>(len);
        for (int idx = 0; idx < len; idx++) {
            long candidateAdj = (Math.abs(newEndMs[idx] - originalEndMs[idx]) > RETIMING_GAP_THRESHOLD_MS)
                    ? newEndMs[idx] : originalEndMs[idx];
            long adjEnd = Math.max(originalEndMs[idx], candidateAdj);
            boolean idxIsSec = secFlags[idx];
            if (!idxIsSec) {
                // extend main lyric lifetime so it can fit its child secondary vocal until singing done
                int nextMainIdx = idx + 1;
                while (nextMainIdx < len && secFlags[nextMainIdx]) {
                    nextMainIdx++;
                }
                long nextMainStart = (nextMainIdx < len) ? startMs[nextMainIdx] : Long.MAX_VALUE;
                boolean overlapsNextMain = (nextMainIdx < len)
                        && (originalEndMs[idx] - nextMainStart > RETIMING_OVERLAP_THRESHOLD_MS);
                for (int sIdx = idx + 1; sIdx < nextMainIdx; sIdx++) {
                    long secEnd = originalEndMs[sIdx];
                    if (!overlapsNextMain && nextMainStart != Long.MAX_VALUE) {
                        secEnd = Math.min(secEnd, nextMainStart);
                    }
                    adjEnd = Math.max(adjEnd, secEnd);
                }
            }
            result.add(new RetimedEntry(lyrics.get(idx), startMs[idx], originalEndMs[idx], adjEnd, idxIsSec));
        }
        return result;
    }
}
