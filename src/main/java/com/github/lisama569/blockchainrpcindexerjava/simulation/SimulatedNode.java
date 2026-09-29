package com.github.lisama569.blockchainrpcindexerjava.simulation;

import com.github.lisama569.blockchainrpcindexerjava.domain.Block;
import com.github.lisama569.blockchainrpcindexerjava.node.NodeBusinessException;
import com.github.lisama569.blockchainrpcindexerjava.node.NodeClient;
import com.github.lisama569.blockchainrpcindexerjava.node.NodeException;
import com.github.lisama569.blockchainrpcindexerjava.node.NodeTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 内存模拟节点。
 *
 * <p>响应规则（由 {@link SimulatedNodeBehavior} 控制，可在运行时注入/解除故障）：</p>
 * <ul>
 *   <li>{@code timeout=true} 或延迟超过超时阈值：抛 {@link NodeTimeoutException}；</li>
 *   <li>{@code businessError=true}：抛 {@link NodeBusinessException}（带错误码，可与超时区分）；</li>
 *   <li>{@code headOverride>=0}：返回明显落后的链头与老视图（STALE 节点）；</li>
 *   <li>{@code useAltChain=true}：优先返回 alt 分叉视图，模拟与主源不同的链；</li>
 *   <li>其他：返回规范链最新视图。</li>
 * </ul>
 */
public class SimulatedNode implements NodeClient {

    private static final Logger log = LoggerFactory.getLogger(SimulatedNode.class);

    private final String id;
    private final SimulatedChain chain;
    private volatile SimulatedNodeBehavior behavior;
    private final long latencyMillis;
    private final long timeoutMillis;

    public SimulatedNode(String nodeId, SimulatedChain chain, SimulatedNodeBehavior behavior,
                        long latencyMillis, long timeoutMillis) {
        this.id = nodeId;
        this.chain = chain;
        this.behavior = behavior;
        this.latencyMillis = latencyMillis;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public String nodeId() {
        return id;
    }

    @Override
    public long chainHeadHeight() throws NodeException {
        sleepLatency();
        if (behavior.timeout()) {
            throw new NodeTimeoutException(id, timeoutMillis);
        }
        if (behavior.businessError()) {
            throw new NodeBusinessException(id, "RPC_UPSTREAM_ERROR", "chainHeadHeight rejected by node");
        }
        long head = behavior.headOverride() >= 0 ? behavior.headOverride()
                : (behavior.useAltChain() && chain.altHead() >= 0 ? chain.altHead() : chain.canonicalHead());
        log.info("[node-response] node={} stage=HEAD head={}", id, head);
        return head;
    }

    @Override
    public Block blockByHeight(long height) throws NodeException {
        sleepLatency();
        if (behavior.timeout()) {
            throw new NodeTimeoutException(id, timeoutMillis);
        }
        if (behavior.businessError()) {
            throw new NodeBusinessException(id, "RPC_BLOCK_UNAVAILABLE", "block " + height + " rejected by node");
        }
        long head = behavior.headOverride() >= 0 ? behavior.headOverride() : chain.canonicalHead();
        if (height > head) {
            log.info("[node-response] node={} stage=BLOCK height={} -> NOT_FOUND(head={})", id, height, head);
            return null;
        }
        Block block = null;
        if (behavior.useAltChain()) {
            block = chain.altAt(height);
        }
        if (block == null) {
            block = chain.canonicalAt(height);
        }
        log.info("[node-response] node={} stage=BLOCK height={} hash={} parent={} txs={}",
                id, height, block == null ? null : block.hash(), block == null ? null : block.parentHash(),
                block == null ? 0 : block.transactions().size());
        return block;
    }

    public void updateBehavior(SimulatedNodeBehavior next) {
        log.info("[node-behavior] node={} -> {}", id, next);
        this.behavior = next;
    }

    private void sleepLatency() throws NodeTimeoutException {
        if (latencyMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(latencyMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NodeTimeoutException(id, e);
        }
        if (latencyMillis >= timeoutMillis) {
            throw new NodeTimeoutException(id, timeoutMillis);
        }
    }
}
