package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;

import java.util.Collection;
import java.util.Map;

/**
 * Stima quanto heap trattiene un esito, una volta sola, al momento dell'inserimento.
 *
 * <p>Il tetto in numero degli esiti presuppone che pesino tutti uguale. Per un esito
 * compatto e' vero (116 byte); per un esito <i>leggibile</i>, che trattiene il payload
 * del chiamante, no: 20.000 esiti da 64 KB misurano 1,28 GB
 * (docs/experiments/control-plane-tuning-2026-09/RISULTATI.md).
 *
 * <p>Il peso e' una <b>stima</b>, non una misura: attraversa la struttura una volta e
 * non riserializza mai il payload — riserializzarlo a ogni accesso costerebbe piu' della
 * memoria che fa risparmiare. La traversata e' limitata in ampiezza e profondita': un
 * payload annidato in modo patologico deve costare come gli altri, non piu' degli altri.
 *
 * <p>Un esito il cui peso stimato superi da solo l'intero budget non puo' essere trattenuto:
 * Caffeine lo ammette e lo sfratta subito. La garanzia di A5 regge comunque - la chiave resta
 * come tombstone e il replay risponde 410 invece di rieseguire la funzione - ma il payload non
 * e' recuperabile. Con il budget predefinito (11,6 MB) serve un singolo esito da 11 MB per
 * arrivarci; chi trattiene payload cosi' grandi deve alzare {@code max-outcome-bytes}.
 */
final class OutcomeWeigher {

    /**
     * Overhead strutturale dell'oggetto Outcome: header dell'oggetto, i campi primitivi e
     * i riferimenti, senza payload ne' header HTTP. Scelto perche' un esito compatto - un
     * output piccolo e nient'altro - pesi complessivamente intorno ai
     * {@link ExecutionStoreProperties#COMPACT_OUTCOME_BYTES} byte su cui il tetto in numero
     * era tarato: al budget predefinito ne entrano quanti ne entravano prima.
     */
    private static final int FIXED_OVERHEAD_BYTES = 96;
    private static final int REFERENCE_BYTES = 16;
    private static final int MAX_DEPTH = 4;
    private static final int MAX_ELEMENTS = 256;
    /** Costo attribuito a un oggetto che non sappiamo attraversare. */
    private static final int OPAQUE_BYTES = 64;

    private OutcomeWeigher() {
    }

    static int weigh(Outcome outcome) {
        long total = FIXED_OVERHEAD_BYTES;
        total += estimate(outcome.output(), 0);
        total += estimate(outcome.headers(), 0);
        total += outcome.encoding() == null ? 0 : outcome.encoding().length();
        if (outcome.error() != null) {
            total += estimate(outcome.error().code(), 0) + estimate(outcome.error().message(), 0);
        }
        // Il peso di Caffeine e' un int, e un esito non puo' pesare zero o l'eviction
        // per peso non avrebbe modo di sfrattarlo.
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, total));
    }

    private static long estimate(Object value, int depth) {
        if (value == null || depth > MAX_DEPTH) {
            return 0;
        }
        return switch (value) {
            // Le stringhe compatte usano un byte per carattere Latin-1: la lunghezza e'
            // la stima giusta, non il doppio.
            // Senza sommare il riferimento: un esito compatto deve restare compatto, e
            // l'overhead dell'oggetto e' gia' contato una volta in FIXED_OVERHEAD_BYTES.
            case String s -> s.length();
            case byte[] bytes -> REFERENCE_BYTES + bytes.length;
            case Number _, Boolean _, Character _ -> REFERENCE_BYTES;
            case Map<?, ?> map -> estimateMap(map, depth);
            case Collection<?> collection -> estimateCollection(collection, depth);
            default -> OPAQUE_BYTES;
        };
    }

    private static long estimateMap(Map<?, ?> map, int depth) {
        long total = REFERENCE_BYTES;
        int seen = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (seen++ >= MAX_ELEMENTS) {
                break;
            }
            total += estimate(entry.getKey(), depth + 1) + estimate(entry.getValue(), depth + 1);
        }
        return total;
    }

    private static long estimateCollection(Collection<?> collection, int depth) {
        long total = REFERENCE_BYTES;
        int seen = 0;
        for (Object element : collection) {
            if (seen++ >= MAX_ELEMENTS) {
                break;
            }
            total += estimate(element, depth + 1);
        }
        return total;
    }
}
