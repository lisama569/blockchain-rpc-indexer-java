package com.github.lisama569.blockchainrpcindexerjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 索引器配置项（application.properties: indexer.*）。 */
@ConfigurationProperties(prefix = "indexer")
public class IndexerProperties {

    private boolean enabled = false;
    private long pollingIntervalMs = 500L;
    private long maxStaleLag = 2L;
    private String pinnedNodeId = "";
    private long rpcTimeoutMs = 1000L;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getPollingIntervalMs() { return pollingIntervalMs; }
    public void setPollingIntervalMs(long pollingIntervalMs) { this.pollingIntervalMs = pollingIntervalMs; }
    public long getMaxStaleLag() { return maxStaleLag; }
    public void setMaxStaleLag(long maxStaleLag) { this.maxStaleLag = maxStaleLag; }
    public String getPinnedNodeId() { return pinnedNodeId; }
    public void setPinnedNodeId(String pinnedNodeId) { this.pinnedNodeId = pinnedNodeId; }
    public long getRpcTimeoutMs() { return rpcTimeoutMs; }
    public void setRpcTimeoutMs(long rpcTimeoutMs) { this.rpcTimeoutMs = rpcTimeoutMs; }
}
