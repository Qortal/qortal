package org.qortal.api.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.qortal.api.model.ConnectedPeer;

import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.ext.Provider;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;

@Provider
@Produces(MediaType.APPLICATION_JSON)
public class ConnectedPeerJacksonWriter implements MessageBodyWriter<List<ConnectedPeer>> {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        // Only handle List<ConnectedPeer>
        if (!List.class.isAssignableFrom(type)) {
            return false;
        }
        
        if (genericType instanceof ParameterizedType) {
            ParameterizedType paramType = (ParameterizedType) genericType;
            Type[] actualTypes = paramType.getActualTypeArguments();
            if (actualTypes.length == 1) {
                Type listType = actualTypes[0];
                if (listType instanceof Class) {
                    return ConnectedPeer.class.isAssignableFrom((Class<?>) listType);
                }
                // Handle case where type is a TypeVariable or other type
                return listType.toString().contains("ConnectedPeer");
            }
        }
        
        return false;
    }

    @Override
    public long getSize(List<ConnectedPeer> connectedPeers, Class<?> type, Type genericType,
                       Annotation[] annotations, MediaType mediaType) {
        return -1; // Size is unknown
    }

    @Override
    public void writeTo(List<ConnectedPeer> connectedPeers, Class<?> type, Type genericType,
                       Annotation[] annotations, MediaType mediaType,
                       MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream)
            throws IOException {
        objectMapper.writer().writeValue(entityStream, connectedPeers);
    }
}

