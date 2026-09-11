import com.sun.management.ThreadMXBean;
import com.sun.net.httpserver.HttpServer;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Test-only reflective launcher. No transformed classes, bean replacement or lifecycle writes. */
public final class P19Probe {
    private static final ThreadMXBean ALLOC = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static volatile Object allocationSink;
    private static Object context;
    private static final ConcurrentHashMap<String, Map<String, Object>> terminalEvents = new ConcurrentHashMap<>();
    private static final String PREFIX = "it.unimib.datai.nanofaas.controlplane.";

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("validate-allocation")) {
            Map<String, Object> validation = new TreeMap<>();
            for (boolean virtual : new boolean[]{false, true}) {
                long before = ALLOC.getTotalThreadAllocatedBytes();
                Runnable work = () -> {
                    for (int n = 0; n < 1024; n++) allocationSink = new byte[65536];
                };
                Thread thread = virtual ? Thread.ofVirtual().unstarted(work) : new Thread(work);
                thread.start();
                thread.join();
                long delta = ALLOC.getTotalThreadAllocatedBytes() - before;
                validation.put(virtual ? "virtual_exited" : "platform_exited", Map.of(
                        "payload_bytes", 67108864L, "observed_bytes", delta,
                        "valid", delta >= 67108864L && delta < 67108864L * 1.15));
                if (delta < 67108864L || delta >= 67108864L * 1.15) throw new AssertionError(validation);
            }
            System.out.println(json(validation));
            return;
        }
        Class<?> app = Class.forName(PREFIX + "ControlPlaneApplication");
        context = Class.forName("org.springframework.boot.SpringApplication")
                .getMethod("run", Class.class, String[].class).invoke(null, app, args);
        if (Boolean.getBoolean("p19.observeTerminals")) {
            Object store = bean("execution.ExecutionStore");
            java.util.function.Consumer<Object> listener = record -> {
                try {
                    if (terminalEvents.size() >= 4096) throw new IllegalStateException("observer event bound exceeded");
                    Object output = call(record, "output");
                    String inputId = output instanceof Map<?, ?> map ? String.valueOf(map.get("id")) : "unavailable";
                    terminalEvents.put((String) call(record, "executionId"), Map.of("status", call(record, "state").toString().toLowerCase(Locale.ROOT),
                            "output_id", inputId, "terminal_observed_ns", System.nanoTime()));
                } catch (Exception failure) {
                    throw new IllegalStateException("P19 terminal observation failed", failure);
                }
            };
            store.getClass().getMethod("onTerminal", java.util.function.Consumer.class).invoke(store, listener);
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", Integer.getInteger("p19.probePort", 18082)), 8);
        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                Map<String, Object> result;
                if (path.startsWith("/terminal/")) {
                    result = terminalEvents.remove(path.substring("/terminal/".length()));
                    if (result == null) result = Map.of("status", "pending");
                } else if (path.equals("/alloc")) {
                    result = Map.of("monotonic_ns", System.nanoTime(), "allocated_bytes", ALLOC.getTotalThreadAllocatedBytes());
                } else if (path.equals("/gc")) {
                    // Existing size accessors perform cache maintenance. Do that before GC,
                    // so post-GC heap is not sampled before observer-triggered reclamation.
                    snapshot();
                    long before = gcCount();
                    long start = System.nanoTime();
                    System.gc();
                    long after = gcCount();
                    result = snapshot();
                    result.put("gc_start_ns", start);
                    result.put("gc_end_ns", System.nanoTime());
                    result.put("gc_count_before", before);
                    result.put("gc_count_after", after);
                    result.put("gc_verified", after > before);
                } else {
                    result = snapshot();
                }
                byte[] bytes = json(result).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Throwable failure) {
                byte[] bytes = failure.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        server.start();
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(g -> Math.max(0, g.getCollectionCount())).sum();
    }

    private static Object call(Object target, String name) throws Exception {
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static Object bean(String suffix) throws Exception {
        return context.getClass().getMethod("getBean", Class.class).invoke(context, Class.forName(PREFIX + suffix));
    }

    private static Map<String, Object> snapshot() throws Exception {
        Map<String, Object> result = new TreeMap<>();
        result.put("monotonic_ns", System.nanoTime());
        result.put("pid", ProcessHandle.current().pid());
        result.put("heap_used_bytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        result.put("allocated_bytes", ALLOC.getTotalThreadAllocatedBytes());
        result.put("platform_threads", ALLOC.getThreadCount());
        result.put("gc_count", gcCount());
        result.put("observer_terminal_events", terminalEvents.size());
        Map<String, Object> populations = new TreeMap<>();
        String[][] accessors = {
                {"live", "execution.ExecutionStore", "inFlightCount"},
                {"outcomes", "execution.ExecutionStore", "size"},
                {"keys", "execution.IdempotencyStore", "size"},
                {"execution_reservations", "capacity.InvocationCapacity", "executionReservedGlobally"},
                {"canonical_input_bytes", "capacity.InvocationCapacity", "inputReservedGlobally"},
                {"physical_input_copy_bytes", "capacity.InvocationCapacity", "physicalInputCopyReservedGlobally"},
                {"waiters", "capacity.WaiterCapacity", "reservedGlobally"},
                {"retained_waiters", "capacity.WaiterCapacity", "retainedWaiters"}
        };
        for (String[] accessor : accessors) {
            try {
                populations.put(accessor[0], Map.of("status", "observed", "value", call(bean(accessor[1]), accessor[2]),
                        "source", accessor[1] + "." + accessor[2]));
            } catch (Exception failure) {
                populations.put(accessor[0], Map.of("status", "unavailable", "reason", failure.toString()));
            }
        }
        result.put("populations", populations);
        // Read fields only on already-created singletons. Never instantiate optional beans.
        Object factory = call(context, "getBeanFactory");
        String[] names = (String[]) call(factory, "getSingletonNames");
        Map<String, Object> fields = new TreeMap<>();
        List<String> beans = new ArrayList<>();
        for (String name : names) {
            Object singleton = factory.getClass().getMethod("getSingleton", String.class).invoke(factory, name);
            if (singleton == null || !singleton.getClass().getName().startsWith("it.unimib.datai.nanofaas")) continue;
            beans.add(name + ":" + singleton.getClass().getName());
            inspect(singleton, name, fields, 0);
        }
        Collections.sort(beans);
        result.put("application_singletons", beans);
        result.put("owner_fields", fields);
        Map<String, Object> buffers = new TreeMap<>();
        for (var pool : ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class)) {
            buffers.put(pool.getName(), Map.of("count", pool.getCount(), "used_bytes", pool.getMemoryUsed()));
        }
        result.put("buffers", buffers);
        return result;
    }

    private static void inspect(Object owner, String path, Map<String, Object> result, int depth) {
        if (depth > 2) return;
        for (Class<?> type = owner.getClass(); type != null && type.getName().startsWith("it.unimib.datai.nanofaas"); type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                String key = path + "." + field.getName();
                try {
                    field.setAccessible(true);
                    Object value = field.get(owner);
                    if (value == null) continue;
                    if (value instanceof Map<?, ?> map) result.put(key + ".count", map.size());
                    else if (value instanceof Collection<?> collection) result.put(key + ".count", collection.size());
                    else if (value instanceof AtomicLong number) result.put(key, number.get());
                    else if (value instanceof AtomicInteger number) result.put(key, number.get());
                    else if (value instanceof Semaphore permits) result.put(key + ".available_permits", permits.availablePermits());
                    else if (value instanceof ThreadPoolExecutor executor) {
                        result.put(key + ".active", executor.getActiveCount());
                        result.put(key + ".queued", executor.getQueue().size());
                        result.put(key + ".pool_size", executor.getPoolSize());
                    } else if (value.getClass().getName().startsWith("com.github.benmanes.caffeine")) {
                        result.put(key + ".estimated_count", call(value, "estimatedSize"));
                        Object policy = call(value, "policy");
                        Optional<?> eviction = (Optional<?>) call(policy, "eviction");
                        if (eviction.isPresent()) {
                            OptionalLong weight = (OptionalLong) call(eviction.get(), "weightedSize");
                            if (weight.isPresent()) result.put(key + ".weighted_bytes", weight.getAsLong());
                        }
                    } else if (value.getClass().getName().startsWith("it.unimib.datai.nanofaas")) inspect(value, key, result, depth + 1);
                } catch (Exception failure) {
                    result.put(key + ".unavailable", failure.toString());
                }
            }
        }
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            List<String> entries = new ArrayList<>();
            map.forEach((key, item) -> entries.add(json(key.toString()) + ":" + json(item)));
            return "{" + String.join(",", entries) + "}";
        }
        if (value instanceof Iterable<?> items) {
            List<String> entries = new ArrayList<>();
            items.forEach(item -> entries.add(json(item)));
            return "[" + String.join(",", entries) + "]";
        }
        return "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
