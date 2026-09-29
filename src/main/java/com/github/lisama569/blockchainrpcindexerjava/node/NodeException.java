package com.github.lisama569.blockchainrpcindexerjava.node;

/**
 * 节点接入异常的共同父类，用具体子类区分失败原因。
 */
public abstract class NodeException extends Exception {
    protected NodeException(String message) {
        super(message);
    }

    protected NodeException(String message, Throwable cause) {
        super(message, cause);
    }

    public abstract boolean timeout();
}
