package com.github.lisama569.blockchainrpcindexerjava.indexer;

import com.github.lisama569.blockchainrpcindexerjava.domain.FailureKind;

/**
 * 索引流程业务异常，携带可区分的失败分类。
 */
public class IndexingException extends RuntimeException {

    private final FailureKind kind;

    public IndexingException(FailureKind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public FailureKind kind() {
        return kind;
    }
}
