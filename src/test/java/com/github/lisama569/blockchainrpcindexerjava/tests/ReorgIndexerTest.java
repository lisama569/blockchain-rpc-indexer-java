package com.github.lisama569.blockchainrpcindexerjava.tests;

import com.github.lisama569.blockchainrpcindexerjava.indexer.BlockIngestor;
import com.github.lisama569.blockchainrpcindexerjava.model.Block;
import com.github.lisama569.blockchainrpcindexerjava.node.MockChainNode;
import com.github.lisama569.blockchainrpcindexerjava.support.Chains;
import com.github.lisama569.blockchainrpcindexerjava.support.Harness;
import com.github.lisama569.blockchainrpcindexerjava.support.LogCapture;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 场景：链重组——父哈希断裂后回滚到共同祖先并沿新链重建，查询结果最终一致。 */
class ReorgIndexerTest {

    @Test
    void reorgRollsBackAndRebuildsOnParentHashBreak() {
        try (LogCapture logs = new LogCapture(
                "com.github.lisama569.blockchainrpcindexerjava")) {

            Harness h = Harness.create(2L);

            // 规范链（节点A）：
            // h0 -> h1 -> h2(alice->bob 100) -> h3(bob->carol 40)
            Block h0 = Chains.genesis(List.of());
            Block h1 = Chains.next(h0, "h1", List.of());
            Block h2 = Chains.next(h1, "h2", List.of(Chains.tx("t2", "alice", "bob", 100)));
            Block h3 = Chains.next(h2, "h3", List.of(Chains.tx("t3", "bob", "carol", 40)));
            List<Block> chainA = List.of(h0, h1, h2, h3);

            // 分叉链（节点B）：在 height=2 处分叉，新哈希 x2/x3，交易不同
            // h0 -> h1 -> x2(alice->carol 60) -> x3(carol->bob 10)
            List<Block> chainB = Chains.fork(chainA, 2L,
                    List.of("x2", "x3"),
                    List.of(List.of(Chains.tx("u2", "alice", "carol", 60)),
                            List.of(Chains.tx("u3", "carol", "bob", 10))));

            MockChainNode nodeA = h.register("nodeA", 1, chainA);
            h.register("nodeB", 2, chainB);

            // 1) 沿 A 索引到链顶 3
            for (int i = 0; i <= 3; i++) {
                h.poller().pollOnce();
            }
            assertEquals(3, h.store().checkpoint());
            assertEquals("h3", h.store().canonicalHashAt(3));
            assertEquals(BigInteger.valueOf(-100), h.store().balances().get("alice"));
            assertEquals(BigInteger.valueOf(60), h.store().balances().get("bob"));
            assertEquals(BigInteger.valueOf(40), h.store().balances().get("carol"));
            assertEquals(2, h.store().txCount());

            // 2) A 掉线、B 成为唯一可用节点；下一轮发现 height=2 之上断裂
            nodeA.setEnabled(false);
            // 当前 checkpoint=3，networkHead(B)=3，UP_TO_DATE；真实场景新链会更长。
            // 给 B 追加 x4，使网络链顶变为 4，轮询取 wanted=4 不会触发（取的是 4 而非断裂点），
            // 因此这里通过直接投递分叉块 x2 的后继方式验证断裂检测：先让 A 提供的旧块被 B 的 x2 替换。
            // 直接投递 height=4（B 上）：父哈希为 x3，与规范链 h3 断裂 => 重组
            Block x4 = Chains.block(4, "x4", "x3", List.of(Chains.tx("u4", "bob", "alice", 5)));
            h.cache().put(x4);
            BlockIngestor.IngestResult result = h.ingestor().ingest(x4,
                    h.registry().byId("nodeB").orElseThrow());

            // 3) 结论必须是 REORGED，回滚到 forkPoint=1，再沿 B 重建到 4
            assertEquals(BlockIngestor.IngestStatus.REORGED, result.status(), result::detail);
            assertEquals(4, h.store().checkpoint());
            assertEquals("x4", h.store().canonicalHashAt(4));
            assertEquals("x2", h.store().canonicalHashAt(2));
            assertEquals("x3", h.store().canonicalHashAt(3));
            assertEquals("h1", h.store().canonicalHashAt(1), "共同祖先保留");

            // 4) 不得新旧混算：旧交易 t2/t3 撤销，仅新交易入账
            assertFalse(h.store().containsTx("t2"), "旧链交易必须撤销");
            assertFalse(h.store().containsTx("t3"), "旧链交易必须撤销");
            assertTrue(h.store().containsTx("u2"));
            assertTrue(h.store().containsTx("u3"));
            assertTrue(h.store().containsTx("u4"));
            assertEquals(3, h.store().txCount(), "不得重复计数");

            // 5) 余额按新链重放：alice -60+5=-55, bob +10-5=5, carol +60-10=50
            assertEquals(BigInteger.valueOf(-55), h.store().balances().get("alice"));
            assertEquals(BigInteger.valueOf(5), h.store().balances().get("bob"));
            assertEquals(BigInteger.valueOf(50), h.store().balances().get("carol"));

            // 6) 交易查询严格按规范链升序，无新旧混排
            List<java.util.Map<String, Object>> rows = h.store().queryTransactions(0, 4);
            assertEquals(List.of("u2", "u3", "u4"),
                    rows.stream().map(r -> (String) r.get("txHash")).toList());

            logs.echo("REORG 重组流水（节点响应/位点/回滚重建）");
            assertTrue(logs.contains("REORG 找到共同祖先: forkPoint=1"));
            assertTrue(logs.contains("REORG 撤销规范化块: height=3"));
            assertTrue(logs.contains("REORG 撤销规范化块: height=2"));
            assertTrue(logs.contains("REORG 重组完成并收敛"));
            assertTrue(logs.contains("checkpoint 推进=4"));
        }
    }
}
