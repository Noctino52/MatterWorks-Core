package com.matterworks.core.domain.shop;

public record VoidShopItem(
        String itemId,
        String displayName,
        int voidPrice,
        String description,
        int sortOrder
) {}
