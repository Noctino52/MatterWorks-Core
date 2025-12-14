package com.matterworks.core.domain.player;

import java.util.UUID;

public class PlayerProfile {

    private final UUID playerId;
    private String username;

    // CAMPO MONEY
    private double money;

    public PlayerProfile(UUID playerId) {
        this.playerId = playerId;
        this.money = 0.0; // Default
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    // --- GESTIONE SOLDI ---

    public double getMoney() {
        return money;
    }

    public void setMoney(double money) {
        this.money = money;
    }

    /**
     * Modifica il saldo (positivo per aggiungere, negativo per togliere).
     * @param amount Quantità da aggiungere/togliere.
     */
    public void modifyMoney(double amount) {
        this.money += amount;
    }
}