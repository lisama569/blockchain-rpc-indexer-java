package com.github.lisama569.blockchainrpcindexerjava.rpc;

import com.github.lisama569.blockchainrpcindexerjava.config.IndexerProperties;
import com.github.lisama569.blockchainrpcindexerjava.model.Block;
import com.github.lisama569.blockchainrpcindexerjava.node.ChainNodeClient;
import com.github.lisama569.blockchainrpcindexerjava.node.NodeBusinessException;
import com.github.lisama569.blockchainrpcindexerjava.node.NodeRegistry;
import com.github.lisama569.blockchainrpcindexerjava.node.NodeStaleException;
import com.github.lisama569.blockchainrpcindexerjava.node.NodeTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 多节点请求分发与故障转移。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>按优先级依次尝试，超时 / 业务错误才允许转移到下一个节点；</li>
 *   <li>对"明显落后"的节点（链顶够不到请求高度，或落后网络链顶超过 max-stale-lag）
 *       直接给出 STALE 结论并跳过，绝不静默采信其数据凑结果；</li>
 *   <li>重组期间可固定节点（pinnedNodeId），禁止跨节点混链；</li>
 *   <li>全部节点失败时，终态原因区分为 ALL_TIMEOUT / ALL_BUSINESS_ERROR / ALL_STALE / MIXED。</li>
 * </ul>
 */
@Component
public class RpcDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RpcDispatcher.class);

    private final NodeRegistry registry;
    private final IndexerProperties props;

    public RpcDispatcher(NodeRegistry registry, IndexerProperties props) {
        this.registry = registry;
        this.props = props;
    }

    /** 探测网络链顶：先全体探活取最高块，再剔除落后超过阈值的节点。 */
    public DispatchResult<Long> fetchHead() {
        List<ChainNodeClient> nodes = registry.nodes();
        if (nodes.isEmpty()) {
            log.warn("RPC fetchHead 失败: 没有已启用节点");
            return DispatchResult.failed(FailureReason.NO_ENABLED_NODE, "no enabled node", List.of());
        }

        List<DispatchResult.Attempt> attempts = new ArrayList<>();
        record Head(ChainNodeClient node, long value) {
        }
        List<Head> oks = new ArrayList<>();

        long networkHead = Long.MIN_VALUE;
        for (ChainNodeClient node : nodes) {
            try {
                long h = node.getBlockNumber();
                attempts.add(new DispatchResult.Attempt(node.id(), "OK", "head=" + h));
                oks.add(new Head(node, h));
                networkHead = Math.max(networkHead, h);
            } catch (NodeTimeoutException e) {
                attempts.add(new DispatchResult.Attempt(node.id(), "TIMEOUT", e.getMessage()));
            } catch (NodeBusinessException e) {
                attempts.add(new DispatchResult.Attempt(node.id(), "BUSINESS_ERROR", e.getMessage()));
            }
        }

        if (oks.isEmpty()) {
            DispatchResult<Long> r = classifyFailures(attempts, "eth_blockNumber");
            log.warn("RPC fetchHead 失败: {}，尝试明细={}", r.message(), attempts);
            return r;
        }

        // 新鲜节点：落后网络链顶不超过 maxStaleLag
        final long referenceHead = networkHead;
        List<Head> fresh = oks.stream()
                .filter(h -> referenceHead - h.value() <= props.getMaxStaleLag())
                .toList();
        if (fresh.isEmpty()) {
            log.warn("RPC fetchHead 失败: 所有成功响应均落后，networkHead={}, 尝试={}", networkHead, attempts);
            return DispatchResult.failed(FailureReason.ALL_STALE,
                    "all nodes stale, networkHead=" + networkHead, attempts);
        }

        // 在新鲜节点中选优先级最高且高度最高者服务
        Head best = fresh.get(0);
        for (Head h : fresh) {
            if (h.value() > best.value() ||
                    (h.value() == best.value() && h.node().priority() < best.node().priority())) {
                best = h;
            }
        }
        log.info("RPC fetchHead 成功: networkHead={}, servedBy={}, 尝试={}", networkHead, best.node().id(), attempts);
        return DispatchResult.ok(networkHead, best.node().id(), attempts);
    }

    /**
     * 按高度取块。pinnedNodeId 非空时只允许该节点服务（重组期间防混链）；
     * 否则按优先级故障转移，链顶够不到请求高度的节点按 STALE 跳过。
     */
    public DispatchResult<Block> fetchBlock(long number, String pinnedNodeId) {
        List<ChainNodeClient> candidates;
        if (pinnedNodeId != null && !pinnedNodeId.isBlank()) {
            ChainNodeClient pinned = registry.byId(pinnedNodeId).orElse(null);
            if (pinned == null) {
                log.warn("RPC fetchBlock({}) 失败: pinned 节点 {} 不存在/未启用", number, pinnedNodeId);
                return DispatchResult.failed(FailureReason.NO_ENABLED_NODE,
                        "pinned node not found: " + pinnedNodeId, List.of());
            }
            candidates = List.of(pinned);
            log.info("RPC fetchBlock({}) 固定节点 {}", number, pinnedNodeId);
        } else {
            candidates = registry.nodes();
            if (candidates.isEmpty()) {
                return DispatchResult.failed(FailureReason.NO_ENABLED_NODE, "no enabled node", List.of());
            }
        }

        List<DispatchResult.Attempt> attempts = new ArrayList<>();
        for (ChainNodeClient node : candidates) {
            // 1) 探活 + 落后判定：链顶够不到请求高度即为明显落后
            long head;
            try {
                head = node.getBlockNumber();
            } catch (NodeTimeoutException e) {
                attempts.add(new DispatchResult.Attempt(node.id(), "TIMEOUT", "head probe: " + e.getMessage()));
                continue;
            } catch (NodeBusinessException e) {
                attempts.add(new DispatchResult.Attempt(node.id(), "BUSINESS_ERROR", "head probe: " + e.getMessage()));
                continue;
            }
            if (head < number) {
                String detail = "stale: head=" + head + " < requested=" + number;
                attempts.add(new DispatchResult.Attempt(node.id(), "STALE", detail));
                log.info("RPC fetchBlock({}) 跳过落后节点 {} ({})", number, node.id(), detail);
                continue;
            }
            // 2) 真正取块
            try {
                Block block = node.getBlockByNumber(number);
                attempts.add(new DispatchResult.Attempt(node.id(), "OK",
                        "hash=" + block.hash() + ", parent=" + block.parentHash()));
                log.info("RPC fetchBlock({}) 成功 servedBy={} hash={}", number, node.id(), block.hash());
                return DispatchResult.ok(block, node.id(), attempts);
            } catch (NodeTimeoutException e) {
                attempts.add(new DispatchResult.Attempt(node.id(), "TIMEOUT", e.getMessage()));
            } catch (NodeBusinessException e) {
                attempts.add(new DispatchResult.Attempt(node.id(), "BUSINESS_ERROR", e.getMessage()));
            } catch (NodeStaleException e) {
                attempts.add(new DispatchResult.Attempt(node.id(), "STALE", e.getMessage()));
            }
        }

        DispatchResult<Block> r = classifyFailures(attempts, "eth_getBlockByNumber(" + number + ")");
        log.warn("RPC fetchBlock({}) 失败: {}，尝试明细={}", number, r.message(), attempts);
        return r;
    }

    private <T> DispatchResult<T> classifyFailures(List<DispatchResult.Attempt> attempts, String rpc) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        attempts.forEach(a -> seen.add(a.outcome()));
        FailureReason reason;
        if (seen.size() == 1) {
            reason = switch (seen.iterator().next()) {
                case "TIMEOUT" -> FailureReason.ALL_TIMEOUT;
                case "BUSINESS_ERROR" -> FailureReason.ALL_BUSINESS_ERROR;
                case "STALE" -> FailureReason.ALL_STALE;
                default -> FailureReason.MIXED;
            };
        } else {
            reason = FailureReason.MIXED;
        }
        return DispatchResult.failed(reason, rpc + " failed on all nodes: " + seen, attempts);
    }
}
