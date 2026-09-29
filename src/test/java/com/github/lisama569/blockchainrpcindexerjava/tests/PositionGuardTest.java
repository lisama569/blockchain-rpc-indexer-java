package com.github.lisama569.blockchainrpcindexerjava.tests;

import com.github.lisama569.blockchainrpcindexerjava.domain.FailureKind;
import com.github.lisama569.blockchainrpcindexerjava.indexer.IndexingException;
import com.github.lisama569.blockchainrpcindexerjava.node.FetchResult;
import com.github.lisama569.blockchainrpcindexerjava.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 位点守卫：外部回退必须被明确拒绝（不是从中间静默继续）；
 * 重组专用回退是唯一合法通道且带流水（在 ReorgIndexTest 中覆盖）。
 */
class PositionGuardTest {

    private static final Logger log = LoggerFactory.getLogger(PositionGuardTest.class);

    @Test
    void externalRollbackIsExplicitlyRejected() {
        TestFixtures fx = TestFixtures.of2(5);
        fx.indexer.runOnce(4); // tip 3
        long tip = fx.positions.current();
        log.info("[position-test] tip before rollback request={}", tip);

        IndexingException ex = assertThrows(IndexingException.class, () -> fx.positions.requestReset(tip - 2));
        assertEquals(FailureKind.POSITION_ROLLBACK_REJECTED, ex.kind());
        log.warn("[position-test] rollback rejected as expected: {}", ex.getMessage());

        assertEquals(tip, fx.positions.current(), "position must remain unchanged after rejected rollback");
        // 回退被拒后索引仍能正常前进，没有“从中间继续”造成错乱
        FetchResult r = fx.indexer.runOnce(2);
        assertTrue(r instanceof FetchResult.BlockFound);
        assertEquals(tip + 2, fx.positions.current());
    }

    @Test
    void rogueBlockAtFinalizedHeightCannotBeValidated_chainUntouched() {
        // 已收敛高度出现一个父哈希来历不明的块：按重组流程回溯，但新鲜节点都无法证实它，
        // 必须 REORG_UNRESOLVED 且原链不动——绝不就地替换或从旧位点续跑
        TestFixtures fx = TestFixtures.of2(4);
        fx.indexer.runOnce(3);
        long tip = fx.positions.current();
        String tipHashBefore = fx.store.blockAt(tip).orElseThrow().hash();

        var rogueBlock = com.github.lisama569.blockchainrpcindexerjava.simulation.SimulatedChain
                .makeBlock(tip, "rogueH", "0xwhatever-unreachable-parent", 1);
        // 回溯期间没有任何可采信节点能证实该父块 -> 重组不可解析
        fx.primary.updateBehavior(new com.github.lisama569.blockchainrpcindexerjava.simulation.SimulatedNodeBehavior(true, false, -1, false));
        fx.replica.updateBehavior(new com.github.lisama569.blockchainrpcindexerjava.simulation.SimulatedNodeBehavior(true, false, -1, false));
        IndexingException ex = assertThrows(IndexingException.class, () -> fx.ingestion.ingest(rogueBlock));
        assertEquals(FailureKind.REORG_UNRESOLVED, ex.kind());
        log.warn("[position-test] unvalidatable finalized-height block refused via reorg pipeline: {}", ex.getMessage());
        assertEquals(tip, fx.positions.current());
        assertEquals(tipHashBefore, fx.store.blockAt(tip).orElseThrow().hash());
    }

    @Test
    void resetToSamePositionIsNoOp() {
        TestFixtures fx = TestFixtures.of2(2);
        fx.indexer.runOnce(2);
        long tip = fx.positions.current();
        fx.positions.requestReset(tip);
        assertEquals(tip, fx.positions.current());
    }
}
