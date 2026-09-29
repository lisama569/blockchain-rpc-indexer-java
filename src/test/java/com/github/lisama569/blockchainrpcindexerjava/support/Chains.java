package com.github.lisama569.blockchainrpcindexerjava.support;

import com.github.lisama569.blockchainrpcindexerjava.model.Block;
import com.github.lisama569.blockchainrpcindexerjava.model.Transaction;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** 构造内存模拟链：线性链、分叉块，交易由 from/to/金额 直接给出。 */
public final class Chains {

    private Chains() {
    }

    public static Transaction tx(String hash, String from, String to, long value) {
        return new Transaction(hash, null, 0L, from, to, BigInteger.valueOf(value));
    }

    public static Block block(long number, String hash, String parentHash, List<Transaction> txs) {
        List<Transaction> bound = txs.stream()
                .map(t -> new Transaction(t.hash(), hash, number, t.from(), t.to(), t.value()))
                .toList();
        return new Block(number, hash, parentHash, number * 1000L, bound);
    }

    public static Block genesis(List<Transaction> txs) {
        return block(0L, "h0", "0x0", txs);
    }

    /** 在 prev 之后接一个新区块。 */
    public static Block next(Block prev, String hash, List<Transaction> txs) {
        return block(prev.number() + 1, hash, prev.hash(), txs);
    }

    /** 复制链并在尾部追加区块（模拟同一节点出块）。 */
    public static List<Block> extend(List<Block> chain, String hash, List<Transaction> txs) {
        List<Block> copy = new ArrayList<>(chain);
        copy.add(next(copy.get(copy.size() - 1), hash, txs));
        return copy;
    }

    /**
     * 以 base 链在 forkHeight-1 处的块为共同祖先，构造分叉子链。
     * altHashes/altTxs 按高度从 forkHeight 开始依次给出。
     */
    public static List<Block> fork(List<Block> base, long forkHeight,
                                   List<String> altHashes, List<List<Transaction>> altTxs) {
        List<Block> alt = new ArrayList<>(base.subList(0, (int) forkHeight));
        for (int i = 0; i < altHashes.size(); i++) {
            long number = forkHeight + i;
            String parent = number == 0 ? "0x0" : alt.get(alt.size() - 1).hash();
            alt.add(block(number, altHashes.get(i), parent, altTxs.get(i)));
        }
        return alt;
    }
}
