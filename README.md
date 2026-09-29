# 本地可控的链上数据接入与索引服务（blockchain-rpc-indexer-java）

一套**完全本地、可模拟**的链上数据接入与增量索引服务。所有"节点"均为内存中的
`MockChainNode`，可编排：超时、业务错误、落后高度、任意分叉链。验证过程
**不依赖任何真实链上节点或外部服务**（无数据库、无网络）。

## 1. 能力总览

| 需求 | 实现 |
| --- | --- |
| 多节点接入、按优先级分发与故障转移 | `node.NodeRegistry` + `rpc.RpcDispatcher` |
| 超时 / 业务错误 / 数据落后 三类可区分结论 | `NodeTimeoutException` / `NodeBusinessException` / `NodeStaleException`，终态 `FailureReason.{ALL_TIMEOUT,ALL_BUSINESS_ERROR,ALL_STALE,MIXED,NO_ENABLED_NODE}` |
| 不为凑结果静默切换到可疑节点 | 落后节点标记 `STALE` 并跳过；全部可疑时返回明确失败，不采信任何数据 |
| 区块/交易增量索引与位点管理 | `index.IndexStore`（规范链 + 交易哈希幂等集合 + 余额） |
| 并发/重复投递不重复计数、不重复入账 | 写锁串行化 + 交易哈希集合去重 + 位点守卫 `CheckpointGuard` |
| 位点回退必须被明确拒绝 | 只接受 `height == checkpoint+1`；回退/跳号返回 `REJECTED_REGRESSION` |
| 重组回滚与重建 | `reorg.ReorgService`：父哈希断裂 → 回溯共同祖先 → 回滚撤销 → 沿新链重放到节点链顶 |
| 重组期间禁止跨节点混链 | `RpcDispatcher.fetchBlock(number, pinnedNodeId)` 固定来源节点 |
| 查询顺序与最终一致 | 规范链按高度 `TreeMap` 升序输出；重组后余额清空重放，杜绝新旧混算 |
| 只读查询 API | `GET /api/indexer/{status,transactions,balances}` |

## 2. 模拟节点接入协议（JSON-RPC 语义）

`ChainNodeClient` 模拟两个 JSON-RPC 方法：

- `eth_blockNumber()` → 节点对外宣称的链顶高度。
  - 超时抛 `NodeTimeoutException`；业务错误抛 `NodeBusinessException`；
  - 模拟节点还可设置 `headLag`：宣称链顶 = 本地链顶 − lag。
- `eth_getBlockByNumber(n)` → 区块（`number/hash/parentHash/transactions`）。
  - 链顶够不到 `n` 时抛业务错误（区块不存在）。

分发规则（`RpcDispatcher`）：

1. **探链顶**：向全部启用节点发 `eth_blockNumber`，取最高值作为网络链顶；
   落后网络链顶超过 `indexer.max-stale-lag` 的节点被判为"明显落后"，不参与服务。
2. **取块**：按 `priority`（小者优先）依次尝试；某节点超时/业务错误才转移到下一节点；
   其链顶 `< 请求高度` 时标记 `STALE` 并跳过。
3. **终态结论**：所有节点均不可用时，`DispatchResult.failureReason` 给出
   `ALL_TIMEOUT` / `ALL_BUSINESS_ERROR` / `ALL_STALE` / `MIXED`（混合故障）/
   `NO_ENABLED_NODE`，并附带每个节点的 `Attempt(nodeId, outcome, detail)` 明细。
4. **不静默采信可疑节点**：只有探活成功且不落后的节点才能返回数据；否则宁可整体失败。

`MockChainNode` 编排方法：`setTimeout / setBusinessError / setHeadLag /
setLatencyMs / setEnabled / setChain / appendBlock`。

## 3. 位点（checkpoint）规则

- `checkpoint` = 规范链最高高度，初始 `-1`；只允许严格 `+1` 推进。
- 同一区块并发/重复投递：存储层写锁 + 交易哈希集合保证**至多入账一次**；
  重复的交易被记录为"去重交易"，计数与余额不变。
- 投递 `height <= checkpoint`（位点回退）或 `height > checkpoint+1`（跳号）：
  `BlockIngestor` 直接返回 `REJECTED_REGRESSION`，**明确拒绝，绝不从中间继续**；
  `CheckpointGuard.requireNext` 以异常表达同一约束。
- 节点故障轮次位点保持不动，下一轮从 `checkpoint+1` 重新补齐。

## 4. 链重组（reorg）规则

当待入账区块的 `parentHash` 与规范链顶不一致（或同高度出现不同哈希）时：

1. `ReorgService` 沿 `parentHash` 向**同一来源节点**（pinned）回溯，直到
   `canonical[h-1].hash == block.parentHash`，得到共同祖先 `forkPoint`；
2. `rollbackAbove(forkPoint)` 自顶向下撤销规范块及其交易哈希，随后清空余额并
   按保留链顺序重放（保证撤销干净，不残留旧链影响）；
3. 新链区块自低向高重新入账，并继续向该节点拉取直到其最新链顶，完成收敛；
4. 期间任何取块失败都给出可解释结论（等待下一轮），不会跨节点拼接出混合链。

查询接口只读规范链，因此在一次重组事务完成前后，结果都指向同一条链，
交易按高度、块内顺序升序返回，不存在新旧数据混算。

## 5. 配置项（`src/main/resources/application.properties`）

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `indexer.enabled` | `false` | 是否启动后台轮询线程；关闭时可手工驱动 `BlockPoller.pollOnce()` / `BlockIngestor.ingest(...)` |
| `indexer.polling-interval-ms` | `500` | 轮询间隔（毫秒，下限 50） |
| `indexer.max-stale-lag` | `2` | 节点落后网络链顶多少块即判为"明显落后"，其数据不采信 |
| `indexer.pinned-node-id` | 空 | 固定服务节点（重组期间由服务内部按来源自动 pin，亦可手工指定） |
| `indexer.rpc-timeout-ms` | `1000` | RPC 超时阈值（模拟节点可用 `latencyMs` 配合） |

## 6. HTTP 查询接口

- `GET /api/indexer/status` → `checkpoint / tipHash / txCount / 各节点链顶`
- `GET /api/indexer/transactions?from=0&to=-1`（`to=-1` 表示到链顶）→ 升序交易明细
- `GET /api/indexer/balances` → 账户余额（交易推导）

## 7. 测试

纯内存单测（`src/test/.../tests`），覆盖三类必测场景，且通过 `LogCapture`
把**节点响应、位点变化、重组流水**回显到单测日志（见 `mvn test` 输出中的
`========== REORG/DUP/FAILOVER ... ==========` 分段）：

- `ReorgIndexerTest`：高度 2 处分叉，验证回滚到共同祖先、旧交易撤销、新链重建、
  余额重放与查询升序一致；
- `DuplicateDeliveryTest`：8 线程并发投递同一块恰好一次入账、旧位点重投被拒、
  `CheckpointGuard` 拒绝回退与跳号；
- `NodeFailoverTest`：超时故障转移、全超时/全业务错误结论可区分、落后节点
  `STALE` 跳过、全落后 `ALL_STALE`、业务错误+落后 `MIXED`、故障时位点不前进；
- `QueryApiTest`：Spring Boot + MockMvc 查询接口冒烟。

```bash
mvn test
```

## 8. 主要包结构

```
model      Block / Transaction（模拟链数据）
node       ChainNodeClient、MockChainNode、NodeRegistry 与三类节点异常
rpc        RpcDispatcher、DispatchResult、FailureReason（分发/故障转移/可解释结论）
index      IndexStore（规范链/位点/幂等/余额/回滚）、BlockCache
reorg      ReorgService（断裂检测、共同祖先、回滚重建收敛）
indexer    BlockIngestor（投递/幂等/拒绝回退）、BlockPoller（轮询）、CheckpointGuard
api        IndexerController（只读查询）
config     IndexerProperties / IndexerConfig / IndexerLifecycle
```
