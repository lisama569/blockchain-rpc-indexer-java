package com.github.lisama569.blockchainrpcindexerjava.indexer;

import com.github.lisama569.blockchainrpcindexerjava.config.IndexerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 位点（offset / checkpoint）管理。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>位点 = 已收敛到规范链的最高连续高度；空库时取配置的冷启动位点之前（startHeight-1）；</li>
 *   <li>{@link #advanceTo(long, String)} 只能前进，且哈希必须与库内区块一致；</li>
 *   <li>{@link #requestReset(long)} 任何外部回退请求都被<strong>明确拒绝</strong>，
 *       抛 {@link FailureKind#POSITION_ROLLBACK_REJECTED}，绝不允许从旧位点“悄悄续跑”造成新旧混算；</li>
 *   <li>唯一合法回退通道 {@link #revertByReorg(long, String)} 只给重组流程使用，
 *       必须带流水原因，且回滚后立即重建到新链位点。</li>
 * </ul>
 */
@Component
public class PositionManager {

    private static final Logger log = LoggerFactory.getLogger(PositionManager.class);

    private final IndexStore store;
    private long position;

    public PositionManager(IndexStore store, IndexerProperties props) {
        this.store = store;
        this.position = props.startHeight() - 1;
        log.info("[position] initialized position={} (startHeight={})", position, props.startHeight());
    }

    public long current() {
        return position;
    }

    public void advanceTo(long newHeight, String hash) {
        if (newHeight <= position) {
            throw new IndexingException(com.github.lisama569.blockchainrpcindexerjava.domain.FailureKind.INTERNAL_ERROR,
                    "position cannot advance backwards: current=" + position + " requested=" + newHeight);
        }
        var block = store.blockAt(newHeight)
                .orElseThrow(() -> new IndexingException(com.github.lisama569.blockchainrpcindexerjava.domain.FailureKind.INTERNAL_ERROR,
                        "cannot advance to height " + newHeight + ": block missing in canonical store"));
        if (hash != null && !hash.equals(block.hash())) {
            throw new IndexingException(com.github.lisama569.blockchainrpcindexerjava.domain.FailureKind.INTERNAL_ERROR,
                    "advance hash mismatch at height " + newHeight + " expected=" + hash + " store=" + block.hash());
        }
        position = newHeight;
        log.info("[position] advanced -> {}", position);
    }

    /**
     * 外部位点回退请求：明确拒绝（幂等重试当前位点属于无进展，不报错）。
     */
    public void requestReset(long requestedHeight) {
        if (requestedHeight == position) {
            log.info("[position] reset to same height {} is a no-op", requestedHeight);
            return;
        }
        if (requestedHeight < position) {
            log.warn("[position] ROLLBACK REJECTED: current={} requested={} (use reorg rollback channel only)",
                    position, requestedHeight);
            throw new IndexingException(com.github.lisama569.blockchainrpcindexerjava.domain.FailureKind.POSITION_ROLLBACK_REJECTED,
                    "position rollback from " + position + " to " + requestedHeight
                            + " rejected: rollback is only allowed through the reorg rollback pipeline");
        }
        throw new IndexingException(com.github.lisama569.blockchainrpcindexerjava.domain.FailureKind.INTERNAL_ERROR,
                "cannot reset position forward: current=" + position + " requested=" + requestedHeight
                        + " (use advanceTo)");
    }

    /**
     * 重组专用回退通道（包内可见）：回退并打印显著流水，要求调用方随后立刻重建。
     */
    void revertByReorg(long newHeight, String reason) {
        long old = position;
        position = newHeight;
        log.warn("[position] REORG REVERT {} -> {} reason={}", old, newHeight, reason);
    }
}

