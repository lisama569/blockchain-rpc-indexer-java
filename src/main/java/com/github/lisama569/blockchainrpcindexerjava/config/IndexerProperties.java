package com.github.lisama569.blockchainrpcindexerjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 索引器配置项（前缀 indexer）。
 *
 * @param maxStaleHeight   节点链头落后主源超过该高度即判定 STALE
 * @param requestTimeoutMs 单次节点请求超时阈值
 * @param startHeight      冷启动起始位点（空库时从该高度开始索引）
 */
@ConfigurationProperties(prefix = "indexer")
public record IndexerProperties(long maxStaleHeight, long requestTimeoutMs, long startHeight) {
}
