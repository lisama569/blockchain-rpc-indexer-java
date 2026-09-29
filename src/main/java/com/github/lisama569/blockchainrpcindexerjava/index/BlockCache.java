package com.github.lisama569.blockchainrpcindexerjava.index;

import com.github.lisama569.blockchainrpcindexerjava.model.Block;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 按区块哈希缓存完整区块；重组回滚时用于重新入账，内存实现。 */
@Component
public class BlockCache {

    private final Map<String, Block> byHash = new ConcurrentHashMap<>();

    public void put(Block block) {
        byHash.putIfAbsent(block.hash(), block);
    }

    public Optional<Block> get(String hash) {
        return Optional.ofNullable(byHash.get(hash));
    }

    public int size() {
        return byHash.size();
    }
}
