package com.github.lisama569.blockchainrpcindexerjava.reorg;

import com.github.lisama569.blockchainrpcindexerjava.index.BlockCache;
import com.github.lisama569.blockchainrpcindexerjava.index.IndexStore;
import com.github.lisama569.blockchainrpcindexerjava.model.Block;
import com.github.lisama569.blockchainrpcindexerjava.node.ChainNodeClient;
import com.github.lisama569.blockchainrpcindexerjava.node.NodeRegistry;
import com.github.lisama569.blockchainrpcindexerjava.rpc.DispatchResult;
import com.github.lisama569.blockchainrpcindexerjava.rpc.RpcDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 链重组处理。
 *
 * <p>当新到区块的父哈希与规范链断裂时：</p>
 * <ol>
 *   <li>沿父哈希向同一来源节点回溯，直到找到与规范链重合的高度（共同祖先）；</li>
 *   <li>回滚并撤销共同祖先之上的全部规范化数据（余额随之重放重建）；</li>
 *   <li>按新链顺序重新入账，并继续拉取到该节点最新链顶，收敛到唯一链；</li>
 *   <li>整个过程固定在同一节点，禁止跨节点混链。</li>
 * </ol>
 */
@Component
public class ReorgService {

    private static final Logger log = LoggerFactory.getLogger(ReorgService.class);

    private final IndexStore store;
    private final BlockCache cache;
    private final RpcDispatcher dispatcher;
    private final NodeRegistry registry;

    public ReorgService(IndexStore store, BlockCache cache, RpcDispatcher dispatcher, NodeRegistry registry) {
        this.store = store;
        this.cache = cache;
        this.dispatcher = dispatcher;
        this.registry = registry;
    }

    /** 以 source 节点返回的 newBlock 为新链线索完成回滚+重建，返回新链顶高度。 */
    public synchronized long reconcile(ChainNodeClient source, Block newBlock) {
        final long beforeCheckpoint = store.checkpoint();
        log.info("REORG 开始重组: source={}, 触发块 height={} hash={} parent={}, 当前 checkpoint={}",
                source.id(), newBlock.number(), newBlock.hash(), newBlock.parentHash(), beforeCheckpoint);
        cache.put(newBlock);

        // 1) 沿父哈希回溯，寻找共同祖先；path 为从新到旧收集的新链区块
        List<Block> path = new ArrayList<>();
        path.add(newBlock);
        long forkPoint = -1L;
        Block cur = newBlock;
        long guard = beforeCheckpoint + 2L; // 回溯深度安全上限
        while (true) {
            long h = cur.number();
            if (h == 0) {
                forkPoint = -1L;
                log.info("REORG 回溯到创世块仍无共同祖先，forkPoint=-1（全链替换）");
                break;
            }
            String canonicalParent = store.canonicalHashAt(h - 1);
            if (canonicalParent != null && canonicalParent.equals(cur.parentHash())) {
                forkPoint = h - 1;
                log.info("REORG 找到共同祖先: forkPoint={}, hash={}", forkPoint, canonicalParent);
                break;
            }
            if (--guard < 0) {
                throw new IllegalStateException("reorg parent walk exceeded safety bound from node " + source.id());
            }
            // 固定向来源节点取父块，防止跨节点混链
            DispatchResult<Block> r = dispatcher.fetchBlock(h - 1, source.id());
            if (!r.success()) {
                throw new IllegalStateException("reorg parent fetch failed from " + source.id()
                        + " at height=" + (h - 1) + " reason=" + r.failureReason() + " : " + r.message());
            }
            Block parent = r.value();
            if (!parent.hash().equals(cur.parentHash())) {
                throw new IllegalStateException("reorg parent hash mismatch from node " + source.id()
                        + " at height=" + (h - 1) + ": expected=" + cur.parentHash() + " got=" + parent.hash());
            }
            cache.put(parent);
            path.add(parent);
            log.info("REORG 回溯父块: height={} hash={} 与规范链不一致，继续向上", parent.number(), parent.hash());
            cur = parent;
        }

        // 2) 回滚到共同祖先（store 内会撤销交易并重放余额）
        store.rollbackAbove(forkPoint);

        // 3) 沿新链自低向高重新入账
        Collections.reverse(path);
        for (Block b : path) {
            IndexStore.AppendOutcome out = store.appendCanonical(b);
            if (!out.accepted() && !out.duplicated()) {
                throw new IllegalStateException("reorg replay rejected: height=" + b.number() + " : " + out.reason());
            }
            log.info("REORG 重建入账: height={} hash={} ({})", b.number(), b.hash(),
                    out.duplicated() ? "duplicated" : "accepted");
        }
        long replayedTip = path.get(path.size() - 1).number();

        // 4) 继续从同一节点拉取到其最新链顶，收敛整链
        long nodeHead;
        try {
            nodeHead = source.getBlockNumber();
        } catch (RuntimeException e) {
            log.warn("REORG 重建后探测链顶失败，停在 {}: {}", replayedTip, e.getMessage());
            return replayedTip;
        }
        long next = replayedTip + 1;
        while (next <= nodeHead) {
            DispatchResult<Block> r = dispatcher.fetchBlock(next, source.id());
            if (!r.success()) {
                log.warn("REORG 收敛中断于 height={}, reason={}: {}（等待下一轮继续）",
                        next, r.failureReason(), r.message());
                break;
            }
            Block b = r.value();
            cache.put(b);
            IndexStore.AppendOutcome out = store.appendCanonical(b);
            if (!out.accepted() && !out.duplicated()) {
                throw new IllegalStateException("reorg extend rejected: height=" + next + " : " + out.reason());
            }
            log.info("REORG 收敛追块: height={} hash={}", next, b.hash());
            next++;
        }

        long finalTip = store.checkpoint();
        log.info("REORG 重组完成并收敛: checkpoint {} -> {}, forkPoint={}, 新链顶 hash={}",
                beforeCheckpoint, finalTip, forkPoint, store.canonicalHashAt(finalTip));
        return finalTip;
    }
}
