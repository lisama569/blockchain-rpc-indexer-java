package com.github.lisama569.blockchainrpcindexerjava.model;

import java.math.BigInteger;

/** 链上交易（模拟数据）。 */
public record Transaction(
        String hash,
        String blockHash,
        long blockNumber,
        String from,
        String to,
        BigInteger value) {
}
