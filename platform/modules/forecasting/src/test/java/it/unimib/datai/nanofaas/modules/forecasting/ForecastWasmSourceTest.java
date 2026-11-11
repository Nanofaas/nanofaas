package it.unimib.datai.nanofaas.modules.forecasting;

import it.unimib.datai.nanofaas.forecastingapi.*;
import java.time.*;
import java.util.Set;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ForecastWasmSourceTest {
    static final class MutableClock extends Clock {
        volatile Instant now=Instant.EPOCH;
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
    final MutableClock clock=new MutableClock();
    final TrendForecastSource.Holt model=new TrendForecastSource.Holt(.4,.4,.95);
    final TrendForecastSource source=new TrendForecastSource("node",Duration.ofSeconds(1),Duration.ofSeconds(3),10,360,()->model,clock);
    final HoltForecastAlgorithm same=(r,s,a,b,p)->TrendForecastSource.holt(new TrendForecastSource.Holt(a,b,p),r,s);
    ForecastQuery query(String node,long generation){return new ForecastQuery(node,"f",generation,Instant.ofEpochSecond(3),Instant.ofEpochSecond(5));}
    void observed(){source.observe("f",1,Instant.EPOCH);source.record(new ExternalArrival("f",1,Instant.EPOCH,"a"));clock.now=Instant.ofEpochSecond(3);}
    @Test void preservesIdentityValidityAndEmptyHistory() {
        assertEquals(ForecastSnapshot.Status.MISSING,source.forecast(query("node",1),model,same).status());
        observed();
        var java=source.forecast(query("node",1),model);
        var wasm=source.forecast(query("node",1),model,same);
        assertEquals("holt_wasm",wasm.provider());assertEquals(java.rate(),wasm.rate());
        assertEquals(java.revision(),wasm.revision());assertEquals(java.producedAt(),wasm.producedAt());assertEquals(java.query(),wasm.query());
        assertEquals(ForecastSnapshot.Status.MISSING,source.forecast(query("other",1),model,same).status());
        assertEquals(ForecastSnapshot.Status.MISSING,source.forecast(query("node",2),model,same).status());
        source.observe("f",2,clock.instant());
        assertEquals(ForecastSnapshot.Status.MISSING,source.forecast(query("node",1),model,same).status());
        assertEquals(ForecastSnapshot.Status.MISSING,source.forecast(query("node",2),model,same).status());
    }
    @Test void failuresAreMissingNotZeroAndDoNotChangeRevision() {
        observed();var expected=source.forecast(query("node",1),model,same);
        for(HoltForecastAlgorithm broken : new HoltForecastAlgorithm[]{(r,s,a,b,p)->{throw new IllegalStateException("trap");},
                (r,s,a,b,p)->new double[]{Double.NaN},(r,s,a,b,p)->new double[s+1],(r,s,a,b,p)->null}) {
            var actual=source.forecast(query("node",1),model,broken);
            assertEquals(ForecastSnapshot.Status.MISSING,actual.status());assertNull(actual.rate());
            assertEquals(expected.revision(),actual.revision());assertEquals(expected.query(),actual.query());
            assertEquals("holt_wasm",actual.provider());
        }
    }
    @Test void calculationDoesNotHoldObservationMonitor() throws Exception {
        observed();var entered=new CountDownLatch(1);var resume=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var pending=workers.submit(()->source.forecast(query("node",1),model,(r,s,a,b,p)->{
                entered.countDown();try{assertTrue(resume.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
                return same.forecast(r,s,a,b,p);
            }));
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                workers.submit(()->{source.record(new ExternalArrival("f",1,clock.instant(),"b"));source.retain(Set.of("f#1"),Set.of());}).get(2,TimeUnit.SECONDS);
            } finally {resume.countDown();}
            assertEquals(3,pending.get(5,TimeUnit.SECONDS).revision());
        }
    }
    @Test void partialIntervalsAndMaximumHorizonUseExistingAggregation() {
        observed();var q=new ForecastQuery("node","f",1,Instant.ofEpochSecond(3,500000000),Instant.ofEpochSecond(9000));
        var expected=source.forecast(q,model);
        var actual=source.forecast(q,model,(r,s,a,b,p)->{assertEquals(4096,s);return same.forecast(r,s,a,b,p);});
        assertEquals(expected.rate(),actual.rate());
    }
    @Test void preservesStaleAndObservedZeroStates() {
        var slower=new TrendForecastSource("node",Duration.ofSeconds(10),Duration.ofSeconds(3),10,360,()->model,clock);
        slower.observe("f",1,Instant.EPOCH);
        slower.record(new ExternalArrival("f",1,Instant.EPOCH,"learned"));
        clock.now=Instant.ofEpochSecond(24);
        var q=new ForecastQuery("node","f",1,Instant.ofEpochSecond(20),Instant.ofEpochSecond(30));
        var actual=slower.forecast(q,new TrendForecastSource.Holt(1,1,1),same);
        assertEquals(ForecastSnapshot.Status.STALE,actual.status());
        assertEquals(0,actual.rate());assertEquals(Instant.ofEpochSecond(20),actual.producedAt());
        assertEquals(slower.forecast(q,model).revision(),actual.revision());
    }
}
