package com.matterworks.core;

import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.database.dao.*;
import com.matterworks.core.models.PlotModel;
import com.matterworks.core.player.PlayerProfile; // Assicurati di usare il tuo modello

import java.math.BigDecimal;
import java.util.UUID;

public class MainDev {
    public static void main(String[] args) {
        DatabaseManager db = new DatabaseManager();
        db.connect();

        // Inizializza i DAO
        PlayerDAO playerDao = new PlayerDAO(db);
        PlotDAO plotDao = new PlotDAO(db);
        TransactionDAO txDao = new TransactionDAO(db);
        VerificationCodeDAO codeDao = new VerificationCodeDAO(db);

        // 1. Nuovo Giocatore
        UUID uuid = UUID.randomUUID();
        String name = "TestPlayer_" + System.currentTimeMillis();
        System.out.println("Creating Player: " + name);

        PlayerProfile p = playerDao.createPlayer(uuid, name);

        // 2. Assegnazione Plot (Void Math simulata)
        int nextIndex = plotDao.getNextAllocationIndex();
        int x = nextIndex * 1000; // Distanza arbitraria 1000 blocchi
        int z = 0;

        System.out.println("Allocating Plot #" + nextIndex + " at " + x + ", " + z);
        plotDao.createPlot(uuid, nextIndex, x, z);

        // 3. Gameplay: Guadagno Soldi
        BigDecimal earned = new BigDecimal("150.00");
        p.setMoney(p.getMoney().add(earned));
        playerDao.save(p);

        // 4. Log Transazione (Fondamentale!)
        txDao.logTransaction(uuid, "STARTER_BONUS", "MONEY", earned, null);

        // 5. Generazione Codice Web
        String code = "A1B2";
        codeDao.saveCode(uuid, code);
        System.out.println("Web Link Code generated: " + code);

        // 6. Verifica
        PlotModel loadedPlot = plotDao.loadPlot(uuid);
        System.out.println("VERIFY -> Plot Owner: " + loadedPlot.getOwnerId() + " | X: " + loadedPlot.getX());

        db.close();
    }
}