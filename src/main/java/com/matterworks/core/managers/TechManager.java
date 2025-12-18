package com.matterworks.core.managers;

import com.matterworks.core.database.dao.TechDefinitionDAO;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.ports.IRepository;
import java.util.*;

public class TechManager {

    private final IRepository repository;
    private final TechDefinitionDAO dao; // Riferimento al DAO specifico

    // Struttura del Nodo Tecnologico
    public record TechNode(String id, String name, double cost, String parentId, String unlocksItemId) {}

    private final Map<String, TechNode> nodes = new LinkedHashMap<>();

    // Items che non richiedono tech tree
    private final Set<String> baseItems = Set.of("drill_mk1", "conveyor_belt", "nexus_core");

    // Costruttore aggiornato per ricevere il DAO
    public TechManager(IRepository repository, TechDefinitionDAO dao) {
        this.repository = repository;
        this.dao = dao;
        initializeTree();
    }

    private void initializeTree() {
        System.out.println("🧬 Loading Tech Tree from Database...");
        List<TechNode> dbNodes = dao.loadAllNodes();

        if (dbNodes.isEmpty()) {
            System.err.println("⚠️ Nessuna Tech Definition trovata nel DB! Uso fallback.");
            // Fallback per evitare crash se il DB è vuoto
            nodes.put("tech_chromator", new TechNode("tech_chromator", "Chromator Tech", 500.0, null, "chromator"));
            nodes.put("tech_mixer", new TechNode("tech_mixer", "Fluid Mixing", 1500.0, "tech_chromator", "color_mixer"));
        } else {
            for (TechNode node : dbNodes) {
                nodes.put(node.id(), node);
                System.out.println("   -> Loaded Tech: " + node.name() + " (Unlocks: " + node.unlocksItemId() + ")");
            }
        }
    }

    public boolean canBuyItem(PlayerProfile p, String itemId) {
        // Admin bypassa tutto
        if (p.isAdmin()) return true;

        // Item base sempre sbloccati
        if (baseItems.contains(itemId)) return true;

        // Cerca se esiste una tech che sblocca questo item e se il player ce l'ha
        for (TechNode node : nodes.values()) {
            if (node.unlocksItemId().equals(itemId)) {
                return p.hasTech(node.id());
            }
        }
        // Se l'item non è nell'albero ma non è base, è bloccato di default
        return false;
    }

    public boolean unlockNode(PlayerProfile p, String nodeId) {
        TechNode node = nodes.get(nodeId);
        if (node == null) return false;

        if (p.hasTech(nodeId)) return false;
        if (p.getMoney() < node.cost()) return false;
        if (node.parentId() != null && !p.hasTech(node.parentId())) return false;

        p.modifyMoney(-node.cost());
        p.addTech(nodeId);
        repository.savePlayerProfile(p);
        System.out.println("🔓 TECNOLOGIA SBLOCCATA: " + node.name());
        return true;
    }

    public Collection<TechNode> getAllNodes() { return nodes.values(); }
    public TechNode getNode(String id) { return nodes.get(id); }
}