package org.qortal.api.model.crosschain;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.Consumes;
import javax.ws.rs.NotSupportedException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.Provider;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

/** Keep legacy empty recovery calls optional, while requiring JSON for any approval. */
@Provider
@Consumes(MediaType.WILDCARD)
public final class HtlcRecoveryApprovalReader implements MessageBodyReader<HtlcRecoveryApproval> {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(JsonParser.Feature.AUTO_CLOSE_SOURCE)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return type == HtlcRecoveryApproval.class;
    }

    @Override
    public HtlcRecoveryApproval readFrom(Class<HtlcRecoveryApproval> type, Type genericType, Annotation[] annotations,
            MediaType mediaType, MultivaluedMap<String, String> headers, InputStream stream) throws IOException {
        PushbackInputStream input = new PushbackInputStream(stream);
        int first = input.read();
        if (first == -1) return null; // No approval and no extra fee deduction, regardless of the empty body's media type.
        input.unread(first);
        if (!MediaType.APPLICATION_JSON_TYPE.isCompatible(mediaType) || mediaType.isWildcardType() || mediaType.isWildcardSubtype())
            throw new NotSupportedException("Recovery approval requires Content-Type: application/json");
        try {
            HtlcRecoveryApproval approval = MAPPER.readValue(input, HtlcRecoveryApproval.class);
            if (approval == null) throw new BadRequestException("Omit the body for normal recovery; an approval must be a JSON object");
            return approval;
        } catch (JsonProcessingException e) {
            throw new BadRequestException("Recovery approval must be a JSON object with tradeAddress, quoteId and integer expiresAt", e);
        }
    }
}
