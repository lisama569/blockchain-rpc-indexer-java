package com.github.lisama569.blockchainrpcindexerjava.api;

import com.github.lisama569.blockchainrpcindexerjava.indexer.IndexingService;
import com.github.lisama569.blockchainrpcindexerjava.indexer.IndexStore;
import com.github.lisama569.blockchainrpcindexerjava.indexer.PositionManager;
import com.github.lisama569.blockchainrpcindexerjava.indexer.ReorgService;
import com.github.lisama569.blockchainrpcindexerjava.node.FetchResult;
import com.github.lisama569.blockchainrpcindexerjava.node.NodeAttempt;
import com.github.lisama569.blockchainrpcindexerjava.node.NodeDispatcher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询 / 运维 HTTP 接口。查询永远只面向已收敛到位点的规范化数据。
 */
@RestController
@RequestMapping("/api")
public class QueryController {

    private final IndexStore store;
    private final PositionManager positions;
    private final ReorgService reorgService;
    private final IndexingService indexingService;
    private final NodeDispatcher dispatcher;

    public QueryController(IndexStore store, PositionManager positions,
                        ReorgService reorgService, IndexingService indexingService, NodeDispatcher dispatcher) {
        this.store = store;
        this.positions = positions;
        this.reorgService = reorgService;
        this.indexingService = indexingService;
        this.dispatcher = dispatcher;
    }

    /** 位点、链头与节点链头探测。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("position", positions.current());
        m.put("canonicalTip", store.tipHeight());
        m.put("blocks", store.blocksAscending().size());
        m.put("transactions", store.transactionsAscending().size());
        m.put("reorgEvents", reorgService.events().size());
        List<NodeAttempt> probes = dispatcher.probeHeads();
        m.put("nodeHeads", probes.stream().map(a -> Map.of(
                "node", a.nodeId(), "stage", a.stage(), "result", a.result(),
                "detail", a.detail() == null ? "" : a.detail(), "usable", a.usable())).toList());
        return m;
    }

    /** 规范化区块，按高度升序——重组后不会出现新旧混算。 */
    @GetMapping("/blocks")
    public Object blocks() {
        return store.blocksAscending();
    }

    /** 规范化交易，按高度/哈希升序。 */
    @GetMapping("/txs")
    public Object txs() {
        return store.transactionsAscending();
    }

    /** 全账户余额（规范化投影）。 */
    @GetMapping("/accounts")
    public Object accounts() {
        return store.allBalances();
    }

    /** 重组流水（detach/attach 哈希、位点变化、是否中止）。 */
    @GetMapping("/reorgs")
    public Object reorgs() {
        return reorgService.events();
    }

    /** 演示/测试：推进索引（默认 1 个高度）。返回最后一次拉取结论，故障可区分。 */
    @PostMapping("/index/step")
    public ResponseEntity<Map<String, Object>> step(@RequestParam(defaultValue = "1") int batches) {
        FetchResult r = indexingService.runOnce(Math.max(1, batches));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("position", positions.current());
        if (r instanceof FetchResult.BlockFound ok) {
            body.put("outcome", "OK");
            body.put("servedBy", ok.servedBy());
            body.put("attempts", ok.attempts());
            return ResponseEntity.ok(body);
        }
        FetchResult.Failed f = (FetchResult.Failed) r;
        body.put("outcome", "FAILED");
        body.put("failureKind", f.kind());
        body.put("reason", f.reason());
        body.put("attempts", f.attempts());
        int code = switch (f.kind()) {
            case BLOCK_NOT_FOUND -> 200; // 已追上链头，属正常
            case NODE_TIMEOUT, NODE_BUSINESS_ERROR, NODE_STALE, ALL_NODES_UNAVAILABLE,
                 NODE_DIVERGENT, REORG_UNRESOLVED -> 502;
            default -> 500;
        };
        return ResponseEntity.status(code).body(body);
    }

    /** 演示/测试：外部尝试回退位点——按规则必须被明确拒绝。 */
    @PostMapping("/position/reset")
    public Map<String, Object> resetPosition(@RequestParam long height) {
        positions.requestReset(height);
        return Map.of("position", positions.current());
    }
}
