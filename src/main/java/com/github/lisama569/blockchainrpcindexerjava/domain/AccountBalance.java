package com.github.lisama569.blockchainrpcindexerjava.domain;

import java.math.BigInteger;

/**
 * 规范化账户余额视图。
 *
 * @param account 账户地址
 * @param balance 已入账到当前位点的余额
 */
public record AccountBalance(String account, BigInteger balance) {
}
