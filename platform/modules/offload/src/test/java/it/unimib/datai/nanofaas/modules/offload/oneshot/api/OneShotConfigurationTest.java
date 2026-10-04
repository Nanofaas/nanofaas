package it.unimib.datai.nanofaas.modules.offload.oneshot.api;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.net.URI;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class OneShotConfigurationTest {
    static OneShotSettings config() {
        return new OneShotSettings(1,"profile","sha256:"+"a".repeat(64),"workflow-validation",true,URI.create("http://cloud:8080"),8,1,Duration.ofMinutes(5),Duration.ofSeconds(20),false,Instant.EPOCH,Duration.ofSeconds(2),.1,1,Map.of("f",new OneShotSettings.Function(3,"sha256:"+"b".repeat(64),"sha256:"+"c".repeat(64),.8,1,.9,.1)));
    }
    @Test void configurationCannotActivateWithoutBothPorts() {
        var store=new OneShotConfigurationStore(()->false,()->true);
        assertThatThrownBy(()->store.replace(0,config())).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.snapshot()).isEmpty();
    }
    @Test void updatesAreAtomicAndFrozenSnapshotDoesNotChange() {
        var store=new OneShotConfigurationStore(()->true,()->true);
        var first=store.replace(0,config());
        var second=store.replace(1,config());
        assertThat(first.revision()).isEqualTo(1);assertThat(second.revision()).isEqualTo(2);
        assertThatThrownBy(()->store.replace(1,config())).isInstanceOf(OneShotConfigurationStore.RevisionConflict.class);
    }
}
