package com.wire.helium;

import com.google.protobuf.GeneratedMessageV3;

import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.ext.Provider;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

/**
 * Java WS RS client custom Protobuf serializer.
 * Auto-installed for any compatible client/server during provider scanning phase
 */
@Provider
@Produces("application/x-protobuf")
public class ProtobufBodyWriter implements MessageBodyWriter<GeneratedMessageV3> {
    @Override
    public boolean isWriteable(Class type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        if (type != GeneratedMessageV3.class) {
            return false;
        }
        boolean mediaTypeAcceptable = mediaType != null && mediaType.getType() != null;
        return mediaTypeAcceptable && mediaType.getType().equals("application/x-protobuf");
    }

    @Override
    public void writeTo(GeneratedMessageV3 generatedMessageV3, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
        generatedMessageV3.writeTo(entityStream);
    }
}
