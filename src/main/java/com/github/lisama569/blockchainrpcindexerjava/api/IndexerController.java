package com.github.lisama569.blockchainrpcindexerjava.api;

import com.github.lisama569.blockchainrpcindexerjava.index.IndexStore;
import com.github.lisama569.blockchainrpcindexerjava.node.ChainNodeClient;
import com.github.lisama569.blockchainrpcindexerjava.node.NodeRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只读查询接口：规范链状态、区间交易（升序）、账户余额。 */
@RestController
@RequestMapping("/api/indexer")
public class IndexerController {

    private final IndexStore store;
    private final NodeRegistry registry;

    public IndexerController(IndexStore store, NodeRegistry registry) {
        this.store = store;
        this.registry = registry;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("checkpoint", store.checkpoint());
        m.put("tipHash", store.canonicalHashAt(store.checkpoint()));
        m.put("txCount", store.txCount());
        List<Map<String, Object>> nodes = registry.nodes().stream().map(n -> {
            Map<String, Object> nm = new LinkedHashMap<>();
            nm.put("id", n.id());
            nm.put("priority", n.priority());
            nm.put("head", safeHead(n));
            return nm;
        }).toList();
        m.put("nodes", nodes);
        return m;
    }

    @GetMapping("/transactions")
    public Map<String, Object> transactions(@RequestParam(defaultValue = "0") long from,
                                            @RequestParam(defaultValue = "-1") long to) {
        long upper = to < 0 ? store.checkpoint() : to;
        List<Map<String, Object>> rows = store.queryTransactions(from, upper);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("checkpoint", store.checkpoint());
        m.put("from", from);
        m.put("to", upper);
        m.put("count", rows.size());
        m.put("transactions", rows);
        return m;
    }

    @GetMapping("/balances")
    public Map<String, Object> balances() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("checkpoint", store.checkpoint());
        m.put("balances", store.balances());
        return m;
    }

    private Object safeHead(ChainNodeClient n) {
        try {
            return n.getBlockNumber();
        } catch (RuntimeException e) {
            return "UNAVAILABLE:" + e.getClass().getSimpleName();
        }
    }
}
