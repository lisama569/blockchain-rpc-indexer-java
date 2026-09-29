package com.github.lisama569.blockchainrpcindexerjava.indexer;

import java.util.List;

/**
 * 重组流水事件（同时输出日志并保留在内存中供查询/测试断言）。
 *
 * @param forkHeight      共同祖先高度
 * @param detachedHashes  被撤销的旧链区块哈希（高 -> 低）
 * @param attachedHashes  新链接入的区块哈希（低 -> 高）
 * @param oldTip          重组前位点
 * @param newTip          重组后位点
 * @param unresolved      是否因无法回溯而中止（此时数据保持原状）
 * @param reason          可读原因
 */
public record ReorgEvent(long forkHeight, List<String> detachedHashes, List<String> attachedHashes,
                         long oldTip, long newTip, boolean unresolved, String reason) {
}
