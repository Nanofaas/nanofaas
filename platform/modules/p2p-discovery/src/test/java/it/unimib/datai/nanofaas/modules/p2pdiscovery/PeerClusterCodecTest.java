package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import io.scalecube.cluster.Member;
import io.scalecube.cluster.transport.api.Message;
import io.scalecube.cluster.transport.api.MessageCodec;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PeerClusterCodecTest {
    private final PeerClusterCodec codec = new PeerClusterCodec();

    private static byte[] encode(Message m) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MessageCodec.INSTANCE.serialize(m, out);   // scalecube's own writer: what a real peer sends
        return out.toByteArray();
    }

    private Message decode(byte[] bytes) throws Exception {
        return codec.deserialize(new ByteArrayInputStream(bytes));
    }

    @Test
    void applicationMessageRoundTrips() throws Exception {
        Message in = Message.withQualifier("p2p.msg").header("topic", "t").sender("a:1").data(new byte[]{1, 2, 3}).build();
        Message out = decode(encode(in));
        assertThat(out.qualifier()).isEqualTo("p2p.msg");
        assertThat(out.header("topic")).isEqualTo("t");
        assertThat(out.sender()).isEqualTo("a:1");
        assertThat((byte[]) out.data()).containsExactly(1, 2, 3);
    }

    @Test
    void scalecubeSystemTypesStillPass() throws Exception {
        Member member = new Member("id", "alias", "ns", "127.0.0.1:1");
        Message out = decode(encode(Message.withQualifier("sc/membership/sync").data(member).build()));
        assertThat((Member) out.data()).isEqualTo(member);
    }

    @Test
    void messageWithoutDataRoundTrips() throws Exception {
        assertThat((Object) decode(encode(Message.withQualifier("q").build())).data()).isNull();
    }

    @Test
    void absurdHeaderCountIsRejectedWithoutAllocating() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeInt(1 << 30);
            oos.writeUTF("k");
            oos.writeObject("v");
        }
        byte[] frame = out.toByteArray();
        assertThatThrownBy(() -> decode(frame)).isInstanceOf(IOException.class);
    }

    @Test
    void externalizableCollectionCountsAreCheckedBeforeAllocation() throws Exception {
        for (String name : List.of("io.scalecube.cluster.membership.SyncData",
                "io.scalecube.cluster.gossip.GossipRequest")) {
            var constructor = Class.forName(name).getDeclaredConstructor();
            constructor.setAccessible(true);
            Object data = constructor.newInstance();
            String fieldName = name.endsWith("SyncData") ? "membership" : "gossips";
            var field = data.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(data, List.of());
            if (name.endsWith("GossipRequest")) {
                var from = data.getClass().getDeclaredField("from");
                from.setAccessible(true);
                from.set(data, "peer");
            }
            byte[] valid = encode(Message.withQualifier("q").data(data).build());
            assertThat((Object) decode(valid).data()).isInstanceOf(data.getClass());
            for (int count : new int[]{-1, 10001, Integer.MAX_VALUE}) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (ObjectOutputStream oos = new ObjectOutputStream(out) {
                    @Override
                    public void writeInt(int value) throws IOException {
                        super.writeInt(value == 0 ? count : value);
                    }
                }) {
                    Message.withQualifier("q").data(data).build().writeExternal(oos);
                }
                assertThatThrownBy(() -> decode(out.toByteArray()))
                        .isInstanceOf(InvalidObjectException.class)
                        .hasMessageContaining("collection count");
            }
        }
    }

    @Test
    void nestedMessageHeadersAreBoundedToo() throws Exception {
        var nested = Message.withQualifier("nested");
        for (int i = 0; i < PeerClusterCodec.MAX_HEADERS; i++) {
            nested.header("h" + i, "v");
        }
        byte[] frame = encode(Message.withQualifier("q").data(nested.build()).build());
        assertThatThrownBy(() -> decode(frame)).isInstanceOf(InvalidObjectException.class);
    }

    @Test
    void largeMembershipIncarnationsAreNotTreatedAsCollectionCounts() throws Exception {
        Class<?> status = Class.forName("io.scalecube.cluster.membership.MemberStatus");
        var constructor = Class.forName("io.scalecube.cluster.membership.MembershipRecord")
                .getDeclaredConstructor(Member.class, status, int.class);
        constructor.setAccessible(true);
        Object record = constructor.newInstance(new Member("id", "alias", "ns", "127.0.0.1:1"),
                status.getEnumConstants()[0], Integer.MAX_VALUE);
        var syncConstructor = Class.forName("io.scalecube.cluster.membership.SyncData")
                .getDeclaredConstructor(java.util.Collection.class);
        syncConstructor.setAccessible(true);
        Object sync = syncConstructor.newInstance(List.of(record));
        byte[] frame = encode(Message.withQualifier("q").data(sync).build());
        assertThat((Object) decode(frame).data()).isInstanceOf(sync.getClass());
    }

    @Test
    void classesOutsideTheAllowListAreRejected() throws Exception {
        byte[] frame = encode(Message.withQualifier("q").data(new AtomicLong(7)).build());
        assertThatThrownBy(() -> decode(frame)).isInstanceOf(IOException.class);
    }
}
