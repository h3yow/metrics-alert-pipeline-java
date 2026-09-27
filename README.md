# Metrics Alert Pipeline（本地内存版）

一套可本地运行的指标采集与告警判定组件：用内存结构模拟时序存储与规则引擎，
在**并发批量写入**与**乱序/迟到到达**下保证最终状态一致、每条结论可判定、
告警状态迁移确定且可追溯。无外部中间件依赖，`mvn spring-boot:run` 即可启动。

## 1. 构建与验证

```bash
mvn -o test            # 离线运行全部 17 个单测/集成测试
mvn spring-boot:run    # 启动 HTTP 服务（默认随机端口，见 application.properties）
```

测试分三层：

| 测试 | 覆盖内容 |
| --- | --- |
| `InMemoryMetricStoreTest` | 乱序、迟到、重复、冲突值、时间戳异常、并发写入一致性、窗口边界、区间查询参数校验 |
| `DefaultAlertEngineTest` | 数据不足、滑动窗口滑出、连续越界抗抖、重复触发抑制、恢复→再触发、迟到样本不污染恢复 |
| `WebApiIntegrationTest` | 错误码在 HTTP 层彼此可区分、非法请求体不透出内部异常 |

测试日志会打印**输入样本**、**水位线/窗口判定依据**与**最终状态/迁移轨迹**，
例如 `input samples: (cpu@1001000=0.0), ...`、
`ALERT TRANSITION NORMAL -> ALERTING at ... reason: breach streak 2 reached required 2 ...`。

## 2. HTTP 接口

### 指标

- `POST /api/metrics/register?metric=cpu`：登记指标（重复登记幂等）。
- `POST /api/metrics/ingest`：批量接入。请求体：
  ```json
  { "samples": [ { "metric": "cpu", "timestamp": 1700000000000, "value": 12.3 } ] }
  ```
  响应按输入顺序逐条给出结论，三类计数互斥且等于样本总数：
  `accepted / ignored / rejected`，每条含 `status` 与可读 `reason`。
- `POST /api/metrics/aggregate?metric=cpu&windowStart=..&windowEnd=..`：
  对左闭右开窗口 `[windowStart, windowEnd)` 聚合（count/sum/min/max/average）。
- `POST /api/metrics/query?metric=cpu&from=..&to=..&bucketMillis=..`：
  按固定桶宽返回对齐的窗口聚合序列。

### 告警

- `POST /api/alerts/rules`：注册规则（指标须已登记）：
  `id, metric, windowSeconds(默认60), threshold(默认0), operator(默认GT),
  requiredBreaches(默认1), minSamples(默认1)`。
- `GET /api/alerts/rules`：列出全部规则（按 id 排序）。
- `POST /api/alerts/evaluate`：对所有规则判定一次。
- `POST /api/alerts/rules/{id}/evaluate`：对单条规则判定。
- `GET /api/alerts/rules/{id}/status`：只读当前状态快照（含完整迁移轨迹）。

## 3. 窗口与迟到语义

- 时间戳一律为 **epoch millis 事件时间**，与处理时间分离；所有窗口均为**左闭右开**。
- 每个指标维护 `maxObservedTs`（已接受样本的最大事件时间），
  **水位线 = maxObservedTs − 60s**（`MAX_LATENESS_MILLIS`）。
- 乱序样本只要时间戳 **≥ 水位线** 就正常计入；聚合基于事件时间切片，
  因此结果与到达顺序无关。
- 单条样本的判定顺序与结论（互斥、可区分）：
  1. `INVALID_SAMPLE`：样本对象为 null；
  2. `INVALID_TIMESTAMP`：时间戳 ≤ 0；
  3. `METRIC_UNREGISTERED`：指标名为空或未登记；
  4. `FUTURE_TIMESTAMP`：时间戳 > 处理时钟 now + 10s（`MAX_FUTURE_MILLIS`）；
  5. `DUPLICATE_IGNORED`：同指标同时间戳同值已存在 —— **幂等忽略**（计入 ignored）；
  6. `CONFLICTING_VALUE`：同时间戳已存在但值不同 —— 拒绝，不覆盖；
  7. `LATE_REJECTED`：时间戳 < 水位线 —— 拒绝；
  8. 否则 `ACCEPTED`，计入并推进 `maxObservedTs`。
  > 重复检查优先于迟到检查：迟到的重发仍被当作幂等忽略，不会变成“迟到拒绝”。
- 读取侧参数校验：窗口要求 `0 < start < end` 且跨度 ≤ 7 天，否则 `INVALID_WINDOW`；
  区间查询要求 `0 < from < to`、跨度整除桶宽、桶数 ≤ 1000，
  跨度/桶数越界返回 `INVALID_QUERY_RANGE`，桶宽问题返回 `INVALID_WINDOW`。

## 4. 告警状态规则

判定依据：`[now − windowSeconds, now)` 内样本平均值与阈值按 `GT/GE/LT/LE` 比较；
窗口内样本数 `< minSamples` 时**不出结论**。

```
INSUFFICIENT_DATA ──数据足够、未越界──────────────▶ NORMAL
INSUFFICIENT_DATA ──数据足够、且已达连续越界要求──▶ ALERTING
NORMAL ──连续 requiredBreaches 次越界────────────▶ ALERTING   （触发，记录迁移）
RECOVERED ──连续 requiredBreaches 次越界─────────▶ ALERTING   （允许再次触发）
ALERTING ──任一次不越界──────────────────────────▶ RECOVERED  （立即恢复）
RECOVERED ──随后一次不越界───────────────────────▶ NORMAL     （恢复确认收敛）
```

- **抗抖动**：`requiredBreaches` 要求连续越界次数达标才触发；中途一次正常即清零。
- **重复抑制**：`ALERTING` 期间继续越界只累加抑制计数，不产生新迁移。
- **数据不足保护**：`NORMAL/RECOVERED` 下样本不足回到 `INSUFFICIENT_DATA`；
  `ALERTING` 下遇到数据缺口保持告警，避免用数据缺口伪造恢复。
- 每次状态变化写入 `TransitionEvent`（时间、from/to、原因、判定均值、窗口范围、样本数），
  完整保留在规则状态快照中，可追溯。

## 5. 并发与幂等约束

- 指标定义放在 `ConcurrentHashMap`；每个指标一把独立锁（`MetricData` 监视器），
  **同指标写入串行、跨指标并行**。去重、迟到判定与 `maxObservedTs` 推进在同一临界区，
  杜绝水位线/去重索引竞态。
- 规则判定同样按规则加锁，保证状态迁移串行有序。
- 幂等键为 `(metric, timestamp)`：同值重复 → 忽略；不同值 → `CONFLICTING_VALUE` 拒绝，
  任何已计入样本都不会被后写覆盖。
- 失败只通过稳定错误码暴露，不回传堆栈：

  | ErrorCode | HTTP | 含义 |
  | --- | --- | --- |
  | `METRIC_NOT_FOUND` | 404 | 读取/注册引用未登记指标 |
  | `RULE_NOT_FOUND` | 404 | 规则不存在 |
  | `INVALID_WINDOW` | 400 | 窗口/桶宽非法 |
  | `INVALID_QUERY_RANGE` | 400 | 区间越界、桶数过多等 |
  | `INVALID_RULE` / `INVALID_SAMPLE` | 400 | 规则/样本定义非法 |
  | `INTERNAL_ERROR` | 500 | 兜底，细节仅写服务端日志 |
