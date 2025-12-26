package com.matterworks.core.managers;

import com.matterworks.core.database.dao.TechDefinitionDAO;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.ports.IRepository;

import java.util.*;
import java.util.stream.Collectors;

public class TechManager {

    // ✅ Ultimo nodo sintetico che sblocca il bottone prestige
    public static final String PRESTIGE_UNLOCK_TECH = "prestige_unlock";

    private final IRepository repository;
    private final TechDefinitionDAO dao;

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

    // item “free” sempre acquistabili (indipendenti dal tech tree)
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
            if (node == null || node.id() == null || node.id().isBlank()) continue;
            nodes.put(node.id(), node);
        }

        // ✅ nodo prestige in fondo
        addSyntheticPrestigeNode();
    }

    // -------------------------
    // “Orphan prereq” handling
    // -------------------------

    /**
     * ✅ Regola chiave:
     * - parentId null/blank => ignorato
     * - parentId NON presente nel tech tree (nodo cancellato dal DB) => ignorato (considerato soddisfatto)
     * - altrimenti => deve essere presente in unlocked tech del player
     */
    public boolean isPrerequisiteSatisfied(PlayerProfile p, String parentId) {
        if (parentId == null || parentId.isBlank()) return true;

        // Se il nodo prerequisito non esiste più nel DB/tree, non deve bloccare nulla
        if (!nodes.containsKey(parentId) && !PRESTIGE_UNLOCK_TECH.equals(parentId)) return true;

        if (p == null) return false;
        return p.hasTech(parentId);
    }

    public boolean areParentsSatisfied(PlayerProfile p, TechNode node) {
        if (node == null) return false;
        List<String> parents = (node.parentIds() != null) ? node.parentIds() : List.of();

        for (String parentId : parents) {
            if (!isPrerequisiteSatisfied(p, parentId)) return false;
        }
        return true;
    }

    // -------------------------
    // Public API
    // -------------------------

    public boolean isPrestigeUnlockTech(String techId) {
        return PRESTIGE_UNLOCK_TECH.equals(techId);
    }

    public boolean isPrestigeUnlocked(PlayerProfile p) {
        return p != null && p.hasTech(PRESTIGE_UNLOCK_TECH);
    }

    public boolean canBuyItem(PlayerProfile p, String itemId) {
        if (p == null) return false;
        if (p.isAdmin()) return true;

        // ✅ drill/belt/nexus sempre disponibili subito
        if (baseItems.contains(itemId)) return true;

        for (TechNode node : nodes.values()) {
            if (node == null) continue;
            if (node.unlockItemIds() != null && node.unlockItemIds().contains(itemId)) {
                return p.hasTech(node.id());
            }
        }
        return false;
    }

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

        // ✅ prerequisiti: orfani ignorati
        if (!areParentsSatisfied(p, node)) return false;

        double effectiveCost = node.effectiveCost(p);
        if (!p.isAdmin() && p.getMoney() < effectiveCost) return false;

        if (!p.isAdmin()) p.modifyMoney(-effectiveCost);

        p.addTech(nodeId);
        repository.savePlayerProfile(p);
        repository.logTransaction(p, "TECH_UNLOCK", "MONEY", -effectiveCost, nodeId);

        return true;
    }

    public Collection<TechNode> getAllNodes() {
        // ✅ non nascondo nulla qui: se basic_mining non esiste, non appare.
        // Se vuoi nascondere altri nodi “speciali”, lo fai qui.
        return nodes.values().stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public TechNode getNode(String id) {
        return nodes.get(id);
    }

    // -------------------------
    // Synthetic prestige node
    // -------------------------

    private void addSyntheticPrestigeNode() {
        List<String> allParents = nodes.keySet().stream()
                .filter(Objects::nonNull)
                .filter(id -> !PRESTIGE_UNLOCK_TECH.equals(id))
                .collect(Collectors.toList());

        TechNode prestige = new TechNode(
                PRESTIGE_UNLOCK_TECH,
                "Prestige (Unlock)",
                0.0,
                0.0,
                allParents,
                List.of()
        );

        nodes.remove(PRESTIGE_UNLOCK_TECH);
        nodes.put(PRESTIGE_UNLOCK_TECH, prestige);
    }
}
