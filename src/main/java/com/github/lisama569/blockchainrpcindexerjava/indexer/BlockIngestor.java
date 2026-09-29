package com.github.lisama569.blockchainrpcindexerjava.indexer;

import com.github.lisama569.blockchainrpcindexerjava.index.BlockCache;
import com.github.lisama569.blockchainrpcindexerjava.index.IndexStore;
import com.github.lisama569.blockchainrpcindexerjava.model.Block;
import com.github.lisama569.blockchainrpcindexerjava.node.ChainNodeClient;
import com.github.lisama569.blockchainrpcindexerjava.reorg.ReorgService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 区块投递（消费）入口。
 *
 * <p>同把锁与 {@link ReorgService} 语义对齐（本类方法 synchronized），保证并发投递安全：</p>
 * <ul>
 *   <li>同高度同哈希：DUPLICATE，幂等忽略，计数与余额不变；</li>
 *   <li>父哈希断裂（含同高度冲突块）：交给 ReorgService 回滚重建，结论 REORGED；</li>
 *   <li>位点回退/跳号：REJECTED_REGRESSION，明确拒绝，绝不在旧位点继续入账；</li>
 *   <li>正常连续块：ACCEPTED。</li>
 * </ul>
 */
@Component
public class BlockIngestor {

    private static final Logger log = LoggerFactory.getLogger(BlockIngestor.class);

    private final IndexStore store;
    private final BlockCache cache;
    private final ReorgService reorgService;

    public BlockIngestor(IndexStore store, BlockCache cache, ReorgService reorgService) {
        this.store = store;
        this.cache = cache;
        this.reorgService = reorgService;
    }

    public synchronized IngestResult ingest(Block block, ChainNodeClient source) {
        long cp = store.checkpoint();
        log.info("INGEST 收到投递: height={} hash={} parent={}, 当前 checkpoint={}, source={}",
                block.number(), block.hash(), block.parentHash(), cp, source == null ? "manual" : source.id());
        cache.put(block);

        // 位点保护：任何回退/跳号一律明确拒绝（哪怕是已在规范链上的旧块重投，也不是"从中间继续"）
        long expected = cp + 1;
        if (block.number() != expected) {
            log.warn("INGEST 拒绝位点回退/跳号: 期望 height={} 实际 height={}, checkpoint={}",
                    expected, block.number(), cp);
            return new IngestResult(IngestStatus.REJECTED_REGRESSION, cp,
                    "expected height " + expected + " but got " + block.number() + ", checkpoint=" + cp);
        }

        IndexStore.AppendOutcome out = store.appendCanonical(block);
        if (out.accepted()) {
            return new IngestResult(IngestStatus.ACCEPTED, store.checkpoint(), "accepted at height " + block.number());
        }
        if (out.duplicated()) {
            return new IngestResult(IngestStatus.DUPLICATE, cp, out.reason());
        }

        // 父哈希断裂或冲突 => 重组
        boolean badParent = out.reason() != null && out.reason().contains("parent hash mismatch");
        boolean conflict = out.reason() != null && out.reason().contains("conflicting block");
        if (badParent || conflict) {
            log.info("INGEST 检测到链分叉({})，触发重组 source={}", out.reason(), source == null ? "manual" : source.id());
            if (source == null) {
                return new IngestResult(IngestStatus.REJECTED_BAD_PARENT, cp,
                        "fork detected but no source node supplied: " + out.reason());
            }
            long newTip = reorgService.reconcile(source, block);
            return new IngestResult(IngestStatus.REORGED, newTip,
                    "reorg to height " + newTip + " triggered by " + out.reason());
        }

        return new IngestResult(IngestStatus.REJECTED_REGRESSION, cp, out.reason());
    }

    public enum IngestStatus { ACCEPTED, DUPLICATE, REORGED, REJECTED_REGRESSION, REJECTED_BAD_PARENT }

    public record IngestResult(IngestStatus status, long checkpoint, String detail) {
    }
}
