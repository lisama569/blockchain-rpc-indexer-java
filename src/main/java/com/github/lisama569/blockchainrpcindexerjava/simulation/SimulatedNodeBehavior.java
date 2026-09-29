package com.github.lisama569.blockchainrpcindexerjava.simulation;

/**
 * 模拟节点的行为开关，用于在测试中制造超时 / 业务错误 / 落后 / 分叉视图。
 */
public record SimulatedNodeBehavior(boolean timeout,
                                    boolean businessError,
                                    long headOverride,
                                    boolean useAltChain) {
    public static SimulatedNodeBehavior normal() {
        return new SimulatedNodeBehavior(false, false, -1L, false);
    }
}
