package it.unimib.datai.nanofaas.sdk.lite;

import java.util.*;
import java.util.concurrent.*;

/** Observe byte arrays held by actual queued/running runtime tasks, never fixture expectations. */
final class CorpusCallbackExecutor extends ThreadPoolExecutor {
    private final Set<Runnable> running = ConcurrentHashMap.newKeySet();
    CorpusCallbackExecutor() { super(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1)); }
    @Override protected void beforeExecute(Thread thread, Runnable task) { running.add(task); }
    @Override protected void afterExecute(Runnable task, Throwable error) { running.remove(task); }
    long retainedBytes() {
        Set<Runnable> owned = new HashSet<>(getQueue());
        owned.addAll(running);
        long bytes = 0;
        for (Runnable task : owned) {
            if (!task.getClass().getSimpleName().equals("CallbackTask")) continue;
            Object payload = field(task, "body");
            bytes += ((byte[]) payload).length;
        }
        return bytes;
    }
    static Object field(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException ex) { throw new AssertionError("missing runtime observation " + name, ex); }
    }
    static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        var method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        try { return method.invoke(target, args); }
        catch (java.lang.reflect.InvocationTargetException ex) {
            if (ex.getCause() instanceof Exception cause) throw cause;
            if (ex.getCause() instanceof Error cause) throw cause;
            throw ex;
        }
    }
}
