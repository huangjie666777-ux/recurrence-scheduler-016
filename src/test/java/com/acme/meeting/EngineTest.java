package com.acme.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.acme.meeting.Config.Validated;
import com.acme.meeting.Config.ValidatedParticipant;

class EngineTest {

    private static long epoch(String iso) {
        return Instant.parse(iso).getEpochSecond();
    }

    private static ValidatedParticipant participant(String id, String zone, List<String> days,
            String ws, String we, List<long[]> busy) {
        Config.Participant p = new Config.Participant();
        p.id = id;
        p.timeZone = zone;
        p.workDays = days;
        p.workStart = ws;
        p.workEnd = we;
        p.busy = new java.util.ArrayList<>();
        Config cfg = new Config();
        cfg.queryStart = "2026-01-01T00:00:00Z";
        cfg.queryEnd = "2030-01-01T00:00:00Z";
        cfg.durationMinutes = 30;
        cfg.participants = List.of(p);
        Validated v = cfg.validate();
        ValidatedParticipant vp = v.participants().get(0);
        return new ValidatedParticipant(vp.id(), vp.zone(), vp.workDays(), vp.workStart(), vp.workEnd(), busy);
    }

    private static Validated config(String qs, String qe, int minutes, ValidatedParticipant... ps) {
        return new Validated(epoch(qs), epoch(qe), minutes, List.of(ps));
    }

    @Test
    void commonSlotAcrossTimeZones() {
        // New York 09:00-17:00 EST (UTC-5), Shanghai 09:00-17:00 CST (UTC+8)
        // overlap: NY 09:00-12:00 == Shanghai 22:00-01:00? No: Shanghai 09:00 = 01:00Z = NY 20:00 (prev day, off work).
        // Real overlap: NY morning 09:00-12:00 EST = 14:00-17:00Z = Shanghai 22:00-01:00 (off work).
        // Shanghai 09:00-10:00 = 01:00-02:00Z = NY 20:00-21:00 (off work). So use shifted hours:
        ValidatedParticipant ny = participant("ny", "America/New_York",
                List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"), "08:00", "12:00", List.of());
        ValidatedParticipant sh = participant("sh", "Asia/Shanghai",
                List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"), "20:00", "23:00", List.of());
        // 2026-01-05 is a Monday. NY 08:00-12:00 EST = 13:00-17:00Z. SH 20:00-23:00 = 12:00-15:00Z.
        // Common: 13:00-15:00Z.
        List<long[]> slots = Engine.findCommonFreeSlots(
                config("2026-01-05T00:00:00Z", "2026-01-06T00:00:00Z", 60, ny, sh));
        assertEquals(1, slots.size());
        assertEquals(epoch("2026-01-05T13:00:00Z"), slots.get(0)[0]);
        assertEquals(epoch("2026-01-05T15:00:00Z"), slots.get(0)[1]);
    }

    @Test
    void busyOverlappingAndCrossDayMerged() {
        ValidatedParticipant p = participant("a", "UTC",
                List.of("MONDAY"), "09:00", "17:00",
                List.of(new long[]{epoch("2026-01-05T10:00:00Z"), epoch("2026-01-05T11:30:00Z")},
                        new long[]{epoch("2026-01-05T11:00:00Z"), epoch("2026-01-05T12:00:00Z")},
                        // cross-day busy: 2026-01-04 23:00 -> 2026-01-05 09:30
                        new long[]{epoch("2026-01-04T23:00:00Z"), epoch("2026-01-05T09:30:00Z")}));
        List<long[]> slots = Engine.findCommonFreeSlots(
                config("2026-01-05T00:00:00Z", "2026-01-06T00:00:00Z", 30, p));
        assertEquals(List.of(), slots.stream().filter(s -> s[0] < epoch("2026-01-05T09:30:00Z")).toList());
        assertEquals(2, slots.size());
        assertEquals(epoch("2026-01-05T09:30:00Z"), slots.get(0)[0]);
        assertEquals(epoch("2026-01-05T10:00:00Z"), slots.get(0)[1]);
        assertEquals(epoch("2026-01-05T12:00:00Z"), slots.get(1)[0]);
        assertEquals(epoch("2026-01-05T17:00:00Z"), slots.get(1)[1]);
    }

    @Test
    void touchingBusyIntervalsDoNotConflict() {
        // busy ends exactly when the other begins: free time between them is zero but
        // work 09-12 minus busy [10,11) and [11,12) leaves [09,10) only.
        ValidatedParticipant p = participant("a", "UTC",
                List.of("MONDAY"), "09:00", "12:00",
                List.of(new long[]{epoch("2026-01-05T10:00:00Z"), epoch("2026-01-05T11:00:00Z")},
                        new long[]{epoch("2026-01-05T11:00:00Z"), epoch("2026-01-05T12:00:00Z")}));
        List<long[]> slots = Engine.findCommonFreeSlots(
                config("2026-01-05T00:00:00Z", "2026-01-06T00:00:00Z", 30, p));
        assertEquals(1, slots.size());
        assertEquals(epoch("2026-01-05T09:00:00Z"), slots.get(0)[0]);
        assertEquals(epoch("2026-01-05T10:00:00Z"), slots.get(0)[1]);
    }

    @Test
    void dstGapShiftsBoundaryForwardByGap() {
        // 2026-03-08 America/New_York springs forward: 02:00-02:59 does not exist.
        // Work 02:30-06:00 -> start shifts forward by the 1h gap to 03:30 EDT (07:30Z),
        // end 06:00 EDT = 10:00Z.
        ValidatedParticipant p = participant("a", "America/New_York",
                List.of("SUNDAY"), "02:30", "06:00", List.of());
        List<long[]> slots = Engine.findCommonFreeSlots(
                config("2026-03-08T00:00:00Z", "2026-03-09T00:00:00Z", 30, p));
        assertEquals(1, slots.size());
        // 03:30 EDT = 07:30Z, 06:00 EDT = 10:00Z; real elapsed = 2.5h (not 3.5 wall hours)
        assertEquals(epoch("2026-03-08T07:30:00Z"), slots.get(0)[0]);
        assertEquals(epoch("2026-03-08T10:00:00Z"), slots.get(0)[1]);
    }

    @Test
    void dstOverlapUsesEarlierOffset() {
        // 2026-11-01 America/New_York falls back: 01:00-01:59 occurs twice.
        // Work 01:30-03:00 -> start uses earlier offset (EDT, UTC-4): 05:30Z; end 03:00 EST = 08:00Z.
        ValidatedParticipant p = participant("a", "America/New_York",
                List.of("SUNDAY"), "01:30", "03:00", List.of());
        List<long[]> slots = Engine.findCommonFreeSlots(
                config("2026-11-01T00:00:00Z", "2026-11-02T00:00:00Z", 30, p));
        assertEquals(1, slots.size());
        assertEquals(epoch("2026-11-01T05:30:00Z"), slots.get(0)[0]);
        assertEquals(epoch("2026-11-01T08:00:00Z"), slots.get(0)[1]);
    }

    @Test
    void queryWindowIsLeftClosedRightOpen() {
        ValidatedParticipant p = participant("a", "UTC",
                List.of("MONDAY"), "09:00", "17:00", List.of());
        List<long[]> slots = Engine.findCommonFreeSlots(
                config("2026-01-05T10:00:00Z", "2026-01-05T16:00:00Z", 30, p));
        assertEquals(1, slots.size());
        assertEquals(epoch("2026-01-05T10:00:00Z"), slots.get(0)[0]);
        assertEquals(epoch("2026-01-05T16:00:00Z"), slots.get(0)[1]);
    }

    @Test
    void noCommonTimeYieldsEmpty() {
        ValidatedParticipant a = participant("a", "UTC", List.of("MONDAY"), "09:00", "12:00", List.of());
        ValidatedParticipant b = participant("b", "UTC", List.of("MONDAY"), "13:00", "17:00", List.of());
        assertTrue(Engine.findCommonFreeSlots(
                config("2026-01-05T00:00:00Z", "2026-01-06T00:00:00Z", 30, a, b)).isEmpty());
    }

    @Test
    void invalidConfigsRejected() {
        Config cfg = new Config();
        cfg.queryStart = "2026-01-02T00:00:00Z";
        cfg.queryEnd = "2026-01-01T00:00:00Z";
        cfg.durationMinutes = 0;
        Config.Participant p = new Config.Participant();
        p.id = "x";
        p.timeZone = "Not/AZone";
        p.workDays = List.of("FUNDAY");
        p.workStart = "17:00";
        p.workEnd = "09:00";
        cfg.participants = List.of(p);
        ConfigException ex = assertThrows(ConfigException.class, cfg::validate);
        String msg = ex.getMessage();
        assertTrue(msg.contains("queryStart"));
        assertTrue(msg.contains("durationMinutes"));
        assertTrue(msg.contains("timeZone"));
        assertTrue(msg.contains("FUNDAY"));
        assertTrue(msg.contains("workStart"));
    }

    @Test
    void duplicateIdsAndEmptyParticipantsRejected() {
        Config cfg = new Config();
        cfg.queryStart = "2026-01-01T00:00:00Z";
        cfg.queryEnd = "2026-01-02T00:00:00Z";
        cfg.durationMinutes = 30;
        cfg.participants = List.of();
        assertThrows(ConfigException.class, cfg::validate);

        Config.Participant p = new Config.Participant();
        p.id = "dup";
        p.timeZone = "UTC";
        p.workDays = List.of("MONDAY");
        p.workStart = "09:00";
        p.workEnd = "17:00";
        cfg.participants = List.of(p, p);
        ConfigException ex = assertThrows(ConfigException.class, cfg::validate);
        assertTrue(ex.getMessage().contains("duplicate"));
    }

    @Test
    void invalidBusyIntervalRejected() {
        Config cfg = new Config();
        cfg.queryStart = "2026-01-01T00:00:00Z";
        cfg.queryEnd = "2026-01-02T00:00:00Z";
        cfg.durationMinutes = 30;
        Config.Participant p = new Config.Participant();
        p.id = "a";
        p.timeZone = "UTC";
        p.workDays = List.of("MONDAY");
        p.workStart = "09:00";
        p.workEnd = "17:00";
        Config.BusyInterval b = new Config.BusyInterval();
        b.start = "2026-01-05T12:00:00Z";
        b.end = "2026-01-05T11:00:00Z";
        p.busy = List.of(b);
        cfg.participants = List.of(p);
        assertThrows(ConfigException.class, cfg::validate);
    }
}
