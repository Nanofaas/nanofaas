import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.*;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

/** Loopback-only census for the real SDK fixtures. Independent scalar reads, never summed aliases. */
public final class SdkObservation {
    public static Object handle(Object input) {
        Map<?, ?> map = (Map<?, ?>) input;
        long end = System.nanoTime() + ((Number)map.get("delayMs")).longValue()*1_000_000;
        while (System.nanoTime() < end) {
            try { Thread.sleep(10); } catch (InterruptedException ignored) { /* deliberately non-cooperative */ }
        }
        return Map.of("id", map.get("id"), "data", "x".repeat(((Number)map.get("outputBytes")).intValue()));
    }
    public static Map<String,Object> fields(Object owner) {
        Map<String,Object> result = new TreeMap<>();
        inspect(owner, "runtime", result, 0);
        result.put("pid", ProcessHandle.current().pid());
        result.put("monotonic_ns", System.nanoTime());
        result.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        result.put("heap_used_bytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        return result;
    }
    private static void inspect(Object owner, String prefix, Map<String,Object> out, int depth) {
        if (owner == null || depth > 4) return;
        for (Class<?> c=owner.getClass(); c!=null && c.getName().startsWith("it.unimib.datai.nanofaas"); c=c.getSuperclass()) {
            for (Field f:c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                String key=prefix+"."+f.getName();
                try {
                    f.setAccessible(true); Object value=f.get(owner);
                    if (value instanceof Number || value instanceof Boolean) out.put(key,value);
                    else if(value instanceof AtomicBoolean v) out.put(key,v.get());
                    else if(value instanceof Map<?,?> v) out.put(key+".count",v.size());
                    else if(value instanceof Collection<?> v) out.put(key+".count",v.size());
                    else if(value instanceof Semaphore v) out.put(key+".available",v.availablePermits());
                    else if(value instanceof ThreadPoolExecutor v) {
                        out.put(key+".active",v.getActiveCount());out.put(key+".queued",v.getQueue().size());
                        out.put(key+".shutdown",v.isShutdown());out.put(key+".terminated",v.isTerminated());
                    } else if(value instanceof ExecutorService v) {
                        out.put(key+".shutdown",v.isShutdown());out.put(key+".terminated",v.isTerminated());
                    } else inspect(value,key,out,depth+1);
                } catch(Exception e) { out.put(key,Map.of("status","unavailable","reason",e.toString())); }
            }
        }
    }
    public static void serve(Supplier<Map<String,Object>> snapshot) throws Exception {
        HttpServer probe=HttpServer.create(new InetSocketAddress("127.0.0.1",19082),8);
        Method json=P19Probe.class.getDeclaredMethod("json",Object.class);json.setAccessible(true);
        probe.createContext("/",e->{
            try {
                byte[] bytes=((String)json.invoke(null,snapshot.get())).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);
            } catch(Exception failure){e.sendResponseHeaders(500,-1);} finally{e.close();}
        });
        Runtime.getRuntime().addShutdownHook(new Thread(()->probe.stop(0)));
        probe.start();
    }
}
