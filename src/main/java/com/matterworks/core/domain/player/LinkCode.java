package com.matterworks.core.domain.player;

public record LinkCode(String code, long expirationTime) {
    public boolean isValid() {
        return System.currentTimeMillis() < expirationTime;
    }
}