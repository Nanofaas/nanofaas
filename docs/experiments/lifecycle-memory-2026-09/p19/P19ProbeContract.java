import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Pure JDK observer test: a completed future must not count as open. */
public class P19ProbeContract {
    public static class Record {
        private final CompletableFuture<Object> completion = new CompletableFuture<>();
        public CompletableFuture<Object> completion() { return completion; }
    }
    public static class Cache {
        private final Map<String, Record> records;
        Cache(Map<String, Record> records) { this.records = records; }
        public Map<String, Record> asMap() { return records; }
    }
    public static class Store {
        private final Cache inFlight;
        Store(Map<String, Record> records) { inFlight = new Cache(records); }
    }
    public static void main(String[] args) throws Exception {
        Method observe;
        try { observe = P19Probe.class.getDeclaredMethod("liveOwners", Object.class); }
        catch (NoSuchMethodException absent) { throw new AssertionError("open futures require an actual owner observer", absent); }
        observe.setAccessible(true);
        Record pending = new Record();
        Record done = new Record();
        done.completion.complete("ok");
        Map<?, ?> populated = (Map<?, ?>) observe.invoke(null, new Store(Map.of("pending", pending, "done", done)));
        if (!populated.get("open_futures").equals(1L)) throw new AssertionError(populated);
        pending.completion.complete("ok");
        Map<?, ?> drained = (Map<?, ?>) observe.invoke(null, new Store(Map.of("pending", pending, "done", done)));
        if (!drained.get("open_futures").equals(0L)) throw new AssertionError(drained);
        System.out.println("P19ProbeContract PASS: 2 retained futures, 1 open, then 0 open");
    }
}
