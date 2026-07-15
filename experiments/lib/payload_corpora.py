"""Generation-independent validation for shared function payload corpora."""

import json
import re
from pathlib import Path


FAMILIES = ("word-stats", "json-transform", "roman-numeral")
PROFILES = ("small", "medium", "large")
PROFILE_SCALES = {
    "word-stats": {"small": 100, "medium": 5_000, "large": 50_000},
    "json-transform": {"small": 10, "medium": 500, "large": 5_000},
    "roman-numeral": {"small": 8, "medium": 64, "large": 3_999},
}
CASES_PER_PROFILE = {"word-stats": 4, "json-transform": 5}
REPO_ROOT = Path(__file__).resolve().parents[2]


def generate_all():
    """Return every deterministic performance corpus as serialized JSON."""
    generated = {}
    for family in FAMILIES:
        for profile in PROFILES:
            corpus = _generate_corpus(family, profile)
            validate_corpus(corpus, family, profile)
            path = (
                Path("functions/test-data")
                / family
                / f"performance-{profile}.json"
            )
            generated[path] = json.dumps(corpus, indent=2, ensure_ascii=False) + "\n"
    return generated


def _generate_corpus(family, profile):
    if family == "word-stats":
        return _generate_word_stats(profile)
    if family == "json-transform":
        return _generate_json_transform(profile)
    return _generate_roman_numeral(profile)


def _generate_word_stats(profile):
    target = PROFILE_SCALES["word-stats"][profile]
    vocabularies = (
        ("distributed", "systems", "process", "message", "network", "latency"),
        ("Quick", "brown", "fox", "jumps", "over", "service"),
        ("worker", "queue", "request", "response", "metric", "trace"),
        ("data", "function", "runtime", "cluster", "scale", "event"),
    )
    cases = []
    for case_index, vocabulary in enumerate(vocabularies, start=1):
        tokens = []
        for index in range(target):
            token = vocabulary[(index + case_index) % len(vocabulary)]
            if (index + 1) % 17 == 0:
                token += "."
            elif (index + 1) % 11 == 0:
                token += ","
            tokens.append(token)
        cases.append(
            {
                "name": f"{profile}-natural-text-{case_index:02d}",
                "input": {"text": " ".join(tokens), "topN": 10},
            }
        )
    return {"family": "word-stats", "profile": profile, "cases": cases}


def _generate_json_transform(profile):
    target = PROFILE_SCALES["json-transform"][profile]
    operations = ("count", "sum", "avg", "min", "max")
    cases = []
    for operation_index, operation in enumerate(operations):
        data = [
            {
                "dept": ("eng", "sales", "ops", "finance")[index % 4],
                "region": ("emea", "na", "apac", "latam")[(index + 1) % 4],
                "tier": ("junior", "mid", "senior")[(index + 2) % 3],
                "salary": 40_000 + ((index * 173 + operation_index * 997) % 90_000),
                "age": 22 + ((index * 5 + operation_index) % 35),
                "score": 50 + ((index * 7 + operation_index * 3) % 51),
            }
            for index in range(target)
        ]
        input_data = {
            "data": data,
            "groupBy": "dept",
            "operation": operation,
        }
        if operation != "count":
            input_data["valueField"] = "salary"
        cases.append({"name": f"{profile}-{operation}", "input": input_data})
    return {"family": "json-transform", "profile": profile, "cases": cases}


def _generate_roman_numeral(profile):
    if profile == "small":
        numbers = (1, 4, 9, 40, 90, 400, 900, 3999)
    elif profile == "medium":
        numbers = tuple(1 + round(index * 3998 / 63) for index in range(64))
    else:
        numbers = range(1, 4000)
    cases = [
        {"name": f"roman-{number:04d}", "input": {"number": number}}
        for number in numbers
    ]
    return {"family": "roman-numeral", "profile": profile, "cases": cases}


def validate_corpus(corpus, family, profile):
    """Raise ValueError when a performance corpus violates its contract."""
    if family not in FAMILIES:
        raise ValueError(f"unknown family: {family}")
    if profile not in PROFILES:
        raise ValueError(f"unknown profile: {profile}")
    if corpus.get("family") != family:
        raise ValueError(f"corpus family must be {family}")
    if corpus.get("profile") != profile:
        raise ValueError(f"corpus profile must be {profile}")

    cases = corpus.get("cases")
    if not isinstance(cases, list) or not cases:
        raise ValueError("cases must be a non-empty list")

    names = [case.get("name") for case in cases]
    if any(not isinstance(name, str) or not name for name in names):
        raise ValueError("every case must have a non-empty name")
    if len(names) != len(set(names)):
        raise ValueError("duplicate case name")
    if any("input" not in case for case in cases):
        raise ValueError("every case must contain input")

    if family == "word-stats":
        _validate_word_stats(cases, profile)
    elif family == "json-transform":
        _validate_json_transform(cases, profile)
    else:
        _validate_roman_numeral(cases, profile)


def _validate_word_stats(cases, profile):
    if len(cases) != CASES_PER_PROFILE["word-stats"]:
        raise ValueError("word-stats corpus must contain 4 cases")
    target = PROFILE_SCALES["word-stats"][profile]
    tolerance = max(2, target // 20)
    for case in cases:
        input_data = case["input"]
        if not isinstance(input_data, dict) or not isinstance(input_data.get("text"), str):
            raise ValueError("word-stats input must contain text")
        count = len(re.findall(r"\w+", input_data["text"]))
        if abs(count - target) > tolerance:
            raise ValueError(f"word count {count} is outside target {target}±{tolerance}")


def _validate_json_transform(cases, profile):
    if len(cases) != CASES_PER_PROFILE["json-transform"]:
        raise ValueError("json-transform corpus must contain 5 cases")
    target = PROFILE_SCALES["json-transform"][profile]
    for case in cases:
        input_data = case["input"]
        if not isinstance(input_data, dict) or not isinstance(input_data.get("data"), list):
            raise ValueError("json-transform input must contain data")
        if len(input_data["data"]) != target:
            raise ValueError(
                f"record count {len(input_data['data'])} does not match {target}"
            )
        if not isinstance(input_data.get("groupBy"), str):
            raise ValueError("json-transform input must contain groupBy")


def _validate_roman_numeral(cases, profile):
    target = PROFILE_SCALES["roman-numeral"][profile]
    if len(cases) != target:
        raise ValueError(f"roman-numeral corpus must contain {target} cases")
    numbers = []
    for case in cases:
        input_data = case["input"]
        number = input_data.get("number") if isinstance(input_data, dict) else None
        if isinstance(number, bool) or not isinstance(number, int):
            raise ValueError("roman-numeral number must be an integer")
        if not 1 <= number <= 3999:
            raise ValueError("roman-numeral number must be between 1 and 3999")
        numbers.append(number)
    if len(numbers) != len(set(numbers)):
        raise ValueError("roman-numeral inputs must be unique")
