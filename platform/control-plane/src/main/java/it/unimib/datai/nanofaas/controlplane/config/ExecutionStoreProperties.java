package it.unimib.datai.nanofaas.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Quanto lo store ricorda, e quanti ne ricorda.
 *
 * <p>{@code ttl}: ritenzione di un esito che qualcuno puo' ancora leggere - un
 * chiamante asincrono che interroga per id, o un retry che replica una chiave di
 * idempotenza. {@code syncTtl}: ritenzione dell'esito di un'esecuzione sincrona
 * senza chiave, la cui risposta e' gia' tornata sulla connessione del chiamante.
 * Breve, perche' allo stato stazionario lo store tiene `ritenzione x tasso di
 * ammissione`: misurato il 2026-08-23 erano 270.000 record e 1,05 GB di dati vivi
 * contro una generazione tenured da 1.002 MB, che teneva il collettore
 * permanentemente al limite - 50,6% del tempo in GC, pause da 2,851 s, e un probe
 * di liveness mancato tre volte di fila. Non zero: {@code X-Execution-Id} torna
 * anche sulle risposte sincrone, quindi {@code GET /v1/executions/{id}} e' una
 * promessa fatta anche a quei chiamanti.
 *
 * <p>{@code maxOutcomeBytes}: the cap in BYTES, which is what actually matters.
 * The count cap ({@code maxOutcomes}) bounds how many outcomes are kept, not how
 * much they weigh, and the two coincide only for the compact 116-byte outcomes
 * that number was calibrated on. But a *readable* outcome - ASYNC or
 * idempotency-keyed - retains the caller's payload: measured, 20,000 outcomes at
 * 64 KB occupy 1.28 GB, and at the default of 100,000 that would be roughly 6 GB.
 * Exactly the shape of the 2026-08-23 failure described above, which the count cap
 * alone does not prevent. An outcome's weight is estimated once at insertion,
 * never by re-serializing the payload on each access.
 *
 * <p>The default is {@code maxOutcomes x 116 bytes}: at the limit it costs what it
 * cost before, so nothing changes for compact outcomes, while large payloads are
 * evicted by weight instead of accumulating.
 *
 * <p>{@code maxLifetime}: tetto assoluto oltre il quale anche un'esecuzione non
 * terminale (incastrata) viene sfrattata, perche' non cresca senza fine.
 */
@ConfigurationProperties(prefix = "nanofaas.execution-store")
public record ExecutionStoreProperties(
        Duration ttl,
        Duration maxLifetime,
        Duration syncTtl,
        long maxOutcomes,
        long maxKeys,
        long maxOutcomeBytes
) {
    private static final long DEFAULT_MAX_OUTCOMES = 100_000;
    private static final long DEFAULT_MAX_KEYS = 100_000;
    /** A compact outcome's weight, the constant the count cap was calibrated on. */
    public static final long COMPACT_OUTCOME_BYTES = 116;

    /**
     * Fabbrica, non costruttore: con due costruttori Spring smette di legare il
     * record per costruttore e cerca quello senza argomenti, che un record non ha.
     */
    public static ExecutionStoreProperties of(Duration ttl, Duration maxLifetime, Duration syncTtl) {
        return new ExecutionStoreProperties(ttl, maxLifetime, syncTtl, DEFAULT_MAX_OUTCOMES, DEFAULT_MAX_KEYS, 0);
    }

    /** Fabbrica, non costruttore: i test che costruiscono lo store con il solo tetto degli esiti. */
    public static ExecutionStoreProperties of(Duration ttl, Duration maxLifetime, Duration syncTtl, long maxOutcomes) {
        return new ExecutionStoreProperties(ttl, maxLifetime, syncTtl, maxOutcomes, DEFAULT_MAX_KEYS, 0);
    }

    public ExecutionStoreProperties {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            ttl = Duration.ofMinutes(5);
        }
        if (maxLifetime == null || maxLifetime.isNegative() || maxLifetime.isZero()) {
            maxLifetime = Duration.ofMinutes(30);
        }
        if (syncTtl == null || syncTtl.isNegative() || syncTtl.isZero()) {
            syncTtl = Duration.ofSeconds(30);
        }
        if (maxOutcomes <= 0) {
            maxOutcomes = DEFAULT_MAX_OUTCOMES;
        }
        if (maxKeys <= 0) {
            maxKeys = DEFAULT_MAX_KEYS;
        }
        if (maxOutcomeBytes <= 0) {
            maxOutcomeBytes = maxOutcomes * COMPACT_OUTCOME_BYTES;
        }
        // Non ha senso tenere piu' a lungo cio' che nessuno puo' leggere.
        if (syncTtl.compareTo(ttl) > 0) {
            syncTtl = ttl;
        }
    }
}
