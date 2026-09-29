package com.github.lisama569.blockchainrpcindexerjava.indexer;

import com.github.lisama569.blockchainrpcindexerjava.domain.Block;
import com.github.lisama569.blockchainrpcindexerjava.domain.FailureKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 区块入账服务（投递式接入）。
 *
 * <p>在 {@link IndexStore} 全局锁内串行处理一个区块：</p>
 * <ul>
 *   <li>按哈希去重：同一区块（含其所有交易）重复/并发投递只入账一次，绝不重复计数；</li>
 *   <li>只接受位点的“下一高度”连续区块；越位、回位、乱序一律拒绝，不跳块；</li>
 *   <li>父哈希与本地规范块不一致 = 链断裂，转交 {@link ReorgService} 回滚重建；</li>
 *   <li>正常入账后位点前进，查询视图始终只反映已收敛数据。</li>
 * </ul>
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final IndexStore store;
    private final PositionManager positions;
    private final ReorgService reorgService;

    public IngestionService(IndexStore store, PositionManager positions, ReorgService reorgService) {
        this.store = store;
        this.positions = positions;
        this.reorgService = reorgService;
    }

    /**
     * @return true 表示本次实际入账；false 表示重复/无进展投递（幂等忽略）
     */
    public boolean ingest(Block block) {
        store.lock();
        try {
            long tip = positions.current();

            if (store.blockExists(block.hash())) {
                log.info("[ingest] duplicate block ignored height={} hash={} tip={}", block.height(), block.hash(), tip);
                return false;
            }

            if (block.height() <= tip) {
                // 已收敛高度：同哈希重复已在前面按幂等忽略；不同哈希说明出现了落在历史高度的分叉
                // （例如更短的新链），这是合法重组信号——先与本块哈希比较，断裂则走 reorg。
                var localBlock = store.blockAt(block.height());
                if (localBlock.isPresent() && !localBlock.get().hash().equals(block.hash())) {
                    log.warn("[ingest] fork at finalized height={}: localHash={} incomingHash={}",
                            block.height(), localBlock.get().hash(), block.hash());
                    reorgService.handleFork(block);
                    return true;
                }
                // 同高度同哈希以外的回位/替换请求一律拒绝：不允许从旧位点“续跑”
                log.warn("[ingest] block at finalized height rejected height={} tip={} hash={}",
                        block.height(), tip, block.hash());
                throw new IndexingException(FailureKind.POSITION_ROLLBACK_REJECTED,
                        "block height " + block.height() + " is already finalized at tip " + tip
                                + "; rollback/replacement must go through reorg pipeline");
            }

            long expectedHeight = tip + 1;
            if (block.height() != expectedHeight) {
                throw new IndexingException(FailureKind.INTERNAL_ERROR,
                        "non-contiguous delivery: expected height " + expectedHeight + " got " + block.height());
            }

            if (tip >= 0) {
                String localParent = store.blockAt(tip).map(Block::hash).orElse(null);
                if (localParent != null && !localParent.equals(block.parentHash())) {
                    log.warn("[ingest] parent-hash break at height={}: block.parent={} canonical[{}]={}",
                            block.height(), block.parentHash(), tip, localParent);
                    reorgService.handleFork(block);
                    return true;
                }
            }

            // 正常入账
            store.putBlock(block);
            int credited = 0;
            for (var tx : block.transactions()) {
                if (store.txExists(tx.txHash())) {
                    log.warn("[ingest] duplicate tx skipped within canonical block: {}", tx.txHash());
                    continue;
                }
                store.credit(tx);
                credited++;
            }
            positions.advanceTo(block.height(), block.hash());
            log.info("[ingest] accepted height={} hash={} txCredited={} newTip={}",
                    block.height(), block.hash(), credited, positions.current());
            return true;
        } finally {
            store.unlock();
        }
    }
}
