package it.unimib.datai.nanofaas.modules.forecasting;

import it.unimib.datai.nanofaas.modules.forecastingwasm.WasmHoltForecastAlgorithm;
import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class HoltParityTest {
    @Test void matchesJavaForDeterministicHistories() {
        var random = new Random(42);
        try (var wasm = new WasmHoltForecastAlgorithm()) {
            for(int iteration=0;iteration<100;iteration++) {
                double[] rates=random.doubles(1+random.nextInt(1000),0,10000).toArray();
                double[] original=rates.clone();
                var model=new TrendForecastSource.Holt(.01+.99*random.nextDouble(),.01+.99*random.nextDouble(),.01+.99*random.nextDouble());
                int steps=1+random.nextInt(4096);
                double[] expected=TrendForecastSource.holt(model,rates,steps);
                double[] actual=wasm.forecast(rates,steps,model.level(),model.trend(),model.damping());
                assertEquals(expected.length,actual.length);
                for(int j=0;j<steps;j++) assertEquals(expected[j],actual[j],1e-9*Math.max(1,Math.abs(expected[j])));
                assertArrayEquals(original,rates);
            }
        }
    }
}
