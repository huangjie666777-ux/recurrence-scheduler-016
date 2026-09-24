# meeting-slot-finder

跨时区会议空闲时段推荐 CLI。读取 JSON 配置，按每位参会者的当地工作日与工作时间展开，
扣除已占用时间段后求所有人的共同空闲区间，输出足够容纳会议的时段及最早会议起止时间。

## 环境要求

- JDK 17+
- Maven 3.9+

## 构建

```bash
mvn package
```

生成可执行 jar：`target/meeting-slot-finder-1.0.0.jar`。

## 运行

```bash
# 输出到文件
java -jar target/meeting-slot-finder-1.0.0.jar -c examples/config.json -o examples/result.json

# 输出到标准输出
java -jar target/meeting-slot-finder-1.0.0.jar --config examples/config.json
```

参数：

- `-c, --config`（必填）：JSON 配置文件路径。
- `-o, --output`（可选）：结果输出文件；缺省打印到 stdout。配置非法时退出码为 2，
  不会改动已有输出文件。

## 配置格式

```json
{
  "queryStart": "2026-09-28T00:00:00Z",
  "queryEnd": "2026-10-03T00:00:00Z",
  "durationMinutes": 60,
  "participants": [
    {
      "id": "alice",
      "timeZone": "Asia/Shanghai",
      "workDays": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"],
      "workStart": "13:00",
      "workEnd": "22:00",
      "busy": [
        { "start": "2026-09-29T12:00:00Z", "end": "2026-09-29T13:00:00Z" }
      ]
    }
  ]
}
```

- `queryStart` / `queryEnd`：查询窗口，带偏移的 ISO-8601 格式，左闭右开 `[queryStart, queryEnd)`。
- `durationMinutes`：会议时长，正整数分钟，按真实经过时间计算（而非当地钟面时间差）。
- `participants`：参会者列表，不能为空，`id` 不得重复。
  - `timeZone`：IANA 时区名（如 `Asia/Shanghai`、`America/New_York`）。
  - `workDays`：每周工作日，取 `MONDAY` ~ `SUNDAY`。
  - `workStart` / `workEnd`：同日工作起止时间，`HH:mm`，起点须早于终点。
  - `busy`：已占用时间段，带偏移的 ISO-8601 格式，可重叠或跨日，起点须早于终点。

校验规则：参会者不能为空、ID 唯一、时长为正整数、所有时间段起点早于终点、
时区与工作日必须合法；任一违反即报错退出（退出码 2），并保留已有输出文件。

## 计算规则

- 工作时间按每位参会者的**当地日期**展开为 UTC 区间，再与查询窗口求交。
- 占用区间先合并（重叠或相邻都合并），再从工作区间中扣除；区间均为左闭右开，
  首尾相接不算冲突。
- 夏令时：缺失的当地边界时间（春季拨快）按缺口时长向后平移；重复的边界时间
  （秋季拨回）取较早的偏移。会议时长始终按真实经过时间计算。
- 对所有人的空闲区间求交，得到共同空闲区间；仅输出长度不小于会议时长的最大区间。

## 输出格式

按时间升序的 JSON：

```json
{
  "durationMinutes": 60,
  "slots": [
    {
      "slotStart": "2026-10-01T12:00:00Z",
      "slotEnd": "2026-10-01T14:00:00Z",
      "meetingStart": "2026-10-01T12:00:00Z",
      "meetingEnd": "2026-10-01T13:00:00Z",
      "localTimes": [
        { "id": "alice", "timeZone": "Asia/Shanghai",
          "meetingStart": "2026-10-01T20:00:00+08:00", "meetingEnd": "2026-10-01T21:00:00+08:00" }
      ]
    }
  ]
}
```

- `slotStart` / `slotEnd`：最大共同空闲区间（UTC）。
- `meetingStart` / `meetingEnd`：该区间内最早的会议起止时间（UTC）。
- `localTimes`：各参会者带偏移的当地会议时间和时区，便于直接核对邀约。
- 无可用时段时 `slots` 为空列表。

## 示例

`examples/config.json` 包含上海、柏林、纽约三位参会者一周（2026-09-28 至 2026-10-03）
的查询。运行：

```bash
java -jar target/meeting-slot-finder-1.0.0.jar -c examples/config.json -o examples/result.json
```

输出 4 个可用时段（见 `examples/result.json`）：周一被 Bob 的占用完全覆盖；
周二 13:00-14:00Z、周三 12:00-13:00Z、周四/周五 12:00-14:00Z（会议取最早的 60 分钟）。

## 测试

```bash
mvn test
```

覆盖跨时区求交、重叠/跨日占用合并、首尾相接、夏令时缺口与重复边界、
查询窗口裁剪、各类非法配置校验等场景。
