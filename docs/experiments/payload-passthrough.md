# Quanto costa la doppia passata JSON sul payload

Misura del 2026-08-22. Domanda: il control plane deserializza il body in un grafo
di oggetti e lo riserializza verso la funzione, per dati che **non legge mai**.
Vale la pena renderlo opaco?

`InvocationRequest.input` è dichiarato `Object`, quindi Jackson materializza
`LinkedHashMap`/`List`/`String` in ingresso; `ExternalDispatcher:62` fa
`request.bodyValue(task.request())`, che serializza tutto da capo. Nei sorgenti di
produzione `.input()` compare due volte: una copia di riferimento nel controller e
`LocalDispatcher`, che è la modalità di test. In `DEPLOYMENT` ed `EXTERNAL` il
payload è puro trasporto.

## Risultato

Jackson 3 (`tools.jackson`), JVM, 30.000 iterazioni di riscaldamento, 5 tornate da
20.000, minimo. Terza colonna: la quota di **un** core a 889 richieste/s, il ritmo
d'arrivo al `peak900` del profilo di confronto.

| payload | dentro+fuori | solo dentro | copia grezza | quota di un core |
|---:|---:|---:|---:|---:|
| 138 byte | 0,99 µs | 0,60 µs | 0,01 µs | **0,088 %** |
| 604 byte | 1,63 µs | 0,84 µs | 0,02 µs | 0,145 % |
| 5.404 byte | 8,46 µs | 2,95 µs | 0,73 µs | 0,752 % |
| 53.454 byte | 74,31 µs | 24,29 µs | 1,26 µs | 6,606 % |

I payload reali degli esperimenti stanno fra 136 e 487 byte
(`functions/*/payloads/*.json`), quindi la doppia passata costa **meno di un
decimo di punto percentuale di un core**.

## Verdetto: non farlo

Renderlo opaco significa cambiare `InvocationRequest` in `platform/common` — il
contratto condiviso da quattro SDK, dal servizio `warm-echo` e da `openapi.yaml` —
per recuperare lo 0,09%. Il costo del cambiamento non è il codice: è che ogni
consumatore del tipo deve seguire.

## Quando rifare il conto

La passata è circa 1,4 µs per KB oltre le dimensioni piccole, e tutto il resto del
percorso in ingresso è costante per richiesta: è **l'unico costo proporzionale al
payload**. Perché superi il 5% di un core servono payload nell'ordine delle
**decine di KB**. Il piano `function-payload-corpora` esiste già, quindi la
condizione è concreta: se un profilo comincia a spedire payload da ~50 KB, questa
misura va rifatta e la conclusione probabilmente si ribalta — a quel punto il
risparmio sarebbe quasi totale (74,31 µs contro 1,26 di copia grezza).

## Cautela

La misura è tempo di CPU su JVM, monothread. Non conta la pressione sul
collettore: il grafo di oggetti allocato in ingresso va poi raccolto, e il
confronto fra build ha mostrato che il GC non è gratis (49,8 s di raccolta seriale
su 450 s di run contro 6,5 s della JVM). Su una build nativa con collettore seriale
la quota reale è quindi un po' più alta di quella in tabella.

## Riprodurre

Test temporaneo in `platform/common/src/test` (l'unico modulo che possiede il tipo
e ha già `tools.jackson.core:jackson-databind` fra le dipendenze di test):

```java
JsonMapper M = JsonMapper.builder().build();
byte[] json = /* {"input":{"text":"...","lang":"it"},"metadata":{...},"headers":{...}} */;
// riscaldare 30_000 giri, poi 5 tornate da 20_000, prendere il minimo
InvocationRequest r = M.readValue(json, InvocationRequest.class);
M.writeValueAsBytes(new InvocationRequest(r.input(), r.metadata(), Map.of("x", "y")));
```
