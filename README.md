# meeting-slot-finder

跨时区会议空闲时段推荐 CLI：读取参会者的时区、工作日、工作时段与已占用时间，计算所有人共同空闲区间，并给出每段中最早的会议起止时间（UTC 与各参会者本地时间）。

## 环境要求

- JDK 17+
- Maven 3.9+

## 构建

```bash
mvn package
```

生成可执行 JAR：`target/meeting-slot-finder-1.0.0.jar`。

## 运行

```bash
java -jar target/meeting-slot-finder-1.0.0.jar -c examples/config.json -o examples/output.json
```

参数：

- `-c, --config`：JSON 配置文件路径（必填）
- `-o, --output`：JSON 输出文件路径（必填）
- `-h, --help`：查看帮助

## 配置格式

```json
{
  "queryStart": "2024-06-17T00:00:00Z",
  "queryEnd": "2024-06-22T00:00:00Z",
  "durationMinutes": 60,
  "participants": [
    {
      "id": "alice",
      "timeZone": "Asia/Shanghai",
      "workDays": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"],
      "workStart": "09:00",
      "workEnd": "21:00",
      "busy": [
        {"start": "2024-06-17T02:00:00Z", "end": "2024-06-17T03:30:00Z"}
      ]
    }
  ]
}
```

字段说明：

- `queryStart` / `queryEnd`：查询窗口，带偏移的 ISO 8601 格式，左闭右开。
- `durationMinutes`：会议时长，正整数分钟。
- `participants`：参会者列表，不能为空，`id` 不得重复。
  - `timeZone`：IANA 时区名（如 `Asia/Shanghai`、`America/New_York`）。
  - `workDays`：每周工作日，取 `MONDAY`～`SUNDAY`。
  - `workStart` / `workEnd`：同日工作起止时间（当地钟面时间，`HH:mm`），起点必须早于终点。
  - `busy`：已占用时间段（可省略），起止为带偏移的 ISO 8601，起点必须早于终点；允许重叠或跨日。

## 计算规则

- 工作时间按每位参会者的当地日期展开，再换算为 UTC 时刻求交集。
- 占用区间先合并（重叠或首尾相接均合并），再从工作时间内扣除；占用与工作时段首尾相接不算冲突。
- 夏令时：当地不存在的边界时间向前平移缺口时长；重复边界取较早偏移对应的时刻。
- 会议时长按真实经过时间（UTC 时刻差）计算，不按当地钟面时间相减。
- 结果限制在左闭右开的查询窗口内，按时间升序输出。

## 输出格式

```json
{
  "slots": [
    {
      "windowStart": "2024-06-17T12:00Z",
      "windowEnd": "2024-06-17T13:00Z",
      "meetingStart": "2024-06-17T12:00Z",
      "meetingEnd": "2024-06-17T13:00Z",
      "localTimes": [
        {"participantId": "alice", "zoneId": "Asia/Shanghai",
         "meetingStart": "2024-06-17T20:00+08:00", "meetingEnd": "2024-06-17T21:00+08:00"}
      ]
    }
  ]
}
```

- `windowStart` / `windowEnd`：足够容纳会议时长的最大共同空闲区间（UTC）。
- `meetingStart` / `meetingEnd`：该区间内最早的会议起止时间（UTC）。
- `localTimes`：各参会者带偏移的本地会议起止时间与时区，便于核对邀约。
- 无可用时段时输出空列表 `{"slots": []}`。

## 错误处理

配置非法（参会者为空、ID 重复、时长非正、时间段起点不早于终点、时区或工作日无效、JSON 解析失败等）时，程序向标准错误输出明确错误信息并以退出码 1 结束，不写入输出文件，已有输出文件保持不变。

## 测试

```bash
mvn test
```

覆盖：跨时区交集、占用扣除与合并、跨日占用、首尾相接不冲突、查询窗口裁剪、夏令时缺口/重复边界、真实经过时长、各类非法配置。

## 示例

`examples/config.json` 为上海、柏林、纽约三地团队的示例配置，运行：

```bash
java -jar target/meeting-slot-finder-1.0.0.jar -c examples/config.json -o examples/output.json
```

输出 `examples/output.json`：2024-06-17 至 2024-06-21 每个工作日 12:00–13:00 UTC（上海 20:00–21:00、柏林 14:00–15:00、纽约 08:00–09:00）的共同空闲时段。
