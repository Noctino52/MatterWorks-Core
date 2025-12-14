package com.matterworks.core.database;

import java.nio.ByteBuffer;
import java.util.UUID;

public class UuidUtils {

    public static byte[] asBytes(UUID uuid) {
        if (uuid == null) return null;
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return bb.array();
    }

    public static UUID asUuid(byte[] bytes) {
        if (bytes == null || bytes.length != 16) return null;
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        long firstLong = bb.getLong();
        long secondLong = bb.getLong();
        return new UUID(firstLong, secondLong);
    }
}