package com.github.lisama569.blockchainrpcindexerjava.support;

import com.github.lisama569.blockchainrpcindexerjava.config.IndexerProperties;
import com.github.lisama569.blockchainrpcindexerjava.index.BlockCache;
import com.github.lisama569.blockchainrpcindexerjava.index.IndexStore;
import com.github.lisama569.blockchainrpcindexerjava.indexer.BlockIngestor;
import com.github.lisama569.blockchainrpcindexerjava.indexer.BlockPoller;


import com.github.lisama569.blockchainrpcindexerjava.node.MockChainNode;
import com.github.lisama569.blockchainrpcindexerjava.node.NodeRegistry;
import com.github.lisama569.blockchainrpcindexerjava.reorg.ReorgService;
import com.github.lisama569.blockchainrpcindexerjava.rpc.RpcDispatcher;

/** 纯内存手工装配整套组件（不启动 Spring）。 */
public record Harness(NodeRegistry registry,
                      IndexerProperties props,
                      RpcDispatcher dispatcher,
                      IndexStore store,
                      BlockCache cache,
                      ReorgService reorgService,
                      BlockIngestor ingestor,
                      BlockPoller poller) {

    public static Harness create(long maxStaleLag) {
        NodeRegistry registry = new NodeRegistry();
        IndexerProperties props = new IndexerProperties();
        props.setMaxStaleLag(maxStaleLag);
        RpcDispatcher dispatcher = new RpcDispatcher(registry, props);
        IndexStore store = new IndexStore();
        BlockCache cache = new BlockCache();
        ReorgService reorg = new ReorgService(store, cache, dispatcher, registry);
        BlockIngestor ingestor = new BlockIngestor(store, cache, reorg);
        BlockPoller poller = new BlockPoller(dispatcher, ingestor, store, cache, registry, props);
        return new Harness(registry, props, dispatcher, store, cache, reorg, ingestor, poller);
    }

    public MockChainNode register(String id, int priority, java.util.List<com.github.lisama569.blockchainrpcindexerjava.model.Block> chain) {
        MockChainNode node = new MockChainNode(id, priority);
        node.setChain(chain);
        registry.register(node);
        return node;
    }
}
