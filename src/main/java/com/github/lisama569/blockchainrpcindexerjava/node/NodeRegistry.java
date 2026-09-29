package com.github.lisama569.blockchainrpcindexerjava.node;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** 已接入节点注册表；priority 越小越优先。 */
public class NodeRegistry {

    private final CopyOnWriteArrayList<MockChainNode> nodes = new CopyOnWriteArrayList<>();

    public void register(MockChainNode node) {
        nodes.add(node);
        nodes.sort(Comparator.comparingInt(ChainNodeClient::priority));
    }

    /** 返回全部已启用节点，按优先级升序。 */
    public List<ChainNodeClient> nodes() {
        return nodes.stream()
                .filter(ChainNodeClient::enabled)
                .map(n -> (ChainNodeClient) n)
                .toList();
    }

    public Optional<ChainNodeClient> byId(String id) {
        return nodes.stream().filter(n -> n.id().equals(id)).findFirst().map(n -> n);
    }
}
