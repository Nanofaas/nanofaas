"""The shared word-stats-runtime-v1 contract; see docs/word-stats-contract.md."""

from collections import Counter
import math
from nanofaas.sdk import nanofaas_function


@nanofaas_function
def handle(input_data):
    fields = input_data if isinstance(input_data, dict) else {}
    text = input_data if isinstance(input_data, str) else fields.get("text")
    if not isinstance(text, str) or not text:
        return {"error": "Field 'text' is required and must be non-empty"}
    top_n = fields.get("topN", 10)
    if (
        isinstance(top_n, bool)
        or not isinstance(top_n, (int, float))
        or (isinstance(top_n, float) and not math.isfinite(top_n))
    ):
        top_n = 10
    frequencies = Counter()
    word, count, total_length = [], 0, 0
    for char in text:
        if char.isalnum():
            word.append(char.lower()[0])
            total_length += 1
        elif word:
            frequencies["".join(word)] += 1
            count += 1
            word.clear()
    if word:
        frequencies["".join(word)] += 1
        count += 1
    if not count:
        return {"error": "No words found in input"}
    top_n = min(len(frequencies), max(0, math.floor(top_n)))
    ordered = sorted(frequencies.items(), key=lambda item: (-item[1], item[0]))[:top_n]
    cents = (200 * total_length + count) // (2 * count)
    return {
        "wordCount": count,
        "uniqueWords": len(frequencies),
        "topWords": [{"word": word, "count": n} for word, n in ordered],
        "averageWordLength": cents / 100,
    }
