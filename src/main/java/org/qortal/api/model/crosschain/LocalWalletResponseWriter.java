package org.qortal.api.model.crosschain;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.ext.Provider;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

/** Preserve JSON objects, arrays and decimal strings without changing other API writers. */
@Provider
@Produces(MediaType.APPLICATION_JSON)
public final class LocalWalletResponseWriter implements MessageBodyWriter<LocalWalletResponse> {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return type == LocalWalletResponse.class;
    }

    @Override
    public long getSize(LocalWalletResponse response, Class<?> type, Type genericType,
                        Annotation[] annotations, MediaType mediaType) {
        return -1;
    }

    @Override
    public void writeTo(LocalWalletResponse response, Class<?> type, Type genericType,
                        Annotation[] annotations, MediaType mediaType,
                        MultivaluedMap<String, Object> headers, OutputStream stream) throws IOException {
        // Jersey owns the response stream; leave its lifecycle to the container.
        MAPPER.writer().without(com.fasterxml.jackson.core.JsonGenerator.Feature.AUTO_CLOSE_TARGET)
                .writeValue(stream, response.payload);
    }
}
