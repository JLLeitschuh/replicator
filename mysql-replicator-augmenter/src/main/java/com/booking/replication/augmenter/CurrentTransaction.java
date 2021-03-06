package com.booking.replication.augmenter;

import com.booking.replication.augmenter.model.event.AugmentedEvent;
import com.booking.replication.augmenter.model.event.AugmentedEventTransaction;
import com.booking.replication.commons.checkpoint.GTID;

import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class CurrentTransaction {
    private static final long DEFAULT_XXID = 0L;

    private final AtomicBoolean started;
    private final AtomicBoolean resuming;
    private final AtomicReference<Collection<AugmentedEvent>> buffer;
    private final AtomicLong xxid;
    private final AtomicLong transactionSequenceNumber;
    private final AtomicLong timestamp;
    private final Class<?> bufferClass;
    private final int bufferSizeLimit;

    private GTID identifier;

    public CurrentTransaction(String bufferClass, int bufferSizeLimit) {
        this.started = new AtomicBoolean();
        this.resuming = new AtomicBoolean();
        this.identifier = new GTID();
        this.buffer = new AtomicReference<>();
        this.xxid = new AtomicLong();
        this.transactionSequenceNumber = new AtomicLong();
        this.timestamp = new AtomicLong();
        this.bufferClass = this.getBufferClass(bufferClass);
        this.bufferSizeLimit = bufferSizeLimit;
    }

    public boolean begin() {
        if (!this.started.getAndSet(true)) {
            if (!this.resuming.get()) {
                this.buffer.set(this.getBufferInstance());
                this.xxid.set(0L);
                this.timestamp.set(0L);
                // This is the sequence number of the transaction within the second when the transaction is committed.
                // Since we can know this number only at commit time, we initialize it at 0 and set it to proper value
                // at commit time.
                this.transactionSequenceNumber.set(0L);
            }
            return true;
        } else {
            return false;
        }
    }

    public int getCurrentBufferSize() {
        if (buffer != null && buffer.get() != null) {
            return buffer.get().size();
        } else {
            return 0;
        }
    }

    public boolean add(AugmentedEvent event) {
        if (this.started.get() && !this.sizeLimitExceeded()) {
            return this.buffer.get().add(event);
        } else {
            return false;
        }
    }

    public Collection<AugmentedEvent> getAndClear() {

        if (this.buffer.get() != null) {

            Collection<AugmentedEvent> augmentedEventQueue = this.buffer.getAndSet(
                    (this.resuming.get())
                            ? (this.getBufferInstance())
                            : (null)
            );
            Collection<AugmentedEvent> augmentedEventList = new ArrayList<>();

            for (AugmentedEvent augmentedEvent : augmentedEventQueue) {

                augmentedEvent.getHeader().setEventTransaction(
                        new AugmentedEventTransaction(
                            this.timestamp.get(),
                            this.identifier.getValue(),
                            this.xxid.get(),
                            this.transactionSequenceNumber.get()
                        )
                );

                augmentedEventList.add(augmentedEvent);
            }

            return augmentedEventList;
        } else {
            return null;
        }
    }

    public boolean commit(long xxid, long timestamp, long transactionSequenceNumber) {
        if (this.started.getAndSet(false)) {
            this.resuming.set(false);
            this.xxid.set(xxid);
            this.timestamp.set(timestamp);
            this.transactionSequenceNumber.set(transactionSequenceNumber);
            return true;
        } else {
            return false;
        }
    }

    public boolean commit(long timestamp, long transactionSequenceNumber) {
        return this.commit(CurrentTransaction.DEFAULT_XXID, timestamp, transactionSequenceNumber);
    }

    public void rewind() {
        this.resuming.set(true);
        this.buffer.set(this.getBufferInstance());
    }

    public boolean started() {
        return this.started.get();
    }

    public boolean resuming() {
        return this.resuming.get();
    }

    public boolean markedForCommit() {
        return !this.started.get() && !this.resuming.get() && this.buffer.get() != null;
    }

    public boolean sizeLimitExceeded() {
        return this.buffer.get() != null && this.buffer.get().size() >= this.bufferSizeLimit;
    }

    private Class<?> getBufferClass(String bufferClass) {
        try {
            return Class.forName(bufferClass);
        } catch (ClassNotFoundException exception) {
            throw new RuntimeException(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Collection<AugmentedEvent> getBufferInstance() {
        try {
            return (Collection<AugmentedEvent>) this.bufferClass.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException(exception);
        }
    }

    public AtomicLong getTimestamp() {
        return timestamp;
    }

    public GTID getIdentifier() {
        return identifier;
    }

    public void setIdentifier(GTID newIdentifier) {
        this.identifier = newIdentifier;
    }

    public Long getXxid() {
        return xxid.get();
    }
}
