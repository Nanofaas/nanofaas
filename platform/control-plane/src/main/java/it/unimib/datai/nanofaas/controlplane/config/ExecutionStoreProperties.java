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
 * <p>{@code maxOutcomes}: il tetto che mancava. Le due durate qui sopra limitano
 * la ritenzione nel tempo ma non nel numero, quindi la memoria necessaria restava
 * proporzionale al tasso di arrivo - e un carico che raddoppia raddoppiava
 * l'heap, senza che nessuna manopola dicesse basta. Un esito compatto misura 116
 * byte, quindi il valore predefinito costa circa 12 MB al suo limite.
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
        long maxKeys
) {
    private static final long DEFAULT_MAX_OUTCOMES = 100_000;
    private static final long DEFAULT_MAX_KEYS = 100_000;

    /**
     * Fabbrica, non costruttore: con due costruttori Spring smette di legare il
     * record per costruttore e cerca quello senza argomenti, che un record non ha.
     */
    public static ExecutionStoreProperties of(Duration ttl, Duration maxLifetime, Duration syncTtl) {
        return new ExecutionStoreProperties(ttl, maxLifetime, syncTtl, DEFAULT_MAX_OUTCOMES, DEFAULT_MAX_KEYS);
    }

    /** Fabbrica, non costruttore: i test che costruiscono lo store con il solo tetto degli esiti. */
    public static ExecutionStoreProperties of(Duration ttl, Duration maxLifetime, Duration syncTtl, long maxOutcomes) {
        return new ExecutionStoreProperties(ttl, maxLifetime, syncTtl, maxOutcomes, DEFAULT_MAX_KEYS);
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
        // Non ha senso tenere piu' a lungo cio' che nessuno puo' leggere.
        if (syncTtl.compareTo(ttl) > 0) {
            syncTtl = ttl;
        }
    }
}
