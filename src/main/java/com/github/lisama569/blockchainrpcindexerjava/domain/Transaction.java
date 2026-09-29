package com.github.lisama569.blockchainrpcindexerjava.domain;

import java.math.BigInteger;

/**
 * 链上交易（简化模型：单笔转账）。
 *
 * @param txHash 交易哈希，全局唯一幂等键
 * @param height 所在区块高度
 * @param from   付款账户
 * @param to     收款账户
 * @param amount 转账金额（非负）
 */
public record Transaction(String txHash, long height, String from, String to, BigInteger amount) {
}
