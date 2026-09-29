package com.github.lisama569.blockchainrpcindexerjava.config;

import com.github.lisama569.blockchainrpcindexerjava.node.NodeRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 索引器装配。 */
@Configuration
@EnableConfigurationProperties(IndexerProperties.class)
public class IndexerConfig {

    @Bean
    public NodeRegistry nodeRegistry() {
        return new NodeRegistry();
    }
}
