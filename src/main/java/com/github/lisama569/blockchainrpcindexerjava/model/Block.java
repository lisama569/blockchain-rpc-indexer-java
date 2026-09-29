package com.github.lisama569.blockchainrpcindexerjava.model;

import java.util.List;

/** 区块（模拟数据）。 */
public record Block(
        long number,
        String hash,
        String parentHash,
        long timestamp,
        List<Transaction> transactions) {
}
