package it.unimib.datai.nanofaas.modules.forecastingwasm;

import it.unimib.datai.nanofaas.forecastingapi.HoltForecastAlgorithm;
import java.io.IOException;
import java.nio.ByteOrder;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.ByteSequence;

/** One trusted, bundled WASM instance; copies and invocation share one serialization boundary. */
public final class WasmHoltForecastAlgorithm implements HoltForecastAlgorithm, AutoCloseable {
    private static final int MAX_HISTORY = 100000;
    private static final int MAX_STEPS = 4096;
    private final Engine engine;
    private final Context context;
    private final Value memory;
    private final Value forecast;
    private final long inputOffset;
    private final long outputOffset;
    private boolean closed;

    public WasmHoltForecastAlgorithm() { this(loadGuest("/wasm/holt-v1.wasm")); }

    WasmHoltForecastAlgorithm(byte[] bytes) {
        Engine newEngine = Engine.create("wasm");
        Context newContext = null;
        try {
            newContext = Context.newBuilder("wasm").engine(newEngine).build();
            Value exports = newContext.eval(Source.newBuilder("wasm", ByteSequence.create(bytes), "holt-v1")
                    .buildLiteral()).newInstance().getMember("exports");
            if (function(exports, "abi_version").execute().asInt() != 1)
                throw new IllegalStateException("Unsupported Holt WASM ABI; expected 1");
            memory = exports.getMember("memory");
            if (memory == null || !memory.hasBufferElements() || memory.getBufferSize() > 16L * 1024 * 1024)
                throw new IllegalStateException("Invalid Holt WASM memory");
            inputOffset = function(exports, "input_ptr").execute().asInt();
            outputOffset = function(exports, "output_ptr").execute().asInt();
            checkRange(inputOffset, MAX_HISTORY * 8L, memory.getBufferSize());
            checkRange(outputOffset, MAX_STEPS * 8L, memory.getBufferSize());
            if (inputOffset < outputOffset + MAX_STEPS * 8L && outputOffset < inputOffset + MAX_HISTORY * 8L)
                throw new IllegalStateException("Overlapping Holt WASM buffers");
            forecast = function(exports, "forecast");
            engine = newEngine;
            context = newContext;
        } catch (RuntimeException | Error failure) {
            try { if (newContext != null) newContext.close(); }
            finally { newEngine.close(); }
            throw failure;
        }
    }

    static byte[] loadGuest(String resource) {
        try (var input = WasmHoltForecastAlgorithm.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing Holt WASM resource: " + resource);
            return input.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read Holt WASM resource: " + resource, failure);
        }
    }

    private static Value function(Value exports, String name) {
        Value value = exports.getMember(name);
        if (value == null || !value.canExecute()) throw new IllegalStateException("Missing Holt WASM export: " + name);
        return value;
    }

    private static void checkRange(long start, long bytes, long size) {
        if (start < 0 || start % 8 != 0 || start > size - bytes)
            throw new IllegalStateException("Invalid Holt WASM buffer range");
    }

    @Override
    public synchronized double[] forecast(double[] rates, int steps, double level, double trend, double damping) {
        if (closed) throw new IllegalStateException("Holt WASM runtime is closed");
        if (rates == null || rates.length < 1 || rates.length > MAX_HISTORY || steps < 1 || steps > MAX_STEPS
                || !weight(level) || !weight(trend) || !weight(damping))
            throw new IllegalArgumentException("Invalid Holt dimensions or weights");
        for (double rate : rates)
            if (!Double.isFinite(rate) || rate < 0) throw new IllegalArgumentException("Holt rates must be finite and nonnegative");
        for (int i = 0; i < rates.length; i++) memory.writeBufferDouble(ByteOrder.LITTLE_ENDIAN, inputOffset + 8L * i, rates[i]);
        int status = forecast.execute(rates.length, steps, level, trend, damping).asInt();
        if (status != 0) throw new IllegalStateException("Holt WASM failed with status " + status);
        double[] result = new double[steps];
        for (int i = 0; i < steps; i++) {
            double value = memory.readBufferDouble(ByteOrder.LITTLE_ENDIAN, outputOffset + 8L * i);
            if (!Double.isFinite(value) || value < 0) throw new IllegalStateException("Invalid Holt WASM output");
            result[i] = value;
        }
        return result;
    }

    private static boolean weight(double value) { return Double.isFinite(value) && value > 0 && value <= 1; }
    synchronized long memorySize() { return memory.getBufferSize(); }

    @Override public synchronized void close() {
        if (!closed) {
            closed = true;
            try { context.close(); } finally { engine.close(); }
        }
    }
}
