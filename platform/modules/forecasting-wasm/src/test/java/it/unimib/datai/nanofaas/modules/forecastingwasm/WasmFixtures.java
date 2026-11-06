package it.unimib.datai.nanofaas.modules.forecastingwasm;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Tiny real Wasm modules exercising the adapter's trust boundary, without a WAT compiler. */
final class WasmFixtures {
    static byte[] module(int abi, int input, int output, int status, boolean trap, boolean nan, String forecastName) {
        var result = new ByteArrayOutputStream();
        result.writeBytes(new byte[]{0,97,115,109,1,0,0,0});
        section(result, 1, new byte[]{2,96,0,1,127,96,5,127,127,124,124,124,1,127});
        section(result, 3, new byte[]{4,0,0,0,1});
        section(result, 5, new byte[]{1,1,16,16});
        var exports = new ByteArrayOutputStream(); exports.write(5);
        export(exports, "memory", 2, 0); export(exports, "abi_version", 0, 0);
        export(exports, "input_ptr", 0, 1); export(exports, "output_ptr", 0, 2);
        export(exports, forecastName, 0, 3); section(result, 7, exports.toByteArray());
        var code = new ByteArrayOutputStream(); code.write(4);
        body(code, constant(abi)); body(code, constant(input)); body(code, constant(output));
        var function = new ByteArrayOutputStream();
        if (trap) function.write(0);
        if (nan) {
            function.writeBytes(constant(output)); function.write(0x44);
            function.writeBytes(new byte[]{0,0,0,0,0,0,(byte)0xf8,0x7f});
            function.writeBytes(new byte[]{0x39,3,0});
        }
        function.writeBytes(constant(status)); body(code, function.toByteArray());
        section(result, 10, code.toByteArray()); return result.toByteArray();
    }
    private static byte[] constant(int value) {
        var out = new ByteArrayOutputStream(); out.write(0x41);
        boolean more;
        do {
            int b = value & 127; value >>= 7;
            more = !((value == 0 && (b & 64) == 0) || (value == -1 && (b & 64) != 0));
            out.write(more ? b | 128 : b);
        } while (more);
        return out.toByteArray();
    }
    private static void uint(ByteArrayOutputStream out, int n) {
        do { int b = n & 127; n >>>= 7; out.write(n == 0 ? b : b | 128); } while (n != 0);
    }
    private static void section(ByteArrayOutputStream out, int id, byte[] bytes) {
        out.write(id); uint(out, bytes.length); out.writeBytes(bytes);
    }
    private static void export(ByteArrayOutputStream out, String name, int kind, int index) {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        uint(out, bytes.length); out.writeBytes(bytes); out.write(kind); out.write(index);
    }
    private static void body(ByteArrayOutputStream out, byte[] ops) {
        uint(out, ops.length + 2); out.write(0); out.writeBytes(ops); out.write(0x0b);
    }
}
