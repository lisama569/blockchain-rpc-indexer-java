package com.github.lisama569.blockchainrpcindexerjava.node;

/** 节点返回业务错误（错误码/错误响应），与超时、落后明确区分。 */
public class NodeBusinessException extends RuntimeException {
    public NodeBusinessException(String message) {
        super(message);
    }
}
