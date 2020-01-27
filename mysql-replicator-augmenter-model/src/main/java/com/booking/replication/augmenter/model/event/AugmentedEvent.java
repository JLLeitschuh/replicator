package com.booking.replication.augmenter.model.event;

import com.booking.replication.augmenter.model.event.format.avro.AvroManager;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.avro.generic.GenericRecord;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;

@SuppressWarnings("unused")
public class AugmentedEvent implements Serializable {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AugmentedEventHeader header;
    private AugmentedEventData data;

    private Object optionalPayload;

    public AugmentedEvent() {
    }

    public AugmentedEvent(AugmentedEventHeader header, AugmentedEventData data) {
        this.header = header;
        this.data = data;
    }

    public AugmentedEventHeader getHeader() {
        return this.header;
    }

    public AugmentedEventData getData() {
        return this.data;
    }

    @JsonIgnore
    public byte[] toJSON() throws IOException {
        return AugmentedEvent.MAPPER.writeValueAsBytes(this);
    }

    public List<GenericRecord> dataToAvro() throws IOException {
        AvroManager eventDataPresenterAvro = new AvroManager(this);
        return eventDataPresenterAvro.convertAugmentedEventDataToAvro();
    }


    public byte[] toJSONPrettyPrint() throws IOException {
        return AugmentedEvent.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(this);
    }

    public Object getOptionalPayload() {
        return optionalPayload;
    }

    public void setOptionalPayload(Object optionalPayload) {
        this.optionalPayload = optionalPayload;
    }
}
