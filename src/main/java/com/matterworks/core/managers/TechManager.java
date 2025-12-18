package com.matterworks.core.managers;

import com.matterworks.core.database.dao.TechDefinitionDAO;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.ports.IRepository;
import java.util.*;

public class TechManager {

    private final IRepository repository;
    private final TechDefinitionDAO dao;

    public record TechNode(
            String id,
            String name,
            double cost,
            List<String> parentIds,
            List<String> unlockItemIds
    ) {}

    private final Map<String, TechNode> nodes = new LinkedHashMap<>();
    private final Set<String> baseItems = Set.of("drill_mk1", "conveyor_belt", "nexus_core");

    public TechManager(IRepository repository, TechDefinitionDAO dao) {
        this.repository = repository;
        this.dao = dao;
        loadFromDatabase();
    }

    public void loadFromDatabase() {
        nodes.clear();
        if (dao == null) return;
        List<TechNode> dbNodes = dao.loadAllNodes();
        for (TechNode node : dbNodes) {
            nodes.put(node.id(), node);
        }
        if (nodes.isEmpty()) {
            System.err.println("!!! TECH TREE EMPTY - Check Database Table 'tech_definitions' !!!");
        }
    }

    public boolean canBuyItem(PlayerProfile p, String itemId) {
        if (p == null) return false;
        if (p.isAdmin()) return true;
        if (baseItems.contains(itemId)) return true;
        for (TechNode node : nodes.values()) {
            if (node.unlockItemIds().contains(itemId)) {
                return p.hasTech(node.id());
            }
        }
        return false;
    }

    public boolean unlockNode(PlayerProfile p, String nodeId) {
        TechNode node = nodes.get(nodeId);
        if (node == null || p == null) return false;
        if (p.hasTech(nodeId)) return false;
        if (p.getMoney() < node.cost()) return false;

        for (String parentId : node.parentIds()) {
            if (!p.hasTech(parentId)) return false;
        }

        p.modifyMoney(-node.cost());
        p.addTech(nodeId);
        repository.savePlayerProfile(p);

        // Log con snapshot completo
        repository.logTransaction(p, "TECH_UNLOCK", "MONEY", -node.cost(), nodeId);

        System.out.println("🔓 [" + p.getUsername() + "] Research Complete: " + node.name());
        return true;
    }

    public Collection<TechNode> getAllNodes() { return nodes.values(); }
    public TechNode getNode(String id) { return nodes.get(id); }
}