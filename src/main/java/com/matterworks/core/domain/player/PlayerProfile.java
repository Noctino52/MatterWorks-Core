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

    // ==========================================================
    // OVERCLOCK (persisted in DB; based on PLAYTIME seconds)
    // ==========================================================
    private long overclockStartPlaytimeSeconds = 0L;
    private long overclockDurationSeconds = 0L; // <=0 inactive, -1 lifetime
    private double overclockMultiplier = 1.0;

    public PlayerProfile(UUID playerId) {
        this.playerId = playerId;
        this.money = 0.0;
        this.voidCoins = 0;
        this.prestigeLevel = 0;
        this.rank = PlayerRank.PLAYER;
        resetTechTreeToDefaults();
    }

    public UUID getPlayerId() { return playerId; }

    public boolean isAdmin() { return rank == PlayerRank.ADMIN; }
    public PlayerRank getRank() { return rank; }
    public void setRank(PlayerRank rank) { this.rank = (rank != null ? rank : PlayerRank.PLAYER); }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public double getMoney() { return money; }
    public void setMoney(double money) { this.money = money; }
    public void modifyMoney(double amount) { this.money += amount; }

    public int getVoidCoins() { return voidCoins; }
    public void setVoidCoins(int voidCoins) { this.voidCoins = voidCoins; }
    public void modifyVoidCoins(int amount) { this.voidCoins += amount; }

    public int getPrestigeLevel() { return prestigeLevel; }
    public void setPrestigeLevel(int prestigeLevel) { this.prestigeLevel = prestigeLevel; }

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

    public void resetTechTreeToDefaults() {
        clearUnlockedTechs();
    }

    // ==========================================================
    // OVERCLOCK API
    // ==========================================================
    public long getOverclockRemainingSeconds(long currentTotalPlaytimeSeconds) {
        if (overclockMultiplier <= 1.0) return 0L;

        if (overclockDurationSeconds == -1) return -1L; // lifetime
        if (overclockDurationSeconds <= 0) return 0L;

        long elapsed = Math.max(0L, currentTotalPlaytimeSeconds - overclockStartPlaytimeSeconds);
        long remaining = overclockDurationSeconds - elapsed;
        return Math.max(0L, remaining);
    }


    // ==========================================================
// OVERCLOCK getters/setters (required by DAO + GridManager)
// ==========================================================



// ==========================================================
// OVERCLOCK getters/setters (required by DAO + GridManager)
// ==========================================================

    public long getOverclockStartPlaytimeSeconds() {
        return overclockStartPlaytimeSeconds;
    }

    public void setOverclockStartPlaytimeSeconds(long v) {
        this.overclockStartPlaytimeSeconds = Math.max(0L, v);
    }

    public long getOverclockDurationSeconds() {
        return overclockDurationSeconds;
    }

    public void setOverclockDurationSeconds(long v) {
        this.overclockDurationSeconds = v;
    }

    public double getOverclockMultiplier() {
        return overclockMultiplier;
    }

    public void setOverclockMultiplier(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0.0) {
            this.overclockMultiplier = 1.0;
        } else {
            this.overclockMultiplier = v;
        }
    }

    public boolean isOverclockActive(long currentTotalPlaytimeSeconds) {
        if (overclockMultiplier <= 1.0) return false;

        // lifetime
        if (overclockDurationSeconds == -1) return true;

        if (overclockDurationSeconds <= 0) return false;

        long elapsed = Math.max(0L, currentTotalPlaytimeSeconds - overclockStartPlaytimeSeconds);
        return elapsed < overclockDurationSeconds;
    }

    public double getActiveOverclockMultiplier(long currentTotalPlaytimeSeconds) {
        return isOverclockActive(currentTotalPlaytimeSeconds) ? overclockMultiplier : 1.0;
    }




    public void clearOverclock() {
        this.overclockStartPlaytimeSeconds = 0L;
        this.overclockDurationSeconds = 0L;
        this.overclockMultiplier = 1.0;
    }

    @Override
    public String toString() {
        String shortId = playerId.toString().substring(0, 8);
        return (username != null ? username : "player") + " (" + shortId + ")";
    }
}
