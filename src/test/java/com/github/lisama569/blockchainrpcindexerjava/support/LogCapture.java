package com.github.lisama569.blockchainrpcindexerjava.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试日志捕获：挂到指定包的 logback logger 上，
 * 捕获节点响应、位点与重组流水，并可在断言时回显到控制台。
 */
public class LogCapture implements AutoCloseable {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final List<Logger> attached = new ArrayList<>();

    public LogCapture(String... loggerNames) {
        appender.start();
        for (String name : loggerNames) {
            Logger logger = (Logger) LoggerFactory.getLogger(name);
            logger.setLevel(Level.INFO);
            logger.addAppender(appender);
            attached.add(logger);
        }
    }

    public List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    public boolean contains(String keyword) {
        return messages().stream().anyMatch(m -> m.contains(keyword));
    }

    public List<String> find(String keyword) {
        return messages().stream().filter(m -> m.contains(keyword)).toList();
    }

    /** 将捕获日志回显到单测控制台（节点响应/位点/重组流水可见）。 */
    public void echo(String banner) {
        System.out.println("========== " + banner + " ==========");
        appender.list.forEach(e -> System.out.println("[" + e.getLevel() + "] " + e.getFormattedMessage()));
        System.out.println("====================================================");
    }

    @Override
    public void close() {
        attached.forEach(l -> l.detachAppender(appender));
        appender.stop();
    }
}
