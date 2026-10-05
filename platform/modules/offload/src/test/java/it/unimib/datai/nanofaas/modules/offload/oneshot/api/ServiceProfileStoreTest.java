package it.unimib.datai.nanofaas.modules.offload.oneshot.api;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class ServiceProfileStoreTest {
    static byte[] fixture() throws Exception { try(var in=ServiceProfileStoreTest.class.getResourceAsStream("/oneshot/profile.json")) {return in.readAllBytes();} }
    @Test void measuredMultipassEvidenceMatchesOnlyItsEnvironmentAndPurpose() throws Exception {
        var store=new ServiceProfileStore();var bytes=fixture();store.replace("profile",0,ServiceProfileStore.hash(bytes),bytes);
        assertThat(store.compatible(OneShotConfigurationTest.config()).profile().synthetic()).isFalse();
        var settings=OneShotConfigurationTest.config();
        var other=new OneShotSettings(1,settings.profileId(),"sha256:"+"e".repeat(64),settings.purpose(),true,settings.cloudUri(),8,1,settings.period(),settings.leadTime(),false,settings.anchor(),settings.preparationBudget(),.1,1,settings.functions());
        assertThatThrownBy(()->store.compatible(other)).isInstanceOf(IllegalArgumentException.class);
        bytes=new String(fixture(),java.nio.charset.StandardCharsets.UTF_8).replace("workflow-validation","scientific-experiment").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        store.replace("profile",1,ServiceProfileStore.hash(bytes),bytes);
        assertThatThrownBy(()->store.compatible(settings)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void syntheticEvidenceRequiresExplicitWorkflowTestOptIn() throws Exception {
        var store=new ServiceProfileStore();var bytes=new String(fixture(),java.nio.charset.StandardCharsets.UTF_8).replace("\"synthetic\": false","\"synthetic\": true").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        store.replace("profile",0,ServiceProfileStore.hash(bytes),bytes);
        var s=OneShotConfigurationTest.config();
        assertThat(store.compatible(s).profile().synthetic()).isTrue();
        var normal=new OneShotSettings(1,s.profileId(),s.environmentFingerprint(),s.purpose(),false,s.cloudUri(),8,1,s.period(),s.leadTime(),false,s.anchor(),s.preparationBudget(),.1,1,s.functions());
        assertThatThrownBy(()->store.compatible(normal)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void mismatchedContentHashCannotInstallAProfile() {
        var store=new ServiceProfileStore();
        assertThatThrownBy(()->store.replace("p",0,"sha256:"+"a".repeat(64),"{}".getBytes(java.nio.charset.StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.get("p")).isEmpty();
    }
}
