package com.github.lisama569.blockchainrpcindexerjava.node;

import com.github.lisama569.blockchainrpcindexerjava.config.IndexerProperties;
import com.github.lisama569.blockchainrpcindexerjava.domain.Block;
import com.github.lisama569.blockchainrpcindexerjava.domain.FailureKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 多节点请求分发与故障转移。
 *
 * <p>铁律：</p>
 * <ol>
 *   <li>按注册顺序（即优先级）逐节点尝试，记录每一次尝试流水；</li>
 *   <li>超时（TIMEOUT）与业务错误（BUSINESS_ERROR）节点跳过，可故障转移到后续节点；</li>
 *   <li>链头明显落后的节点标记 STALE，其数据<strong>绝不采信</strong>，不会为凑结果静默切换过去；</li>
 *   <li>同高度多个新鲜节点返回不同哈希 => DIVERGENT，不敢选边，交由上层处理或失败；</li>
 *   <li>没有任何可采信来源时，返回带精确 {@link FailureKind} 的失败结论。</li>
 * </ol>
 */
@Component
public class NodeDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NodeDispatcher.class);

    private final List<NodeClient> nodes;
    private final long maxStaleHeight;

    public NodeDispatcher(List<NodeClient> nodes, IndexerProperties props) {
        this.nodes = List.copyOf(nodes);
        this.maxStaleHeight = props.maxStaleHeight();
        log.info("[dispatcher] nodes={} maxStaleHeight={}",
                this.nodes.stream().map(NodeClient::nodeId).toList(), maxStaleHeight);
    }

    /** 常规拉取：localTip 为本地位点，落后于本地位点的节点直接判 STALE。 */
    public FetchResult fetchBlock(long height, long localTip, String expectedHashOrNull) {
        return doFetch(height, localTip, expectedHashOrNull, false);
    }

    /**
     * 重组回溯拉取：不以本地位点判 STALE（回溯历史高度本就允许较短的链头），
     * 且不使用本地期望哈希，改由多个新鲜节点同哈希确认；只有单一新鲜节点时单独采信并标注。
     */
    public FetchResult fetchBlockForReorg(long height, long referenceHead) {
        return doFetch(height, referenceHead, null, true);
    }

    /** 探测全部节点链头（运维/演示用），返回完整尝试流水。 */
    public List<NodeAttempt> probeHeads() {
        List<NodeAttempt> attempts = new ArrayList<>();
        for (NodeClient n : nodes) {
            try {
                long head = n.chainHeadHeight();
                attempts.add(new NodeAttempt(n.nodeId(), "HEAD", "OK", "head=" + head, true));
            } catch (NodeException e) {
                attempts.add(new NodeAttempt(n.nodeId(), "HEAD", resultOf(e), e.getMessage(), false));
            }
        }
        return attempts;
    }

    /**
     * 查询“新鲜节点”中最高的链头：落后于最领先节点超过容忍高度的节点不计入，
     * 故障节点不计入。返回 -1 表示没有任何可采信链头。
     */
    public long freshChainHead() {
        long best = Long.MIN_VALUE;
        long[] heads = new long[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            try {
                heads[i] = nodes.get(i).chainHeadHeight();
                best = Math.max(best, heads[i]);
            } catch (NodeException e) {
                heads[i] = Long.MIN_VALUE;
            }
        }
        long freshBest = -1L;
        for (long head : heads) {
            if (head != Long.MIN_VALUE && head >= best - maxStaleHeight) {
                freshBest = Math.max(freshBest, head);
            }
        }
        return freshBest;
    }

    // ------------------------------------------------------------------

    private FetchResult doFetch(long height, long localTipOrRefHead, String expectedHash, boolean reorgMode) {
        List<NodeAttempt> attempts = new ArrayList<>();
        List<NodeClient> fresh = new ArrayList<>();
        boolean sawTimeout = false;
        boolean sawBusiness = false;
        boolean sawStale = false;

        // ---- 阶段一：链头探测 + 新鲜度判定 ----
        long bestHead = Long.MIN_VALUE;
        long[] heads = new long[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            NodeClient n = nodes.get(i);
            try {
                long head = n.chainHeadHeight();
                heads[i] = head;
                bestHead = Math.max(bestHead, head);
                attempts.add(new NodeAttempt(n.nodeId(), "HEAD", "OK", "head=" + head, true));
            } catch (NodeException e) {
                heads[i] = Long.MIN_VALUE;
                String r = resultOf(e);
                if (e.timeout()) {
                    sawTimeout = true;
                } else {
                    sawBusiness = true;
                }
                attempts.add(new NodeAttempt(n.nodeId(), "HEAD", r, e.getMessage(), false));
            }
        }

        for (int i = 0; i < nodes.size(); i++) {
            NodeClient n = nodes.get(i);
            long head = heads[i];
            if (head == Long.MIN_VALUE) {
                continue;
            }
            boolean staleRelPeers = bestHead != Long.MIN_VALUE && head < bestHead - maxStaleHeight;
            boolean staleRelLocal = !reorgMode && head < localTipOrRefHead;
            if (staleRelPeers || staleRelLocal) {
                sawStale = true;
                attempts.add(new NodeAttempt(n.nodeId(), "HEAD", "STALE",
                        "head=" + head + " (bestHead=" + bestHead + ", localTip=" + localTipOrRefHead
                                + (staleRelPeers ? ", behind peers" : "") + (staleRelLocal ? ", behind local tip" : "") + ")",
                        false));
            } else {
                fresh.add(n);
            }
        }

        if (fresh.isEmpty()) {
            FailureKind kind;
            String reason;
            // 存在落后节点时必须优先把 STALE 事实暴露出来：即使同时有超时/业务错误，
            // 也不能归为单纯超时，以免掩盖“有个可疑节点手里恰好有数据”的情况。
            if (sawStale && !sawTimeout && !sawBusiness) {
                kind = FailureKind.NODE_STALE;
                reason = "all reachable nodes are stale at height " + height;
            } else if (sawStale) {
                kind = FailureKind.ALL_NODES_UNAVAILABLE;
                reason = "no trustworthy node for height " + height
                        + ": reachable node(s) are STALE and others failed"
                        + " (timeout=" + sawTimeout + ", businessError=" + sawBusiness + ")";
            } else if (sawTimeout && !sawBusiness) {
                kind = FailureKind.NODE_TIMEOUT;
                reason = "all nodes timed out while probing head for height " + height;
            } else if (sawBusiness && !sawTimeout) {
                kind = FailureKind.NODE_BUSINESS_ERROR;
                reason = "all nodes returned business errors while probing head for height " + height;
            } else {
                kind = FailureKind.ALL_NODES_UNAVAILABLE;
                reason = "no fresh/usable node for height " + height
                        + " (timeout=" + sawTimeout + ", businessError=" + sawBusiness + ", stale=" + sawStale + ")";
            }
            log.warn("[dispatcher] fetch FAIL height={} kind={} reason={} attempts={}", height, kind, reason, attempts);
            return new FetchResult.Failed(kind, reason, List.copyOf(attempts));
        }

        // ---- 阶段二：逐新鲜节点拉块并做哈希比对 ----
        List<NodeClient> blockOk = new ArrayList<>();
        List<Block> blocks = new ArrayList<>();
        boolean allNull = true;
        for (NodeClient n : fresh) {
            try {
                Block b = n.blockByHeight(height);
                if (b == null) {
                    attempts.add(new NodeAttempt(n.nodeId(), "BLOCK", "NOT_FOUND",
                            "height=" + height + " not found on node", false));
                    continue;
                }
                allNull = false;
                blockOk.add(n);
                blocks.add(b);
                attempts.add(new NodeAttempt(n.nodeId(), "BLOCK", "OK",
                        "height=" + height + " hash=" + b.hash() + " parent=" + b.parentHash(), true));
            } catch (NodeException e) {
                attempts.add(new NodeAttempt(n.nodeId(), "BLOCK", resultOf(e), e.getMessage(), false));
            }
        }

        if (allNull) {
            log.info("[dispatcher] fetch NOT_FOUND height={} attempts={}", height, attempts);
            return new FetchResult.Failed(FailureKind.BLOCK_NOT_FOUND,
                    "no fresh node has block at height " + height + " (chain has not advanced)", List.copyOf(attempts));
        }

        // 哈希比对：新鲜节点之间不允许分歧
        Set<String> hashes = new HashSet<>();
        for (Block b : blocks) {
            hashes.add(b.hash());
        }
        if (hashes.size() > 1) {
            for (int i = 0; i < blockOk.size(); i++) {
                markDivergent(attempts, blockOk.get(i).nodeId(), blocks.get(i).hash(), hashes);
            }
            String reason = "fresh nodes disagree at height " + height + ": hashes=" + hashes;
            log.warn("[dispatcher]fetch DIVERGENT height={} {}", height, reason);
            return new FetchResult.Failed(FailureKind.NODE_DIVERGENT, reason, List.copyOf(attempts));
        }

        // 与本地期望哈希比对（仅常规模式）：分歧不静默采纳，标记冲突节点并失败
        Block chosen = blocks.get(0);
        if (expectedHash != null && !expectedHash.equals(chosen.hash())) {
            String reason = "node hash " + chosen.hash() + " != expected(local canonical) " + expectedHash
                    + " at height " + height;
            for (int i = 0; i < blockOk.size(); i++) {
                attempts.add(new NodeAttempt(blockOk.get(i).nodeId(), "BLOCK", "DIVERGENT", reason, false));
            }
            log.warn("[dispatcher] fetch DIVERGENT height={} {}", height, reason);
            return new FetchResult.Failed(FailureKind.NODE_DIVERGENT, reason, List.copyOf(attempts));
        }

        String servedBy = String.join(",", blockOk.stream().map(NodeClient::nodeId).toList());
        log.info("[dispatcher] fetch OK height={} hash={} servedBy={} (freshAgreed={}/{})",
                height, chosen.hash(), servedBy, blocks.size(), fresh.size());
        return new FetchResult.BlockFound(chosen, servedBy, List.copyOf(attempts));
    }

    private static void markDivergent(List<NodeAttempt> attempts, String nodeId, String hash, Set<String> allHashes) {
        attempts.add(new NodeAttempt(nodeId, "BLOCK", "DIVERGENT",
                "hash=" + hash + " among disagreeing hashes=" + allHashes, false));
    }

    private static String resultOf(NodeException e) {
        if (e instanceof NodeTimeoutException) {
            return "TIMEOUT";
        }
        if (e instanceof NodeBusinessException nbe) {
            return "BUSINESS_ERROR(" + nbe.code() + ")";
        }
        return "ERROR";
    }

    /** 供上层在失败流水里取到某个节点的结论（测试辅助）。 */
    public static Optional<NodeAttempt> lastAttemptOf(List<NodeAttempt> attempts, String nodeId, String stage) {
        NodeAttempt found = null;
        for (NodeAttempt a : attempts) {
            if (a.nodeId().equals(nodeId) && a.stage().equals(stage)) {
                found = a;
            }
        }
        return Optional.ofNullable(found);
    }
}
