package it.unimib.datai.nanofaas.modules.forecasting;
import it.unimib.datai.nanofaas.controlplane.registry.*;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.forecastingapi.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ForecastObservationLifecycleTest {
    @Test void boundedCatalogOverflowDoesNotStopObservationAndRemovalMakesRoom() {
        var clock=new EwmaForecastSourceTest.MutableClock();var start=clock.instant();
        var source=new EwmaForecastSource("edge",.5,Duration.ofSeconds(1),Duration.ofSeconds(30),1,clock);
        var catalog=mock(FunctionCatalogView.class);var replicas=mock(ManagedReplicaControl.class);
        var first=mock(RegisteredFunction.class);var second=mock(RegisteredFunction.class);
        when(replicas.generationOf(first)).thenReturn(new FunctionGeneration("first",1));
        when(replicas.generationOf(second)).thenReturn(new FunctionGeneration("second",2));
        when(catalog.listRegistered()).thenReturn(List.of(first,second));
        var lifecycle=new ForecastObservationLifecycle(source,catalog,replicas,clock);
        assertThatCode(lifecycle::refresh).doesNotThrowAnyException();
        clock.now=start.plusSeconds(1);lifecycle.refresh();
        assertThat(source.forecast(query("first",1,clock)).status()).isEqualTo(ForecastSnapshot.Status.AVAILABLE);
        assertThat(source.forecast(query("second",2,clock)).status()).isEqualTo(ForecastSnapshot.Status.MISSING);
        when(catalog.listRegistered()).thenReturn(List.of(second));
        lifecycle.refresh();clock.now=start.plusSeconds(2);lifecycle.refresh();
        assertThat(source.forecast(query("first",1,clock)).status()).isEqualTo(ForecastSnapshot.Status.MISSING);
        assertThat(source.forecast(query("second",2,clock)).status()).isEqualTo(ForecastSnapshot.Status.AVAILABLE);
    }
    private static ForecastQuery query(String f,long generation,Clock clock) {
        return new ForecastQuery("edge",f,generation,clock.instant(),clock.instant().plusSeconds(60));
    }
}
