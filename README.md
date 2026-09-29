# 本地可控的链上数据接入与索引服务（blockchain-rpc-indexer）

一套**完全本地、可模拟、不依赖任何真实链上节点或外部服务**的链上数据接入与索引服务。
用内存模拟节点驱动，重点解决三类问题下“查询结果始终一致且可解释”：

1. 多节点来源的请求分发、故障转移与**可区分失败结论**（超时 / 业务错误 / 节点落后 / 节点分歧）；
2. 区块、交易的增量索引与位点管理：重复/并发投递不重复计数、不重复入账，位点回退被明确拒绝；
3. 链重组（reorg）时的回滚与重建：父哈希断裂检测 → 撤销受影响规范化数据 → 沿新链重建并收敛。

技术栈：Java 21、Spring Boot 3.4、仅 spring-boot-starter-web（存储全部内存实现，无数据库/中间件）。

---

## 1. 快速开始

```bash
mvn spring-boot:run          # 启动（server.port=0，随机端口；也可在 application.properties 固定）
mvn test                    # 全部 19 个单测，含重组/重复投递/节点故障场景流水日志
```

启动后默认装配：一条内存模拟链（高度 0..7）+ 两个视图一致的模拟节点
`node-primary`、`node-replica`，无需任何外部进程。

HTTP 接口：

| 方法 | 路径 | 说明 |
| ---- | ---- | ---- |
| GET  | `/api/status` | 位点、规范化链尖、区块/交易数、各节点链头探测结果（含 usable 标记） |
| GET  | `/api/blocks` | 规范化区块，按高度升序，重组后保证父哈希连续、无新旧混算 |
| GET  | `/api/txs` | 规范化交易，按高度/哈希升序 |
| GET  | `/api/accounts` | 全部账户余额（规范化数据的投影） |
| GET  | `/api/reorgs` | 重组流水：分叉点、detached/attached 哈希、位点变化、是否中止 |
| POST | `/api/index/step?batches=1` | 拉取式推进索引；成功返回 200+节点尝试流水，故障返回带 `failureKind` 的 502 |
| POST | `/api/position/reset?height=N` | 演示外部回退位点（**必然被拒绝**，返回 409 `POSITION_ROLLBACK_REJECTED`） |

示例：

```bash
curl -X POST 'http://localhost:<port>/api/index/step?batches=8'
curl http://localhost:<port>/api/status
curl http://localhost:<port>/api/blocks
curl http://localhost:<port>/api/reorgs
```

---

## 2. 节点接入协议

### 2.1 抽象接口

每个节点来源实现统一接口 `NodeClient`：

```java
String nodeId();                       // 节点标识，用于流水/定位
long   chainHeadHeight() throws NodeException;   // 链头高度
Block  blockByHeight(long height) throws NodeException; // 区块，不存在返回 null
```

节点异常用类型区分（**失败原因可区分**）：

- `NodeTimeoutException`（超时，`timeout()==true`）；
- `NodeBusinessException(code, message)`（节点可达但返回业务错误，带错误码）。

### 2.2 分发与故障转移（`NodeDispatcher`）

两阶段流程：

1. **链头探测**：按注册顺序（即优先级）探测每个节点链头；
2. **拉块 + 哈希比对**：只对“新鲜节点”发拉块请求，并对同高度返回的哈希做交叉比对。

判定规则（**绝不静默切换到可疑节点凑结果**）：

- **STALE（落后）判定**：节点链头落后“最领先节点”超过 `indexer.max-stale-height`，
  或常规模式下落后于本地位点 → 标记 `STALE`、`usable=false`，**其数据一律不采信**；
- 超时节点标记 `TIMEOUT`、业务错误节点标记 `BUSINESS_ERROR(code)`，可故障转移到后续节点；
- 多个新鲜节点同高度返回不同哈希 → `NODE_DIVERGENT`，**不选边、不静默采用**；
- 常规模式下，节点哈希与本地规范化期望哈希冲突 → 同样按 `NODE_DIVERGENT` 拒绝；
- 重组回溯走独立方法 `fetchBlockForReorg`：不用本地位点判 STALE，且只从新鲜节点拉取，
  多源哈希一致才采信。

返回值是密封类型 `FetchResult`：

- 成功：`BlockFound(block, servedBy, attempts)`；
- 失败：`Failed(FailureKind, reason, attempts)`，`attempts` 是每一次节点尝试流水
  （nodeId / stage=HEAD|BLOCK / result / usable / detail）。

### 2.3 失败结论分类（`FailureKind`，可区分）

| FailureKind | 含义 | HTTP |
| ---- | ---- | ---- |
| `NODE_TIMEOUT` | 全部节点超时（或仅探测到超时类故障） | 502 |
| `NODE_BUSINESS_ERROR` | 全部节点返回业务错误 | 502 |
| `NODE_STALE` | 可达节点全部明显落后 | 502 |
| `ALL_NODES_UNAVAILABLE` | 超时/错误与落后并存，没有任何可采信来源 | 502 |
| `NODE_DIVERGENT` | 新鲜节点同高度哈希分歧 / 与本地期望冲突 | 502 |
| `BLOCK_NOT_FOUND` | 新鲜节点都没有该高度块（链尚未推进，**正常停顿**，不是错误） | 200 |
| `POSITION_ROLLBACK_REJECTED` | 外部位点回退被明确拒绝 | 409 |
| `REORG_UNRESOLVED` | 重组无法回溯到可采信共同祖先，原链保持不动 | 502 |

> 说明：存在 STALE 节点时结论会优先暴露 STALE 事实；即便同时有节点超时，也归并为
> `ALL_NODES_UNAVAILABLE`，避免“恰好在落后节点手里有数据”时被伪装成单纯超时后静默采用。

### 2.4 模拟节点（`simulation` 包，零外部依赖）

- `SimulatedChain`：确定性生成区块/交易（哈希 `H<height>` / `altH<height>`，每块 2 笔转账），
  支持造分叉（`makeAltBlock/extendAlt`）与链重组（`promoteAltFrom`）；
- `SimulatedNodeBehavior`：运行时故障注入开关——`timeout`、`businessError`、
  `headOverride`（伪造落后链头）、`useAltChain`（分叉视图），可随时恢复；
- `SimulatedNode`：按行为产出响应并打印 `[node-response]` 日志，延迟达到超时阈值即抛超时。

---

## 3. 增量索引、位点规则与幂等

### 3.1 位点（`PositionManager`）

- 位点 = **已收敛到规范化链的最高连续高度**；空库初始化为 `indexer.start-height - 1`；
- `advanceTo(height, hash)`：只允许前进，且要求该高度区块已在规范化库中、哈希一致；
- `requestReset(height)`：**任何外部回退请求都被明确拒绝**，抛
  `POSITION_ROLLBACK_REJECTED`，绝不允许“从旧位点中间继续”造成新旧数据混算；
- 唯一合法回退通道是包内可见的 `revertByReorg(height, reason)`，
  只供重组流程调用，强制带原因流水，且回滚后立即重建到新链位点。

### 3.2 入账（`IngestionService`，投递式接入）

全局锁内串行处理单个区块：

1. 按区块哈希去重：重复投递返回 `false`，位点不动、不重复计数；
2. 交易按 `txHash` 幂等入账：`from -amount`、`to +amount`，重复 tx 跳过；
3. 只接受“位点+1”的连续高度，越位/乱序报错，不跳块；
4. 父哈希与本地规范化块不一致（包括已收敛高度出现不同哈希的分叉块）→ 触发重组；
5. 并发投递由同一把锁 + 哈希/交易唯一约束保证**恰好入账一次**（有 16 线程并发测试）。

拉取式主循环 `IndexingService.runOnce(n)`：从 `位点+1` 逐块拉取入账；
`BLOCK_NOT_FOUND` 视为追上链头正常停止，其它失败按可区分结论立刻停止。

---

## 4. 重组规则（`ReorgService`）

当入账发现父哈希断裂时：

1. **回溯**：从断裂块起，沿 `parentHash` 逐高度向历史查询（只信新鲜节点、多源哈希一致），
   直到找到与本地规范化哈希一致的**共同祖先 forkHeight**；
2. **回滚**：`removeBlocksAbove(forkHeight)` 逐笔**反向冲正余额**、删除区块/交易索引，
   位点经重组专用通道回退到 forkHeight，打印 detached 流水（高→低）；
3. **重建**：将新链片段从低到高用与正常入账完全一致的规则重新入账；
4. **收敛**：继续沿新链拉到最新链尖（新链更短时多余旧块同样被撤销），
   绝不暴露“回滚了但没建完”的中间状态；
5. 任一环节拿不到可采信数据 → 抛 `REORG_UNRESOLVED`，
   在发生任何撤销**之前**中止时原链原样保留；若已重建但无法追到链尖，同样以失败结论暴露，交由上层重试。

查询（区块/交易/余额）永远只反映**已收敛位点**的数据，区块按高度升序且父哈希连续，
余额 = 规范化链上交易的投影（所有账户余额之和恒为 0，测试据此校验无重复/漏入账）。

流水保存在内存并可经 `GET /api/reorgs` 查看，关键字段：
`forkHeight / detachedHashes / attachedHashes / oldTip / newTip / unresolved / reason`。

---

## 5. 配置项（`application.properties`）

```properties
server.port=0

# 节点链头落后“最领先节点”超过该高度即判定 STALE（数据不采信）
indexer.max-stale-height=2
# 单次节点请求超时阈值（毫秒）；模拟节点延迟 >= 该值即产生 NodeTimeoutException
indexer.request-timeout-ms=500
# 冷启动起始位点：空库时从该高度开始索引（位点初始化为 startHeight-1）
indexer.start-height=0

# 单测日志级别：节点响应/位点/重组流水默认 INFO，拒绝与中止为 WARN/ERROR
logging.level.com.github.lisama569.blockchainrpcindexerjava=INFO
```

接入真实节点时，实现一个 `NodeClient`（如 JSON-RPC/HTTP）并以你自己的
`@Bean List<NodeClient>` 覆盖默认模拟装配即可；分发、位点、重组规则无需改动。

---

## 6. 测试覆盖与可观察性

测试为纯单测（手工装配，不依赖 Spring 外部环境；另含一个 Spring Boot HTTP 冒烟测试），
日志中可直接看到 `[node-response]` 节点响应、`[position]` 位点变化、`[reorg]/[reorg-flow]` 重组流水：

| 测试类 | 覆盖点 |
| ---- | ---- |
| `NodeFailoverTest`（8） | 主超时→备接管、主业务错误→其余接管、全超时、全业务错误、落后节点绝不采信、全落后、新鲜节点分歧、链未推进 NOT_FOUND |
| `ReorgIndexTest`（3） | 等长新链回滚重建并收敛、更短新链撤销多余块、不可信回溯时 `REORG_UNRESOLVED` 且原链不动 |
| `DuplicateDeliveryTest`（3） | 同块重复投递幂等、16 线程并发投递恰好入账一次、追上链头后重复拉取不重复计数 |
| `PositionGuardTest`（3） | 外部回退被拒且不影响后续前进、已收敛高度的不可信块无法替换原链、同位点 reset 为 no-op |
| `ApiSmokeTest`（1） | 推进/查询/状态接口正常，外部回退返回 409 与可区分 `failureKind` |

运行并查看流水：

```bash
mvn test -Dtest=ReorgIndexTest          # 观察 detach/attach、位点回退与 CONVERGED 流水
mvn test -Dtest=NodeFailoverTest       # 观察 TIMEOUT/BUSINESS_ERROR/STALE/DIVERGENT 尝试流水
```

## 7. 包结构

```
domain/      Block, Transaction, AccountBalance, FailureKind
node/         NodeClient, NodeException 层级, NodeAttempt, FetchResult, NodeDispatcher
simulation/   SimulatedChain, SimulatedNode, SimulatedNodeBehavior, SimulationConfig
indexer/      IndexStore, PositionManager, IngestionService, ReorgService,
              IndexingService, IndexingException, ReorgEvent
api/           QueryController, ApiExceptionHandler
config/        IndexerProperties
```
