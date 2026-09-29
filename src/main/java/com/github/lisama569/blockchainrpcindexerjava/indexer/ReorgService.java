package com.github.lisama569.blockchainrpcindexerjava.indexer;

import com.github.lisama569.blockchainrpcindexerjava.domain.Block;
import com.github.lisama569.blockchainrpcindexerjava.domain.FailureKind;
import com.github.lisama569.blockchainrpcindexerjava.node.FetchResult;
import com.github.lisama569.blockchainrpcindexerjava.node.NodeAttempt;
import com.github.lisama569.blockchainrpcindexerjava.node.NodeDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 链重组处理（reorg）。
 *
 * <p>当 {@link IngestionService} 发现新区块的 parentHash 与本地规范块断裂时转入本服务：</p>
 * <ol>
 *   <li>从断裂高度起沿父哈希向历史回溯（数据只从新鲜节点多数派拉取，{@code fetchBlockForReorg}）；</li>
 *   <li>找到与本地规范链哈希一致的共同祖先 forkHeight；</li>
 *   <li>回滚：撤销 forkHeight 之上的全部规范区块、交易并冲正余额，位点回退到 forkHeight；</li>
 *   <li>重建：沿新链从低到高重新入账，位点逐块前进到新链尖；</li>
 *   <li>全程记录 {@link ReorgEvent} 流水；任何一步拿不到可采信数据 => REORG_UNRESOLVED，
 *       <b>原链原封不动</b>，绝不出现新旧数据混算。</li>
 * </ol>
 *
 * <p>并发安全：调用方（IngestionService）持有 {@link IndexStore} 的全局锁，本服务不再加锁。</p>
 */
@Service
public class ReorgService {

    private static final Logger log = LoggerFactory.getLogger(ReorgService.class);
    private static final int MAX_REORG_DEPTH = 64;

    private final IndexStore store;
    private final PositionManager positions;
    private final NodeDispatcher dispatcher;
    private final List<ReorgEvent> events = new ArrayList<>();

    public ReorgService(IndexStore store, PositionManager positions, NodeDispatcher dispatcher) {
        this.store = store;
        this.positions = positions;
        this.dispatcher = dispatcher;
    }

    /**
     * 处理断裂。{@code firstNewBlock} 是新链在断裂处（localTip+1）的区块，
     * 其 parentHash 与本地规范块不一致。
     */
    public void handleFork(Block firstNewBlock) {
        long oldTip = positions.current();
        log.warn("[reorg] parent-hash break detected at height={}: newHash={} newParent={} localParent={}",
                firstNewBlock.height(), firstNewBlock.hash(), firstNewBlock.parentHash(),
                store.blockAt(firstNewBlock.height()).map(Block::hash).orElse(null));

        // 1) 回溯共同祖先
        List<Block> incoming = new ArrayList<>();
        incoming.add(firstNewBlock);
        Block cursor = firstNewBlock;
        long forkHeight = -1L;
        List<NodeAttempt> fatalAttempts = List.of();

        for (int depth = 0; depth <= MAX_REORG_DEPTH; depth++) {
            long parentHeight = cursor.height() - 1;
            if (parentHeight < 0) {
                // 回溯到创世之前仍无一致点：无可信共同祖先
                unresolved(firstNewBlock, oldTip, "no common ancestor down to genesis", List.of());
                return;
            }
            var localOpt = store.blockAt(parentHeight);
            if (localOpt.isPresent() && localOpt.get().hash().equals(cursor.parentHash())) {
                forkHeight = parentHeight;
                break;
            }
            FetchResult r = dispatcher.fetchBlockForReorg(parentHeight, oldTip);
            if (r instanceof FetchResult.Failed f) {
                fatalAttempts = f.attempts();
                unresolved(firstNewBlock, oldTip,
                        "cannot fetch trusted block at height " + parentHeight + ": " + f.kind() + " " + f.reason(),
                        f.attempts());
                return;
            }
            Block parent = ((FetchResult.BlockFound) r).block();
            incoming.add(parent);
            cursor = parent;
        }

        if (forkHeight < 0) {
            unresolved(firstNewBlock, oldTip, "reorg depth exceeded " + MAX_REORG_DEPTH, fatalAttempts);
            return;
        }

        // incoming 是高 -> 低 的新链片段（forkHeight+1 .. firstNewBlock.height）
        List<Block> detached = store.removeBlocksAbove(forkHeight);
        positions.revertByReorg(forkHeight,
                "reorg common ancestor at height " + forkHeight + ", detaching " + detached.size() + " blocks");
        log.warn("[reorg] forkHeight={} detached={} {}", forkHeight, detached.size(),
                detached.stream().map(b -> b.height() + ":" + b.hash()).toList());

        // 2) 重建：低 -> 高
        List<String> attachedHashes = new ArrayList<>();
        for (int i = incoming.size() - 1; i >= 0; i--) {
            Block b = incoming.get(i);
            attachOne(b);
            attachedHashes.add(b.hash());
        }

        // 3) 收敛：沿新链继续拉到链尖，避免新链比旧链短时停在半截、查询暴露非最新状态
        long newTipExpected = probeNewChainHead();
        long guard = 0L;
        while (positions.current() < newTipExpected && guard++ < MAX_REORG_DEPTH) {
            long h = positions.current() + 1;
            FetchResult more = dispatcher.fetchBlockForReorg(h, oldTip);
            if (more instanceof FetchResult.Failed f) {
                // 已完成部分回滚但无法收敛到链尖：按未解决记录流水，保留已重建部分并交由上层重试
                ReorgEvent partial = new ReorgEvent(forkHeight,
                        detached.stream().map(Block::hash).toList(),
                        List.copyOf(attachedHashes), oldTip, positions.current(), true,
                        "partially reattached but cannot converge: " + f.kind() + " " + f.reason());
                events.add(partial);
                throw new IndexingException(FailureKind.REORG_UNRESOLVED,
                        "reorg reattached to height " + positions.current()
                                + " but cannot converge to new tip: " + f.kind() + " " + f.reason());
            }
            Block b = ((FetchResult.BlockFound) more).block();
            if (!b.parentHash().equals(store.blockAt(h - 1).map(Block::hash).orElse(null))) {
                ReorgEvent partial = new ReorgEvent(forkHeight,
                        detached.stream().map(Block::hash).toList(),
                        List.copyOf(attachedHashes), oldTip, positions.current(), true,
                        "another parent-hash break appeared at height " + h);
                events.add(partial);
                throw new IndexingException(FailureKind.REORG_UNRESOLVED,
                        "reorg rebuild saw another parent-hash break at height " + h);
            }
            attachOne(b);
            attachedHashes.add(b.hash());
        }

        long newTip = positions.current();
        ReorgEvent ev = new ReorgEvent(forkHeight,
                detached.stream().map(Block::hash).toList(),
                List.copyOf(attachedHashes), oldTip, newTip, false,
                "canonical chain switched to alternative branch");
        events.add(ev);
        log.warn("[reorg] CONVERGED forkHeight={} oldTip={} newTip={} attached={} detached={}",
                forkHeight, oldTip, newTip, attachedHashes,
                detached.stream().map(Block::hash).toList());
    }

    private void attachOne(Block b) {
        // 入账规则与 IngestionService 完全一致：putBlock + 逐笔幂等 credit，由位点前进收尾
        if (!store.blockExists(b.hash()) || store.blockAt(b.height()).isEmpty()) {
            store.putBlock(b);
        }
        for (var tx : b.transactions()) {
            store.credit(tx);
        }
        positions.advanceTo(b.height(), b.hash());
        log.info("[reorg] attached height={} hash={}", b.height(), b.hash());
    }

    /** 查询新鲜节点中的最高链头（STALE 节点不计），供重组收敛使用。 */
    private long probeNewChainHead() {
        return dispatcher.freshChainHead();
    }

    private void unresolved(Block firstNewBlock, long oldTip, String reason, List<NodeAttempt> attempts) {
        ReorgEvent ev = new ReorgEvent(-1L, List.of(), List.of(), oldTip, oldTip, true, reason);
        events.add(ev);
        log.error("[reorg] UNRESOLVED reason={} tip={} attempts={} (canonical chain untouched)",
                reason, oldTip, attempts.stream().map(a -> a.nodeId() + ":" + a.stage() + "=" + a.result()).toList());
        throw new IndexingException(FailureKind.REORG_UNRESOLVED,
                "reorg unresolved at height " + firstNewBlock.height() + ": " + reason);
    }

    public List<ReorgEvent> events() {
        return List.copyOf(events);
    }
}
