package com.github.lisama569.blockchainrpcindexerjava.indexer;

/**
 * 位点推进保护。
 *
 * <p>规范化位点只能从 current 严格推进到 current+1：</p>
 * <ul>
 *   <li>proposed &lt;= current：位点回退/重复，明确拒绝，绝不从中间继续；</li>
 *   <li>proposed &gt; current+1：跳号，拒绝（避免跳过未索引区块）；</li>
 *   <li>proposed == current+1：允许。</li>
 * </ul>
 */
public final class CheckpointGuard {

    private CheckpointGuard() {
    }

    public static long requireNext(long current, long proposed) {
        if (proposed <= current) {
            throw new CheckpointRegressionException(
                    "checkpoint regression rejected: current=" + current + " proposed=" + proposed);
        }
        if (proposed != current + 1) {
            throw new CheckpointRegressionException(
                    "checkpoint gap rejected: current=" + current + " proposed=" + proposed);
        }
        return proposed;
    }

    /** 位点回退/跳号被拒绝时抛出，调用方必须给出明确结论而不是继续消费。 */
    public static class CheckpointRegressionException extends RuntimeException {
        public CheckpointRegressionException(String message) {
            super(message);
        }
    }
}
