package com.matterworks.core.domain.shop;

import com.matterworks.core.domain.matter.MatterPayload;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.ports.IRepository;

import java.util.UUID;

public class MarketManager {

    private final IRepository repository;

    public MarketManager(IRepository repository) {
        this.repository = repository;
    }

    public void sellItem(MatterPayload item, UUID sellerId) {
        // Logica semplice: 10$ per ogni cubo. In futuro useremo il ValueCalculator.
        double value = 10.0;

        // Carichiamo il profilo (Sincrono per ora, in produzione useremmo cache/async)
        PlayerProfile player = repository.loadPlayerProfile(sellerId);

        if (player != null) {
            player.modifyMoney(value);
            repository.savePlayerProfile(player);

            System.out.println("💰 MARKET: Venduto " + item.shape() + " (" + item.color() + ") per " + value + "$ -> Saldo: " + player.getMoney());
        } else {
            System.err.println("❌ MARKET: Player non trovato per la vendita!");
        }
    }
}