package com.github.lisama569.blockchainrpcindexerjava.tests;

import com.github.lisama569.blockchainrpcindexerjava.domain.FailureKind;
import com.github.lisama569.blockchainrpcindexerjava.node.FetchResult;
import com.github.lisama569.blockchainrpcindexerjava.node.NodeAttempt;
import com.github.lisama569.blockchainrpcindexerjava.simulation.SimulatedNodeBehavior;
import com.github.lisama569.blockchainrpcindexerjava.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 节点接入与故障转移场景：超时、业务错误、落后、分歧必须给出可区分结论，
 * 且绝不为凑结果采信可疑（STALE）节点。
 */
class NodeFailoverTest {

    private static final Logger log = LoggerFactory.getLogger(NodeFailoverTest.class);

    private void logAttempts(String title, List<NodeAttempt> attempts) {
        log.info("===== {} =====", title);
        attempts.forEach(a -> log.info("[attempt] node={} stage={} result={} usable={} detail={}",
                a.nodeId(), a.stage(), a.result(), a.usable(), a.detail()));
    }

    @Test
    void timeoutOnPrimary_failoverToReplica_blockServedAndFailureVisibleInTrace() {
        TestFixtures fx = TestFixtures.of2(7);
        fx.primary.updateBehavior(new SimulatedNodeBehavior(true, false, -1, false));

        FetchResult r = fx.dispatcher.fetchBlock(0, -1, null);
        logAttempts("primary timeout -> replica serves", switchAttemps(r));

        assertTrue(r instanceof FetchResult.BlockFound, "replica must take over");
        FetchResult.BlockFound ok = (FetchResult.BlockFound) r;
        assertEquals("node-replica", ok.servedBy());
        List<NodeAttempt> attempts = ok.attempts();
        NodeAttempt primaryAttempt = attempts.stream()
                .filter(a -> a.nodeId().equals("node-primary") && a.stage().equals("HEAD"))
                .findFirst().orElseThrow();
        assertEquals("TIMEOUT", primaryAttempt.result());
        assertEquals(false, primaryAttempt.usable(), "timed-out node must be marked unusable");
    }

    @Test
    void businessErrorOnPrimary_failoverToReplica_andConclusionsAreDistinguished() {
        TestFixtures fx = TestFixtures.of3(7);
        fx.primary.updateBehavior(new SimulatedNodeBehavior(false, true, -1, false));

        FetchResult r = fx.dispatcher.fetchBlock(2, -1, null);
        logAttempts("primary business-error -> others serve", switchAttemps(r));

        assertTrue(r instanceof FetchResult.BlockFound);
        List<NodeAttempt> attempts = ((FetchResult.BlockFound) r).attempts();
        NodeAttempt bad = attempts.stream()
                .filter(a -> a.nodeId().equals("node-primary") && a.stage().equals("HEAD"))
                .findFirst().orElseThrow();
        assertTrue(bad.result().startsWith("BUSINESS_ERROR"), "business error must be distinguishable, got " + bad.result());
        assertNotNull(bad.result());
    }

    @Test
    void allNodesTimeout_distinctTimeoutConclusion() {
        TestFixtures fx = TestFixtures.of2(7);
        fx.primary.updateBehavior(new SimulatedNodeBehavior(true, false, -1, false));
        fx.replica.updateBehavior(new SimulatedNodeBehavior(true, false, -1, false));

        FetchResult r = fx.dispatcher.fetchBlock(1, -1, null);
        logAttempts("all timeout", switchAttemps(r));

        assertTrue(r instanceof FetchResult.Failed);
        assertEquals(FailureKind.NODE_TIMEOUT, ((FetchResult.Failed) r).kind());
    }

    @Test
    void allNodesBusinessError_distinctBusinessConclusion() {
        TestFixtures fx = TestFixtures.of2(7);
        fx.primary.updateBehavior(new SimulatedNodeBehavior(false, true, -1, false));
        fx.replica.updateBehavior(new SimulatedNodeBehavior(false, true, -1, false));

        FetchResult r = fx.dispatcher.fetchBlock(1, -1, null);
        logAttempts("all business error", switchAttemps(r));

        assertTrue(r instanceof FetchResult.Failed);
        assertEquals(FailureKind.NODE_BUSINESS_ERROR, ((FetchResult.Failed) r).kind());
    }

    @Test
    void staleReplicaIsNeverUsedEvenWhenPrimaryTimesOut() {
        // primary 超时，replica 明显落后（链头 2，而本地已索引到 4）：
        // 请求高度 2 恰好是落后节点“手上有数据”的位置，更能证明它绝不被采信
        TestFixtures fx = TestFixtures.of2(7);
        fx.primary.updateBehavior(new SimulatedNodeBehavior(true, false, -1, false));
        fx.replica.updateBehavior(new SimulatedNodeBehavior(false, false, 2, false));

        FetchResult r = fx.dispatcher.fetchBlock(2, 4, null);
        logAttempts("primary timeout, replica stale -> must fail, never serve stale", switchAttemps(r));

        assertTrue(r instanceof FetchResult.Failed);
        FetchResult.Failed f = (FetchResult.Failed) r;
        // 超时与落后同时存在时，结论是“无可用节点”，绝不允许返回 STALE 节点的数据充当成功
        assertEquals(FailureKind.ALL_NODES_UNAVAILABLE, f.kind(),
                "stale data must not be silently trusted: " + f.reason());
        boolean timeoutMarked = f.attempts().stream()
                .anyMatch(a -> a.nodeId().equals("node-primary") && "TIMEOUT".equals(a.result()) && !a.usable());
        boolean staleMarked = f.attempts().stream()
                .anyMatch(a -> a.nodeId().equals("node-replica") && "STALE".equals(a.result()) && !a.usable());
        assertTrue(timeoutMarked, "timed-out node must be recorded TIMEOUT/unusable");
        assertTrue(staleMarked, "stale node must be recorded STALE/unusable");
        assertTrue(!f.attempts().stream().anyMatch(a -> a.nodeId().equals("node-replica") && a.stage().equals("BLOCK") && a.usable()),
                "stale replica must never be used to serve a block");
    }

    @Test
    void allStaleNodes_distinctStaleConclusion() {
        TestFixtures fx = TestFixtures.of2(7);
        // 两节点都声称链头只有 1（相对彼此不算落后，但相对“本地已索引到 4”算落后）
        fx.primary.updateBehavior(new SimulatedNodeBehavior(false, false, 1, false));
        fx.replica.updateBehavior(new SimulatedNodeBehavior(false, false, 1, false));

        FetchResult r = fx.dispatcher.fetchBlock(5, 4, null);
        logAttempts("all nodes behind local tip -> STALE", switchAttemps(r));

        assertTrue(r instanceof FetchResult.Failed);
        assertEquals(FailureKind.NODE_STALE, ((FetchResult.Failed) r).kind());
    }

    @Test
    void divergentNodesAtSameHeight_distinctDivergentConclusion() {
        TestFixtures fx = TestFixtures.of2(7);
        // primary 看 alt 分叉视图（先造 alt 块），replica 仍看规范链
        // alt 视图必须与规范链等长，否则 alt 节点会因链头短而被判 STALE，无法用于验证“新鲜节点分歧”
        String altParent = null;
        for (long h = 3; h <= 7; h++) {
            var alt = fx.chain.makeAltBlock(h, altParent);
            fx.chain.extendAlt(alt);
            altParent = alt.hash();
        }
        fx.primary.updateBehavior(new SimulatedNodeBehavior(false, false, -1, true));

        FetchResult r = fx.dispatcher.fetchBlock(3, -1, null);
        logAttempts("fresh nodes disagree at same height -> DIVERGENT", switchAttemps(r));

        assertTrue(r instanceof FetchResult.Failed);
        assertEquals(FailureKind.NODE_DIVERGENT, ((FetchResult.Failed) r).kind());
    }

    @Test
    void blockNotFoundWhenChainHasNotAdvanced_isNormalPauseNotError() {
        TestFixtures fx = TestFixtures.of2(2);
        FetchResult r = fx.dispatcher.fetchBlock(9, -1, null);
        logAttempts("height beyond chain head -> NOT_FOUND", switchAttemps(r));
        assertTrue(r instanceof FetchResult.Failed);
        assertEquals(FailureKind.BLOCK_NOT_FOUND, ((FetchResult.Failed) r).kind());
    }

    private List<NodeAttempt> switchAttemps(FetchResult r) {
        return r instanceof FetchResult.BlockFound b ? b.attempts() : ((FetchResult.Failed) r).attempts();
    }
}
