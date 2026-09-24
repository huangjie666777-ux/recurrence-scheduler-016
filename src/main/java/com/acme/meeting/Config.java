package com.acme.meeting;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = false)
public class Config {
    public String queryStart;
    public String queryEnd;
    public int durationMinutes;
    public List<Participant> participants = new ArrayList<>();

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class Participant {
        public String id;
        public String timeZone;
        public List<String> workDays = new ArrayList<>();
        public String workStart;
        public String workEnd;
        public List<BusyInterval> busy = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class BusyInterval {
        public String start;
        public String end;
    }

    public Validated validate() {
        List<String> errors = new ArrayList<>();
        OffsetDateTime qs = parseTime(queryStart, "queryStart", errors);
        OffsetDateTime qe = parseTime(queryEnd, "queryEnd", errors);
        if (qs != null && qe != null && !qs.toInstant().isBefore(qe.toInstant())) {
            errors.add("queryStart must be earlier than queryEnd");
        }
        if (durationMinutes <= 0) {
            errors.add("durationMinutes must be a positive integer, got " + durationMinutes);
        }
        if (participants == null || participants.isEmpty()) {
            errors.add("participants must not be empty");
        }
        List<ValidatedParticipant> vps = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        if (participants != null) {
            for (int i = 0; i < participants.size(); i++) {
                Participant p = participants.get(i);
                String label = "participants[" + i + "]";
                if (p.id == null || p.id.isBlank()) {
                    errors.add(label + ".id must not be blank");
                } else if (!ids.add(p.id)) {
                    errors.add("duplicate participant id: " + p.id);
                }
                ZoneId zone = null;
                try {
                    zone = ZoneId.of(p.timeZone);
                } catch (Exception e) {
                    errors.add(label + ".timeZone is not a valid IANA time zone: " + p.timeZone);
                }
                Set<DayOfWeek> days = new HashSet<>();
                if (p.workDays == null || p.workDays.isEmpty()) {
                    errors.add(label + ".workDays must not be empty");
                } else {
                    for (String d : p.workDays) {
                        try {
                            days.add(DayOfWeek.valueOf(d.trim().toUpperCase()));
                        } catch (Exception e) {
                            errors.add(label + ".workDays contains invalid day: " + d);
                        }
                    }
                }
                LocalTime ws = parseLocalTime(p.workStart, label + ".workStart", errors);
                LocalTime we = parseLocalTime(p.workEnd, label + ".workEnd", errors);
                if (ws != null && we != null && !ws.isBefore(we)) {
                    errors.add(label + ": workStart must be earlier than workEnd");
                }
                List<long[]> busy = new ArrayList<>();
                if (p.busy != null) {
                    for (int j = 0; j < p.busy.size(); j++) {
                        BusyInterval b = p.busy.get(j);
                        OffsetDateTime bs = parseTime(b.start, label + ".busy[" + j + "].start", errors);
                        OffsetDateTime be = parseTime(b.end, label + ".busy[" + j + "].end", errors);
                        if (bs != null && be != null) {
                            if (!bs.toInstant().isBefore(be.toInstant())) {
                                errors.add(label + ".busy[" + j + "]: start must be earlier than end");
                            } else {
                                busy.add(new long[]{bs.toInstant().getEpochSecond(), be.toInstant().getEpochSecond()});
                            }
                        }
                    }
                }
                if (zone != null && ws != null && we != null && ws.isBefore(we) && !days.isEmpty()) {
                    vps.add(new ValidatedParticipant(p.id, zone, days, ws, we, busy));
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new ConfigException(String.join("; ", errors));
        }
        return new Validated(qs.toInstant().getEpochSecond(), qe.toInstant().getEpochSecond(), durationMinutes, vps);
    }

    private static OffsetDateTime parseTime(String value, String field, List<String> errors) {
        if (value == null) {
            errors.add(field + " is required");
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (Exception e) {
            errors.add(field + " is not a valid ISO-8601 offset date-time: " + value);
            return null;
        }
    }

    private static LocalTime parseLocalTime(String value, String field, List<String> errors) {
        if (value == null) {
            errors.add(field + " is required");
            return null;
        }
        try {
            return LocalTime.parse(value);
        } catch (Exception e) {
            errors.add(field + " is not a valid local time (HH:mm): " + value);
            return null;
        }
    }

    public record ValidatedParticipant(String id, ZoneId zone, Set<DayOfWeek> workDays,
                                       LocalTime workStart, LocalTime workEnd, List<long[]> busy) {}

    public record Validated(long queryStartEpoch, long queryEndEpoch, int durationMinutes,
                            List<ValidatedParticipant> participants) {}
}
