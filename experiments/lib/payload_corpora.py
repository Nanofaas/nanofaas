"""Generation-independent validation for shared function payload corpora."""

import re


FAMILIES = ("word-stats", "json-transform", "roman-numeral")
PROFILES = ("small", "medium", "large")
PROFILE_SCALES = {
    "word-stats": {"small": 100, "medium": 5_000, "large": 50_000},
    "json-transform": {"small": 10, "medium": 500, "large": 5_000},
    "roman-numeral": {"small": 8, "medium": 64, "large": 3_999},
}
CASES_PER_PROFILE = {"word-stats": 4, "json-transform": 5}


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
