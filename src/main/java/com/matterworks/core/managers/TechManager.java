package com.matterworks.core.managers;

import com.matterworks.core.database.dao.TechDefinitionDAO;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.ports.IRepository;

import java.util.*;

public class TechManager {

    private final IRepository repository;
    private final TechDefinitionDAO dao;

    /**
     * baseCost = costo "di default" da DB (prestige 0)
     * prestigeCostMult = quanto cresce linearmente per livello di prestigio:
     * effective = baseCost * (1 + prestigeLevel * prestigeCostMult)
     */
    public record TechNode(
            String id,
            String name,
            double baseCost,
            double prestigeCostMult,
            List<String> parentIds,
            List<String> unlockItemIds
    ) {
        public double effectiveCost(int prestigeLevel) {
            int p = Math.max(0, prestigeLevel);
            double mult = Math.max(0.0, prestigeCostMult);
            double factor = 1.0 + (p * mult);
            double cost = baseCost * factor;
            // protezione extra
            if (Double.isNaN(cost) || Double.isInfinite(cost)) return baseCost;
            return Math.max(0.0, cost);
        }

        public double effectiveCost(PlayerProfile player) {
            int p = (player == null) ? 0 : player.getPrestigeLevel();
            return effectiveCost(p);
        }

        public double effectiveFactor(int prestigeLevel) {
            int p = Math.max(0, prestigeLevel);
            double mult = Math.max(0.0, prestigeCostMult);
            return 1.0 + (p * mult);
        }
    }

    private final Map<String, TechNode> nodes = new LinkedHashMap<>();

    // item “free” sempre disponibili
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

    /** Costo effettivo del nodo per il player corrente (prestigio incluso). */
    public double getEffectiveNodeCost(PlayerProfile p, String nodeId) {
        TechNode node = nodes.get(nodeId);
        if (node == null) return 0.0;
        if (p != null && p.isAdmin()) return 0.0;
        return node.effectiveCost(p);
    }

    public boolean unlockNode(PlayerProfile p, String nodeId) {
        TechNode node = nodes.get(nodeId);
        if (node == null || p == null) return false;
        if (p.hasTech(nodeId)) return false;

        // prerequisiti
        for (String parentId : node.parentIds()) {
            if (!p.hasTech(parentId)) return false;
        }

        // costo dinamico
        double effectiveCost = node.effectiveCost(p);

        if (!p.isAdmin() && p.getMoney() < effectiveCost) return false;

        if (!p.isAdmin()) {
            p.modifyMoney(-effectiveCost);
        }
        p.addTech(nodeId);

        repository.savePlayerProfile(p);

        // Log con costo effettivo
        repository.logTransaction(p, "TECH_UNLOCK", "MONEY", -effectiveCost, nodeId);

        System.out.println("📚 [" + p.getUsername() + "] Research Complete: " + node.name()
                + " (base=" + node.baseCost()
                + ", prestige=" + p.getPrestigeLevel()
                + ", mult=" + node.prestigeCostMult()
                + ", paid=" + (int) effectiveCost + ")");

        return true;
    }

    public Collection<TechNode> getAllNodes() {
        return nodes.values();
    }

    public TechNode getNode(String id) {
        return nodes.get(id);
    }
}
