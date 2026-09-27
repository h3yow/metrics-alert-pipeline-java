# Metrics Alert Pipeline（本地内存版）

基于 Spring Boot 的本地可运行指标采集与告警判定组件。时序数据与规则引擎全部用内存结构模拟，
保证**并发写入与乱序到达时最终状态一致且可解释**：每条被拒绝/忽略的数据都有明确、可区分的原因，
每次告警状态迁移都留有可追溯记录。

## 1. 构建与运行

```bash
mvn spring-boot:run          # 启动（application.properties 中 server.port=0 表示随机端口）
mvn test                     # 运行全部测试，日志会打印输入样本、窗口判定依据与最终状态
```

也可以指定固定端口：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080
```

## 2. API

### 2.1 批量接入样本

`POST /api/metrics/ingest`

```json
[
  {"metric": "cpu", "timestamp": 1700000000000, "value": 0.92},
  {"metric": "cpu", "timestamp": 1700000001000, "value": 0.95}
]
```

响应逐条给出判定，任何一条都不会被静默丢弃：

```json
{
  "acceptedCount": 1,
  "rejectedCount": 1,
  "results": [
    {"metric": "cpu", "timestamp": 1700000000000, "status": "ACCEPTED", "reason": "..."},
    {"metric": "cpu", "timestamp": 1700000000000, "status": "DUPLICATE", "reason": "sample with timestamp ... already exists ..."}
  ]
}
```

接入判定 `status` 五种取值，彼此可区分：

| status | 含义 | reason 中的判定依据 |
|---|---|---|
| `ACCEPTED` | 计入事件时间序列 | — |
| `DUPLICATE` | 同一指标同一时间戳已存在（重复上报） | 冲突时间戳 |
| `TOO_LATE` | 迟到超过允许窗口 | 当前 watermark、maxTimestamp、allowedLateness |
| `FUTURE_TIMESTAMP` | 时间戳领先墙钟超过容差（时间戳异常） | now 与 maxFutureMillis |
| `INVALID_TIMESTAMP` | 时间戳非正数或指标名为空（时间戳异常） | 非法字段值 |

校验顺序固定（非法字段 → 未来时间 → 重复 → 迟到），因此同一条样本的判定结果可复现。

### 2.2 查询滚动窗口聚合

`GET /api/metrics/{metric}/windows?from={epochMillis}&to={epochMillis}&windowMillis={ms}`

返回与 `[from,to)` 相交的全部**滚动窗口**（含空窗口，`count=0`），每个窗口含
`windowStart/windowEnd/count/min/max/sum/avg`。

窗口按 epoch 对齐：`windowStart = floor(ts / windowMillis) * windowMillis`，
区间为左闭右开 `[windowStart, windowEnd)`。

失败时 HTTP 状态与 `errorCode` 彼此可区分：

| HTTP | errorCode | 触发条件 |
|---|---|---|
| 400 | `UNKNOWN_METRIC` | 从未接入过该指标 |
| 400 | `ILLEGAL_WINDOW` | `windowMillis<=0`，或窗口数量超过 10000 的扫描上限 |
| 416 | `OUT_OF_RANGE` | 缺少/非法 `from/to`、`to<=from`、时间戳非正 |
| 400 | `EMPTY_BATCH` | 接入空批次 |
| 400 | `INVALID_PAYLOAD` | 请求体缺失或 JSON 畸形 |
| 400 | `INVALID_RULE` | 规则字段非法或 ruleId 重复 |
| 400 | `UNKNOWN_RULE` | 查询不存在的规则 |
| 500 | `INTERNAL_ERROR` | 兜底错误，内部异常堆栈不会透出给调用方 |

### 2.3 告警规则与状态

注册：`POST /api/alerts/rules`

```json
{
  "ruleId": "cpu-high",
  "metric": "cpu",
  "operator": "GT",
  "threshold": 0.9,
  "windowMillis": 60000,
  "minSamples": 5,
  "breachThreshold": 3,
  "recoverThreshold": 2
}
```

- `operator`：`GT / GTE / LT / LTE`，对滑动窗口内的 `avg` 做比较；
- `windowMillis`：告警判定用**滑动窗口**，在事件时间 `t` 处取左开右闭区间 `(t-windowMillis, t]`；
- `minSamples`：窗口内样本数下限；
- `breachThreshold` / `recoverThreshold`：连续越限/连续恢复的去抖次数。

查询：`GET /api/alerts/rules/{ruleId}`、`GET /api/alerts/rules?metric=cpu`、
`GET /api/alerts/rules`。状态视图包含当前状态、两个连续计数、最近一次评估的事件时间、
窗口均值、样本数，以及完整的 `transitions` 迁移历史（事件时间、from→to、判定依据）。

## 3. 窗口与迟到语义

- 所有聚合均基于**事件时间**（样本自带 timestamp），与写入顺序、写入时刻无关，
  因此乱序到达后重算结果完全一致。
- 每个指标维护 `maxTimestamp`（已见最大事件时间）与
  **watermark = maxTimestamp − allowedLatenessMillis**（默认 5000ms）。
- 新样本 `ts`：
  - `ts` 已存在 → `DUPLICATE`；
  - `ts < watermark`（严格小于，即 `ts < maxTimestamp − allowedLateness`）→ `TOO_LATE`，不计入；
  - 恰好落在 `watermark` 上（`ts == maxTimestamp − allowedLateness`）属于允许迟到边界，**会计入**；
  - 其余 → `ACCEPTED`，并在 `ts > maxTimestamp` 时推进水位线。
- `ts > now + maxFutureMillis`（默认 60s）→ `FUTURE_TIMESTAMP`，防止异常时间戳污染水位线。
- 被接受的样本全量保留在内存中，历史窗口查询不会因为水位线推进而丢点。

## 4. 告警状态规则

状态机共三态：`INSUFFICIENT_DATA`（初始）、`OK`、`FIRING`。

每接受一条样本，就在该事件时间点对该指标下的每条规则评估一次滑动窗口：

1. **数据不足**：窗口样本数 `< minSamples` → 不产生告警，越限/恢复计数均清零；
   若之前为 `OK/FIRING`，迁移到 `INSUFFICIENT_DATA` 并留痕（表示数据缺口）。
2. **可评估且越限**：`breachStreak++`、`recoverStreak=0`：
   - 当前非 `FIRING` 且 `breachStreak >= breachThreshold` → 迁移到 `FIRING`；
   - 已经是 `FIRING` → **重复触发抑制**，状态与迁移记录都不变。
3. **可评估且未越限**：`recoverStreak++`、`breachStreak=0`：
   - 仅当当前为 `FIRING` 且 `recoverStreak >= recoverThreshold` 才迁移回 `OK`
     （"刚恢复"或短时抖动不会误报，恢复同样需要去抖）。
4. 从 `INSUFFICIENT_DATA` 遇到第一个可评估窗口时，依据是否越限与连续计数进入 `OK` 并留痕。
5. **再触发**：回到 `OK` 后重新攒满 `breachThreshold` 次连续越限会再次进入 `FIRING` 并新增一条迁移记录。

迁移链示例：`INSUFFICIENT_DATA -> OK -> FIRING -> OK -> FIRING`，每条记录包含事件时间、
窗口均值、样本数与文字判定原因，保证确定且可追溯。

**乱序幂等**：每接受一条样本，都在该指标**当前最大事件时间**（水位线视角的最新点）处评估规则——
即使这条样本本身是迟到的，评估点也始终指向最新事件时间，因此最终状态只取决于已接受数据集合，与到达顺序无关。
状态机只接受不小于上次的评估时间：更早事件时间直接忽略；同一评估时间被乱序重放时，
仅用最新窗口结果重判一次状态（迟到样本可能让窗口数据变充分），不会重复累加连续计数。
规则注册晚于数据到达时，会以该指标最新事件时间做一次引导评估。

## 5. 并发与幂等约束

- 存储按指标名分桶（`ConcurrentHashMap`），每个指标一把监视器锁；
  不同指标的写入完全并行，同一指标的去重、水位线推进与数据插入在锁内一次完成。
- 因此唯一时间戳在任意并发度下**恰好被接受一次**，重复提交稳定返回 `DUPLICATE`；
  最终聚合结果只取决于时间戳集合，与线程调度、批次切分无关。
- 告警规则运行时自带锁，事件时间单调推进，乱序评估幂等，最终状态确定。
- 幂等键：`(metric, timestamp)`。接入与判定均为内存操作，进程重启后状态清空。
- 单批次上限 `pipeline.max-batch-size`（默认 1000），单次查询窗口数上限 10000，防止内存扫描失控。

可在 `application.properties` 调整：

```properties
pipeline.allowed-lateness-millis=5000
pipeline.max-future-millis=60000
pipeline.max-batch-size=1000
pipeline.transition-history-size=100
```

## 6. 验证方法

```bash
mvn test
```

| 测试类 | 覆盖点 |
|---|---|
| `IngestionRejectTest` | 重复/迟到/未来/非法时间戳的区分、水位线判定依据、拒绝样本不计入 |
| `AggregationQueryTest` | 乱序写入与顺序写入聚合一致；允许迟到内计入、超窗拒绝；UNKNOWN_METRIC/ILLEGAL_WINDOW/OUT_OF_RANGE 可区分 |
| `AlertStateMachineTest` | 数据不足不误报、抖动抑制、FIRING 重复抑制、恢复去抖、恢复后再触发、迁移链可追溯、乱序评估幂等 |
| `ConcurrencyConsistencyTest` | 8 线程 3 轮乱序并发：ACCEPTED/DUPLICATE 计数稳定、聚合与时间戳集合一致、最终 FIRING 确定 |
| `HttpApiIntegrationTest` | HTTP 边界端到端：各 errorCode 与状态码可区分，内部异常不透出 |

测试日志（INFO）会打印输入样本、窗口对齐与水位线判定依据、逐条接入原因、
告警完整迁移链与最终状态，便于人工核对。

手工快速验证示例：

```bash
curl -s -XPOST localhost:8080/api/metrics/ingest -H 'Content-Type: application/json' \
  -d '[{"metric":"cpu","timestamp":1700000000000,"value":0.99}]'
curl -s 'localhost:8080/api/metrics/cpu/windows?from=1700000000000&to=1700000060000&windowMillis=60000'
```
