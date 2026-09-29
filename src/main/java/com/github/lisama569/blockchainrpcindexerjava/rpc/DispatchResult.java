package com.github.lisama569.blockchainrpcindexerjava.rpc;

import java.util.List;

/** 一次 RPC 分发的可解释结果：要么成功，要么带明确的终结原因。 */
public record DispatchResult<T>(
        boolean success,
        T value,
        String servedBy,
        FailureReason failureReason,
        String message,
        List<Attempt> attempts) {

    public record Attempt(String nodeId, String outcome, String detail) {
    }

    public static <T> DispatchResult<T> ok(T value, String servedBy, List<Attempt> attempts) {
        return new DispatchResult<>(true, value, servedBy, null, null, attempts);
    }

    public static <T> DispatchResult<T> failed(FailureReason reason, String message, List<Attempt> attempts) {
        return new DispatchResult<>(false, null, null, reason, message, attempts);
    }
}
