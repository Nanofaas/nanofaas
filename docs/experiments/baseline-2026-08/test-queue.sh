#!/bin/bash
# Il controllo che la coda esegua tutte le righe, non solo la prima.
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
printf '#!/bin/bash\ncat > /dev/null 2>&1\nexit 0\n' > "$T/collect.sh"
chmod +x "$T/fake/nanolab.sh" "$T/collect.sh"

sed -e "s|^HERE=.*|HERE=$T|" -e "s|^NANOLAB=.*|NANOLAB=$T/fake|" -e "s|^MCFAAS=.*|MCFAAS=$T/fake|" \
    -e "s|^RUNS=.*|RUNS=$T/runs|" \
    -e 's|az |true az |g' -e 's|pgrep |false pgrep |g' \
    -e 's|uv run --project packages/sonata-tasks python3 "\$HERE/tables.py"|true tables|' \
    -e 's|git add|true git|g' -e 's|git commit|true commit|g' -e 's|git rev-parse|echo stub|g' \
    -e 's|caffeinate -dimsu -w \$\$ &|true caffeinate \&|' \
    "$HERE/run-queue.sh" > "$T/drv.sh"
sed -i '' 's|^    stato "\$NAME: archiviato"|    mkdir -p "$HERE/raw/$NAME" \&\& echo ok > "$HERE/raw/$NAME/dato"\n    stato "$NAME: archiviato"|' "$T/drv.sh"
cp "$HERE/queue.tsv" "$T/queue.tsv"
chmod +x "$T/drv.sh"

attesi=$(grep -cv '^#' "$T/queue.tsv")
(cd "$T" && ./drv.sh > /dev/null 2>&1)
avviati=$(grep -c ": avvio" "$T/STATO.md" 2>/dev/null || echo 0)
if [ "$avviati" != "$attesi" ]; then
    echo "FALLITO: avviati $avviati run su $attesi" >&2
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
rotti=$(grep -c ": avvio" "$T/STATO.md" 2>/dev/null || echo 0)
if [ "$rotti" -ge "$attesi" ]; then
    echo "FALLITO: il test non distingue piu' la versione rotta ($rotti su $attesi)" >&2
    exit 1
fi

echo "ok: $avviati run su $attesi con la lettura su fd 9, $rotti con la lettura su stdin"
