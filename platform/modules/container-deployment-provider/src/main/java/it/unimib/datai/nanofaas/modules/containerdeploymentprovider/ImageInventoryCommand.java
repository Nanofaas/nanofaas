package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Inventory commands have their own deadline and drain both pipes while the process runs. */
final class ImageInventoryCommand {
    String run(List<String> arguments, Duration timeout, int maxBytes) {
        Process process = null;
        FutureTask<byte[]> stdout = null;
        FutureTask<byte[]> stderr = null;
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            process = new ProcessBuilder(arguments).start();
            Process running = process;
            stdout = new FutureTask<>(() -> read(running, running.getInputStream(), maxBytes));
            stderr = new FutureTask<>(() -> read(running, running.getErrorStream(), maxBytes));
            Thread.startVirtualThread(stdout);
            Thread.startVirtualThread(stderr);
            if (!process.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                throw new IllegalStateException("inventory deadline exceeded");
            }
            byte[] result = stdout.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            stderr.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (process.exitValue() != 0) throw new IllegalStateException("inventory command failed");
            return new String(result, StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("inventory interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("inventory unavailable", e);
        } finally {
            if (process != null) terminate(process);
            if (stdout != null) stdout.cancel(true);
            if (stderr != null) stderr.cancel(true);
        }
    }

    private static byte[] read(Process process, InputStream stream, int maxBytes) throws Exception {
        try (stream; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (bytes.size() + count > maxBytes) {
                    terminate(process);
                    throw new IllegalStateException("inventory output exceeded limit");
                }
                bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        }
    }

    private static void terminate(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
