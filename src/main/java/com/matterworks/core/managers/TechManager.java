package com.matterworks.core.managers;

import com.matterworks.core.database.dao.TechDefinitionDAO;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.ui.MariaDBAdapter;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Tech Tree business logic.
 *
 * IMPORTANT:
 * - Prestige is NOT synthesized at runtime anymore.
 *   It's a normal DB node with node_id "prestige".
 *
 * - Machine tier upgrades are NOT new machine items.
 *   They are global effects derived from unlocked tech nodes.
 *   (Placed machines automatically benefit because speed is computed dynamically.)
 */
public class TechManager {

    public static final String PRESTIGE_NODE_ID = "prestige";

    private final MariaDBAdapter repository;
    private final TechDefinitionDAO dao;

    public record TechNode(
            String id,
            String name,
            double baseCost,
            double prestigeCostMult,
            List<String> parentIds,
            List<String> unlockItemIds,

            // Upgrade node support (optional; empty/default for normal nodes)
            List<String> upgradeMachineIds,
            int upgradeToTier,              // 0 = none, 2 or 3 for tier upgrades
            double speedMultiplier,          // default 1.0
            double nexusSellMultiplier,      // default 1.0
            boolean enablesPrestige
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

        public boolean isUpgradeNode() {
            return upgradeToTier >= 2 && upgradeMachineIds != null && !upgradeMachineIds.isEmpty();
        }

        public boolean targetsMachine(String machineTypeId) {
            if (machineTypeId == null || machineTypeId.isBlank()) return false;
            if (upgradeMachineIds == null || upgradeMachineIds.isEmpty()) return false;
            return upgradeMachineIds.contains(machineTypeId);
        }

        public double safeSpeedMultiplier() {
            double v = speedMultiplier;
            if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0.0) return 1.0;
            return v;
        }

        public double safeNexusSellMultiplier() {
            double v = nexusSellMultiplier;
            if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0.0) return 1.0;
            return v;
        }
    }

    private final Map<String, TechNode> nodes = new LinkedHashMap<>();

    /**
     * Items always buyable even without tech unlock.
     * NOTE: renamed drill -> drill (global rename will be completed in other classes in Part 2/3).
     */
    private final Set<String> baseItems = Set.of("drill", "conveyor_belt", "nexus_core");

    public TechManager(MariaDBAdapter repository, TechDefinitionDAO dao) {
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
    }

    public Collection<TechNode> getAllNodes() {
        return nodes.values().stream().filter(Objects::nonNull).collect(Collectors.toList());
    }

    public TechNode getNode(String id) {
        return nodes.get(id);
    }

    public boolean isPrerequisiteSatisfied(PlayerProfile p, String parentId) {
        if (parentId == null || parentId.isBlank()) return true;

        // If parentId doesn't exist in DB nodes, we do NOT block unlock (robustness).
        if (!nodes.containsKey(parentId)) return true;

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

    public boolean canBuyItem(PlayerProfile p, String itemId) {
        if (p == null) return false;
        if (p.isAdmin()) return true;

        if (itemId == null || itemId.isBlank()) return false;
        if (baseItems.contains(itemId)) return true;

        for (TechNode node : nodes.values()) {
            if (node == null) continue;
            List<String> unlocks = node.unlockItemIds();
            if (unlocks != null && unlocks.contains(itemId)) {
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
        if (!areParentsSatisfied(p, node)) return false;

        double cost = node.effectiveCost(p);
        if (!p.isAdmin() && p.getMoney() < cost) return false;

        if (!p.isAdmin()) p.modifyMoney(-cost);

        p.addTech(nodeId);
        repository.savePlayerProfile(p);

        if (!p.isAdmin()) {
            repository.logTransaction(p, "TECH_UNLOCK", "MONEY", -cost, nodeId);
        } else {
            repository.logTransaction(p, "TECH_UNLOCK_ADMIN", "MONEY", 0.0, nodeId);
        }

        return true;
    }

    // ==========================================================
    // PRESTIGE GATING (DB-driven node)
    // ==========================================================

    public boolean isPrestigeUnlocked(PlayerProfile p) {
        if (p == null) return false;

        // Fast path: standard id
        if (p.hasTech(PRESTIGE_NODE_ID)) return true;

        // Slow path: if DB used a different id but flagged enables_prestige (optional feature)
        for (String techId : p.getUnlockedTechs()) {
            TechNode n = nodes.get(techId);
            if (n != null && n.enablesPrestige()) return true;
        }

        return false;
    }

    // ==========================================================
    // MACHINE UPGRADE TIERS (DB-driven)
    // ==========================================================

    /**
     * DEPRECATED (Tier-driven ticks change):
     * Tech no longer provides a speed multiplier for machines.
     * Machine tier affects processing ticks (read from DB).
     *
     * Kept for backward compatibility; always returns 1.0.
     */
    public double getTechSpeedMultiplierForMachine(PlayerProfile p, String machineTypeId) {
        return 1.0;
    }


    /**
     * Extra sell multiplier for Nexus due to tech upgrades (tier system).
     * This is separate from prestige sell multiplier.
     */
    public double getTechNexusSellMultiplier(PlayerProfile p) {
        if (p == null) return 1.0;

        int bestTier = 1;
        double bestMult = 1.0;

        for (String techId : p.getUnlockedTechs()) {
            TechNode node = nodes.get(techId);
            if (node == null) continue;
            if (!node.isUpgradeNode()) continue;

            // Only consider nodes that target nexus_core.
            if (!node.targetsMachine("nexus_core")) continue;

            int tier = node.upgradeToTier();
            if (tier > bestTier) {
                bestTier = tier;
                bestMult = node.safeNexusSellMultiplier();
            }
        }

        if (Double.isNaN(bestMult) || Double.isInfinite(bestMult) || bestMult <= 0.0) return 1.0;
        return bestMult;
    }

    public int getUnlockedTierForMachine(PlayerProfile profile, String machineTypeId) {
        if (profile == null || machineTypeId == null || machineTypeId.isBlank()) return 1;

        // Hard rename defensive
        if ("drill_mk1".equals(machineTypeId)) machineTypeId = "drill";

        int best = 1;

        for (TechNode node : getAllNodes()) {
            if (node == null) continue;
            if (!node.isUpgradeNode()) continue;

            int toTier = node.upgradeToTier();
            if (toTier <= best) continue;

            if (!profile.hasTech(node.id())) continue;
            if (!node.targetsMachine(machineTypeId)) continue;

            best = toTier;
            if (best >= 3) break; // max tier
        }

        return best;
    }

}
