package com.github.lisama569.blockchainrpcindexerjava.domain;

/**
 * 接入层失败结论分类。结论必须可区分，不允许以“成功”掩盖可疑节点。
 */
public enum FailureKind {
    /** 请求节点超时（网络不可达 / 模拟延迟超过阈值） */
    NODE_TIMEOUT,
    /** 节点可达但返回业务错误（错误码 / 5xx / 明确拒绝） */
    NODE_BUSINESS_ERROR,
    /** 节点数据明显落后（链头落后本地位点或落后主源超过容忍高度） */
    NODE_STALE,
    /** 多个新鲜节点在同一高度返回不同哈希，数据分歧，不敢采信 */
    NODE_DIVERGENT,
    /** 全部节点不可用（超时 / 业务错误，没有任何新鲜来源可服务） */
    ALL_NODES_UNAVAILABLE,
    /** 新鲜节点均确认该高度区块不存在 */
    BLOCK_NOT_FOUND,
    /** 位点回退被明确拒绝（回退只能走重组回滚流程） */
    POSITION_ROLLBACK_REJECTED,
    /** 重组期间无法沿父哈希回溯到共同祖先，链状态保持原状不做半套切换 */
    REORG_UNRESOLVED,
    /** 其他内部错误 */
    INTERNAL_ERROR
}
