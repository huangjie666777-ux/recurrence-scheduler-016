package com.example.meeting;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SlotFinderTest {

    private Config.Participant participant(String id, String zone, List<DayOfWeek> days,
                                           String workStart, String workEnd, Config.BusySlot... busy) {
        Config.Participant p = new Config.Participant();
        p.id = id;
        p.timeZone = ZoneId.of(zone);
        p.workDays = days;
        p.workStart = LocalTime.parse(workStart);
        p.workEnd = LocalTime.parse(workEnd);
        p.busy = List.of(busy);
        return p;
    }

    private Config.BusySlot busy(String start, String end) {
        Config.BusySlot b = new Config.BusySlot();
        b.start = OffsetDateTime.parse(start);
        b.end = OffsetDateTime.parse(end);
        return b;
    }

    private Config config(String start, String end, int minutes, Config.Participant... ps) {
        Config c = new Config();
        c.queryStart = OffsetDateTime.parse(start);
        c.queryEnd = OffsetDateTime.parse(end);
        c.durationMinutes = minutes;
        c.participants = List.of(ps);
        return c;
    }

    @Test
    void findsCommonSlotAcrossTimeZones() {
        // 2024-01-15 是周一
        Config c = config("2024-01-15T00:00:00Z", "2024-01-16T00:00:00Z", 60,
                participant("a", "Asia/Shanghai", List.of(DayOfWeek.MONDAY), "09:00", "18:00"),
                participant("b", "Europe/Berlin", List.of(DayOfWeek.MONDAY), "09:00", "17:00"));
        List<SlotFinder.Slot> slots = SlotFinder.findSlots(c);
        assertEquals(1, slots.size());
        // 上海 09:00-18:00 (+08:00) = 01:00-10:00Z；柏林 09:00-17:00 (+01:00) = 08:00-16:00Z
        assertEquals("2024-01-15T08:00Z", slots.get(0).windowStart());
        assertEquals("2024-01-15T10:00Z", slots.get(0).windowEnd());
        assertEquals("2024-01-15T08:00Z", slots.get(0).meetingStart());
        assertEquals("2024-01-15T09:00Z", slots.get(0).meetingEnd());
        assertEquals(2, slots.get(0).localTimes().size());
        assertEquals("2024-01-15T16:00+08:00", slots.get(0).localTimes().get(0).meetingStart());
    }

    @Test
    void busySlotsSubtractAndMerge() {
        Config c = config("2024-01-15T00:00:00Z", "2024-01-16T00:00:00Z", 60,
                participant("a", "UTC", List.of(DayOfWeek.MONDAY), "09:00", "17:00",
                        busy("2024-01-15T10:00:00Z", "2024-01-15T11:00:00Z"),
                        busy("2024-01-15T10:30:00Z", "2024-01-15T12:00:00Z")));
        List<SlotFinder.Slot> slots = SlotFinder.findSlots(c);
        assertEquals(2, slots.size());
        assertEquals("2024-01-15T09:00Z", slots.get(0).windowStart());
        assertEquals("2024-01-15T10:00Z", slots.get(0).windowEnd());
        assertEquals("2024-01-15T12:00Z", slots.get(1).windowStart());
        assertEquals("2024-01-15T17:00Z", slots.get(1).windowEnd());
    }

    @Test
    void touchingBusyDoesNotConflict() {
        // 占用恰好在工作时段边界首尾相接，不应产生冲突
        Config c = config("2024-01-15T00:00:00Z", "2024-01-16T00:00:00Z", 60,
                participant("a", "UTC", List.of(DayOfWeek.MONDAY), "09:00", "12:00",
                        busy("2024-01-15T08:00:00Z", "2024-01-15T09:00:00Z"),
                        busy("2024-01-15T12:00:00Z", "2024-01-15T13:00:00Z")));
        List<SlotFinder.Slot> slots = SlotFinder.findSlots(c);
        assertEquals(1, slots.size());
        assertEquals("2024-01-15T09:00Z", slots.get(0).windowStart());
        assertEquals("2024-01-15T12:00Z", slots.get(0).windowEnd());
    }

    @Test
    void crossDayBusyIsSubtracted() {
        Config c = config("2024-01-15T00:00:00Z", "2024-01-17T00:00:00Z", 30,
                participant("a", "UTC", List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), "09:00", "17:00",
                        busy("2024-01-15T16:00:00Z", "2024-01-16T10:00:00Z")));
        List<SlotFinder.Slot> slots = SlotFinder.findSlots(c);
        assertEquals(2, slots.size());
        assertEquals("2024-01-15T09:00Z", slots.get(0).windowStart());
        assertEquals("2024-01-15T16:00Z", slots.get(0).windowEnd());
        assertEquals("2024-01-16T10:00Z", slots.get(1).windowStart());
        assertEquals("2024-01-16T17:00Z", slots.get(1).windowEnd());
    }

    @Test
    void windowClippedToQueryRange() {
        Config c = config("2024-01-15T10:00:00Z", "2024-01-15T11:00:00Z", 30,
                participant("a", "UTC", List.of(DayOfWeek.MONDAY), "09:00", "17:00"));
        List<SlotFinder.Slot> slots = SlotFinder.findSlots(c);
        assertEquals(1, slots.size());
        assertEquals("2024-01-15T10:00Z", slots.get(0).windowStart());
        assertEquals("2024-01-15T11:00Z", slots.get(0).windowEnd());
    }

    @Test
    void noCommonSlotReturnsEmpty() {
        Config c = config("2024-01-15T00:00:00Z", "2024-01-16T00:00:00Z", 60,
                participant("a", "UTC", List.of(DayOfWeek.MONDAY), "09:00", "12:00"),
                participant("b", "UTC", List.of(DayOfWeek.MONDAY), "13:00", "17:00"));
        assertTrue(SlotFinder.findSlots(c).isEmpty());
    }

    @Test
    void slotShorterThanDurationIsSkipped() {
        Config c = config("2024-01-15T00:00:00Z", "2024-01-16T00:00:00Z", 90,
                participant("a", "UTC", List.of(DayOfWeek.MONDAY), "09:00", "10:00"));
        assertTrue(SlotFinder.findSlots(c).isEmpty());
    }

    @Test
    void dstGapBoundaryShiftsForward() {
        // America/New_York 2024-03-10 02:00-03:00 不存在（春进）
        Config c = config("2024-03-10T00:00:00Z", "2024-03-11T00:00:00Z", 30,
                participant("a", "America/New_York", List.of(DayOfWeek.SUNDAY), "02:30", "05:00"));
        List<SlotFinder.Slot> slots = SlotFinder.findSlots(c);
        assertEquals(1, slots.size());
        // 02:30 缺口向前平移 1 小时 -> 03:30 EST(UTC-5) 之前为 EST；实际 03:30 为 EDT(UTC-4) -> 07:30Z
        assertEquals("2024-03-10T07:30Z", slots.get(0).windowStart());
        assertEquals("2024-03-10T09:00Z", slots.get(0).windowEnd());
    }

    @Test
    void dstOverlapBoundaryUsesEarlierOffset() {
        // America/New_York 2024-11-03 01:00-02:00 重复（秋退），取较早偏移 EDT(UTC-4)
        Config c = config("2024-11-03T00:00:00Z", "2024-11-04T00:00:00Z", 30,
                participant("a", "America/New_York", List.of(DayOfWeek.SUNDAY), "01:30", "03:00"));
        List<SlotFinder.Slot> slots = SlotFinder.findSlots(c);
        assertEquals(1, slots.size());
        // 01:30 EDT = 05:30Z；03:00 EST = 08:00Z
        assertEquals("2024-11-03T05:30Z", slots.get(0).windowStart());
        assertEquals("2024-11-03T08:00Z", slots.get(0).windowEnd());
    }

    @Test
    void durationUsesRealElapsedTime() {
        // 跨春进边界：当地 01:00-04:00 实际只经过 2 小时
        Config c = config("2024-03-10T00:00:00Z", "2024-03-11T00:00:00Z", 150,
                participant("a", "America/New_York", List.of(DayOfWeek.SUNDAY), "01:00", "04:00"));
        List<SlotFinder.Slot> slots = SlotFinder.findSlots(c);
        // 01:00 EST=06:00Z 到 04:00 EDT=08:00Z，真实经过 2 小时 < 150 分钟
        assertTrue(slots.isEmpty());
    }

    @Test
    void rejectsEmptyParticipants() {
        Config c = config("2024-01-15T00:00:00Z", "2024-01-16T00:00:00Z", 60);
        c.participants = List.of();
        assertThrows(IllegalArgumentException.class, () -> SlotFinder.findSlots(c));
    }

    @Test
    void rejectsDuplicateIds() {
        Config c = config("2024-01-15T00:00:00Z", "2024-01-16T00:00:00Z", 60,
                participant("a", "UTC", List.of(DayOfWeek.MONDAY), "09:00", "17:00"),
                participant("a", "UTC", List.of(DayOfWeek.MONDAY), "09:00", "17:00"));
        assertThrows(IllegalArgumentException.class, () -> SlotFinder.findSlots(c));
    }

    @Test
    void rejectsNonPositiveDuration() {
        Config c = config("2024-01-15T00:00:00Z", "2024-01-16T00:00:00Z", 0,
                participant("a", "UTC", List.of(DayOfWeek.MONDAY), "09:00", "17:00"));
        assertThrows(IllegalArgumentException.class, () -> SlotFinder.findSlots(c));
    }

    @Test
    void rejectsReversedRanges() {
        Config c = config("2024-01-16T00:00:00Z", "2024-01-15T00:00:00Z", 60,
                participant("a", "UTC", List.of(DayOfWeek.MONDAY), "09:00", "17:00"));
        assertThrows(IllegalArgumentException.class, () -> SlotFinder.findSlots(c));

        Config c2 = config("2024-01-15T00:00:00Z", "2024-01-16T00:00:00Z", 60,
                participant("a", "UTC", List.of(DayOfWeek.MONDAY), "17:00", "09:00"));
        assertThrows(IllegalArgumentException.class, () -> SlotFinder.findSlots(c2));

        Config c3 = config("2024-01-15T00:00:00Z", "2024-01-16T00:00:00Z", 60,
                participant("a", "UTC", List.of(DayOfWeek.MONDAY), "09:00", "17:00",
                        busy("2024-01-15T12:00:00Z", "2024-01-15T11:00:00Z")));
        assertThrows(IllegalArgumentException.class, () -> SlotFinder.findSlots(c3));
    }
}
