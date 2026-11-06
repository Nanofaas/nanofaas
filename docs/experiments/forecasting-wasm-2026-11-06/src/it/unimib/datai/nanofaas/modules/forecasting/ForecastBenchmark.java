package it.unimib.datai.nanofaas.modules.forecasting;

import it.unimib.datai.nanofaas.controlplane.ControlPlaneApplication;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionCatalogView;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.forecastingapi.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.json.JsonParserFactory;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/** Experiment-only entry point: real Spring application, no new production endpoint. */
public final class ForecastBenchmark {
    private static volatile double sink;
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static String request(String method, String path, String body) throws Exception {
        var response = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:8080" + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new AssertionError(response.statusCode() + ": " + response.body());
        return response.body();
    }
    private static void select(String provider) throws Exception {
        var snapshot = JsonParserFactory.getJsonParser().parseMap(request("GET", "/v1/admin/runtime-config", ""));
        request("PATCH", "/v1/admin/runtime-config/forecasting", "{\"expectedRevision\":" + snapshot.get("revision")
                + ",\"values\":{\"provider\":\"" + provider + "\"}}");
    }
    private static double[] run(HoltForecastAlgorithm algorithm, double[] rates, int steps) {
        var result = algorithm.forecast(rates, steps, .4, .4, .95);
        if (result.length != steps || !Double.isFinite(result[steps - 1])) throw new AssertionError("invalid forecast");
        sink = result[steps - 1];
        return result;
    }
    public static void main(String[] args) throws Exception {
        long boot = System.nanoTime();
        var application = new SpringApplication(ControlPlaneApplication.class);
        application.setMainApplicationClass(ControlPlaneApplication.class);
        try (var context = application.run(args)) {
            System.out.printf(Locale.ROOT, "BENCH {\"kind\":\"startup\",\"ms\":%.3f,\"heap_max\":%d}%n",
                    (System.nanoTime()-boot)/1e6, Runtime.getRuntime().maxMemory());
            var wasm = context.getBeanProvider(HoltForecastAlgorithm.class).getIfAvailable();
            String mode = context.getEnvironment().getProperty("benchmark.mode", "java");
            if (mode.equals("wasm") && wasm == null) throw new AssertionError("WASM missing");
            HoltForecastAlgorithm java = (r,s,a,b,p) -> TrendForecastSource.holt(new TrendForecastSource.Holt(a,b,p),r,s);
            HoltForecastAlgorithm algorithm = mode.equals("wasm") ? wasm : java;
            long coldStart=System.nanoTime();
            run(algorithm,new Random(42).doubles(360,0,300).toArray(),60);
            System.out.printf("BENCH {\"kind\":\"cold_call\",\"ns\":%d}%n",System.nanoTime()-coldStart);
            request("POST", "/v1/functions", "{\"name\":\"wasm-bench\",\"image\":\"local/echo\",\"executionMode\":\"LOCAL\",\"concurrency\":4}");
            var function = context.getBean(FunctionCatalogView.class).listRegistered().stream()
                    .filter(f -> f.name().equals("wasm-bench")).findFirst().orElseThrow();
            var generation = context.getBean(FunctionCapacityRegistry.class).activeGeneration(function.name());
            // The provider-free coordinator has no generation registry; seed observation in this test client.
            context.getBean(TrendForecastSource.class).observe(function.name(), generation.id(), Instant.now());
            context.getBean(EwmaForecastSource.class).observe(function.name(), generation.id(), Instant.now());
            for (int i=0;i<40;i++) {
                request("POST", "/v1/functions/wasm-bench:invoke", "{\"input\":{\"value\":42}}");
                Thread.sleep(100);
            }
            var now = Instant.now();
            var query = new ForecastQuery("local", function.name(), generation.id(), now, now.plusSeconds(3));
            var source = context.getBean(ForecastSource.class);
            select("holt");
            var baseline = source.forecast(query);
            if (baseline.status()!=ForecastSnapshot.Status.AVAILABLE || baseline.rate()<=0) throw new AssertionError(baseline);
            if (mode.equals("wasm")) {
                select("holt_wasm");
                var actual = source.forecast(query);
                if (actual.status()!=ForecastSnapshot.Status.AVAILABLE || !actual.provider().equals("holt_wasm")) throw new AssertionError(actual);
                // HTTP toggle can cross a window boundary; numeric parity is checked on identical arrays below.
                select("holt");
                if (source.forecast(query).status()!=ForecastSnapshot.Status.AVAILABLE) throw new AssertionError("toggle lost history");
            }
            System.out.println("BENCH {\"kind\":\"smoke\",\"ok\":true}");
            for (int history : new int[]{60,360,4096}) for (int steps : new int[]{1,60,4096}) {
                double[] rates = new Random(42).doubles(history,0,300).toArray();
                long start=System.nanoTime();run(algorithm,rates,steps);long cold=System.nanoTime()-start;
                double[] expected=run(java,rates,steps), actual=run(algorithm,rates,steps);
                for(int i=0;i<steps;i++) if(Math.abs(expected[i]-actual[i])>1e-9*Math.max(1,Math.abs(expected[i]))) throw new AssertionError("parity");
                for(int i=0;i<200;i++) run(algorithm,rates,steps);
                long[] times=new long[300];
                for(int i=0;i<times.length;i++){start=System.nanoTime();run(algorithm,rates,steps);times[i]=System.nanoTime()-start;}
                Arrays.sort(times);
                System.out.printf(Locale.ROOT,"BENCH {\"kind\":\"micro\",\"history\":%d,\"steps\":%d,\"first_shape_ns\":%d,\"p50_ns\":%d,\"p95_ns\":%d}%n",history,steps,cold,times[149],times[284]);
            }
            run(algorithm,new double[100000],4096);
            var stop = new AtomicBoolean();
            var observer = context.getBean(ExternalArrivalObserver.class);
            var arrivals = Thread.ofPlatform().start(() -> {
                while(!stop.get()) {
                    observer.record(new ExternalArrival(function.name(),generation.id(),Instant.now(),"bench"));
                    LockSupport.parkNanos(1_000_000);
                }
            });
            select(mode.equals("wasm") ? "holt_wasm" : "holt");
            long[] concurrent=new long[300];
            try {
                for(int i=0;i<concurrent.length;i++) {
                    now=Instant.now();long start=System.nanoTime();
                    if(source.forecast(new ForecastQuery("local",function.name(),generation.id(),now,now.plusSeconds(60))).status()!=ForecastSnapshot.Status.AVAILABLE) throw new AssertionError("concurrent forecast");
                    concurrent[i]=System.nanoTime()-start;
                }
            } finally {stop.set(true);arrivals.join();}
            Arrays.sort(concurrent);
            System.out.printf("BENCH {\"kind\":\"concurrent_snapshot\",\"p50_ns\":%d,\"p95_ns\":%d}%n",concurrent[149],concurrent[284]);
            double[] rates=new Random(42).doubles(360,0,300).toArray();
            for(int i=0;i<1000;i++)run(algorithm,rates,60);
            long count=0,start=System.nanoTime(),end=start+60_000_000_000L;
            System.out.println("BENCH {\"kind\":\"sustained_start\"}");
            do {run(algorithm,rates,60);count++;} while(System.nanoTime()<end);
            System.out.printf(Locale.ROOT,"BENCH {\"kind\":\"sustained\",\"calls\":%d,\"seconds\":%.6f,\"checksum\":%.6f}%n",count,(System.nanoTime()-start)/1e9,sink);
        } finally { HTTP.close(); }
        System.out.println("BENCH {\"kind\":\"closed\",\"ok\":true}");
    }
}
