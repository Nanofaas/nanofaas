package it.unimib.datai.nanofaas.sdk.lite.handler;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public final class BoundedJson {
    private final ObjectMapper mapper;

    public BoundedJson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public byte[] serialize(Object value, int maxBytes) {
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");
        LimitedOutputStream output = new LimitedOutputStream(maxBytes);
        try {
            mapper.writeValue(output, value);
            return output.toByteArray();
        } catch (IOException ex) {
            if (causedByLimit(ex)) throw new PayloadTooLargeException(ex);
            throw new SerializationException(ex);
        }
    }

    private static boolean causedByLimit(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof LimitExceededIOException) return true;
        }
        return false;
    }

    public static final class PayloadTooLargeException extends RuntimeException {
        private PayloadTooLargeException(Throwable cause) { super(cause); }
    }

    public static final class SerializationException extends RuntimeException {
        private SerializationException(Throwable cause) { super(cause); }
    }

    private static final class LimitExceededIOException extends IOException { }

    private static final class LimitedOutputStream extends OutputStream {
        private final int limit;
        private final ByteArrayOutputStream output;

        private LimitedOutputStream(int limit) {
            this.limit = limit;
            this.output = new ByteArrayOutputStream(Math.min(limit, 8192));
        }

        @Override
        public void write(int value) throws IOException {
            ensureCapacity(1);
            output.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            ensureCapacity(length);
            output.write(bytes, offset, length);
        }

        private void ensureCapacity(int additional) throws LimitExceededIOException {
            if (additional < 0 || additional > limit - output.size()) {
                throw new LimitExceededIOException();
            }
        }

        private byte[] toByteArray() { return output.toByteArray(); }
    }
}
