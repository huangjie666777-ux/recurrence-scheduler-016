package com.acme.meeting;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes common free intervals across participants. All intervals are
 * half-open [start, end) epoch-second pairs; durations are real elapsed
 * time, never wall-clock differences.
 */
public final class Engine {

    private Engine() {}

    /** Returns maximal common free intervals (epoch seconds, sorted, non-overlapping). */
    public static List<long[]> findCommonFreeSlots(Config.Validated config) {
        long qs = config.queryStartEpoch();
        long qe = config.queryEndEpoch();
        List<long[]> common = null;
        for (Config.ValidatedParticipant p : config.participants()) {
            List<long[]> free = freeIntervals(p, qs, qe);
            common = (common == null) ? free : intersect(common, free);
            if (common.isEmpty()) {
                break;
            }
        }
        return common == null ? List.of() : common;
    }

    /** Work windows per local work day, minus busy intervals, clamped to [qs, qe). */
    static List<long[]> freeIntervals(Config.ValidatedParticipant p, long qs, long qe) {
        ZoneId zone = p.zone();
        LocalDate from = Instant.ofEpochSecond(qs).atZone(zone).toLocalDate().minusDays(1);
        LocalDate to = Instant.ofEpochSecond(qe).atZone(zone).toLocalDate();
        List<long[]> windows = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (!p.workDays().contains(date.getDayOfWeek())) {
                continue;
            }
            // atZone: nonexistent local times (DST gap) shift forward by the gap
            // length; ambiguous local times (DST overlap) resolve to the earlier offset.
            long start = p.workStart().atDate(date).atZone(zone).toInstant().getEpochSecond();
            long end = p.workEnd().atDate(date).atZone(zone).toInstant().getEpochSecond();
            start = Math.max(start, qs);
            end = Math.min(end, qe);
            if (start < end) {
                windows.add(new long[]{start, end});
            }
        }
        windows = merge(windows);
        List<long[]> busy = new ArrayList<>();
        for (long[] b : p.busy()) {
            long s = Math.max(b[0], qs);
            long e = Math.min(b[1], qe);
            if (s < e) {
                busy.add(new long[]{s, e});
            }
        }
        return subtract(windows, merge(busy));
    }

    /** Sorts and merges overlapping or touching intervals. */
    static List<long[]> merge(List<long[]> intervals) {
        List<long[]> sorted = new ArrayList<>(intervals);
        sorted.sort((a, b) -> Long.compare(a[0], b[0]) != 0
                ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        List<long[]> out = new ArrayList<>();
        for (long[] iv : sorted) {
            if (!out.isEmpty() && iv[0] <= out.get(out.size() - 1)[1]) {
                long[] last = out.get(out.size() - 1);
                last[1] = Math.max(last[1], iv[1]);
            } else {
                out.add(new long[]{iv[0], iv[1]});
            }
        }
        return out;
    }

    static List<long[]> subtract(List<long[]> base, List<long[]> cuts) {
        List<long[]> out = new ArrayList<>();
        int j = 0;
        for (long[] b : base) {
            long cur = b[0];
            while (j < cuts.size() && cuts.get(j)[1] <= cur) {
                j++;
            }
            int k = j;
            while (k < cuts.size() && cuts.get(k)[0] < b[1]) {
                if (cuts.get(k)[0] > cur) {
                    out.add(new long[]{cur, cuts.get(k)[0]});
                }
                cur = Math.max(cur, cuts.get(k)[1]);
                if (cur >= b[1]) {
                    break;
                }
                k++;
            }
            if (cur < b[1]) {
                out.add(new long[]{cur, b[1]});
            }
        }
        return out;
    }

    static List<long[]> intersect(List<long[]> a, List<long[]> b) {
        List<long[]> out = new ArrayList<>();
        int i = 0, j = 0;
        while (i < a.size() && j < b.size()) {
            long s = Math.max(a.get(i)[0], b.get(j)[0]);
            long e = Math.min(a.get(i)[1], b.get(j)[1]);
            if (s < e) {
                out.add(new long[]{s, e});
            }
            if (a.get(i)[1] < b.get(j)[1]) {
                i++;
            } else {
                j++;
            }
        }
        return out;
    }
}
