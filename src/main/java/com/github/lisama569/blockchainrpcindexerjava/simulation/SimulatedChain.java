package com.github.lisama569.blockchainrpcindexerjava.simulation;

import com.github.lisama569.blockchainrpcindexerjava.domain.Block;
import com.github.lisama569.blockchainrpcindexerjava.domain.Transaction;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * 模拟链：完全内存化、确定性生成区块与交易，测试全程不接触真实节点。
 *
 * <p>内部维护两张高度表：</p>
 * <ul>
 *   <li>canonical：规范链（从高度 0 创世块开始连续）；</li>
 *   <li>alt：备选分叉链，只保存分叉点以上（高度 &gt; forkHeight）的区块。</li>
 * </ul>
 *
 * <p>{@link #promoteAltFrom(long)} 把备选链提升为规范链，模拟真实链重组。</p>
 */
public class SimulatedChain {

    private static final String GENESIS_PARENT = "0x0";
    private static final int DEFAULT_TX_PER_BLOCK = 2;

    private final TreeMap<Long, Block> canonical = new TreeMap<>();
    private final TreeMap<Long, Block> alt = new TreeMap<>();

    /** 构造一条从创世块到 tipHeight（含）的确定性规范链，每个区块 2 笔交易。 */
    public static SimulatedChain create(long tipHeight) {
        SimulatedChain chain = new SimulatedChain();
        String parent = GENESIS_PARENT;
        for (long h = 0; h <= tipHeight; h++) {
            Block b = makeBlock(h, "H", parent, DEFAULT_TX_PER_BLOCK);
            chain.canonical.put(h, b);
            parent = b.hash();
        }
        return chain;
    }

    /** 在规范链尾部追加一个新区块（每块 2 笔交易），返回新区块。 */
    public Block appendCanonical() {
        long h = canonical.lastKey() + 1;
        Block b = makeBlock(h, "H", canonical.get(h - 1).hash(), DEFAULT_TX_PER_BLOCK);
        canonical.put(h, b);
        return b;
    }

    /**
     * 在高度 height 制造一个备选分叉区块（哈希前缀 alt，明显区别于规范链）。
     *
     * @param height     分叉区块高度（必须 &gt; 分叉点）
     * @param parentHash 父哈希（分叉点处传规范块哈希，更高处分两种：alt 块哈希或 null=自动取上一 alt/规范块）
     */
    public Block makeAltBlock(long height, String parentHash) {
        String parent = parentHash;
        if (parent == null) {
            Block prevAlt = alt.get(height - 1);
            parent = prevAlt != null ? prevAlt.hash() : canonical.get(height - 1).hash();
        }
        Block b = makeBlock(height, "altH", parent, DEFAULT_TX_PER_BLOCK);
        return b;
    }

    public void extendAlt(Block block) {
        alt.put(block.height(), block);
    }

    /** 将 alt 链从 forkHeight+1 起整体替换 canonical，模拟链重组。 */
    public void promoteAltFrom(long forkHeight) {
        for (long h = forkHeight + 1; h <= canonical.lastKey() || alt.containsKey(h); h++) {
            Block altBlock = alt.get(h);
            if (altBlock == null) {
                if (h > forkHeight) {
                    canonical.remove(h);
                }
                if (!alt.isEmpty() && h > alt.lastKey()) {
                    break;
                }
                if (alt.isEmpty()) {
                    break;
                }
            } else {
                canonical.put(h, altBlock);
            }
            if (h > 100_000L) {
                throw new IllegalStateException("alt promotion guard");
            }
        }
        alt.clear();
    }

    public List<Block> canonical() {
        return List.copyOf(canonical.values());
    }

    public List<Block> alt() {
        return List.copyOf(alt.values());
    }

    public Block canonicalAt(long height) {
        return canonical.get(height);
    }

    public Block altAt(long height) {
        return alt.get(height);
    }

    public long canonicalHead() {
        return canonical.isEmpty() ? -1L : canonical.lastKey();
    }

    public long altHead() {
        return alt.isEmpty() ? -1L : alt.lastKey();
    }

    // ------------------------------------------------------------------

    /** 确定性造块：哈希、交易哈希、账户、金额全部可复算，便于测试断言。 */
    public static Block makeBlock(long height, String tag, String parentHash, int txCount) {
        List<Transaction> txs = new ArrayList<>(txCount);
        for (int i = 0; i < txCount; i++) {
            String from = "A" + Math.floorMod(height * 7L + i, 5);
            String to = "A" + Math.floorMod(height * 7L + i + 1, 5);
            BigInteger amount = BigInteger.valueOf(10L * (height + 1) + i);
            txs.add(new Transaction(tag + "-tx-" + height + "-" + i, height, from, to, amount));
        }
        return new Block(height, tag + (height), parentHash, List.copyOf(txs));
    }
}
