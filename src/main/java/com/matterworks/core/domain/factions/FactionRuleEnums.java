package com.matterworks.core.domain.factions;

public final class FactionRuleEnums {

    private FactionRuleEnums() {}

    public enum Sentiment {
        LIKE,
        DISLIKE
    }

    public enum MatchType {
        CONTAINS,
        EXACT
    }

    public enum CombineMode {
        ALL,
        ANY
    }
}
