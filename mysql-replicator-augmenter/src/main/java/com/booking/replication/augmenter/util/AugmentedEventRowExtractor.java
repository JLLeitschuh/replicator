package com.booking.replication.augmenter.util;

import com.booking.replication.augmenter.model.event.AugmentedEvent;
import com.booking.replication.augmenter.model.event.DeleteRowsAugmentedEventData;
import com.booking.replication.augmenter.model.event.UpdateRowsAugmentedEventData;
import com.booking.replication.augmenter.model.event.WriteRowsAugmentedEventData;
import com.booking.replication.augmenter.model.row.AugmentedRow;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class AugmentedEventRowExtractor {

    private static final Logger LOG = LogManager.getLogger(AugmentedEventRowExtractor.class);

    public static List<AugmentedRow> extractAugmentedRows(AugmentedEvent augmentedEvent) {

        Long commitTimestamp = augmentedEvent.getHeader().getEventTransaction().getCommitTimestamp();
        Long transactionSequenceNumber = augmentedEvent.getHeader().getEventTransaction().getTransactionSequenceNumber();

        List<AugmentedRow> augmentedRows = new ArrayList<>();

        switch (augmentedEvent.getHeader().getEventType()) {

            case INSERT:
                WriteRowsAugmentedEventData writeRowsAugmentedEventData =
                        ((WriteRowsAugmentedEventData) augmentedEvent.getData());

                Collection<AugmentedRow> extractedAugmentedRowsFromInsert =
                        writeRowsAugmentedEventData.getRows();

                // This part overrides the:
                //      - commitTimestamp of all rows in transaction to the
                //        transaction commit time.
                //      - transactionSequenceNumber
                //      - microsecondsTimestamp
                overrideRowsCommitTimeAndSetMicroseconds(
                        commitTimestamp,
                        transactionSequenceNumber,
                        extractedAugmentedRowsFromInsert
                );

                augmentedRows.addAll(extractedAugmentedRowsFromInsert);

                break;

            case UPDATE:
                UpdateRowsAugmentedEventData updateRowsAugmentedEventData =
                        ((UpdateRowsAugmentedEventData) augmentedEvent.getData());

                Collection<AugmentedRow> extractedAugmentedRowsFromUpdate =
                        updateRowsAugmentedEventData.getRows();

                overrideRowsCommitTimeAndSetMicroseconds(
                        commitTimestamp,
                        transactionSequenceNumber,
                        extractedAugmentedRowsFromUpdate
                );

                augmentedRows.addAll(extractedAugmentedRowsFromUpdate);

                break;

            case DELETE:
                DeleteRowsAugmentedEventData deleteRowsAugmentedEventData =
                        ((DeleteRowsAugmentedEventData) augmentedEvent.getData());

                Collection<AugmentedRow> extractedAugmentedRowsFromDelete =
                        deleteRowsAugmentedEventData.getRows();

                overrideRowsCommitTimeAndSetMicroseconds(
                        commitTimestamp,
                        transactionSequenceNumber,
                        extractedAugmentedRowsFromDelete
                );

                augmentedRows.addAll(extractedAugmentedRowsFromDelete);

                break;

            default:
                break;
        }
        return augmentedRows;
    }

    private static void overrideRowsCommitTimeAndSetMicroseconds(
            Long commitTimestamp,
            Long transactionSequenceNumber,
            Collection<AugmentedRow> extractedAugmentedRows) {

        for (AugmentedRow ar : extractedAugmentedRows) {

            ar.setCommitTimestamp(commitTimestamp);
            ar.setTransactionSequenceNumber(transactionSequenceNumber);

            Long microsOverride = commitTimestamp * 1000 + ar.getTransactionSequenceNumber();

            LOG.debug(String.format("table : %s, UUID: %s, commit-ts: %d, seq-no: %d, micro-ts: %d",ar.getTableName(),
                    ar.getTransactionUUID(), commitTimestamp, transactionSequenceNumber, microsOverride));

            ar.setRowMicrosecondTimestamp(microsOverride);
        }
    }
}
