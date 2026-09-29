package com.github.lisama569.blockchainrpcindexerjava.node;

import com.github.lisama569.blockchainrpcindexerjava.domain.Block;

/**
 * 节点来源抽象。真实实现可以是 JSON-RPC/HTTP 客户端；
 * 本工程默认使用 {@link com.github.lisama569.blockchainrpcindexerjava.simulation.SimulatedNode}。
 */
public interface NodeClient {

    /** 节点标识，用于流水与故障定位 */
    String nodeId();

    /** 节点当前链头高度（模拟调用，可能抛超时/业务异常） */
    long chainHeadHeight() throws NodeException;

    /** 按高度拉取区块；不存在时返回 null */
    Block blockByHeight(long height) throws NodeException;
}
