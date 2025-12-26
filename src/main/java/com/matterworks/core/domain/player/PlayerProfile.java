package com.matterworks.core.domain.player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class PlayerProfile {

    public enum PlayerRank { PLAYER, ADMIN, MODERATOR }

    private final UUID playerId;
    private String username;
    private double money;
    private int voidCoins;
    private int prestigeLevel;
    private PlayerRank rank;

    private Set<String> unlockedTechs = new HashSet<>();

    public PlayerProfile(UUID playerId) {
        this.playerId = playerId;
        this.money = 0.0;
        this.voidCoins = 0;
        this.prestigeLevel = 0;
        this.rank = PlayerRank.PLAYER;
        resetTechTreeToDefaults();
    }

    public boolean isAdmin() { return rank == PlayerRank.ADMIN; }
    public PlayerRank getRank() { return rank; }
    public void setRank(PlayerRank rank) { this.rank = rank; }

    public UUID getPlayerId() { return playerId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public double getMoney() { return money; }
    public void setMoney(double money) { this.money = money; }
    public void modifyMoney(double amount) { this.money += amount; }

    public int getVoidCoins() { return voidCoins; }
    public void setVoidCoins(int voidCoins) { this.voidCoins = voidCoins; }
    public void modifyVoidCoins(int amount) { this.voidCoins += amount; }

    public int getPrestigeLevel() { return prestigeLevel; }
    public void setPrestigeLevel(int level) { this.prestigeLevel = level; }

    public Set<String> getUnlockedTechs() { return unlockedTechs; }

    public void addTech(String techId) {
        if (techId == null || techId.isBlank()) return;
        if (unlockedTechs == null) unlockedTechs = new HashSet<>();
        unlockedTechs.add(techId);
    }

    public boolean hasTech(String techId) {
        if (techId == null || techId.isBlank()) return false;
        return unlockedTechs != null && unlockedTechs.contains(techId);
    }

    public void clearUnlockedTechs() {
        if (unlockedTechs == null) unlockedTechs = new HashSet<>();
        else unlockedTechs.clear();
    }

    /** ✅ Reset “pulito”: nessuna tech di base obbligatoria */
    public void resetTechTreeToDefaults() {
        clearUnlockedTechs();
    }

    @Override
    public String toString() {
        String shortId = playerId.toString().substring(0, 8);
        return username + " (" + shortId + ")";
    }
}
