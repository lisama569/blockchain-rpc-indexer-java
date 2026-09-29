package com.github.lisama569.blockchainrpcindexerjava.indexer;

import com.github.lisama569.blockchainrpcindexerjava.node.FetchResult;
import com.github.lisama569.blockchainrpcindexerjava.node.NodeDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 拉取式增量索引主循环。
 *
 * <p>每一轮从 {@code 位点+1} 高度经 {@link NodeDispatcher} 拉一个可采信区块，
 * 再交给 {@link IngestionService} 入账（父哈希断裂会在入账处触发重组）。</p>
 * <ul>
 *   <li>BLOCK_NOT_FOUND = 链尚未推进，属于正常停顿，不算错误；</li>
 *   <li>TIMEOUT / BUSINESS_ERROR / STALE / DIVERGENT / ALL_NODES_UNAVAILABLE 等
 *       带可区分结论直接停下，不跳块、不向可疑节点静默切换凑结果。</li>
 * </ul>
 */
@Service
public class IndexingService {

    private static final Logger log = LoggerFactory.getLogger(IndexingService.class);

    private final NodeDispatcher dispatcher;
    private final IngestionService ingestion;
    private final PositionManager positions;

    public IndexingService(NodeDispatcher dispatcher, IngestionService ingestion, PositionManager positions) {
        this.dispatcher = dispatcher;
        this.ingestion = ingestion;
        this.positions = positions;
    }

    /**
     * 尝试推进至多 maxBatches 个高度。
     *
     * @return 最后一次拉取结果：BlockFound 表示最后一轮拿到了块（可能还有更多可继续推进）；
     *         Failed(BLOCK_NOT_FOUND) 表示已追上链头正常停下；其它 Failed 为可区分故障结论。
     */
    public FetchResult runOnce(int maxBatches) {
        FetchResult last = null;
        for (int i = 0; i < maxBatches; i++) {
            long tip = positions.current();
            long height = tip + 1;
            String expectedHash = null; // 下一高度本地尚无块，不做本地期望哈希比对；STALE 由 tip 判定
            FetchResult r = dispatcher.fetchBlock(height, tip, expectedHash);
            if (r instanceof FetchResult.Failed f) {
                log.info("[indexer] stop at height={} kind={} reason={}", height, f.kind(), f.reason());
                return r;
            }
            FetchResult.BlockFound ok = (FetchResult.BlockFound) r;
            boolean applied = ingestion.ingest(ok.block());
            log.info("[indexer] height={} applied={} tip={}", height, applied, positions.current());
            last = r;
        }
        return last;
    }
}
