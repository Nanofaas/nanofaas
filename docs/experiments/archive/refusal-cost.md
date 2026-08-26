# Quanto costa un rifiuto, e quanto ne hanno tolto le pulizie

Misura del 2026-08-22, in locale, JVM, senza Azure. Chiude la domanda che la cella
`azure-cpu1-inbound-opt-c2` non aveva potuto rispondere: le ottimizzazioni sul
percorso in ingresso valgono qualcosa?

## Perché in locale e non su Azure

Nessuno dei due assetti disponibili può misurarlo:

- **a 1 CPU** il sistema è strozzato all'80–85% dei periodi e la varianza fra
  ripetizioni è dell'ordine del 5%, contro un segnale sotto l'1%;
- **a 4 CPU** il sistema **non è CPU-bound** — 0% di throttling, coda a 3,33 su 20 —
  quindi risparmiare CPU dove nessuno la aspetta non produce niente di osservabile.

E non serve il G1: quanto lavoro faccia il codice per rifiuto è una proprietà del
codice, non del compilatore. Il collettore cambia i numeri assoluti, non il fatto
che togliere lavoro tolga lavoro.

## Risultato

50.000 giri di riscaldamento, 5 tornate da 50.000, minimo.

Un rifiuto viene lanciato in profondità dentro una pipeline Reactor, non da un
metodo di test, quindi il costo va misurato alla profondità di stack giusta:

| profondità di stack | con stack trace | senza | risparmio |
|---:|---:|---:|---:|
| 1 | 2,565 µs | 0,009 µs | 2,556 µs |
| 20 | 2,973 µs | 0,020 µs | 2,953 µs |
| **60** | **4,041 µs** | **0,312 µs** | **3,729 µs** |
| **120** | **5,627 µs** | **0,552 µs** | **5,075 µs** |

E il lavoro che il rifiuto anticipato evita — costruire l'`InvocationTask`, il
record, depositarlo nello store e rimuoverlo:

| operazione | costo |
|---|---:|
| costruisci + deposita + rimuovi | **0,095 µs** |

## Il conto

A 590 rifiuti al secondo (il `peak900` del profilo di confronto), su **un** core:

| | µs per rifiuto | quota di un core |
|---|---:|---:|
| stack trace soppresso | 3,73 | 0,220 % |
| esecuzione non costruita | 0,095 | 0,006 % |
| **totale** | **3,83** | **0,226 %** |

**Le pulizie in ingresso valgono circa un quarto di punto percentuale di un
core.** La cella su Azure non le ha viste perché il segnale è venti volte sotto
il rumore, ed è la risposta corretta: sono pulizia, non prestazioni.

## Le stime che questa misura ha corretto

Tre volte in una sessione ho dedotto costi relativi leggendo il codice, e tre
volte il numero era sbagliato di un fattore:

| avevo detto | misurato |
|---|---|
| «costruire il record pesa molto più della riscrittura header, è lo spreco dominante» | 0,095 µs — **quaranta volte meno** dello stack trace |
| «la validazione costerà ~3 µs» | l'intera passata JSON dentro+fuori ne costa 0,99 |
| «gli stack trace valgono qualche punto percentuale» | 0,22% |

La lezione non è che le stime fossero grossolane: è che **non c'era ragione di
stimare**. Un benchmark locale costa tre minuti e zero dollari, e ognuna di queste
domande si chiude con un numero invece che con un argomento.

## Riprodurre

Test temporaneo in `platform/control-plane/src/test`. La parte non ovvia è la
profondità di stack: misurare `new QueueFullException()` da un metodo di test
sottostima il risparmio di circa il 30%, perché lo stack da riempire è corto.

```java
static Object deep(int n, Supplier<Object> make) {   // stack realistico
    return n <= 0 ? make.get() : deep(n - 1, make);
}
// riscaldare 50_000 giri, poi 5 tornate da 50_000, prendere il minimo
```
