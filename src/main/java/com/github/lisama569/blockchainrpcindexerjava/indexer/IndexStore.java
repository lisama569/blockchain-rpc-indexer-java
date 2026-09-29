package com.github.lisama569.blockchainrpcindexerjava.indexer;

import com.github.lisama569.blockchainrpcindexerjava.domain.AccountBalance;
import com.github.lisama569.blockchainrpcindexerjava.domain.Block;
import com.github.lisama569.blockchainrpcindexerjava.domain.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 规范化索引存储（内存实现）。
 *
 * <p>一致性保证：</p>
 * <ul>
 *   <li>所有结构读写经过同一把 {@link ReentrantLock} 串行化，并发/重复投递不会交错入账；</li>
 *   <li>区块以 hash 去重、交易以 txHash 去重：重复投递同一块/同一交易绝不重复计数；</li>
 *   <li>区块按高度连续存放，重组回滚用 {@link #removeBlocksAbove(long)} 冲正并撤销；</li>
 *   <li>余额仅为规范化数据的投影，回滚时按交易原金额反向冲正。</li>
 * </ul>
 */
@Component
public class IndexStore {

    private static final Logger log = LoggerFactory.getLogger(IndexStore.class);

    private final ReentrantLock lock = new ReentrantLock();
    private final TreeMap<Long, Block> blocksByHeight = new TreeMap<>();
    private final ConcurrentMap<String, Long> blockHashToHeight = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Transaction> txByHash = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, BigInteger> balances = new ConcurrentHashMap<>();

    public void lock() {
        lock.lock();
    }

    public void unlock() {
        lock.unlock();
    }

    public boolean blockExists(String hash) {
        return blockHashToHeight.containsKey(hash);
    }

    public boolean txExists(String txHash) {
        return txByHash.containsKey(txHash);
    }

    /** 存区块元数据（不记余额；余额通过 {@link #credit(Transaction)} 入账，保证可单独冲正）。 */
    public void putBlock(Block block) {
        blocksByHeight.put(block.height(), block);
        blockHashToHeight.put(block.hash(), block.height());
        log.info("[store] putBlock height={} hash={} parent={} txs={}",
                block.height(), block.hash(), block.parentHash(), block.transactions().size());
    }

    public void putTx(Transaction tx) {
        txByHash.put(tx.txHash(), tx);
    }

    public Optional<Block> blockAt(long height) {
        return Optional.ofNullable(blocksByHeight.get(height));
    }

    public Optional<Block> blockByHash(String hash) {
        Long h = blockHashToHeight.get(hash);
        return h == null ? Optional.empty() : Optional.ofNullable(blocksByHeight.get(h));
    }

    public long tipHeight() {
        return blocksByHeight.isEmpty() ? -1L : blocksByHeight.lastKey();
    }

    /** 入账一笔交易：from 减、to 加。txHash 已存在则直接忽略（幂等）。 */
    public void credit(Transaction tx) {
        if (txByHash.putIfAbsent(tx.txHash(), tx) != null) {
            log.warn("[store] duplicate tx ignored on credit: {}", tx.txHash());
            return;
        }
        balances.merge(tx.from(), tx.amount().negate(), BigInteger::add);
        balances.merge(tx.to(), tx.amount(), BigInteger::add);
        log.info("[store] credit tx={} height={} {} -{} -> +{} to {}",
                tx.txHash(), tx.height(), tx.from(), tx.amount(), tx.amount(), tx.to());
    }

    /** 冲正一笔交易：与 {@link #credit(Transaction)} 完全反向。 */
    public void debit(Transaction tx) {
        if (txByHash.remove(tx.txHash()) == null) {
            log.warn("[store] tx already absent on debit: {}", tx.txHash());
            return;
        }
        balances.merge(tx.from(), tx.amount(), BigInteger::add);
        balances.merge(tx.to(), tx.amount().negate(), BigInteger::add);
        log.info("[store] debit(reverse) tx={} height={} +{} back to {} , -{} from {}",
                tx.txHash(), tx.height(), tx.amount(), tx.from(), tx.amount(), tx.to());
    }

    /**
     * 撤销高度 &gt; aboveHeight 的全部规范化数据（重组回滚专用）。
     * 先逐笔反向冲正余额，再删区块索引。返回被撤销区块（高 -&gt; 低，便于沿父哈希回放日志）。
     */
    public List<Block> removeBlocksAbove(long aboveHeight) {
        List<Block> detached = new ArrayList<>();
        for (Long h : new ArrayList<>(blocksByHeight.navigableKeySet().tailSet(aboveHeight + 1L, true))) {
            Block b = blocksByHeight.get(h);
            detached.add(b);
            for (int i = b.transactions().size() - 1; i >= 0; i--) {
                debit(b.transactions().get(i));
            }
            blocksByHeight.remove(h);
            blockHashToHeight.remove(b.hash());
            log.info("[store] detachBlock height={} hash={}", h, b.hash());
        }
        return detached;
    }

    public AccountBalance balance(String account) {
        return new AccountBalance(account, balances.getOrDefault(account, BigInteger.ZERO));
    }

    public List<AccountBalance> allBalances() {
        List<AccountBalance> list = new ArrayList<>();
        balances.entrySet().stream()
                .map(e -> new AccountBalance(e.getKey(), e.getValue()))
                .sorted((a, b) -> a.account().compareTo(b.account()))
                .forEach(list::add);
        return list;
    }

    public List<Block> blocksAscending() {
        return List.copyOf(blocksByHeight.values());
    }

    public List<Transaction> transactionsAscending() {
        return txByHash.values().stream()
                .sorted((a, b) -> a.height() == b.height()
                        ? a.txHash().compareTo(b.txHash())
                        : Long.compare(a.height(), b.height()))
                .toList();
    }
}
