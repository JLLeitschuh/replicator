package com.booking.replication.augmenter.model;

@SuppressWarnings("unused")
public enum QueryAugmentedEventDataType {
    BEGIN(0),
    COMMIT(1),
    DDL_DEFINER(2),
    DDL_TABLE(3),
    DDL_TEMPORARY_TABLE(4),
    DDL_VIEW(5),
    DDL_ANALYZE(6),
    PSEUDO_GTID(7);

    private final int code;

    QueryAugmentedEventDataType(int code) {
        this.code = code;
    }

    public int getCode() {
        return this.code;
    }
}