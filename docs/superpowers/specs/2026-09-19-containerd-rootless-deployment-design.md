# Provider di deployment containerd rootless

Stato: proposta per revisione; nessuna implementazione avviata.

## Obiettivo e requisiti

Aggiungere `containerd-deployment-provider` accanto ai provider Docker e Kubernetes.
I tre provider implementano `ManagedDeploymentProvider`, senza esporre dettagli del
runtime al control plane, allo scheduler o all'autoscaler. Containerd e crun devono
funzionare rootless; gestione container tramite `containerd-java`, rete tramite
`libcni-java` e l'integrazione `CniContainerNetwork` già disponibile.

Interpretazione di «selezionato da solo»: al massimo un provider di deployment
nell'artefatto, come stabiliscono oggi i descrittori Docker/Kubernetes. Con un solo
provider disponibile il resolver esistente lo sceglie implicitamente. Non si
introduce una nuova priorità di rilevamento tra tre daemon presenti sulla macchina.
È possibile continuare a costruire il control plane senza provider.

La parità richiesta riguarda i comportamenti degli scenari Docker: registrazione,
invocazioni sync/async, callback, scaling e scale-to-zero, aggiornamento della
configurazione, recovery, limiti delle risorse e rimozione senza risorse orfane.
Il nuovo runtime deve essere esercitato realmente, anche con immagini native.

## Evidenze dal codice

- Branch creato: `feat/containerd-rootless-deployment`, da `main` locale
  `05f49dcb`. Worktree: `.claude/worktrees/containerd-rootless`.
- Le modifiche presenti nel checkout originario dello scheduler restano separate.
- `ManagedDeploymentProvider` espone già provision, reconcile, updateSpec,
  deprovision, replica control e osservazione della readiness.
- `DeploymentProviderResolver` seleziona un provider univoco disponibile e compatibile;
  mantiene anche hint espliciti, backend persistito e il fallback EXTERNAL esistente.
- I descrittori dei due provider sono mutuamente esclusivi; Kubernetes è il default.
- `ControlPlaneModulesPlugin.selectAll` controlla i conflitti di pari priorità prima
  di eliminare gli optional che confliggono con un default. Due provider optional
  in conflitto romperebbero `all`, anche se entrambi esclusi da Kubernetes.
- Il lifecycle Docker contiene già recovery, pending removal, readiness e proxy
  HTTP con limiti/timeout aggiornabili. Assume però endpoint per nome Docker o porta
  pubblicata; `ContainerInstanceSpec` e `ManagedContainer` sono package-private.
- `/home/michele/containerd-java`, HEAD `7054e0b`, versione dichiarata `0.3.0`:
  gRPC nativo, `runtimeBinaryName("crun")`, network SPI, labels, limiti CPU/memoria,
  Java >=22 e metadata GraalVM. Il build non applica `maven-publish`.
- Il suo `cniJar` è un source set separato, non ancora una pubblicazione completa
  con dipendenze transitive. `/home/michele/libcni-java`, HEAD `cca2948`, pubblica
  `io.libcni:libcni-java:0.1.0` e richiede Java >=21.
- `NetworkAttachment` contiene gli IP, ma `Containers` non espone una lettura
  dell'attachment dopo start/restart del client. `detachNetwork` inghiotte gli errori;
  la rimozione può perdere i riferimenti necessari a ritentare CNI DEL/snapshot cleanup.
- `ContainerSpec` non espone cpuset e memory reservation, usati invece da Docker.
  Non è verificata la gestione dei cgroup delegati nel percorso crun della libreria.

Queste sono osservazioni statiche: non sono risultati di test rootless eseguiti.

## Architettura proposta

```text
Control plane / scheduler / autoscaler
                  |
         ManagedDeploymentProvider (SPI invariata)
          /                |                    \
 Kubernetes             Docker               containerd
                         \                    /
                      container-deployment-runtime
                     lifecycle + proxy + readiness
                         |                    |
                    Docker adapter      containerd-java
                                           |       \
                                     crun rootless  CniContainerNetwork
                                                     -> libcni-java
```

Estrarre il lifecycle e il proxy già utilizzati da Docker in una libreria interna
`platform/container-deployment-runtime`. Non è un modulo selezionabile, non contiene
autoconfiguration e non trascina Docker, Fabric8 o containerd. Docker e containerd
dipendono da questa libreria e forniscono i rispettivi adattatori; Kubernetes resta
sul proprio lifecycle nativo. Il core continua a vedere soltanto la SPI esistente.

L'adattatore locale restituisce un endpoint HTTP raggiungibile per ogni replica:
Docker mantiene nome/porta pubblicata, containerd usa l'IP CNI. Porte Docker,
snapshot, namespace, CNI e socket restano negli adattatori. L'endpoint della funzione
restituito al core è quello del proxy, che resta stabile durante lo scaling.

Alternativa scartata: dipendere dal modulo Docker e sostituirne un bean, perché
porterebbe due provider e docker-java nell'artefatto containerd. Duplicare tutto il
lifecycle eviterebbe l'estrazione iniziale ma duplicherebbe anche recovery e cleanup:
non è la scelta proposta. L'estrazione è una fase autonoma con regressione Docker.

## Selezione e configurazione

| Modulo | Backend ID | Default | Conflitti |
| --- | --- | --- | --- |
| `k8s-deployment-provider` | `k8s` | sì | Docker, containerd |
| `container-deployment-provider` | `container-local` | no | Kubernetes, containerd |
| `containerd-deployment-provider` | `containerd` | no | Kubernetes, Docker |

`all` continua a scegliere Kubernetes. Selezioni esplicite con due provider falliscono
durante la build. Gli altri moduli possono essere combinati liberamente secondo i
vincoli esistenti. Il nuovo namespace di configurazione è `nanofaas.containerd`.

Impostazioni: socket rootless, namespace containerd, snapshotter, percorso crun,
directory di stato, network CNI, directory config/plugin/cache CNI, timeout plugin,
bind-host del proxy, callback URL, timeout/intervallo readiness e cpuset opzionale.
Il socket di default deriva da `XDG_RUNTIME_DIR`; assenza della variabile richiede
un percorso esplicito. Mai fallback silenzioso al socket rootful di sistema.
La callback URL deve essere raggiungibile dalle repliche sulla rete CNI.

## Rootless e rete

Topologia iniziale: control plane JVM/native avviato nello stesso user/network/mount
namespace RootlessKit del daemon containerd, con percorsi espliciti per stato e cache.
Le repliche hanno netns proprie collegate al bridge CNI; il proxy usa IP e porta
interna 8080, senza richiedere DNS per i nomi dei container. RootlessKit pubblica le
porte API/management del control plane verso l'host; la pubblicazione e il relativo
cleanup appartengono al launcher/NanoLab.

Il setup può installare pacchetti con privilegi nella VM di test. I processi daemon,
shim, runtime e control plane devono appartenere all'utente non privilegiato sull'host.
UID 0 dentro lo user namespace non prova un'esecuzione rootful. Verificare UID mapping,
proprietario del socket e cgroup delegati; non limitarsi a un flag `rootless=true`.

Usare CNI ADD/DEL e DNS della libreria, senza eseguire `nerdctl run`, `ctr run` o
`crun` direttamente dal provider. Non assumere che CNI assegni anche nomi DNS alle
repliche. Verificare separatamente routing, DNS esterno e callback.

Riferimenti verificati per i prerequisiti: [containerd rootless](https://rootlesscontaine.rs/getting-started/containerd/),
[nerdctl rootless](https://github.com/containerd/nerdctl/blob/main/docs/rootless.md),
[specifica CNI](https://github.com/containernetworking/cni/blob/main/SPEC.md).
Il modello di avvio è già illustrato in `containerd-java/docs/end-to-end.md`.

## Errori, ownership e recovery

Identità persistente: namespace + backend + function label + replica index. Aggiungere
un label backend ai nuovi containerd; non cambiare i nomi persistiti Docker.
Nomi containerd validi e <=76 caratteri, con hash deterministico per evitare collisioni
di nomi lunghi/normalizzati. Il prefisso effettivo è persistito in deploymentObjects.

Creazione fallita: rollback di task, container, snapshot e rete; conservare gli errori
di cleanup insieme alla causa iniziale. Cleanup incompleto: mantenere riferimenti
persistenti, riportare `PartialDeprovisionException` al core e rendere il retry
idempotente anche dopo restart. Non cancellare metadata prima che sia possibile
recuperare tutti i residui. Un container già assente non implica snapshot/CNI assenti.

`reconcile` adotta repliche sane e crea solo quelle mancanti; non chiama `provision`
e non forza CNI ADD su task già attive. `close` chiude client/proxy senza rimuovere
deployment. I limiti CPU/memoria/cpuset devono essere applicati e verificati nei cgroup;
se l'ambiente non li permette, errore esplicito, non avvio illimitato.

## Confini e accettazione

Gli interventi nelle librerie e in NanoLab sono prerequisiti/deliverable su repository
separati, con branch propri. Questo branch NanoFaaS non contiene loro sorgenti copiati.
Il piano non modifica tali repository; ne elenca il lavoro necessario.

Non si estende lo scope a registry privati o hot-update dei limiti OCI: si conserva il
contratto Docker per `imagePullSecrets` non supportati e PATCH di timeout/concorrenza.
Non si deve ridurre la matrice Docker silenziosamente: gli scenari non eseguibili
impediscono di dichiarare parità completa e devono essere riportati con la causa.

Accettazione: selezione isolata dei tre artefatti, SPI invariata, regressione Docker/K8s,
matrice NanoLab equivalente su containerd rootless+crun, recovery e cleanup senza leak,
esecuzione JVM e GraalVM verificata. I test che saltano per assenza del runtime non
valgono come evidenza E2E.
