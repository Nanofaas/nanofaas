package it.unimib.datai.nanofaas.modules.offload.oneshot.api;
import org.junit.jupiter.api.Test;
import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.registry.*;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.forecastingapi.*;
import it.unimib.datai.nanofaas.p2papi.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ProfileEpochInputFactoryTest {
    @Test void pinnedInputUsesOneRevisionAndCannotUseUncalibratedReplicaRange() throws Exception {
        var configs=new OneShotConfigurationStore(()->true,()->true);var first=configs.replace(0,OneShotConfigurationTest.config());
        var profiles=new ServiceProfileStore();var bytes=ServiceProfileStoreTest.fixture();profiles.replace("profile",0,ServiceProfileStore.hash(bytes),bytes);
        var control=mock(ManagedReplicaControl.class);var peers=mock(PeerTransport.class);
        when(peers.localEndpoint()).thenReturn(Optional.of(new PeerEndpoint("a","run",java.net.URI.create("http://a:8080"))));
        var cc=new ConcurrencyControlConfig(ConcurrencyControlMode.STATIC_PER_POD,1,1,1,0L,0L,.5,.15,null,null);
        var spec=new FunctionSpec("f","image@sha256:"+"b".repeat(64),List.of(),Map.of("NANOFAAS_ONE_SHOT_PROFILE","true","NANOFAAS_MAX_CONCURRENT_HANDLERS","1"),new ResourceSpec(null,new ResourceQuantity(java.math.BigDecimal.ONE,1)),30000,8,100,0,null,ExecutionMode.DEPLOYMENT,RuntimeMode.HTTP,null,new ScalingConfig(ScalingStrategy.NONE,0,8,List.of(),cc));
        var f=new RegisteredFunction(spec,new DeploymentMetadata(ExecutionMode.DEPLOYMENT,ExecutionMode.DEPLOYMENT,"local",null).withDesiredReplicas(0));
        when(control.generationOf(f)).thenReturn(new FunctionGeneration("f",3));when(control.supportsPhysicalReplicaControl(any())).thenReturn(true);
        ForecastSource forecasts=query->new ForecastSnapshot(query,ForecastSnapshot.Status.AVAILABLE,3.0,1,"oracle",Instant.EPOCH);
        var factory=new ProfileEpochInputFactory(configs,profiles,()->List.of(f),control,peers,forecasts);
        var from=Instant.EPOCH.plusSeconds(300);var until=from.plusSeconds(300);
        factory.pin(1,from,until,first);configs.replace(1,OneShotConfigurationTest.config());
        assertThat(factory.freeze(1,from,until).revision()).isEqualTo(1);
        factory.unpin(1);assertThat(factory.freeze(1,from,until).revision()).isEqualTo(2);
        bytes=new String(bytes,java.nio.charset.StandardCharsets.UTF_8).replace("\"maxReplicas\": 8","\"maxReplicas\": 2").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        profiles.replace("profile",1,ServiceProfileStore.hash(bytes),bytes);
        assertThatThrownBy(()->factory.freeze(2,from,until)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("replica range");
    }
}
