package com.github.lisama569.blockchainrpcindexerjava.api;

import com.github.lisama569.blockchainrpcindexerjava.domain.FailureKind;
import com.github.lisama569.blockchainrpcindexerjava.indexer.IndexingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把索引层的可区分失败结论映射为可区分的 HTTP 状态，绝不以 200 掩盖失败。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IndexingException.class)
    public ResponseEntity<Map<String, Object>> handle(IndexingException ex) {
        FailureKind kind = ex.kind();
        HttpStatus http = switch (kind) {
            case POSITION_ROLLBACK_REJECTED -> HttpStatus.CONFLICT;          // 409
            case REORG_UNRESOLVED, NODE_TIMEOUT, NODE_BUSINESS_ERROR,
                 NODE_STALE, NODE_DIVERGENT, ALL_NODES_UNAVAILABLE -> HttpStatus.BAD_GATEWAY; // 502
            case BLOCK_NOT_FOUND -> HttpStatus.NOT_FOUND;                       // 404
            default -> HttpStatus.INTERNAL_SERVER_ERROR;                            // 500
        };
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outcome", "FAILED");
        body.put("failureKind", kind.name());
        body.put("reason", ex.getMessage());
        return ResponseEntity.status(http).body(body);
    }
}
