#!/bin/bash
# Controlli di regressione per ripresa, fail-closed e lettura della coda.
#
# La prima versione ne eseguiva una e si dichiarava esaurita: i comandi del
# corpo ereditavano stdin, dove stava la coda, e ssh la divorava fino alla fine.
# Il primo test non lo prese perche' i suoi stub non leggevano stdin - per
# questo il finto nanolab qui sotto fa `cat > /dev/null`, come ssh.
#
#   ./test-queue.sh
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/raw" "$T/runs" "$T/fake"
printf '#!/bin/bash\ncat > /dev/null 2>&1\nexit 0\n' > "$T/fake/nanolab.sh"
printf '#!/bin/bash\ncat > /dev/null 2>&1\n[ "${FAIL_COLLECT:-0}" = 1 ] && exit 1\nmkdir -p "%s/raw/$2"\necho ok > "%s/raw/$2/dato"\n' "$T" "$T" > "$T/collect.sh"
chmod +x "$T/fake/nanolab.sh" "$T/collect.sh"

sed -e "s|^HERE=.*|HERE=$T|" -e "s|^NANOLAB=.*|NANOLAB=$T/fake|" -e "s|^MCFAAS=.*|MCFAAS=$T/fake|" \
    -e "s|^RUNS=.*|RUNS=$T/runs|" \
    -e 's|az |true az |g' -e 's|pgrep |false pgrep |g' \
    -e 's|uv run --project packages/sonata-tasks python3 "\$HERE/tables.py"|true tables|' \
    -e 's|git add|true git|g' -e 's|git commit|true commit|g' -e 's|git rev-parse|echo stub|g' \
    -e 's|caffeinate -dimsu -w \$\$ &|true caffeinate \&|' \
    "$HERE/run-queue.sh" > "$T/drv.sh"
cp "$HERE/queue.tsv" "$T/queue.tsv"
while IFS= read -r scenario; do
    mkdir -p "$T/fake/packages/nanolab/scenarios-v2"
    echo scenario > "$T/fake/packages/nanolab/scenarios-v2/$scenario"
done < <(grep -v '^#' "$T/queue.tsv" | cut -f2 | sort -u)
chmod +x "$T/drv.sh"

attesi=$(grep -cv '^#' "$T/queue.tsv")
(cd "$T" && ./drv.sh > /dev/null 2>&1)
avviati=$(grep -c ": avvio" "$T/STATO.md" 2>/dev/null || true)
if [ "$avviati" != "$attesi" ]; then
    echo "FALLITO: avviati $avviati run su $attesi" >&2
    exit 1
fi
ultimo=$(grep -v '^#' "$T/queue.tsv" | tail -1 | cut -f1)
if [ ! -f "$T/raw/$ultimo/.complete" ] \
        || ! grep -q "raw/$ultimo/scenario.yaml" "$T/SHA256SUMS" 2>/dev/null \
        || ! grep -q "raw/$ultimo/tabella.md" "$T/SHA256SUMS" 2>/dev/null; then
    echo "FALLITO: archivio finale senza marker o checksum di scenario/tabella" >&2
    exit 1
fi

# E che il difetto, se tornasse, verrebbe visto: la stessa coda con la lettura
# su stdin deve fermarsi prima.
sed -e 's|read -r -u 9|read -r|' -e 's|^done 9< <|done < <|' \
    -e 's|< /dev/null > "\$RUNS|> "$RUNS|' -e 's|"\$IP" < /dev/null >>|"$IP" >>|' \
    "$T/drv.sh" > "$T/rotto.sh"
chmod +x "$T/rotto.sh"
rm -rf "$T/raw" "$T/STATO.md"; mkdir -p "$T/raw"
(cd "$T" && ./rotto.sh > /dev/null 2>&1)
rotti=$(grep -c ": avvio" "$T/STATO.md" 2>/dev/null || true)
if [ "$rotti" -ge "$attesi" ]; then
    echo "FALLITO: il test non distingue piu' la versione rotta ($rotti su $attesi)" >&2
    exit 1
fi

# Se la pulizia del rilascio ereditato fallisce, la coda non deve avviare la
# matrice sopra pod e stato del run precedente.
sed '/^pulisci_helm()/,/^}/c\
pulisci_helm() { return 1; }' "$T/drv.sh" > "$T/cleanup-fail.sh"
chmod +x "$T/cleanup-fail.sh"
rm -rf "$T/raw" "$T/STATO.md"; mkdir -p "$T/raw"
(cd "$T" && ./cleanup-fail.sh > /dev/null 2>&1)
rc=$?
avviati_cleanup=$(grep -c ": avvio" "$T/STATO.md" 2>/dev/null || true)
if [ "$rc" -eq 0 ] || [ "$avviati_cleanup" -ne 0 ]; then
    echo "FALLITO: pulizia Helm fallita ma la coda ha avviato $avviati_cleanup run" >&2
    exit 1
fi

# Una directory parziale non e' un archivio concluso e deve essere rifatta.
primo=$(grep -v '^#' "$T/queue.tsv" | head -1 | cut -f1)
rm -rf "$T/raw" "$T/STATO.md"; mkdir -p "$T/raw/$primo"
echo parziale > "$T/raw/$primo/image-sizes.json"
(cd "$T" && ./drv.sh > /dev/null 2>&1)
avviati_parziale=$(grep -c ": avvio" "$T/STATO.md" 2>/dev/null || true)
if [ "$avviati_parziale" -ne "$attesi" ] || [ -e "$T/raw/$primo/image-sizes.json" ]; then
    echo "FALLITO: archivio parziale saltato, avviati $avviati_parziale run su $attesi" >&2
    exit 1
fi

# Un collector fallito non puo' trasformarsi in un archivio completato.
grep -v '^#' "$T/queue.tsv" | head -1 > "$T/queue-one.tsv"
mv "$T/queue-one.tsv" "$T/queue.tsv"
rm -rf "$T/raw" "$T/STATO.md"; mkdir -p "$T/raw"
(cd "$T" && FAIL_COLLECT=1 ./drv.sh > /dev/null 2>&1)
rc=$?
if [ "$rc" -eq 0 ] || [ -e "$T/raw/$primo/.complete" ]; then
    echo "FALLITO: collect.sh fallito ma la coda ha dichiarato completo il run" >&2
    exit 1
fi

echo "ok: coda completa, cleanup fail-closed, archivi verificati e raccolta fail-closed"
