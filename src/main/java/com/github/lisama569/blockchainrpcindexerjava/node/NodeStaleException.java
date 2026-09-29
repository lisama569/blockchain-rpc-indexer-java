package com.github.lisama569.blockchainrpcindexerjava.node;

/** 节点数据明显落后，拒绝使用其数据，不得静默采信。 */
public class NodeStaleException extends RuntimeException {
    public NodeStaleException(String message) {
        super(message);
    }
}
