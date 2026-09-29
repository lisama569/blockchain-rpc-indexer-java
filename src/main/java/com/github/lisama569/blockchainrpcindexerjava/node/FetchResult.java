package com.github.lisama569.blockchainrpcindexerjava.node;

import com.github.lisama569.blockchainrpcindexerjava.domain.Block;
import com.github.lisama569.blockchainrpcindexerjava.domain.FailureKind;

import java.util.List;

/**
 * 分发结果：要么拿到可采信的区块，要么给出可区分的失败结论。
 * 绝不允许携带“来自可疑节点的数据”却标记为成功。
 */
public sealed interface FetchResult {

    /** 成功：block 来自 fresh 且与多数派一致的节点 */
    record BlockFound(Block block, String servedBy, List<NodeAttempt> attempts) implements FetchResult {
    }

    /** 失败：kind 给出分类，attempts 给出全部节点尝试流水 */
    record Failed(FailureKind kind, String reason, List<NodeAttempt> attempts) implements FetchResult {
    }
}
