package com.matterworks.core.domain.player;

import java.util.UUID;

public class PlayerProfile {

    public enum PlayerRank { PLAYER, ADMIN, MODERATOR }

    private final UUID playerId;
    private String username;
    private double money;
    private PlayerRank rank; // NUOVO

    public PlayerProfile(UUID playerId) {
        this.playerId = playerId;
        this.money = 0.0;
        this.rank = PlayerRank.PLAYER; // Default
    }

    public boolean isAdmin() { return rank == PlayerRank.ADMIN; }

    public PlayerRank getRank() { return rank; }
    public void setRank(PlayerRank rank) { this.rank = rank; }

    // ... (Getter e Setter per ID, Username, Money rimangono uguali) ...
    public UUID getPlayerId() { return playerId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public double getMoney() { return money; }
    public void setMoney(double money) { this.money = money; }
    public void modifyMoney(double amount) { this.money += amount; }
}