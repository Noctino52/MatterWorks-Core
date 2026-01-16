package com.matterworks.core.domain.telemetry.production;

import com.matterworks.core.domain.matter.MatterPayload;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bucket-based ring buffer.
 * - bucketSizeSeconds: 5 seconds
 * - retention: 10 minutes (covers 1m/5m/10m)
 *
 * Snapshot returns only keys with non-zero values in the requested window:
 * - Appears when used/produced/sold
 * - Disappears when it falls out of the time window
 */
public final class InMemoryProductionTelemetry implements ProductionTelemetry {

    private static final int BUCKET_SIZE_SECONDS = 5;
    private static final int RETENTION_SECONDS = 10 * 60;
    private static final int BUCKET_COUNT = RETENTION_SECONDS / BUCKET_SIZE_SECONDS; // 120

    private final Clock clock;
    private final ConcurrentHashMap<UUID, PlayerBuffer> buffers = new ConcurrentHashMap<>();

    public InMemoryProductionTelemetry() {
        this(Clock.systemUTC());
    }

    public InMemoryProductionTelemetry(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void recordProduced(UUID playerId, MatterPayload payload, long quantity) {
        if (playerId == null || payload == null || quantity <= 0) return;
        getOrCreate(playerId).recordProduced(nowEpochSeconds(), payload, quantity);
    }

    @Override
    public void recordConsumed(UUID playerId, MatterPayload payload, long quantity) {
        if (playerId == null || payload == null || quantity <= 0) return;
        getOrCreate(playerId).recordConsumed(nowEpochSeconds(), payload, quantity);
    }

    @Override
    public void recordSold(UUID playerId, MatterPayload payload, long quantity, double moneyEarned) {
        if (playerId == null || payload == null || quantity <= 0) return;
        getOrCreate(playerId).recordSold(nowEpochSeconds(), payload, quantity, Math.max(0.0, moneyEarned));
    }

    @Override
    public ProductionStatsSnapshot getSnapshot(UUID playerId, ProductionTimeWindow window) {
        if (playerId == null || window == null) {
            return new ProductionStatsSnapshot(
                    ProductionTimeWindow.ONE_MINUTE,
                    Map.of(), Map.of(),
                    Map.of(), Map.of(),
                    Map.of(), Map.of(),
                    0L, 0L, 0L, 0.0
            );
        }

        PlayerBuffer buffer = buffers.get(playerId);
        if (buffer == null) {
            return new ProductionStatsSnapshot(window,
                    Map.of(), Map.of(),
                    Map.of(), Map.of(),
                    Map.of(), Map.of(),
                    0L, 0L, 0L, 0.0
            );
        }

        return buffer.snapshot(nowEpochSeconds(), window);
    }

    private PlayerBuffer getOrCreate(UUID playerId) {
        return buffers.computeIfAbsent(playerId, ignored -> new PlayerBuffer());
    }

    private long nowEpochSeconds() {
        return clock.instant().getEpochSecond();
    }

    private static int windowBuckets(ProductionTimeWindow window) {
        int seconds = window.getSeconds();
        return Math.max(1, seconds / BUCKET_SIZE_SECONDS);
    }

    private static int bucketIndex(long bucketStartSeconds) {
        long bucket = (bucketStartSeconds / BUCKET_SIZE_SECONDS);
        return (int) (bucket % BUCKET_COUNT);
    }

    private static long bucketStartSeconds(long epochSeconds) {
        return (epochSeconds / BUCKET_SIZE_SECONDS) * BUCKET_SIZE_SECONDS;
    }

    private static final class PlayerBuffer {
        private final Bucket[] buckets = new Bucket[BUCKET_COUNT];

        PlayerBuffer() {
            for (int i = 0; i < buckets.length; i++) buckets[i] = new Bucket();
        }

        void recordProduced(long nowSec, MatterPayload payload, long qty) {
            Bucket b = rotateAndGet(nowSec);
            b.producedByColor.merge(MatterTelemetryKeys.colorKey(payload), qty, Long::sum);
            b.producedByMatter.merge(MatterTelemetryKeys.matterKey(payload), qty, Long::sum);
        }

        void recordConsumed(long nowSec, MatterPayload payload, long qty) {
            Bucket b = rotateAndGet(nowSec);
            b.consumedByColor.merge(MatterTelemetryKeys.colorKey(payload), qty, Long::sum);
            b.consumedByMatter.merge(MatterTelemetryKeys.matterKey(payload), qty, Long::sum);
        }

        void recordSold(long nowSec, MatterPayload payload, long qty, double money) {
            Bucket b = rotateAndGet(nowSec);

            String ck = MatterTelemetryKeys.colorKey(payload);
            String mk = MatterTelemetryKeys.matterKey(payload);

            b.soldByColor.computeIfAbsent(ck, ignored -> new SoldStats()).add(qty, money);
            b.soldByMatter.computeIfAbsent(mk, ignored -> new SoldStats()).add(qty, money);
        }

        ProductionStatsSnapshot snapshot(long nowSec, ProductionTimeWindow window) {
            int wBuckets = windowBuckets(window);
            long windowStartSec = nowSec - window.getSeconds();

            Map<String, Long> producedColor = new HashMap<>();
            Map<String, Long> producedMatter = new HashMap<>();

            Map<String, Long> consumedColor = new HashMap<>();
            Map<String, Long> consumedMatter = new HashMap<>();

            Map<String, SoldStats> soldColor = new HashMap<>();
            Map<String, SoldStats> soldMatter = new HashMap<>();

            long totalProduced = 0L;
            long totalConsumed = 0L;

            long cursor = bucketStartSeconds(nowSec);
            for (int i = 0; i < wBuckets; i++) {
                if (cursor < bucketStartSeconds(windowStartSec)) break;

                int idx = bucketIndex(cursor);
                Bucket b = buckets[idx];

                if (b.bucketStartSeconds == cursor) {
                    for (var e : b.producedByColor.entrySet()) {
                        long v = e.getValue();
                        if (v <= 0) continue;
                        producedColor.merge(e.getKey(), v, Long::sum);
                        totalProduced += v;
                    }
                    for (var e : b.producedByMatter.entrySet()) {
                        long v = e.getValue();
                        if (v <= 0) continue;
                        producedMatter.merge(e.getKey(), v, Long::sum);
                    }

                    for (var e : b.consumedByColor.entrySet()) {
                        long v = e.getValue();
                        if (v <= 0) continue;
                        consumedColor.merge(e.getKey(), v, Long::sum);
                        totalConsumed += v;
                    }
                    for (var e : b.consumedByMatter.entrySet()) {
                        long v = e.getValue();
                        if (v <= 0) continue;
                        consumedMatter.merge(e.getKey(), v, Long::sum);
                    }

                    for (var e : b.soldByColor.entrySet()) {
                        SoldStats s = e.getValue();
                        if (s == null || s.getQuantity() <= 0) continue;
                        soldColor.computeIfAbsent(e.getKey(), ignored -> new SoldStats())
                                .add(s.getQuantity(), s.getMoneyEarned());
                    }
                    for (var e : b.soldByMatter.entrySet()) {
                        SoldStats s = e.getValue();
                        if (s == null || s.getQuantity() <= 0) continue;
                        soldMatter.computeIfAbsent(e.getKey(), ignored -> new SoldStats())
                                .add(s.getQuantity(), s.getMoneyEarned());
                    }
                }

                cursor -= BUCKET_SIZE_SECONDS;
            }

            long totalSoldQty = 0L;
            double totalMoney = 0.0;
            for (SoldStats s : soldMatter.values()) { // matter is the primary view for sales
                totalSoldQty += s.getQuantity();
                totalMoney += s.getMoneyEarned();
            }

            return new ProductionStatsSnapshot(
                    window,
                    producedColor, producedMatter,
                    consumedColor, consumedMatter,
                    soldColor, soldMatter,
                    totalProduced,
                    totalConsumed,
                    totalSoldQty,
                    totalMoney
            );
        }

        private synchronized Bucket rotateAndGet(long nowSec) {
            long start = bucketStartSeconds(nowSec);
            int idx = bucketIndex(start);
            Bucket b = buckets[idx];

            if (b.bucketStartSeconds != start) {
                b.bucketStartSeconds = start;

                b.producedByColor.clear();
                b.producedByMatter.clear();

                b.consumedByColor.clear();
                b.consumedByMatter.clear();

                b.soldByColor.clear();
                b.soldByMatter.clear();
            }
            return b;
        }
    }

    private static final class Bucket {
        long bucketStartSeconds = Long.MIN_VALUE;

        final Map<String, Long> producedByColor = new HashMap<>();
        final Map<String, Long> producedByMatter = new HashMap<>();

        final Map<String, Long> consumedByColor = new HashMap<>();
        final Map<String, Long> consumedByMatter = new HashMap<>();

        final Map<String, SoldStats> soldByColor = new HashMap<>();
        final Map<String, SoldStats> soldByMatter = new HashMap<>();
    }
}
