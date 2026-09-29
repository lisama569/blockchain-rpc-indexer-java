package com.github.lisama569.blockchainrpcindexerjava.domain;

import java.util.List;

/**
 * 区块（规范化链上的最小数据单元）。
 *
 * @param height     区块高度
 * @param hash       区块哈希
 * @param parentHash 父区块哈希
 * @param transactions 区块内交易（顺序即入账顺序）
 */
public record Block(long height, String hash, String parentHash, List<Transaction> transactions) {
}
