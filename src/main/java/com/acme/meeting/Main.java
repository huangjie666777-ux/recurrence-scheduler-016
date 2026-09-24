package com.acme.meeting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "meeting-slot-finder", mixinStandardHelpOptions = true, version = "1.0.0",
        description = "Finds common free meeting slots across time zones.")
public class Main implements Callable<Integer> {

    @Option(names = {"-c", "--config"}, required = true, description = "Path to the JSON config file.")
    Path configFile;

    @Option(names = {"-o", "--output"}, description = "Path to the output JSON file (default: stdout).")
    Path outputFile;

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }

    @Override
    public Integer call() {
        ObjectMapper mapper = new ObjectMapper();
        Config config;
        try {
            config = mapper.readValue(configFile.toFile(), Config.class);
        } catch (IOException e) {
            System.err.println("error: cannot read config file " + configFile + ": " + e.getMessage());
            return 2;
        }
        Config.Validated validated;
        try {
            validated = config.validate();
        } catch (ConfigException e) {
            System.err.println("error: invalid configuration: " + e.getMessage());
            return 2;
        }

        long durationSec = validated.durationMinutes() * 60L;
        List<long[]> slots = Engine.findCommonFreeSlots(validated);

        ObjectNode root = mapper.createObjectNode();
        root.put("durationMinutes", validated.durationMinutes());
        ArrayNode arr = root.putArray("slots");
        for (long[] slot : slots) {
            if (slot[1] - slot[0] < durationSec) {
                continue;
            }
            ObjectNode node = arr.addObject();
            node.put("slotStart", utc(slot[0]));
            node.put("slotEnd", utc(slot[1]));
            long meetingStart = slot[0];
            long meetingEnd = slot[0] + durationSec;
            node.put("meetingStart", utc(meetingStart));
            node.put("meetingEnd", utc(meetingEnd));
            ArrayNode locals = node.putArray("localTimes");
            for (Config.ValidatedParticipant p : validated.participants()) {
                ObjectNode lp = locals.addObject();
                lp.put("id", p.id());
                lp.put("timeZone", p.zone().getId());
                lp.put("meetingStart", local(meetingStart, p));
                lp.put("meetingEnd", local(meetingEnd, p));
            }
        }

        try {
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + System.lineSeparator();
            if (outputFile != null) {
                Files.writeString(outputFile, json, StandardCharsets.UTF_8);
                System.out.println("wrote " + slots.size() + " slot(s) to " + outputFile);
            } else {
                System.out.print(json);
            }
        } catch (IOException e) {
            System.err.println("error: cannot write output: " + e.getMessage());
            return 1;
        }
        return 0;
    }

    private static String utc(long epochSec) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSec), ZoneOffset.UTC));
    }

    private static String local(long epochSec, Config.ValidatedParticipant p) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSec), p.zone()));
    }
}
