package com.example.meeting;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SlotFinder {

    public record Interval(Instant start, Instant end) {}

    public record LocalTimeInfo(String participantId, String zoneId,
                                String meetingStart, String meetingEnd) {}

    public record Slot(String windowStart, String windowEnd,
                       String meetingStart, String meetingEnd,
                       List<LocalTimeInfo> localTimes) {}

    public static List<Slot> findSlots(Config config) {
        validate(config);
        Instant queryStart = config.queryStart.toInstant();
        Instant queryEnd = config.queryEnd.toInstant();
        Duration duration = Duration.ofMinutes(config.durationMinutes);

        List<Interval> common = null;
        for (Config.Participant p : config.participants) {
            List<Interval> free = freeIntervals(p, queryStart, queryEnd);
            common = common == null ? free : intersect(common, free);
        }
        if (common == null) common = List.of();

        List<Slot> slots = new ArrayList<>();
        for (Interval window : common) {
            if (Duration.between(window.start(), window.end()).compareTo(duration) < 0) continue;
            Instant meetingStart = window.start();
            Instant meetingEnd = meetingStart.plus(duration);
            List<LocalTimeInfo> locals = new ArrayList<>();
            for (Config.Participant p : config.participants) {
                locals.add(new LocalTimeInfo(p.id, p.timeZone.getId(),
                        OffsetDateTime.ofInstant(meetingStart, p.timeZone).toString(),
                        OffsetDateTime.ofInstant(meetingEnd, p.timeZone).toString()));
            }
            slots.add(new Slot(isoUtc(window.start()), isoUtc(window.end()),
                    isoUtc(meetingStart), isoUtc(meetingEnd), locals));
        }
        return slots;
    }

    static void validate(Config config) {
        if (config == null) throw new IllegalArgumentException("配置为空");
        if (config.queryStart == null || config.queryEnd == null)
            throw new IllegalArgumentException("queryStart/queryEnd 不能为空");
        if (!config.queryStart.toInstant().isBefore(config.queryEnd.toInstant()))
            throw new IllegalArgumentException("查询起点必须早于终点");
        if (config.durationMinutes <= 0)
            throw new IllegalArgumentException("会议时长必须为正整数分钟");
        if (config.participants == null || config.participants.isEmpty())
            throw new IllegalArgumentException("参会者不能为空");
        List<String> ids = new ArrayList<>();
        for (Config.Participant p : config.participants) {
            if (p.id == null || p.id.isBlank())
                throw new IllegalArgumentException("参会者 ID 不能为空");
            if (ids.contains(p.id))
                throw new IllegalArgumentException("参会者 ID 重复: " + p.id);
            ids.add(p.id);
            if (p.timeZone == null)
                throw new IllegalArgumentException("参会者 " + p.id + " 时区无效或缺失");
            if (p.workDays == null || p.workDays.isEmpty())
                throw new IllegalArgumentException("参会者 " + p.id + " 工作日不能为空");
            if (p.workStart == null || p.workEnd == null)
                throw new IllegalArgumentException("参会者 " + p.id + " 工作起止时间不能为空");
            if (!p.workStart.isBefore(p.workEnd))
                throw new IllegalArgumentException("参会者 " + p.id + " 工作起点必须早于终点");
            if (p.busy == null) p.busy = List.of();
            for (Config.BusySlot b : p.busy) {
                if (b.start == null || b.end == null)
                    throw new IllegalArgumentException("参会者 " + p.id + " 占用时间段起止不能为空");
                if (!b.start.toInstant().isBefore(b.end.toInstant()))
                    throw new IllegalArgumentException("参会者 " + p.id + " 占用时间段起点必须早于终点");
            }
        }
    }

    static List<Interval> freeIntervals(Config.Participant p, Instant queryStart, Instant queryEnd) {
        List<Interval> work = new ArrayList<>();
        LocalDate from = queryStart.atZone(p.timeZone).toLocalDate().minusDays(1);
        LocalDate to = queryEnd.atZone(p.timeZone).toLocalDate().plusDays(1);
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (!p.workDays.contains(day.getDayOfWeek())) continue;
            // ZonedDateTime.of: 夏令时缺口向前平移缺口时长，重复边界取较早偏移
            ZonedDateTime start = ZonedDateTime.of(LocalDateTime.of(day, p.workStart), p.timeZone);
            ZonedDateTime end = ZonedDateTime.of(LocalDateTime.of(day, p.workEnd), p.timeZone);
            Instant s = start.toInstant();
            Instant e = end.toInstant();
            if (!e.isAfter(s)) continue;
            s = max(s, queryStart);
            e = min(e, queryEnd);
            if (e.isAfter(s)) work.add(new Interval(s, e));
        }
        List<Interval> busy = new ArrayList<>();
        for (Config.BusySlot b : p.busy) {
            Instant s = max(b.start.toInstant(), queryStart);
            Instant e = min(b.end.toInstant(), queryEnd);
            if (e.isAfter(s)) busy.add(new Interval(s, e));
        }
        return subtract(merge(work), merge(busy));
    }

    static List<Interval> merge(List<Interval> intervals) {
        List<Interval> sorted = new ArrayList<>(intervals);
        sorted.sort(Comparator.comparing(Interval::start));
        List<Interval> merged = new ArrayList<>();
        for (Interval iv : sorted) {
            // 相邻（首尾相接）区间合并
            if (!merged.isEmpty() && !iv.start().isAfter(merged.get(merged.size() - 1).end())) {
                Interval last = merged.get(merged.size() - 1);
                merged.set(merged.size() - 1, new Interval(last.start(), max(last.end(), iv.end())));
            } else {
                merged.add(iv);
            }
        }
        return merged;
    }

    static List<Interval> subtract(List<Interval> base, List<Interval> cuts) {
        List<Interval> result = new ArrayList<>();
        for (Interval b : base) {
            Instant cursor = b.start();
            for (Interval c : cuts) {
                if (!c.end().isAfter(cursor)) continue;
                if (!c.start().isBefore(b.end())) break;
                if (c.start().isAfter(cursor)) result.add(new Interval(cursor, c.start()));
                cursor = max(cursor, c.end());
                if (!cursor.isBefore(b.end())) break;
            }
            if (cursor.isBefore(b.end())) result.add(new Interval(cursor, b.end()));
        }
        return result;
    }

    static List<Interval> intersect(List<Interval> a, List<Interval> b) {
        List<Interval> result = new ArrayList<>();
        int i = 0, j = 0;
        while (i < a.size() && j < b.size()) {
            Instant s = max(a.get(i).start(), b.get(j).start());
            Instant e = min(a.get(i).end(), b.get(j).end());
            if (e.isAfter(s)) result.add(new Interval(s, e));
            if (a.get(i).end().isBefore(b.get(j).end())) i++; else j++;
        }
        return result;
    }

    private static Instant max(Instant a, Instant b) { return a.isAfter(b) ? a : b; }
    private static Instant min(Instant a, Instant b) { return a.isBefore(b) ? a : b; }

    static String isoUtc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC).toString();
    }
}
