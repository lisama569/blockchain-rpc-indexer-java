package com.github.lisama569.blockchainrpcindexerjava.node;

/**
 * 单次节点尝试流水，用于“失败原因可区分”的日志与结论。
 *
 * @param nodeId  被尝试的节点
 * @param stage   尝试阶段（HEAD / BLOCK）
 * @param result  尝试结论（OK / TIMEOUT / BUSINESS_ERROR / STALE / DIVERGENT / NOT_FOUND）
 * @param detail  可读细节（响应哈希、高度、错误码等）
 * @param usable  该节点的响应是否被采信（可疑节点永远为 false）
 */
public record NodeAttempt(String nodeId, String stage, String result, String detail, boolean usable) {
}
