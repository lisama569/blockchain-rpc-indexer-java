package com.github.lisama569.blockchainrpcindexerjava.index;

import com.github.lisama569.blockchainrpcindexerjava.model.Block;
import com.github.lisama569.blockchainrpcindexerjava.model.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 规范化链存储（内存实现）。
 *
 * <p>维护：链顶位点 checkpoint、按高度唯一的规范块、已入账交易哈希集合（幂等）、
 * 账户余额（由交易推导）。所有写操作走同一把写锁，保证并发/重复投递不重复计数。</p>
 */
@Component
public class IndexStore {

    private static final Logger log = LoggerFactory.getLogger(IndexStore.class);

    /** 链顶位点；尚无区块时为 -1。新块高度必须严格等于 checkpoint+1 且父哈希相连。 */
    private volatile long checkpoint = -1L;
    private final TreeMap<Long, Block> canonical = new TreeMap<>();
    private final Set<String> appliedTx = ConcurrentHashMap.newKeySet();
    private final Map<String, BigInteger> balances = new ConcurrentHashMap<>();

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public long checkpoint() {
        return checkpoint;
    }

    public String canonicalHashAt(long height) {
        lock.readLock().lock();
        try {
            Block b = canonical.get(height);
            return b == null ? null : b.hash();
        } finally {
            lock.readLock().unlock();
        }
    }

    public Block canonicalBlockAt(long height) {
        lock.readLock().lock();
        try {
            return canonical.get(height);
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean containsTx(String txHash) {
        return appliedTx.contains(txHash);
    }

    public long txCount() {
        return appliedTx.size();
    }

    public Map<String, BigInteger> balances() {
        lock.readLock().lock();
        try {
            Map<String, BigInteger> snapshot = new LinkedHashMap<>();
            new java.util.TreeSet<>(balances.keySet()).forEach(k -> {
                BigInteger v = balances.get(k);
                if (v.signum() != 0) {
                    snapshot.put(k, v);
                }
            });
            return snapshot;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 按高度区间升序返回交易明细（区间内保持块顺序与块内顺序）。 */
    public List<Map<String, Object>> queryTransactions(long fromHeight, long toHeight) {
        long upper = toHeight < 0 ? checkpoint : toHeight;
        lock.readLock().lock();
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            if (canonical.isEmpty() || fromHeight > upper || upper < canonical.firstKey()) {
                return rows;
            }
            long lo = Math.max(fromHeight, canonical.firstKey());
            for (Map.Entry<Long, Block> e : canonical.subMap(lo, true, upper, true).entrySet()) {
                Block b = e.getValue();
                for (Transaction tx : b.transactions()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("blockNumber", b.number());
                    row.put("blockHash", b.hash());
                    row.put("txHash", tx.hash());
                    row.put("from", tx.from());
                    row.put("to", tx.to());
                    row.put("value", tx.value());
                    rows.add(row);
                }
            }
            return rows;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 校验父哈希后追加规范块并幂等入账。
     * <ul>
     *   <li>同高度同哈希 = 重复投递，幂等返回 DUPLICATED；</li>
     *   <li>同高度不同哈希 = 冲突，拒绝（调用方应走重组）；</li>
     *   <li>高度不连续（位点回退/跳号）= 拒绝，绝不从中间继续；</li>
     *   <li>父哈希与链顶不符 = 拒绝 BAD_PARENT。</li>
     * </ul>
     */
    public AppendOutcome appendCanonical(Block block) {
        lock.writeLock().lock();
        try {
            long n = block.number();
            Block existing = canonical.get(n);
            if (existing != null) {
                if (existing.hash().equals(block.hash())) {
                    log.info("INDEX 重复投递已忽略: height={}, hash={}, checkpoint 保持={}", n, block.hash(), checkpoint);
                    return new AppendOutcome(false, true, "duplicate block at height " + n);
                }
                log.warn("INDEX 拒绝冲突块: height={} existing={} incoming={}", n, existing.hash(), block.hash());
                return new AppendOutcome(false, false,
                        "conflicting block at height " + n + ": canonical=" + existing.hash() + " incoming=" + block.hash());
            }

            long expected = checkpoint + 1;
            if (n != expected) {
                log.warn("INDEX 拒绝非连续位点: 期望 height={}, 实际 height={}, checkpoint={}（禁止回退/跳号）",
                        expected, n, checkpoint);
                return new AppendOutcome(false, false,
                        "checkpoint regression/gap: expected=" + expected + " actual=" + n + " checkpoint=" + checkpoint);
            }
            if (checkpoint >= 0) {
                String tipHash = canonical.get(checkpoint).hash();
                if (!tipHash.equals(block.parentHash())) {
                    log.warn("INDEX 拒绝父哈希断裂: height={}, incoming.parent={}, canonicalTip.height={} hash={}",
                            n, block.parentHash(), checkpoint, tipHash);
                    return new AppendOutcome(false, false,
                            "parent hash mismatch at height " + n + ": parent=" + block.parentHash()
                                    + " tip=" + tipHash);
                }
            }

            canonical.put(n, block);
            int applied = 0;
            int skipped = 0;
            for (Transaction tx : block.transactions()) {
                if (appliedTx.add(tx.hash())) {
                    credit(tx.to(), tx.value());
                    credit(tx.from(), tx.value().negate());
                    applied++;
                } else {
                    skipped++;
                    log.info("INDEX 交易重复入账已忽略: tx={} height={}", tx.hash(), n);
                }
            }
            checkpoint = n;
            log.info("INDEX 规范化入账: height={}, hash={}, parent={}, 新交易={}, 去重交易={}, checkpoint 推进={}",
                    n, block.hash(), block.parentHash(), applied, skipped, checkpoint);
            return new AppendOutcome(true, false, "accepted");
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 回滚并撤销所有 height &gt; heightExclusive 的规范化数据；余额/交易集合重放到保留高度。 */
    public long rollbackAbove(long heightExclusive) {
        lock.writeLock().lock();
        try {
            long oldCheckpoint = checkpoint;
            if (oldCheckpoint <= heightExclusive) {
                log.info("REORG 无需回滚: checkpoint={} <= forkPoint={}", oldCheckpoint, heightExclusive);
                return checkpoint;
            }
            // 自顶向下撤销规范块
            for (long h = oldCheckpoint; h > heightExclusive; h--) {
                Block removed = canonical.remove(h);
                if (removed != null) {
                    for (Transaction tx : removed.transactions()) {
                        appliedTx.remove(tx.hash());
                    }
                    log.info("REORG 撤销规范化块: height={}, hash={}, txs={}", h, removed.hash(),
                            removed.transactions().size());
                }
            }
            // 余额重建：清空后按保留链顺序重放，杜绝新旧混算
            balances.clear();
            for (Block b : canonical.values()) {
                for (Transaction tx : b.transactions()) {
                    if (appliedTx.contains(tx.hash())) {
                        credit(tx.to(), tx.value());
                        credit(tx.from(), tx.value().negate());
                    }
                }
            }
            checkpoint = heightExclusive;
            log.info("REORG 回滚完成: checkpoint {} -> {}，保留规范块 {} 个，已入账交易 {} 笔",
                    oldCheckpoint, checkpoint, canonical.size(), appliedTx.size());
            return checkpoint;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void credit(String account, BigInteger delta) {
        balances.merge(account, delta, BigInteger::add);
    }

    public record AppendOutcome(boolean accepted, boolean duplicated, String reason) {
    }
}
