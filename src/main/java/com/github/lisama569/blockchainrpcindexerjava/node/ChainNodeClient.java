package com.github.lisama569.blockchainrpcindexerjava.node;

import com.github.lisama569.blockchainrpcindexerjava.model.Block;

/** 链节点客户端：模拟 JSON-RPC（eth_blockNumber / eth_getBlockByNumber）。 */
public interface ChainNodeClient {

    String id();

    int priority();

    boolean enabled();

    long getBlockNumber();

    Block getBlockByNumber(long number);
}
