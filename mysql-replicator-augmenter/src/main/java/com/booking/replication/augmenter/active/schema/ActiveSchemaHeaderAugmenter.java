package com.booking.replication.augmenter.active.schema;

import com.booking.replication.augmenter.model.AugmentedEventHeader;
import com.booking.replication.augmenter.model.AugmentedEventType;
import com.booking.replication.supplier.model.RawEventData;
import com.booking.replication.supplier.model.RawEventHeaderV4;

public class ActiveSchemaHeaderAugmenter {
    private final ActiveSchemaContext context;

    public ActiveSchemaHeaderAugmenter(ActiveSchemaContext context) {
        this.context = context;
    }

    public AugmentedEventHeader apply(RawEventHeaderV4 eventHeader, RawEventData eventData) {
        AugmentedEventType type = this.getAugmentedEventType(eventHeader);

        if (type == null) {
            return null;
        }

        return new AugmentedEventHeader(eventHeader.getTimestamp(), this.context.getCheckpoint(), type);
    }

    private AugmentedEventType getAugmentedEventType(RawEventHeaderV4 eventHeader) {
        switch (eventHeader.getEventType()) {
            case WRITE_ROWS:
            case EXT_WRITE_ROWS:
                return AugmentedEventType.WRITE_ROWS;
            case UPDATE_ROWS:
            case EXT_UPDATE_ROWS:
                return AugmentedEventType.UPDATE_ROWS;
            case DELETE_ROWS:
            case EXT_DELETE_ROWS:
                return AugmentedEventType.DELETE_ROWS;
            case QUERY:
            case XID:
                return AugmentedEventType.QUERY;
            default:
                return null;
        }
    }
}
