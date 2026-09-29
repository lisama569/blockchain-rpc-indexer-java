package com.github.lisama569.blockchainrpcindexerjava.node;

public class NodeTimeoutException extends NodeException {
    public NodeTimeoutException(String nodeId, long timeoutMillis) {
        super("node " + nodeId + " request timeout after " + timeoutMillis + "ms");
    }

    public NodeTimeoutException(String nodeId, Throwable cause) {
        super("node " + nodeId + " timeout: " + cause.getMessage(), cause);
    }

    @Override
    public boolean timeout() {
        return true;
    }
}
