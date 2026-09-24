package com.example.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "meeting-slot-finder", mixinStandardHelpOptions = true,
        description = "推荐跨时区共同空闲时段")
public class MeetingSlotFinder implements Callable<Integer> {

    @Option(names = {"-c", "--config"}, required = true, description = "JSON 配置文件路径")
    Path configFile;

    @Option(names = {"-o", "--output"}, required = true, description = "JSON 输出文件路径")
    Path outputFile;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    public static void main(String[] args) {
        System.exit(new CommandLine(new MeetingSlotFinder()).execute(args));
    }

    @Override
    public Integer call() {
        try {
            Config config = readConfig();
            List<SlotFinder.Slot> slots = SlotFinder.findSlots(config);
            writeOutput(slots);
            System.out.println("已写入 " + outputFile + "，共 " + slots.size() + " 个可用时段");
            return 0;
        } catch (IllegalArgumentException | IOException e) {
            System.err.println("错误: " + e.getMessage());
            return 1;
        }
    }

    private Config readConfig() throws IOException {
        if (!Files.isRegularFile(configFile)) {
            throw new IllegalArgumentException("配置文件不存在: " + configFile);
        }
        Config config;
        try {
            config = MAPPER.readValue(configFile.toFile(), Config.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("配置 JSON 解析失败: " + e.getOriginalMessage());
        }
        return config;
    }

    private void writeOutput(List<SlotFinder.Slot> slots) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode arr = root.putArray("slots");
        for (SlotFinder.Slot s : slots) {
            ObjectNode node = arr.addObject();
            node.put("windowStart", s.windowStart());
            node.put("windowEnd", s.windowEnd());
            node.put("meetingStart", s.meetingStart());
            node.put("meetingEnd", s.meetingEnd());
            ArrayNode locals = node.putArray("localTimes");
            for (SlotFinder.LocalTimeInfo lt : s.localTimes()) {
                ObjectNode ln = locals.addObject();
                ln.put("participantId", lt.participantId());
                ln.put("zoneId", lt.zoneId());
                ln.put("meetingStart", lt.meetingStart());
                ln.put("meetingEnd", lt.meetingEnd());
            }
        }
        JsonNode json = root;
        Path parent = outputFile.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), json);
    }
}
