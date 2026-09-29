package com.github.lisama569.blockchainrpcindexerjava.simulation;

import com.github.lisama569.blockchainrpcindexerjava.config.IndexerProperties;
import com.github.lisama569.blockchainrpcindexerjava.node.NodeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 默认装配：一条内存模拟链 + 两个视图一致的模拟节点（primary / replica），
 * 开箱即可演示，不依赖任何真实节点或外部服务。
 *
 * <p>测试通过自己的 {@code @Bean} 声明覆盖 {@link SimulatedChain} / {@code List<NodeClient>}。</p>
 */
@Configuration
public class SimulationConfig {

    private static final Logger log = LoggerFactory.getLogger(SimulationConfig.class);

    @Bean
    @ConditionalOnMissingBean
    public SimulatedChain simulatedChain() {
        // 默认链：高度 0..7，主备两个节点看到完全一致的视图
        SimulatedChain chain = SimulatedChain.create(7);
        log.info("[sim] bootstrapped simulated canonical chain tip=7 (8 blocks)");
        return chain;
    }

    @Bean
    @ConditionalOnMissingBean
    public List<NodeClient> simulatedNodes(SimulatedChain chain, IndexerProperties props) {
        long timeout = props.requestTimeoutMs() > 0 ? props.requestTimeoutMs() : 1000L;
        NodeClient primary = new SimulatedNode("node-primary", chain, SimulatedNodeBehavior.normal(), 0L, timeout);
        NodeClient replica = new SimulatedNode("node-replica", chain, SimulatedNodeBehavior.normal(), 0L, timeout);
        log.info("[sim] registered simulated nodes: node-primary, node-replica timeoutMs={}", timeout);
        return List.of(primary, replica);
    }
}
