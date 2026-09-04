# Il `RateLimitWebFilter` vale qualcosa? Sync no, `:enqueue` sì

Misura del 2026-09-04, in locale, JVM, senza Azure. Passo 0 dell'Esperimento C
in [`../../plans/2026-09-04-overload-path-fixes.md`](../../plans/2026-09-04-overload-path-fixes.md):
prima di spendere una corsa Azure per vedere se il `RateLimitWebFilter`
(`platform/control-plane/.../api/RateLimitWebFilter.java`) produce una
differenza misurabile, quella nota aveva già escluso che l'effetto possa
essere visto su Azure — il segnale atteso era sotto il rumore CFS del
1 core (~5%). Questo banco chiude la domanda con un numero prima di decidere
se la corsa vale la pena.

Gemello di [`refusal-cost.md`](refusal-cost.md): stesso metodo, domanda
diversa. Quella nota misurava quanto costa **rifiutare** (stack trace,
esecuzione non costruita). Questa misura quanto costa il **percorso** che
arriva al rifiuto — vecchio (deserializzazione JSON dentro il controller,
throw/catch di un'eccezione, ed eventuale handoff a `boundedElastic` su
`:enqueue`) contro nuovo (il filtro: drena il body grezzo, nessuna
deserializzazione, nessun handoff, nessuna eccezione).

## Un tranello, e come è stato tolto

La prima versione di questo banco misurava il filtro con
`MockServerWebExchange.from(MockServerHttpRequest...)` costruito **dentro**
il ciclo cronometrato — e il filtro risultava *più lento* del vecchio
percorso di un fattore venti (16 µs contro 0,7 µs). Falso: la costruzione
dello scambio mock costa da sola ~14,9 µs, ed è un costo dell'infrastruttura
di test che in produzione Netty paga per **ogni** richiesta, filtrata o no —
comune ai due percorsi, non specifico del filtro. Isolarla (stessa tecnica
del `baseline` per l'imbottitura dello stack: misurare la sola costruzione,
sottrarla dal composito) ha ribaltato il numero. La lezione è la stessa di
`refusal-cost.md`: un banco locale mal fatto mente con la stessa sicurezza di
uno fatto bene — la differenza è controllare l'aritmetica, non fidarsi del
primo numero che esce.

## Risultato

50.000 giri di riscaldamento, 5 tornate da 50.000, minimo. Profondità di
stack 60, stessa riga di riferimento di `refusal-cost.md`. Quattro corse
indipendenti, per vedere se il segno regge:

| corsa | risparmio sync | risparmio `:enqueue` |
|---|---:|---:|
| 1 | −467 ns | +9.491 ns |
| 2 | −326 ns | +11.818 ns |
| 3 | −289 ns | +13.058 ns |
| 4 | −605 ns | +7.262 ns |

**Il segno regge in tutte e quattro.** L'ampiezza no — l'handoff a
`boundedElastic` misurato va da 7,9 a 13,3 µs fra una corsa e l'altra, più
rumoroso di quanto la nota di agosto lasciasse intendere (quella citava
6,1 µs, su un'altra sessione, un'altra macchina — coerente in ordine di
grandezza, non in cifra).

Componenti della corsa 2, per riferimento:

| operazione | costo |
|---|---:|
| deserializzazione JSON (sola andata) | 0,723 µs |
| throw/catch senza stack trace | 0,004 µs |
| handoff `boundedElastic`, netto | 13,347 µs |
| `RateLimitWebFilter`, solo il filtro (esclusa la costruzione dello scambio) | 1,016 µs |

## Il conto

**Sul percorso sync (`:invoke`)**, il filtro **perde**: la pipeline Reactor
(`getBody()` → `doOnNext` → `then(Mono.defer(...))` → `block()`) costa di
per sé più della deserializzazione JSON e del throw/catch che sostituisce.
A 590 rifiuti/s (lo stesso riferimento di `refusal-cost.md`), −300...−600 ns
per rifiuto è −0,018%...−0,036% di un core — sotto la soglia a cui
`refusal-cost.md` stessa giudicava "pulizia, non prestazioni" per un effetto
*positivo* venti volte più grande. Qui il segno è negativo, ma la scala è la
stessa: irrilevante in assoluto, e troppo piccolo per essere visto su Azure
(il rumore CFS a 1 core, ~5%, lo sommergerebbe).

**Sul percorso `:enqueue`**, il filtro **vince**, ed è l'unico dei due punti
dove vale la pena guardare oltre il locale: 7,3–13,1 µs risparmiati per
rifiuto, cioè 0,43%–0,77% di un core a 590 rifiuti/s — un ordine di
grandezza sopra il segnale che `refusal-cost.md` aveva già giudicato troppo
piccolo per Azure, e potenzialmente sopra il rumore CFS se la corsa satura
davvero un core.

## Cosa corregge nel piano

La sezione "Quanto vale" di Parte I §1 nel piano stimava il guadagno sync a
~0,06% di un core, positivo. Era un conto analitico sulle uniche misure
disponibili all'epoca (deserializzazione JSON, handoff), mai verificato
contro il costo reale della pipeline reattiva del filtro — che non esisteva
ancora quando quel conto è stato scritto. Misurato, il segno è opposto e la
scala resta la stessa (irrilevante). Non cambia la decisione — il filtro
resta una pulizia legittima sul sync, non una regressione operativamente
visibile — ma cambia la frase che la descrive.

**Per l'Esperimento C**: la corsa Azure, se si fa, va fatta in modalità
`INVOCATION_MODE=async` (default di k6 è `sync`, va forzato). Un run in sync
non vedrebbe niente — coerente con quanto la stessa Esperimento C già
prevedeva ("il caso più forte per il filtro è l'handoff `boundedElastic` su
`:enqueue`"), solo che ora si sa anche che il caso debole (sync) non è
debolmente positivo ma debolmente negativo.

## Riprodurre

Test temporaneo in `platform/control-plane/src/test`
(`RefusalCostPasso0Test.java`, cancellato dopo l'uso, come da convenzione di
`refusal-cost.md`). Stessa tecnica di quella nota per la profondità di
stack; in più, la costruzione dello scambio mock va isolata e sottratta dal
composito filtro+scambio, per la ragione spiegata sopra:

```java
static Object deep(int n, Supplier<Object> make) {   // stack realistico
    return n <= 0 ? make.get() : deep(n - 1, make);
}
// riscaldare 50_000 giri, poi 5 tornate da 50_000, prendere il minimo

// il costo composito (costruzione + filtro) meno il costo della sola
// costruzione isola il filtro - altrimenti l'infrastruttura di test domina
// la misura e mente sul segno.
```
