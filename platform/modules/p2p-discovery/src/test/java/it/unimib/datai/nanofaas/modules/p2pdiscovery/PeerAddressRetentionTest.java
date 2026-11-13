package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import io.scalecube.cluster.Member;
import io.scalecube.cluster.membership.MembershipEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Reconnect seeds are kept for rejoining, but peer churn must not grow them without limit. */
class PeerAddressRetentionTest {
    @Test
    void churnedPeerAddressesAreBoundedAndTheMostRecentSurvive() throws Exception {
        var node = new PeerCluster("local", 0, "127.0.0.1", List.of());
        var onMembership = PeerCluster.class.getDeclaredMethod("onMembership", MembershipEvent.class);
        onMembership.setAccessible(true);
        var known = PeerCluster.class.getDeclaredField("knownAddresses");
        known.setAccessible(true);

        int churn = PeerCluster.MAX_KNOWN_ADDRESSES * 5;
        for (int i = 0; i < churn; i++) {
            var member = new Member("peer-" + i, "peer", "10.0.0.1:" + i, "default");
            onMembership.invoke(node, MembershipEvent.createAdded(member, null, 0));
            onMembership.invoke(node, MembershipEvent.createRemoved(member, null, 0));
        }

        Set<?> retained = (Set<?>) known.get(node);
        assertThat(retained).hasSize(PeerCluster.MAX_KNOWN_ADDRESSES);
        assertThat(retained.contains("10.0.0.1:" + (churn - 1))).isTrue();
        assertThat(retained.contains("10.0.0.1:0")).isFalse();
    }
}
