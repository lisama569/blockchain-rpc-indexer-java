package com.github.lisama569.blockchainrpcindexerjava.tests;

import com.github.lisama569.blockchainrpcindexerjava.domain.Block;
import com.github.lisama569.blockchainrpcindexerjava.domain.FailureKind;
import com.github.lisama569.blockchainrpcindexerjava.indexer.IndexingException;
import com.github.lisama569.blockchainrpcindexerjava.indexer.ReorgEvent;
import com.github.lisama569.blockchainrpcindexerjava.node.FetchResult;
import com.github.lisama569.blockchainrpcindexerjava.simulation.SimulatedNodeBehavior;
import com.github.lisama569.blockchainrpcindexerjava.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 链重组场景：父哈希断裂 -> 回滚受影响规范数据 -> 沿新链重建 -> 查询最终一致；
 * 以及回溯期间所有节点不可信时 REORG_UNRESOLVED 且原链保持不动。
 */
class ReorgIndexTest {

    private static final Logger log = LoggerFactory.getLogger(ReorgIndexTest.class);

    @Test
    void reorg_detachesOldBranch_attachesNewChain_andQueriesConverge() {
        // 规范链 0..7，先索引到 5
        TestFixtures fx = TestFixtures.of2(7);
        FetchResult r0 = fx.indexer.runOnce(6); // 0..5
        assertTrue(r0 instanceof FetchResult.BlockFound);
        assertEquals(5L, fx.positions.current());

        Block old5 = fx.chain.canonicalAt(5);
        BigInteger balBeforeA0 = fx.store.balance("A0").balance();
        log.info("[reorg-test] before reorg tip={} hash5={} balanceA0={}",
                fx.positions.current(), old5.hash(), balBeforeA0);

        // 在高度 3 分叉造 alt 链（3..7），两个节点都切换到 alt 视图，然后链本身 promote
        String parent = null;
        for (long h = 3; h <= 7; h++) {
            Block alt = fx.chain.makeAltBlock(h, parent);
            fx.chain.extendAlt(alt);
            parent = alt.hash();
        }
        Block new3 = fx.chain.altAt(3);
        Block new6 = fx.chain.altAt(6);
        assertNotEquals(old5.hash(), fx.chain.altAt(5).hash());
        fx.primary.updateBehavior(new SimulatedNodeBehavior(false, false, -1, true));
        fx.replica.updateBehavior(new SimulatedNodeBehavior(false, false, -1, true));
        fx.chain.promoteAltFrom(2);

        // 投递高度 6 的新链块：本地 5 的哈希与新块 parent 断裂 -> 触发重组
        fx.ingestion.ingest(new6);

        ReorgEvent ev = fx.reorgService.events().get(fx.reorgService.events().size() - 1);
        logReorg(ev);

        assertEquals(2L, ev.forkHeight(), "common ancestor must be height 2");
        assertEquals(false, ev.unresolved());
        // 重组流程会沿新链自动收敛到链尖：投递高度6后应直接重建到新链 tip=7
        assertEquals(7L, fx.positions.current(), "reorg must converge to the new chain tip, never expose a half-rebuilt state");
        // 高度 3..5 的规范块已被替换成 alt 版本
        assertEquals(new3.hash(), fx.store.blockAt(3).orElseThrow().hash());
        // 旧哈希彻底不存在
        assertTrue(fx.store.blockByHash(old5.hash()).isEmpty(), "old canonical block must be detached");
        // 区块顺序：升序且哈希连续
        List<Block> blocks = fx.store.blocksAscending();
        for (int i = 1; i < blocks.size(); i++) {
            assertEquals(blocks.get(i - 1).hash(), blocks.get(i).parentHash(),
                    "canonical chain parent-hash continuity after reorg");
        }
        // 位点已在新链 tip；再拉一轮只能是 NOT_FOUND，余额必须等于“按新链重算”的结果
        FetchResult extra = fx.indexer.runOnce(1);
        assertTrue(extra instanceof FetchResult.Failed
                && ((FetchResult.Failed) extra).kind() == FailureKind.BLOCK_NOT_FOUND);
        assertEquals(7L, fx.positions.current());
        BigInteger balAfter = fx.store.balance("A0").balance();
        BigInteger expected = recomputeBalance(fx, "A0");
        log.info("[reorg-test] after reorg+catchup tip={} balanceA0={} recomputed={}",
                fx.positions.current(), balAfter, expected);
        assertEquals(expected, balAfter, "balances must be fully reversed+re-applied, no mixed old/new accounting");
    }

    @Test
    void reorg_unresolvedWhenNoTrustedNode_canStateUntouched() {
        TestFixtures fx = TestFixtures.of2(7);
        fx.indexer.runOnce(6);
        long tipBefore = fx.positions.current();
        Block tipBlockBefore = fx.store.blockAt(tipBefore).orElseThrow();

        // 手工制造一个高度 6 的断裂块（parent 不指向本地 5），并让两节点在回溯时全部超时
        Block forkBlock = com.github.lisama569.blockchainrpcindexerjava.simulation.SimulatedChain
                .makeBlock(6, "evilH", "0xdeadbeef", 1);
        fx.primary.updateBehavior(new SimulatedNodeBehavior(true, false, -1, false));
        fx.replica.updateBehavior(new SimulatedNodeBehavior(true, false, -1, false));

        IndexingException ex = assertThrows(IndexingException.class, () -> fx.ingestion.ingest(forkBlock));
        assertEquals(FailureKind.REORG_UNRESOLVED, ex.kind());
        log.info("[reorg-test] unresolved exception kind={} msg={}", ex.kind(), ex.getMessage());

        // 原链原封不动
        assertEquals(tipBefore, fx.positions.current());
        assertEquals(tipBlockBefore.hash(), fx.store.blockAt(tipBefore).orElseThrow().hash());
        ReorgEvent ev = fx.reorgService.events().get(fx.reorgService.events().size() - 1);
        assertTrue(ev.unresolved());
        assertEquals(tipBefore, ev.oldTip());
        assertEquals(tipBefore, ev.newTip());
    }

    @Test
    void reorg_shorterNewChain_extraBlocksAreDetachedAndQueriesStayOrdered() {
        // 规范链 0..7，本地全部索引到 7
        TestFixtures fx = TestFixtures.of2(7);
        fx.indexer.runOnce(8);
        assertEquals(7L, fx.positions.current());

        // 制造一条在高度 6 分叉、只延伸到高度 6 的更短 alt 链
        Block alt6 = fx.chain.makeAltBlock(6, fx.chain.canonicalAt(5).hash());
        fx.chain.extendAlt(alt6);
        fx.primary.updateBehavior(new SimulatedNodeBehavior(false, false, 6, true));
        fx.replica.updateBehavior(new SimulatedNodeBehavior(false, false, 6, true));

        // 投递断裂块（高度 6 的 alt 版本）：共同祖先是 5；旧的 6、7 必须被撤销
        fx.ingestion.ingest(alt6);

        ReorgEvent ev = fx.reorgService.events().get(fx.reorgService.events().size() - 1);
        logReorg(ev);
        assertEquals(5L, ev.forkHeight());
        assertEquals(6L, fx.positions.current(), "tip must shrink to the shorter new chain tip");
        assertEquals(2, ev.detachedHashes().size(), "old blocks 6 and 7 detached");
        assertEquals(alt6.hash(), fx.store.blockAt(6).orElseThrow().hash());
        assertTrue(fx.store.blockAt(7).isEmpty());
        // 顺序与连续性
        List<Block> blocks = fx.store.blocksAscending();
        for (int i = 1; i < blocks.size(); i++) {
            assertEquals(blocks.get(i - 1).hash(), blocks.get(i).parentHash());
        }
        // 余额可解释：与按现有规范链重算结果一致
        assertEquals(recomputeBalance(fx, "A0"), fx.store.balance("A0").balance());
    }

    private static BigInteger recomputeBalance(TestFixtures fx, String account) {
        BigInteger sum = BigInteger.ZERO;
        for (Block b : fx.store.blocksAscending()) {
            for (var tx : b.transactions()) {
                if (tx.to().equals(account)) {
                    sum = sum.add(tx.amount());
                }
                if (tx.from().equals(account)) {
                    sum = sum.subtract(tx.amount());
                }
            }
        }
        return sum;
    }

    private static void logReorg(ReorgEvent ev) {
        log.info("----- REORG EVENT forkHeight={} oldTip={} newTip={} unresolved={} reason={}",
                ev.forkHeight(), ev.oldTip(), ev.newTip(), ev.unresolved(), ev.reason());
        log.info("[reorg-flow] detached(old->new order): {}", ev.detachedHashes());
        log.info("[reorg-flow] attached(low->high): {}", ev.attachedHashes());
    }
}
