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
 * <p>{@code maxOutcomeBytes}: il tetto in BYTE, che e' quello che conta davvero.
 * Il tetto in numero ({@code maxOutcomes}) limita quanti esiti si tengono, non
 * quanto pesano, e i due coincidono solo per gli esiti compatti da 116 byte su cui
 * quel numero era stato tarato. Ma un esito *leggibile* - ASYNC o con chiave di
 * idempotenza - trattiene il payload del chiamante: misurati 20.000 esiti da 64 KB
 * occupano 1,28 GB, e al valore predefinito di 100.000 sarebbero circa 6 GB.
 * Esattamente la forma del guasto del 2026-08-23 descritto qui sopra, che il solo
 * tetto in numero non impedisce. Il peso di un esito viene stimato una volta sola
 * all'inserimento, mai riserializzando il payload a ogni accesso.
 *
 * <p>Il valore predefinito e' {@code maxOutcomes x 116 byte}: al limite costa quanto
 * costava prima, quindi per gli esiti compatti non cambia nulla, mentre i payload
 * grandi vengono sfrattati per peso invece che accumularsi.
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
    /** Il peso di un esito compatto, la costante su cui il tetto in numero era tarato. */
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
