package com.github.lisama569.blockchainrpcindexerjava.node;

public class NodeBusinessException extends NodeException {
    private final String code;

    public NodeBusinessException(String nodeId, String code, String message) {
        super("node " + nodeId + " business error [" + code + "]: " + message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    @Override
    public boolean timeout() {
        return false;
    }
}
