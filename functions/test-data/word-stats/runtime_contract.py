"""Generate the shared B5 contract and medium-corpus qualification fixtures."""

from collections import Counter
import argparse
import json
import math
from pathlib import Path
import unicodedata

ROOT = Path(__file__).resolve().parent


def expected(input_value):
    """Independent reference for the documented word-statistics contract."""
    fields = input_value if isinstance(input_value, dict) else {}
    text = input_value if isinstance(input_value, str) else fields.get("text")
    if not isinstance(text, str) or not text:
        return {"error": "Field 'text' is required and must be non-empty"}
    tokens, current = [], []
    for char in text:
        if unicodedata.category(char)[0] in "LN":
            current.append(char.lower()[0])
        elif current:
            tokens.append("".join(current))
            current = []
    if current:
        tokens.append("".join(current))
    if not tokens:
        return {"error": "No words found in input"}
    top = fields.get("topN", 10)
    if (
        isinstance(top, bool)
        or not isinstance(top, (int, float))
        or (isinstance(top, float) and not math.isfinite(top))
    ):
        top = 10
    top = min(len(set(tokens)), max(0, math.floor(top)))
    counts = Counter(tokens)
    ordered = sorted(counts.items(), key=lambda item: (-item[1], item[0]))[:top]
    count = len(tokens)
    cents = (200 * sum(map(len, tokens)) + count) // (2 * count)
    return {
        "wordCount": count,
        "uniqueWords": len(counts),
        "topWords": [{"word": word, "count": n} for word, n in ordered],
        "averageWordLength": cents / 100,
    }


def document():
    baseline = json.loads((ROOT / "correctness.json").read_text())["cases"]
    for case in baseline:
        assert expected(case["input"]) == case["expected"]
    cases = [(case["name"], case["input"]) for case in baseline]
    cases += [
        ("punctuation separates words", {"text": "one,two-three_four"}),
        ("leading and Unicode spaces", {"text": "  alpha\u00a0beta\talpha  "}),
        ("Unicode scalar lengths", {"text": "éé 𐐀"}),
        ("scalar lexical order", {"text": "𐐀 豈"}),
        ("simple lowercase without final sigma", {"text": "ΟΣ οσ ος"}),
        ("simple lowercase first scalar", {"text": "İ I ı i"}),
        ("combining mark separates words", {"text": "a\u093fb"}),
        ("emoji separates words", {"text": "a😀b"}),
        ("Unicode numeric categories", {"text": "² Ⅷ ٢"}),
        ("half-up rounding", {"text": "a a a a a a a bb"}),
        ("zero topN", {"text": "a b c", "topN": 0}),
        ("negative topN", {"text": "a b c", "topN": -2}),
        ("fractional topN", {"text": "a b c", "topN": 1.9}),
        ("boolean topN is not numeric", {"text": "a b c", "topN": True}),
        ("string topN defaults", {"text": "a b c", "topN": "1"}),
        ("null topN defaults", {"text": "a b c", "topN": None}),
        ("huge topN clamps to vocabulary", {"text": "a b c", "topN": 1e100}),
        ("non-string text", {"text": 42}),
        ("null input", None),
        ("array input", ["hello"]),
        ("empty text", {"text": ""}),
        ("whitespace only", {"text": " \t\n\u00a0"}),
        ("punctuation only", {"text": "?!"}),
    ]
    cases += [
        ("benchmark " + c["name"], c["input"])
        for c in json.loads((ROOT / "performance-medium.json").read_text())["cases"]
    ]
    return {
        "contract": "word-stats-runtime-v1",
        "cases": [{"name": n, "input": i, "expected": expected(i)} for n, i in cases],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    assert expected({"text": "éé 𐐀"})["averageWordLength"] == 1.5
    assert expected({"text": "a a a a a a a bb"})["averageWordLength"] == 1.13
    assert len(expected({"text": "a b c", "topN": 10**400})["topWords"]) == 3
    text = json.dumps(document(), ensure_ascii=False, indent=2, allow_nan=False) + "\n"
    path = ROOT / "runtime-contract.json"
    if args.check:
        assert path.read_text() == text, "Regenerate runtime-contract.json"
    else:
        path.write_text(text)
    print(
        "Verified" if args.check else "Generated",
        len(document()["cases"]),
        "shared runtime cases",
    )


if __name__ == "__main__":
    main()
