package com.github.lisama569.blockchainrpcindexerjava.indexer;

import com.github.lisama569.blockchainrpcindexerjava.config.IndexerProperties;
import com.github.lisama569.blockchainrpcindexerjava.index.BlockCache;
import com.github.lisama569.blockchainrpcindexerjava.index.IndexStore;
import com.github.lisama569.blockchainrpcindexerjava.model.Block;
import com.github.lisama569.blockchainrpcindexerjava.node.ChainNodeClient;
import com.github.lisama569.blockchainrpcindexerjava.node.NodeRegistry;
import com.github.lisama569.blockchainrpcindexerjava.rpc.DispatchResult;
import com.github.lisama569.blockchainrpcindexerjava.rpc.FailureReason;
import com.github.lisama569.blockchainrpcindexerjava.rpc.RpcDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 轮询驱动：探链顶 → 取下一块 → 投递。
 *
 * <p>对节点故障给出可区分结论：HEAD_UNAVAILABLE（附带 ALL_TIMEOUT/ALL_BUSINESS_ERROR/ALL_STALE/MIXED）、
 * BLOCK_UNAVAILABLE；落后节点不会被静默采信（dispatcher 已剔除）。位点落后于网络时逐块补齐。</p>
 */
@Component
public class BlockPoller {

    private static final Logger log = LoggerFactory.getLogger(BlockPoller.class);

    private final RpcDispatcher dispatcher;
    private final BlockIngestor ingestor;
    private final IndexStore store;
    private final BlockCache cache;
    private final NodeRegistry registry;
    private final IndexerProperties props;

    private ScheduledExecutorService scheduler;

    public BlockPoller(RpcDispatcher dispatcher, BlockIngestor ingestor, IndexStore store,
                       BlockCache cache, NodeRegistry registry, IndexerProperties props) {
        this.dispatcher = dispatcher;
        this.ingestor = ingestor;
        this.store = store;
        this.cache = cache;
        this.registry = registry;
        this.props = props;
    }

    /** 执行一轮拉取。 */
    public PollOutcome pollOnce() {
        long cp = store.checkpoint();

        DispatchResult<Long> headResult = dispatcher.fetchHead();
        if (!headResult.success()) {
            log.warn("POLL 链顶不可用: reason={}, detail={}, 尝试={}",
                    headResult.failureReason(), headResult.message(), headResult.attempts());
            return new PollOutcome("HEAD_UNAVAILABLE:" + headResult.failureReason(), cp, headResult.message());
        }
        long networkHead = headResult.value();
        log.info("POLL 位点对比: checkpoint={}, networkHead={}", cp, networkHead);
        if (networkHead <= cp) {
            return new PollOutcome("UP_TO_DATE", cp, "checkpoint=" + cp + " networkHead=" + networkHead);
        }

        long wanted = cp + 1;
        DispatchResult<Block> blockResult = dispatcher.fetchBlock(wanted, pinnedOrNone());
        if (!blockResult.success()) {
            log.warn("POLL 取块不可用 height={}: reason={}, detail={}, 尝试={}",
                    wanted, blockResult.failureReason(), blockResult.message(), blockResult.attempts());
            return new PollOutcome("BLOCK_UNAVAILABLE:" + blockResult.failureReason(), cp, blockResult.message());
        }

        Block block = blockResult.value();
        cache.put(block);
        String servedBy = blockResult.servedBy();
        Optional<ChainNodeClient> source = registry.byId(servedBy);
        BlockIngestor.IngestResult ir = ingestor.ingest(block, source.orElse(null));
        log.info("POLL 投递结论: height={}, status={}, checkpoint={}, detail={}, servedBy={}",
                wanted, ir.status(), ir.checkpoint(), ir.detail(), servedBy);
        return new PollOutcome(ir.status().name(), ir.checkpoint(), ir.detail() + " servedBy=" + servedBy);
    }

    private String pinnedOrNone() {
        return props.getPinnedNodeId() == null ? "" : props.getPinnedNodeId();
    }

    public void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "block-poller");
            t.setDaemon(true);
            return t;
        });
        long interval = Math.max(50L, props.getPollingIntervalMs());
        scheduler.scheduleWithFixedDelay(this::safePoll, interval, interval, TimeUnit.MILLISECONDS);
        log.info("POLL 轮询已启动: intervalMs={}, pinnedNodeId='{}', maxStaleLag={}",
                interval, props.getPinnedNodeId(), props.getMaxStaleLag());
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
            log.info("POLL 轮询已停止");
        }
    }

    private void safePoll() {
        try {
            // 一轮内持续补块直到追平，或在故障处分轮次（结论可解释）
            PollOutcome outcome;
            do {
                outcome = pollOnce();
            } while ("ACCEPTED".equals(outcome.status()) || "DUPLICATE".equals(outcome.status())
                    || "REORGED".equals(outcome.status()));
        } catch (RuntimeException e) {
            log.error("POLL 轮询异常: {}", e.getMessage(), e);
        }
    }

    public record PollOutcome(String status, long checkpoint, String detail) {
    }
}
