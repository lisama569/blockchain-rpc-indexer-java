package com.github.lisama569.blockchainrpcindexerjava.rpc;

/** RPC 故障转移后无可用节点时的终结原因（三类可区分结论）。 */
public enum FailureReason {
    ALL_TIMEOUT,
    ALL_BUSINESS_ERROR,
    ALL_STALE,
    NO_ENABLED_NODE,
    MIXED
}
