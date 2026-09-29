package com.github.lisama569.blockchainrpcindexerjava.config;

import com.github.lisama569.blockchainrpcindexerjava.indexer.BlockPoller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** 按 indexer.enabled 决定是否启动后台轮询。 */
@Component
public class IndexerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(IndexerLifecycle.class);

    private final BlockPoller poller;
    private final IndexerProperties props;
    private volatile boolean running = false;

    public IndexerLifecycle(BlockPoller poller, IndexerProperties props) {
        this.poller = poller;
        this.props = props;
    }

    @Override
    public void start() {
        if (props.isEnabled()) {
            poller.start();
            running = true;
        } else {
            log.info("索引器后台轮询未启用 (indexer.enabled=false)，仅提供查询接口");
        }
    }

    @Override
    public void stop() {
        if (running) {
            poller.stop();
            running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
