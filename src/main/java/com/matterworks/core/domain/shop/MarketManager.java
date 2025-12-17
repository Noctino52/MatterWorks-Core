package com.matterworks.core.domain.shop;

import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.matter.MatterPayload;
import com.matterworks.core.domain.matter.MatterShape;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.ports.IRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MarketManager {

    private final IRepository repository;
    private final Map<MatterColor, Double> basePrices;

    public MarketManager(IRepository repository) {
        this.repository = repository;
        this.basePrices = new HashMap<>();
        initializePrices();
    }

    private void initializePrices() {
        // Prezzi Base per Colore
        basePrices.put(MatterColor.RAW, 1.0);

        // Colori Primari (Lavorati)
        basePrices.put(MatterColor.RED, 5.0);
        basePrices.put(MatterColor.BLUE, 5.0);
        basePrices.put(MatterColor.YELLOW, 5.0);

        // Colori Secondari (Mixati - Valgono molto di più)
        basePrices.put(MatterColor.PURPLE, 25.0);
        basePrices.put(MatterColor.ORANGE, 25.0);
        basePrices.put(MatterColor.GREEN, 25.0);

        // Colore Finale
        basePrices.put(MatterColor.WHITE, 100.0);
    }

    public void sellItem(MatterPayload item, UUID sellerId) {
        if (item == null) return;

        double value = calculateValue(item);

        // Carichiamo il profilo, aggiorniamo i soldi e salviamo subito
        PlayerProfile player = repository.loadPlayerProfile(sellerId);

        if (player != null) {
            player.modifyMoney(value);
            repository.savePlayerProfile(player);

            System.out.println("💰 MARKET: Venduto " + item.shape() + " (" + item.color() + ") per $" + String.format("%.2f", value));
            System.out.println("   [Saldo Attuale: $" + String.format("%.2f", player.getMoney()) + "]");
        } else {
            System.err.println("❌ MARKET: Errore critico - Player non trovato " + sellerId);
        }
    }

    private double calculateValue(MatterPayload item) {
        // 1. Prezzo base del colore
        double base = basePrices.getOrDefault(item.color(), 0.5);

        // 2. Moltiplicatore forma (complessità)
        double multiplier = 1.0;
        if (item.shape() == MatterShape.SPHERE) multiplier = 1.5;   // Liquidi/Sfere valgono di più
        if (item.shape() == MatterShape.PYRAMID) multiplier = 2.0;  // Cristalli
        if (item.isComplex()) multiplier *= 1.2;                    // Bonus effetti (shiny, etc.)

        return base * multiplier;
    }
}