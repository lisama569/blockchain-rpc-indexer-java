package com.github.lisama569.blockchainrpcindexerjava.tests;

import com.github.lisama569.blockchainrpcindexerjava.indexer.BlockPoller;
import com.github.lisama569.blockchainrpcindexerjava.model.Block;
import com.github.lisama569.blockchainrpcindexerjava.node.MockChainNode;
import com.github.lisama569.blockchainrpcindexerjava.rpc.DispatchResult;
import com.github.lisama569.blockchainrpcindexerjava.rpc.FailureReason;
import com.github.lisama569.blockchainrpcindexerjava.support.Chains;
import com.github.lisama569.blockchainrpcindexerjava.support.Harness;
import com.github.lisama569.blockchainrpcindexerjava.support.LogCapture;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 场景：多节点分发/故障转移；超时、业务错误、明显落后三类结论可区分，落后节点数据不被采信。 */
class NodeFailoverTest {

    private List<Block> chainTo(long head) {
        List<Block> chain = new ArrayList<>();
        chain.add(Chains.genesis(List.of()));
        for (long n = 1; n <= head; n++) {
            Block prev = chain.get(chain.size() - 1);
            chain.add(Chains.next(prev, "h" + n,
                    List.of(Chains.tx("t" + n, "alice", "bob", 1))));
        }
        return chain;
    }

    @Test
    void timeoutFailsOverToHealthyNode() {
        try (LogCapture logs = new LogCapture(
                "com.github.lisama569.blockchainrpcindexerjava")) {
            Harness h = Harness.create(2L);
            MockChainNode primary = h.register("primary", 1, chainTo(4));
            MockChainNode backup = h.register("backup", 2, chainTo(4));
            primary.setTimeout(true);

            DispatchResult<Long> head = h.dispatcher().fetchHead();
            assertTrue(head.success());
            assertEquals(4L, head.value());
            assertEquals("backup", head.servedBy());
            assertTrue(head.attempts().stream().anyMatch(a -> a.nodeId().equals("primary") && a.outcome().equals("TIMEOUT")),
                    "必须记录主节点超时");

            DispatchResult<Block> blk = h.dispatcher().fetchBlock(2, null);
            assertTrue(blk.success());
            assertEquals("backup", blk.servedBy());
            assertEquals("h2", blk.value().hash());

            logs.echo("FAILOVER 超时故障转移流水（节点响应）");
            assertTrue(logs.contains("eth_blockNumber 超时"));
            assertTrue(logs.contains("RPC fetchBlock(2) 成功 servedBy=backup"));
        }
    }

    @Test
    void allTimeoutAndAllBusinessErrorAreDistinguishable() {
        try (LogCapture logs = new LogCapture(
                "com.github.lisama569.blockchainrpcindexerjava")) {
            // 全部超时 => ALL_TIMEOUT
            Harness h1 = Harness.create(2L);
            MockChainNode a1 = h1.register("a", 1, chainTo(3));
            MockChainNode b1 = h1.register("b", 2, chainTo(3));
            a1.setTimeout(true);
            b1.setTimeout(true);
            DispatchResult<Long> r1 = h1.dispatcher().fetchHead();
            assertFalse(r1.success());
            assertEquals(FailureReason.ALL_TIMEOUT, r1.failureReason());
            BlockPoller.PollOutcome p1 = h1.poller().pollOnce();
            assertTrue(p1.status().startsWith("HEAD_UNAVAILABLE:ALL_TIMEOUT"), p1::status);

            // 全部业务错误 => ALL_BUSINESS_ERROR
            Harness h2 = Harness.create(2L);
            MockChainNode a2 = h2.register("a", 1, chainTo(3));
            MockChainNode b2 = h2.register("b", 2, chainTo(3));
            a2.setBusinessError(true);
            b2.setBusinessError(true);
            DispatchResult<Long> r2 = h2.dispatcher().fetchHead();
            assertFalse(r2.success());
            assertEquals(FailureReason.ALL_BUSINESS_ERROR, r2.failureReason());
            BlockPoller.PollOutcome p2 = h2.poller().pollOnce();
            assertTrue(p2.status().startsWith("HEAD_UNAVAILABLE:ALL_BUSINESS_ERROR"), p2::status);

            logs.echo("FAILOVER 超时/业务错误分类流水");
            assertTrue(logs.contains("eth_blockNumber failed on all nodes: [TIMEOUT]"));
            assertTrue(logs.contains("eth_blockNumber failed on all nodes: [BUSINESS_ERROR]"));
        }
    }

    @Test
    void staleNodeIsSkippedAndNeverSilentlyTrusted() {
        try (LogCapture logs = new LogCapture(
                "com.github.lisama569.blockchainrpcindexerjava")) {
            Harness h = Harness.create(2L);
            // 主节点优先级高但明显落后（链顶 3），备份节点最新（链顶 8）
            MockChainNode primary = h.register("primary", 1, chainTo(8));
            primary.setHeadLag(5);
            MockChainNode backup = h.register("backup", 2, chainTo(8));

            DispatchResult<Block> blk = h.dispatcher().fetchBlock(7, null);
            assertTrue(blk.success(), "应当跳过落后节点并由健康节点服务");
            assertEquals("backup", blk.servedBy());
            assertTrue(blk.attempts().stream()
                            .anyMatch(a -> a.nodeId().equals("primary") && a.outcome().equals("STALE")),
                    "主节点必须被标记为 STALE 而不是静默采用其数据");

            // 只有落后节点可用时：明确 ALL_STALE，绝不凑结果
            backup.setEnabled(false);
            DispatchResult<Block> onlyStale = h.dispatcher().fetchBlock(7, null);
            assertFalse(onlyStale.success());
            assertEquals(FailureReason.ALL_STALE, onlyStale.failureReason());

            // 主节点业务错误 + 备节点落后 => MIXED，宁可失败也不采信可疑节点
            MockChainNode primary2 = h.register("primary2", 3, chainTo(8));
            primary2.setBusinessError(true);
            backup.setEnabled(false);
            DispatchResult<Block> mixed = h.dispatcher().fetchBlock(7, null);
            assertFalse(mixed.success());
            assertEquals(FailureReason.MIXED, mixed.failureReason());

            logs.echo("FAILOVER 落后节点判定流水（节点响应/STALE）");
            assertTrue(logs.contains("跳过落后节点 primary"));
            assertTrue(logs.contains("failed on all nodes: [STALE]"));
            assertTrue(logs.contains("failed on all nodes: [STALE, BUSINESS_ERROR]"));
        }
    }

    @Test
    void pollingDoesNotAdvanceWhenNodeUnavailable() {
        try (LogCapture logs = new LogCapture(
                "com.github.lisama569.blockchainrpcindexerjava")) {
            Harness h = Harness.create(2L);
            MockChainNode node = h.register("only", 1, chainTo(2));
            node.setTimeout(true);

            BlockPoller.PollOutcome p = h.poller().pollOnce();
            assertEquals(-1L, p.checkpoint());
            assertTrue(p.status().startsWith("HEAD_UNAVAILABLE:ALL_TIMEOUT"));
            assertEquals(-1L, h.store().checkpoint(), "节点不可用时位点不得前进");

            node.setTimeout(false);
            BlockPoller.PollOutcome ok = h.poller().pollOnce();
            assertEquals("ACCEPTED", ok.status());
            assertEquals(0L, h.store().checkpoint());

            logs.echo("FAILOVER 轮询位点保护流水");
            assertTrue(logs.contains("链顶不可用"));
        }
    }
}
