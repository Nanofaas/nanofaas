package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import io.scalecube.cluster.transport.api.Message;
import io.scalecube.cluster.transport.api.MessageCodec;

import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidObjectException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * scalecube's default codec feeds whatever arrives on the cluster port to an unfiltered
 * {@code ObjectInputStream}, and sizes a map from an integer the sender chose. This codec keeps the same wire
 * format (so it interoperates with plain scalecube nodes) but reads the envelope itself: a cap on the number
 * of headers, and a class allow-list and size limits on everything deserialized.
 */
final class PeerClusterCodec implements MessageCodec {
    static final int MAX_HEADERS = 16;
    static final int MAX_COLLECTION_ENTRIES = 10000;

    /** scalecube's own types and the JDK basics they use, application payloads travel as byte[]. */
    private static final ObjectInputFilter FILTER = ObjectInputFilter.Config.createFilter(
            "maxdepth=16;maxrefs=100000;maxbytes=4194304;maxarray=4194304;"
                    + "io.scalecube.cluster.**;java.lang.String;java.lang.Integer;java.lang.Long;java.lang.Boolean;"
                    + "java.lang.Enum;java.util.*;!*");

    @Override
    public Message deserialize(InputStream in) throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(in) {
            @Override
            public int readInt() throws IOException {
                int value = super.readInt();
                // scalecube 2.7.1 allocates collections in readExternal, beyond maxarray's reach.
                // Inspect the immediate caller only: nested MembershipRecord incarnation ints are not sizes.
                int limit = StackWalker.getInstance().walk(frames -> frames.skip(1).findFirst()
                        .filter(f -> f.getMethodName().equals("readExternal"))
                        .map(f -> switch (f.getClassName()) {
                            case "io.scalecube.cluster.membership.SyncData",
                                 "io.scalecube.cluster.gossip.GossipRequest" -> MAX_COLLECTION_ENTRIES;
                            case "io.scalecube.cluster.transport.api.Message" -> MAX_HEADERS;
                            default -> Integer.MAX_VALUE;
                        }).orElse(Integer.MAX_VALUE));
                if (limit != Integer.MAX_VALUE && (value < 0 || value > limit)) {
                    throw new InvalidObjectException("unreasonable collection count: " + value);
                }
                return value;
            }
        }) {
            ois.setObjectInputFilter(FILTER);
            int count = ois.readInt();
            if (count < 0 || count > MAX_HEADERS) {
                throw new InvalidObjectException("unreasonable header count: " + count);
            }
            Map<String, String> headers = HashMap.newHashMap(count);
            for (int i = 0; i < count; i++) {
                String key = ois.readUTF();
                headers.put(key, (String) ois.readObject());
            }
            Object data = ois.readObject();
            return Message.withHeaders(headers).data(data).build();
        }
    }

    @Override
    public void serialize(Message message, OutputStream out) throws IOException {
        try {
            MessageCodec.INSTANCE.serialize(message, out);
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
}
