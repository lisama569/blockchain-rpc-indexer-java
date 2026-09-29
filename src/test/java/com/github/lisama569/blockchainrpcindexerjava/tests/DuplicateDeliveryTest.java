package com.github.lisama569.blockchainrpcindexerjava.tests;

import com.github.lisama569.blockchainrpcindexerjava.domain.Block;
import com.github.lisama569.blockchainrpcindexerjava.node.FetchResult;
import com.github.lisama569.blockchainrpcindexerjava.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重复 / 并发投递同一条数据：不得重复计数、重复入账。
 */
class DuplicateDeliveryTest {

    private static final Logger log = LoggerFactory.getLogger(DuplicateDeliveryTest.class);

    @Test
    void sameBlockDeliveredTwice_onlyIngestedOnce() {
        TestFixtures fx = TestFixtures.of2(3);
        fx.indexer.runOnce(3); // 0,1,2
        long tip = fx.positions.current();
        int txCount = fx.store.transactionsAscending().size();

        Block dup = fx.chain.canonicalAt(2);
        boolean second = fx.ingestion.ingest(dup);
        log.info("[dup-test] re-deliver block height=2 hash={} acceptedAgain={}", dup.hash(), second);

        assertEquals(false, second, "duplicate delivery must be idempotent");
        assertEquals(tip, fx.positions.current(), "position must not move on duplicate");
        assertEquals(txCount, fx.store.transactionsAscending().size(), "no duplicate transactions");
    }

    @Test
    void concurrentDuplicateDelivery_blockAndBalancesCountExactlyOnce() throws Exception {
        TestFixtures fx = TestFixtures.of2(3);
        fx.indexer.runOnce(2); // tip 1
        Block next = fx.chain.canonicalAt(2);
        BigInteger balBefore = netWealth(fx);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            Callable<Boolean> task = () -> fx.ingestion.ingest(next);
            @SuppressWarnings("unchecked")
            Future<Boolean>[] futures = IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(task))
                    .toArray(Future[]::new);
            int accepted = 0;
            for (Future<Boolean> f : futures) {
                if (f.get()) {
                    accepted++;
                }
            }
            log.info("[dup-test] concurrent deliveries={} accepted={} tip={}", threads, accepted, fx.positions.current());
            assertEquals(1, accepted, "exactly one concurrent delivery may be accepted");
        } finally {
            pool.shutdown();
        }

        assertEquals(2L, fx.positions.current());
        // 只应有 2 笔新交易（高度 2 的块里共 2 笔）
        long txAt2 = fx.store.transactionsAscending().stream().filter(t -> t.height() == 2).count();
        assertEquals(2L, txAt2);
        // 入账守恒：新块金额在账户体系内只划转一次
        BigInteger wealthAfter = netWealth(fx);
        assertEquals(balBefore, wealthAfter, "double-entry conservation must hold (no double credit)");
    }

    @Test
    void pullLoopRepeatedAfterCatchup_doesNotDoubleCount() {
        TestFixtures fx = TestFixtures.of2(2);
        fx.indexer.runOnce(10); // 追上链头
        int tx = fx.store.transactionsAscending().size();
        long tip = fx.positions.current();

        // 再来一轮：节点返回 NOT_FOUND，正常停顿
        FetchResult r = fx.indexer.runOnce(5);
        log.info("[dup-test] extra run after catch-up result={}",
                r instanceof FetchResult.Failed f ? f.kind() : "BlockFound");
        assertEquals(tip, fx.positions.current());
        assertEquals(tx, fx.store.transactionsAscending().size());
        assertTrue(r instanceof FetchResult.Failed);
        assertEquals(com.github.lisama569.blockchainrpcindexerjava.domain.FailureKind.BLOCK_NOT_FOUND,
                ((FetchResult.Failed) r).kind());
    }

    private static BigInteger netWealth(TestFixtures fx) {
        // 转账模型下所有账户余额之和恒为 0
        return fx.store.allBalances().stream()
                .map(a -> a.balance())
                .reduce(BigInteger.ZERO, BigInteger::add);
    }
}
