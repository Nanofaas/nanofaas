package it.unimib.datai.nanofaas.modules.forecastingwasm;

import java.util.HexFormat;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.io.ByteSequence;

public final class WasmEmbeddingProbe {
    static int add() {
        byte[] wasm = HexFormat.of().parseHex("0061736d0100000001070160027f7f017f030201000707010361646400000a09010700200020016a0b");
        try (Engine engine = Engine.create("wasm"); Context context = Context.newBuilder("wasm").engine(engine).build()) {
            return context.eval(Source.newBuilder("wasm", ByteSequence.create(wasm), "add").buildLiteral())
                    .newInstance().getMember("exports").getMember("add").execute(20, 22).asInt();
        }
    }
    public static void main(String[] args) {
        int answer = add();
        if (answer != 42) throw new AssertionError(answer);
        System.out.println("WASM_NATIVE_PROBE=" + answer);
    }
}
