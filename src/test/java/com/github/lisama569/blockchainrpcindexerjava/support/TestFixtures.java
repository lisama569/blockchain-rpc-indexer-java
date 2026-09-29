package com.github.lisama569.blockchainrpcindexerjava.support;

import com.github.lisama569.blockchainrpcindexerjava.config.IndexerProperties;
import com.github.lisama569.blockchainrpcindexerjava.indexer.IndexStore;
import com.github.lisama569.blockchainrpcindexerjava.indexer.IndexingService;
import com.github.lisama569.blockchainrpcindexerjava.indexer.IngestionService;
import com.github.lisama569.blockchainrpcindexerjava.indexer.PositionManager;
import com.github.lisama569.blockchainrpcindexerjava.indexer.ReorgService;
import com.github.lisama569.blockchainrpcindexerjava.node.NodeClient;
import com.github.lisama569.blockchainrpcindexerjava.node.NodeDispatcher;
import com.github.lisama569.blockchainrpcindexerjava.simulation.SimulatedChain;
import com.github.lisama569.blockchainrpcindexerjava.simulation.SimulatedNodeBehavior;
import com.github.lisama569.blockchainrpcindexerjava.simulation.SimulatedNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 纯手工装配（不启动 Spring）的测试夹具：模拟链 + 多个可控行为的模拟节点 + 全套索引组件。
 */
public final class TestFixtures {

    public static final long TIMEOUT_MS = 500L;

    public final SimulatedChain chain;
    public final SimulatedNode primary;
    public final SimulatedNode replica;
    public final SimulatedNode third;
    public final List<SimulatedNode> simNodes;
    public final IndexStore store;
    public final PositionManager positions;
    public final NodeDispatcher dispatcher;
    public final ReorgService reorgService;
    public final IngestionService ingestion;
    public final IndexingService indexer;

    private TestFixtures(SimulatedChain chain, List<SimulatedNode> simNodes) {
        this.chain = chain;
        this.simNodes = simNodes;
        this.primary = simNodes.get(0);
        this.replica = simNodes.get(1);
        this.third = simNodes.size() > 2 ? simNodes.get(2) : null;
        IndexerProperties props = new IndexerProperties(2L, TIMEOUT_MS, 0L);
        this.store = new IndexStore();
        this.positions = new PositionManager(store, props);
        List<NodeClient> clients = new ArrayList<>(simNodes);
        this.dispatcher = new NodeDispatcher(clients, props);
        this.reorgService = new ReorgService(store, positions, dispatcher);
        this.ingestion = new IngestionService(store, positions, reorgService);
        this.indexer = new IndexingService(dispatcher, ingestion, positions);
    }

    /** 3 节点夹具（primary / replica / tertiary），链高 tipHeight，初始全部正常。 */
    public static TestFixtures of3(long tipHeight) {
        SimulatedChain chain = SimulatedChain.create(tipHeight);
        List<SimulatedNode> nodes = new ArrayList<>();
        nodes.add(new SimulatedNode("node-primary", chain, SimulatedNodeBehavior.normal(), 0, TIMEOUT_MS));
        nodes.add(new SimulatedNode("node-replica", chain, SimulatedNodeBehavior.normal(), 0, TIMEOUT_MS));
        nodes.add(new SimulatedNode("node-tertiary", chain, SimulatedNodeBehavior.normal(), 0, TIMEOUT_MS));
        return new TestFixtures(chain, nodes);
    }

    /** 2 节点夹具。 */
    public static TestFixtures of2(long tipHeight) {
        SimulatedChain chain = SimulatedChain.create(tipHeight);
        List<SimulatedNode> nodes = new ArrayList<>();
        nodes.add(new SimulatedNode("node-primary", chain, SimulatedNodeBehavior.normal(), 0, TIMEOUT_MS));
        nodes.add(new SimulatedNode("node-replica", chain, SimulatedNodeBehavior.normal(), 0, TIMEOUT_MS));
        return new TestFixtures(chain, nodes);
    }

    public void failoverHealthy() {
        for (SimulatedNode n : simNodes) {
            n.updateBehavior(SimulatedNodeBehavior.normal());
        }
    }
}
