package com.github.lisama569.blockchainrpcindexerjava.node;

import com.github.lisama569.blockchainrpcindexerjava.model.Block;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 可编排行为的模拟节点（模拟 JSON-RPC 数据源）。
 *
 * <p>支持注入：超时、业务错误、固定落后高度（lag）、延迟，以及任意一条分叉链，
 * 全部行为在内存中完成，不依赖任何真实节点或外部服务。</p>
 */
public class MockChainNode implements ChainNodeClient {

    private static final Logger log = LoggerFactory.getLogger(MockChainNode.class);

    private final String id;
    private final int priority;
    private volatile boolean enabled = true;

    private List<Block> chain = new ArrayList<>();
    /** 对外宣称的链顶 = 本地区块链顶 - headLag，用于模拟明显落后的节点。 */
    private volatile long headLag = 0L;
    private volatile boolean timeout = false;
    private volatile boolean businessError = false;
    private volatile long latencyMs = 0L;

    public MockChainNode(String id, int priority) {
        this.id = Objects.requireNonNull(id);
        this.priority = priority;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public long getBlockNumber() {
        sleepIfNeeded();
        if (timeout) {
            log.info("[node:{}] << eth_blockNumber 超时", id);
            throw new NodeTimeoutException("node " + id + " eth_blockNumber timeout");
        }
        if (businessError) {
            log.info("[node:{}] << eth_blockNumber 业务错误", id);
            throw new NodeBusinessException("node " + id + " eth_blockNumber business error");
        }
        long reported = localHead() - headLag;
        log.info("[node:{}] << eth_blockNumber => {} (本地链顶 {}, lag={})", id, reported, localHead(), headLag);
        return reported;
    }

    @Override
    public Block getBlockByNumber(long number) {
        sleepIfNeeded();
        if (timeout) {
            log.info("[node:{}] << eth_getBlockByNumber({}) 超时", id, number);
            throw new NodeTimeoutException("node " + id + " getBlockByNumber(" + number + ") timeout");
        }
        if (businessError) {
            log.info("[node:{}] << eth_getBlockByNumber({}) 业务错误", id, number);
            throw new NodeBusinessException("node " + id + " getBlockByNumber(" + number + ") business error");
        }
        long head = localHead() - headLag;
        if (number > head) {
            log.info("[node:{}] << eth_getBlockByNumber({}) 业务错误: 区块不存在(节点链顶={})", id, number, head);
            throw new NodeBusinessException("node " + id + " block " + number + " not found, head=" + head);
        }
        Block block = findBlock(number);
        if (block == null) {
            log.info("[node:{}] << eth_getBlockByNumber({}) 业务错误: 区块缺失", id, number);
            throw new NodeBusinessException("node " + id + " block " + number + " missing");
        }
        log.info("[node:{}] << eth_getBlockByNumber({}) => hash={}, parent={}, txs={}",
                id, number, block.hash(), block.parentHash(), block.transactions().size());
        return block;
    }

    private Block findBlock(long number) {
        // chain 按 number 连续递增存放，支持从任意起始高度开始
        if (chain.isEmpty()) {
            return null;
        }
        long base = chain.get(0).number();
        int idx = (int) (number - base);
        if (idx < 0 || idx >= chain.size()) {
            return null;
        }
        return chain.get(idx);
    }

    private long localHead() {
        return chain.isEmpty() ? -1L : chain.get(chain.size() - 1).number();
    }

    private void sleepIfNeeded() {
        if (latencyMs > 0) {
            try {
                Thread.sleep(latencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NodeTimeoutException("node " + id + " interrupted");
            }
        }
    }

    // ---- 测试编排方法 ----

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setChain(List<Block> chain) {
        this.chain = new ArrayList<>(chain);
    }

    /** 在链尾追加一个区块（模拟出块）。 */
    public void appendBlock(Block block) {
        this.chain.add(block);
    }

    public void setHeadLag(long lag) {
        this.headLag = Math.max(0L, lag);
    }

    public void setTimeout(boolean timeout) {
        this.timeout = timeout;
    }

    public void setBusinessError(boolean businessError) {
        this.businessError = businessError;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }
}
