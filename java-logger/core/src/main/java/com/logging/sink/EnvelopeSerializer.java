package com.logging.sink;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Shared Jackson configuration used by every sink so the on-the-wire JSON shape
 * is identical no matter which transport delivers the message.
 */
public final class EnvelopeSerializer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    private EnvelopeSerializer() {}

    public static ObjectMapper mapper() {
        return OBJECT_MAPPER;
    }

    public static String toJson(LogEnvelope envelope) throws JsonProcessingException {
        return OBJECT_MAPPER.writeValueAsString(envelope.toMap());
    }
}
