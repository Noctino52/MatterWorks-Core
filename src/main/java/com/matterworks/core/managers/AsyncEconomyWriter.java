package com.matterworks.core.managers;

import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.ui.MariaDBAdapter;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Write-behind persistence for:
 * - player profiles (money changes)
 * - transactions (aggregated)
 *
 * Critical rule: NEVER touch DB inside the 20 TPS simulation tick.
 */
final class AsyncEconomyWriter implements AutoCloseable {

    private static final long FLUSH_EVERY_MS = 250L;

    private final MariaDBAdapter repository;

    private final ScheduledExecutorService writer;

    private final ConcurrentHashMap<UUID, PlayerProfile> dirtyProfiles = new ConcurrentHashMap<>();

    private final AtomicReference<ConcurrentHashMap<TxKey, TxAgg>> txAggRef =
            new AtomicReference<>(new ConcurrentHashMap<>());

    AsyncEconomyWriter(MariaDBAdapter repository) {
        this.repository = repository;

        this.writer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mw-economy-writer");
            t.setDaemon(true);
            try {
                t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            } catch (Throwable ignored) {}
            return t;
        });

        writer.scheduleWithFixedDelay(this::flushSafely, FLUSH_EVERY_MS, FLUSH_EVERY_MS, TimeUnit.MILLISECONDS);
    }

    void markProfileDirty(UUID playerId, PlayerProfile profile) {
        if (playerId == null || profile == null) return;
        dirtyProfiles.put(playerId, profile);
    }

    void recordTransaction(PlayerProfile player,
                           String actionType,
                           String currency,
                           double amount,
                           String itemId,
                           Integer factionId,
                           Double value) {

        if (player == null || actionType == null || currency == null || itemId == null) return;

        TxKey key = new TxKey(player.getPlayerId(), actionType, currency, itemId, factionId);

        ConcurrentHashMap<TxKey, TxAgg> map = txAggRef.get();
        TxAgg agg = map.computeIfAbsent(key, _k -> new TxAgg(player, actionType, currency, itemId, factionId));

        agg.amount.add(amount);
        if (value != null) {
            agg.value.add(value);
            agg.hasValue = true;
        }
    }

    private void flushSafely() {
        try {
            flushOnce();
        } catch (Throwable t) {
            System.err.println("[ECONOMY_WRITER] Flush failed:");
            t.printStackTrace();
        }
    }

    private void flushOnce() {
        // 1) Save dirty profiles
        if (!dirtyProfiles.isEmpty()) {
            for (Map.Entry<UUID, PlayerProfile> e : dirtyProfiles.entrySet()) {
                UUID id = e.getKey();
                PlayerProfile p = e.getValue();
                if (id == null || p == null) continue;

                // remove first: if it changes again, it will be re-added
                dirtyProfiles.remove(id, p);

                try {
                    repository.savePlayerProfile(p);
                } catch (Throwable t) {
                    // retry later
                    dirtyProfiles.put(id, p);
                }
            }
        }

        // 2) Save aggregated transactions
        ConcurrentHashMap<TxKey, TxAgg> snap = txAggRef.getAndSet(new ConcurrentHashMap<>());
        if (snap.isEmpty()) return;

        for (TxAgg agg : snap.values()) {
            if (agg == null || agg.player == null) continue;

            double amount = agg.amount.sum();
            if (Math.abs(amount) < 0.0000001) continue;

            Double value = agg.hasValue ? agg.value.sum() : null;

            try {
                repository.logTransaction(
                        agg.player,
                        agg.actionType,
                        agg.currency,
                        amount,
                        agg.itemId,
                        agg.factionId,
                        value
                );
            } catch (Throwable ignored) {
                // Transactions must never stall the server.
                // We drop on failure (safe default).
            }
        }
    }

    @Override
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(2, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private record TxKey(UUID playerId, String actionType, String currency, String itemId, Integer factionId) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TxKey other)) return false;
            return Objects.equals(playerId, other.playerId)
                    && Objects.equals(actionType, other.actionType)
                    && Objects.equals(currency, other.currency)
                    && Objects.equals(itemId, other.itemId)
                    && Objects.equals(factionId, other.factionId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(playerId, actionType, currency, itemId, factionId);
        }
    }

    private static final class TxAgg {
        final PlayerProfile player;
        final String actionType;
        final String currency;
        final String itemId;
        final Integer factionId;

        final DoubleAdder amount = new DoubleAdder();
        final DoubleAdder value = new DoubleAdder();
        volatile boolean hasValue = false;

        TxAgg(PlayerProfile player, String actionType, String currency, String itemId, Integer factionId) {
            this.player = player;
            this.actionType = actionType;
            this.currency = currency;
            this.itemId = itemId;
            this.factionId = factionId;
        }
    }
}
