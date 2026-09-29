package com.github.lisama569.blockchainrpcindexerjava.tests;

import com.github.lisama569.blockchainrpcindexerjava.indexer.BlockIngestor;
import com.github.lisama569.blockchainrpcindexerjava.indexer.CheckpointGuard;
import com.github.lisama569.blockchainrpcindexerjava.model.Block;
import com.github.lisama569.blockchainrpcindexerjava.node.MockChainNode;
import com.github.lisama569.blockchainrpcindexerjava.support.Chains;
import com.github.lisama569.blockchainrpcindexerjava.support.Harness;
import com.github.lisama569.blockchainrpcindexerjava.support.LogCapture;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 场景：重复投递与并发投递同一条数据；位点回退必须被明确拒绝。 */
class DuplicateDeliveryTest {

    @Test
    void duplicateAndConcurrentDeliveryAreIdempotent() throws Exception {
        try (LogCapture logs = new LogCapture(
                "com.github.lisama569.blockchainrpcindexerjava")) {

            Harness h = Harness.create(2L);
            Block h0 = Chains.genesis(List.of());
            Block h1 = Chains.next(h0, "h1",
                    List.of(Chains.tx("t1", "alice", "bob", 100)));
            Block h2 = Chains.next(h1, "h2",
                    List.of(Chains.tx("t2", "bob", "carol", 30)));
            MockChainNode node = h.register("node", 1, List.of(h0, h1, h2));

            // 正常推进
            assertEquals(BlockIngestor.IngestStatus.ACCEPTED,
                    h.ingestor().ingest(h0, node).status());
            assertEquals(BlockIngestor.IngestStatus.ACCEPTED,
                    h.ingestor().ingest(h1, node).status());

            // 1) 顺序重复投递旧块 => 位点回退被明确拒绝（不重复入账）
            BlockIngestor.IngestResult dup = h.ingestor().ingest(h1, node);
            assertEquals(BlockIngestor.IngestStatus.REJECTED_REGRESSION, dup.status());
            assertEquals(1, h.store().checkpoint());
            assertEquals(1, h.store().txCount());
            assertEquals(BigInteger.valueOf(100), h.store().balances().get("bob"));

            // 2) 并发投递同一块 h2（8 个线程），至多一次 ACCEPTED，其余幂等（DUPLICATE 或回退拒绝）
            ExecutorService pool = Executors.newFixedThreadPool(8);
            List<Callable<BlockIngestor.IngestResult>> tasks = java.util.stream.IntStream.range(0, 8)
                    .<Callable<BlockIngestor.IngestResult>>mapToObj(i -> () -> h.ingestor().ingest(h2, node))
                    .toList();
            List<Future<BlockIngestor.IngestResult>> futures = pool.invokeAll(tasks);
            long accepted = 0;
            long idempotent = 0;
            for (Future<BlockIngestor.IngestResult> f : futures) {
                BlockIngestor.IngestResult r = f.get();
                if (r.status() == BlockIngestor.IngestStatus.ACCEPTED) accepted++;
                if (r.status() == BlockIngestor.IngestStatus.DUPLICATE
                        || r.status() == BlockIngestor.IngestStatus.REJECTED_REGRESSION) idempotent++;
            }
            pool.shutdown();
            assertEquals(1, accepted, "同一块只能被接受一次");
            assertEquals(7, idempotent, "其余并发投递必须幂等，不重复入账");
            assertEquals(2, h.store().checkpoint());
            assertEquals(2, h.store().txCount(), "交易不得重复计数");
            assertEquals(BigInteger.valueOf(70), h.store().balances().get("bob"));
            assertEquals(BigInteger.valueOf(30), h.store().balances().get("carol"));
            assertEquals(BigInteger.valueOf(-100), h.store().balances().get("alice"));

            // 3) 再次投递旧区间 => 回退拒绝，不新增
            BlockIngestor.IngestResult again = h.ingestor().ingest(h2, node);
            assertEquals(BlockIngestor.IngestStatus.REJECTED_REGRESSION, again.status());
            assertEquals(2, h.store().txCount());

            // 4) 位点回退：投递已跳过的旧块被明确拒绝；位点守卫抛异常
            BlockIngestor.IngestResult regression = h.ingestor().ingest(h0, node);
            assertEquals(BlockIngestor.IngestStatus.REJECTED_REGRESSION, regression.status());
            assertThrows(CheckpointGuard.CheckpointRegressionException.class,
                    () -> CheckpointGuard.requireNext(2, 0));
            assertThrows(CheckpointGuard.CheckpointRegressionException.class,
                    () -> CheckpointGuard.requireNext(2, 5));

            logs.echo("DUP 重复/并发/位点流水（节点响应/位点/幂等）");
            assertTrue(logs.contains("拒绝位点回退/跳号"));
            assertTrue(logs.contains("checkpoint 推进=2"));
            assertTrue(logs.contains("规范化入账"));
        }
    }
}
