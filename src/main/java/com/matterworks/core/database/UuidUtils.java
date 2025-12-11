package com.matterworks.core.database;

import java.nio.ByteBuffer;
import java.util.UUID;

public class UuidUtils {

    /**
     * Converts Java UUID to 16-byte array for MariaDB BINARY(16).
     */
    public static byte[] asBytes(UUID uuid) {
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return bb.array();
    }

    /**
     * Converts 16-byte array from MariaDB back to Java UUID.
     */
    public static UUID asUuid(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        long firstLong = bb.getLong();
        long secondLong = bb.getLong();
        return new UUID(firstLong, secondLong);
    }
}