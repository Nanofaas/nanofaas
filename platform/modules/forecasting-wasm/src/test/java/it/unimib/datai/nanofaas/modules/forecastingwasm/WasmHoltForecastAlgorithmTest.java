package it.unimib.datai.nanofaas.modules.forecastingwasm;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class WasmHoltForecastAlgorithmTest {
    @Test void computesRealGuestValidatesInputsAndClosesIdempotently() {
        var algorithm = new WasmHoltForecastAlgorithm();
        try {
            double[] input = {1,2,3};
            assertArrayEquals(new double[]{4,5}, algorithm.forecast(input, 2, 1,1,1));
            assertArrayEquals(new double[]{1,2,3}, input);
            for (double[] bad : new double[][]{{}, {-1}, {Double.NaN}, {Double.POSITIVE_INFINITY}, new double[100001]})
                assertThrows(IllegalArgumentException.class, () -> algorithm.forecast(bad, 1, .4,.4,.95));
            assertThrows(IllegalArgumentException.class, () -> algorithm.forecast(null, 1, 1,1,1));
            assertThrows(IllegalArgumentException.class, () -> algorithm.forecast(input, 0, 1,1,1));
            assertThrows(IllegalArgumentException.class, () -> algorithm.forecast(input, 4097, 1,1,1));
            for (double bad : new double[]{0,-1,1.1,Double.NaN,Double.POSITIVE_INFINITY}) {
                assertThrows(IllegalArgumentException.class, () -> algorithm.forecast(input, 1, bad,1,1));
                assertThrows(IllegalArgumentException.class, () -> algorithm.forecast(input, 1, 1,bad,1));
                assertThrows(IllegalArgumentException.class, () -> algorithm.forecast(input, 1, 1,1,bad));
            }
            assertThrows(IllegalStateException.class, () -> algorithm.forecast(new double[]{0,Double.MAX_VALUE},1,1,1,1));
            long size = algorithm.memorySize();
            double[] max = new double[100000]; Arrays.fill(max,7);
            for (int i=0;i<10;i++) {
                double[] out=algorithm.forecast(max,4096,.4,.4,.95);
                assertEquals(4096,out.length); assertEquals(7,out[4095]);
            }
            assertEquals(size,algorithm.memorySize());
        } finally { algorithm.close(); algorithm.close(); }
        assertThrows(IllegalStateException.class, () -> algorithm.forecast(new double[]{1},1,1,1,1));
    }
    @Test void matchesSharedKnownVectors() throws Exception {
        try (var input = getClass().getResourceAsStream("/holt-vectors.json"); var algorithm = new WasmHoltForecastAlgorithm()) {
            var parser = org.springframework.boot.json.JsonParserFactory.getJsonParser();
            for (Object item : parser.parseList(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))) {
                var vector = (java.util.Map<?, ?>) item;
                double[] rates = ((java.util.List<?>) vector.get("rates")).stream().mapToDouble(v -> ((Number) v).doubleValue()).toArray();
                double[] expected = ((java.util.List<?>) vector.get("expected")).stream().mapToDouble(v -> ((Number) v).doubleValue()).toArray();
                assertArrayEquals(expected, algorithm.forecast(rates, ((Number) vector.get("steps")).intValue(),
                        ((Number) vector.get("level")).doubleValue(), ((Number) vector.get("trend")).doubleValue(),
                        ((Number) vector.get("damping")).doubleValue()), 1e-9);
            }
        }
    }
    @Test void serializesConcurrentCallsWithoutSharingResults() throws Exception {
        try (var algorithm=new WasmHoltForecastAlgorithm(); var executor=Executors.newFixedThreadPool(4)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<double[]>>();
            for(int i=1;i<=40;i++) { final int n=i; tasks.add(executor.submit(()->algorithm.forecast(new double[]{n},60,1,1,1))); }
            for(int i=0;i<40;i++) assertEquals(i+1,tasks.get(i).get(10,java.util.concurrent.TimeUnit.SECONDS)[59]);
        }
    }
    @Test void rejectsInvalidGuestBeforeUse() {
        assertThrows(IllegalStateException.class, () -> WasmHoltForecastAlgorithm.loadGuest("/missing.wasm"));
        assertThrows(RuntimeException.class,()->new WasmHoltForecastAlgorithm(new byte[]{0,1,2}));
        for(byte[] guest : new byte[][]{
                WasmFixtures.module(2,0,800000,0,false,false,"forecast"),
                WasmFixtures.module(1,1,800000,0,false,false,"forecast"),
                WasmFixtures.module(1,-8,800000,0,false,false,"forecast"),
                WasmFixtures.module(1,0,0,0,false,false,"forecast"),
                WasmFixtures.module(1,0,1048576,0,false,false,"forecast"),
                WasmFixtures.module(1,0,800000,0,false,false,"missing")}) {
            assertThrows(RuntimeException.class,()->new WasmHoltForecastAlgorithm(guest));
        }
    }
    @Test void rejectsTrapsStatusFailuresAndNonFiniteOutput() {
        for(byte[] guest : new byte[][]{
                WasmFixtures.module(1,0,800000,1,false,false,"forecast"),
                WasmFixtures.module(1,0,800000,0,true,false,"forecast"),
                WasmFixtures.module(1,0,800000,0,false,true,"forecast")}) {
            try(var algorithm=new WasmHoltForecastAlgorithm(guest)) {
                assertThrows(RuntimeException.class,()->algorithm.forecast(new double[]{1},1,1,1,1));
            }
        }
    }
}
