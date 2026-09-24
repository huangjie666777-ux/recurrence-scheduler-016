package com.example.meeting;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

public class Config {
    public OffsetDateTime queryStart;
    public OffsetDateTime queryEnd;
    public int durationMinutes;
    public List<Participant> participants;

    public static class Participant {
        public String id;
        public ZoneId timeZone;
        public List<DayOfWeek> workDays;
        public LocalTime workStart;
        public LocalTime workEnd;
        public List<BusySlot> busy = List.of();
    }

    public static class BusySlot {
        public OffsetDateTime start;
        public OffsetDateTime end;
    }
}
