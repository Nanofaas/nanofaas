// T1 - HTTP pool: profile and isolated comparison.
//
// Question: with a slow backend and an exhausted pool, how long does a request
// that cannot acquire a connection wait, and what does the caller get?
//
// The arms have the SAME capacity (same maxConnections): the only variable is
// what happens to the surplus requests. Comparing the global pool (~500
// connections/host) against a small one would measure capacity, not the
// intervention.
//
// Alternated arms (A,B,A,B,...) in one process, after warm-up:
//   A = "default-wait" - pendingAcquireTimeout at Reactor Netty's default (45 s),
//                        i.e. today's effective behaviour.
//   B = "budget-wait"  - pendingAcquireTimeout equal to the function's budget.
//
// The backend answers after HOLD_MS, so connections stay busy and the surplus
// requests end up in the acquisition queue.
//
// CANCEL reproduces what ExternalDispatcher does: .timeout(budget) on the Mono,
// which CANCELS upstream and with it the pending acquisition. Without it the
// harness measures a scenario production does not have - which is exactly the
// mistake the first version of this benchmark made.
import com.sun.net.httpserver.HttpServer;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class T1PoolBench {

    /** How many requests the backend actually served: capacity spent, useful or not. */
    static final AtomicInteger backendServed = new AtomicInteger();

    static final int POOL = 8;              // connections granted, THE SAME in both arms
    static final int CONCURRENCY = 64;      // concurrent requests: 8x the pool
    static final long HOLD_MS = 300;        // how long the backend holds the connection
    static final long BUDGET_MS = 1000;     // the function's budget: the limit that matters
    static final long SHIPPED_ACQUIRE_WAIT_MS = 5000; // the shipped default (= connectTimeoutMs)
    static final long DEFAULT_ACQUIRE_WAIT_MS = 45_000; // Reactor Netty's default
    static final int REPS = 6;              // ripetizioni per braccio
    static final int WARMUP = 2;

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Daemon threads: a non-daemon pool keeps the JVM alive after the benchmark
        // ends, and the output stays in System.out's buffer without ever arriving.
        ExecutorService serverPool = Executors.newFixedThreadPool(64, r -> {
            Thread t = new Thread(r, "bench-backend");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(serverPool);
        server.createContext("/slow", exchange -> {
            backendServed.incrementAndGet();
            try {
                Thread.sleep(HOLD_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{\"ok\":true}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/slow";

        List<Result> global = new ArrayList<>();
        List<Result> bounded = new ArrayList<>();
        List<Result> shipped = new ArrayList<>();

        boolean cancel = args.length > 0 && args[0].equals("--cancel-at-budget");
        for (int i = 0; i < WARMUP; i++) {
            round(client(DEFAULT_ACQUIRE_WAIT_MS), url, cancel);
            round(client(SHIPPED_ACQUIRE_WAIT_MS), url, cancel);
            round(client(BUDGET_MS), url, cancel);
        }
        for (int i = 0; i < REPS; i++) {
            // Alternated order: a drift of the machine hits the arms equally.
            global.add(round(client(DEFAULT_ACQUIRE_WAIT_MS), url, cancel));
            shipped.add(round(client(SHIPPED_ACQUIRE_WAIT_MS), url, cancel));
            bounded.add(round(client(BUDGET_MS), url, cancel));
        }
        server.stop(0);
        serverPool.shutdownNow();

        System.out.println("{");
        System.out.printf("  \"bench\": \"T1PoolBench\", \"cancelAtBudget\": %s,%n", cancel);
        System.out.printf ("  \"params\": {\"pool\": %d, \"concurrency\": %d, \"holdMs\": %d, \"budgetMs\": %d, \"defaultAcquireWaitMs\": %d, \"reps\": %d},%n",
                POOL, CONCURRENCY, HOLD_MS, BUDGET_MS, DEFAULT_ACQUIRE_WAIT_MS, REPS);
        emit("default-wait-45s", global);
        System.out.println(",");
        emit("shipped-default-5s", shipped);
        System.out.println(",");
        emit("budget-wait-1s", bounded);
        System.out.println();
        System.out.println("}");
        System.out.flush();
        // Reactor Netty's pools stay alive; the benchmark is done, so exit.
        System.exit(0);
    }

    static HttpClient client(long acquireWaitMs) {
        ConnectionProvider provider = ConnectionProvider.builder("t1-bench-" + acquireWaitMs)
                .maxConnections(POOL)
                .pendingAcquireMaxCount(CONCURRENCY * 2)
                .pendingAcquireTimeout(Duration.ofMillis(acquireWaitMs))
                .build();
        return HttpClient.create(provider).responseTimeout(Duration.ofMillis(30_000));
    }

    static Result round(HttpClient client, String url) throws Exception {
        return round(client, url, false);
    }

    static Result round(HttpClient client, String url, boolean cancelAtBudget) throws Exception {
        CountDownLatch done = new CountDownLatch(CONCURRENCY);
        long[] latencies = new long[CONCURRENCY];
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger overBudget = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        backendServed.set(0);
        long start = System.nanoTime();
        for (int i = 0; i < CONCURRENCY; i++) {
            final int idx = i;
            long issued = System.nanoTime();
            Mono<String> call = client.get().uri(url).responseContent().aggregate().asString();
            if (cancelAtBudget) {
                // Like the dispatcher: when the budget expires the request is cancelled.
                call = call.timeout(Duration.ofMillis(BUDGET_MS));
            }
            call
                    .doOnSuccess(body -> {
                        long ms = (System.nanoTime() - issued) / 1_000_000;
                        latencies[idx] = ms;
                        if (ms > BUDGET_MS) {
                            overBudget.incrementAndGet();
                        } else {
                            ok.incrementAndGet();
                        }
                        done.countDown();
                    })
                    .onErrorResume(err -> {
                        latencies[idx] = (System.nanoTime() - issued) / 1_000_000;
                        failed.incrementAndGet();
                        done.countDown();
                        return Mono.empty();
                    })
                    .subscribe();
        }
        done.await();
        long wallMs = (System.nanoTime() - start) / 1_000_000;

        // The backend can keep receiving already-queued requests after the caller
        // gave up: that is exactly the capacity spent on nobody.
        Thread.sleep(HOLD_MS * 2);
        Arrays.sort(latencies);
        return new Result(
                latencies[latencies.length / 2],
                latencies[(int) (latencies.length * 0.95)],
                latencies[latencies.length - 1],
                wallMs, ok.get(), overBudget.get(), failed.get(), backendServed.get());
    }

    record Result(long p50, long p95, long max, long wallMs, int ok, int overBudget, int failed,
                  int backendServed) {}

    static void emit(String arm, List<Result> rs) {
        long[] p50 = rs.stream().mapToLong(Result::p50).sorted().toArray();
        long[] p95 = rs.stream().mapToLong(Result::p95).sorted().toArray();
        long[] max = rs.stream().mapToLong(Result::max).sorted().toArray();
        System.out.printf("  \"%s\": {%n", arm);
        System.out.printf("    \"p50_median_ms\": %d, \"p95_median_ms\": %d, \"max_median_ms\": %d,%n",
                p50[p50.length / 2], p95[p95.length / 2], max[max.length / 2]);
        System.out.printf("    \"max_min_ms\": %d, \"max_max_ms\": %d,%n", max[0], max[max.length - 1]);
        int within = rs.stream().mapToInt(Result::ok).sum();
        int served = rs.stream().mapToInt(Result::backendServed).sum();
        System.out.printf("    \"within_budget\": %d, \"over_budget\": %d, \"failed\": %d,%n",
                within, rs.stream().mapToInt(Result::overBudget).sum(),
                rs.stream().mapToInt(Result::failed).sum());
        System.out.printf("    \"backend_served\": %d, \"backend_work_wasted\": %d,%n",
                served, served - within);
        System.out.printf("    \"runs\": %d%n", rs.size());
        System.out.print("  }");
    }
}
