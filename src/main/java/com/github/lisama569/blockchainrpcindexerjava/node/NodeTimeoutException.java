package com.github.lisama569.blockchainrpcindexerjava.node;

/** 节点请求超时，属于可故障转移的故障类型。 */
public class NodeTimeoutException extends RuntimeException {
    public NodeTimeoutException(String message) {
        super(message);
    }
}
