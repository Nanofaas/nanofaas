import json

import pytest

from experiments.lib.payload_corpora import (
    CASES_PER_PROFILE,
    PROFILE_SCALES,
    generate_all,
    generate_catalog_samples,
    validate_corpus,
)


def _word_corpus(words=100):
    text = " ".join(f"word{i % 11}" for i in range(words))
    return {
        "family": "word-stats",
        "profile": "small",
        "cases": [
            {"name": f"word-{index}", "input": {"text": text, "topN": 10}}
            for index in range(4)
        ],
    }


def _json_corpus(rows=10):
    operations = ("count", "sum", "avg", "min", "max")
    data = [{"dept": "eng", "salary": index} for index in range(rows)]
    return {
        "family": "json-transform",
        "profile": "small",
        "cases": [
            {
                "name": operation,
                "input": {
                    "data": data,
                    "groupBy": "dept",
                    "operation": operation,
                    **({} if operation == "count" else {"valueField": "salary"}),
                },
            }
            for operation in operations
        ],
    }


def _roman_corpus():
    return {
        "family": "roman-numeral",
        "profile": "small",
        "cases": [
            {"name": f"roman-{number}", "input": {"number": number}}
            for number in (1, 4, 9, 40, 90, 400, 900, 3999)
        ],
    }


def test_expected_profile_scales():
    assert PROFILE_SCALES["word-stats"] == {
        "small": 100,
        "medium": 5_000,
        "large": 50_000,
    }
    assert PROFILE_SCALES["json-transform"] == {
        "small": 10,
        "medium": 500,
        "large": 5_000,
    }
    assert PROFILE_SCALES["roman-numeral"] == {
        "small": 8,
        "medium": 64,
        "large": 3_999,
    }
    assert CASES_PER_PROFILE == {"word-stats": 4, "json-transform": 5}


@pytest.mark.parametrize("corpus", [_word_corpus(), _json_corpus(), _roman_corpus()])
def test_valid_corpora_are_accepted(corpus):
    validate_corpus(corpus, corpus["family"], corpus["profile"])


@pytest.mark.parametrize(
    ("mutate", "message"),
    [
        (lambda corpus: corpus.update(cases=[]), "non-empty"),
        (
            lambda corpus: corpus["cases"][1].update(
                name=corpus["cases"][0]["name"]
            ),
            "duplicate",
        ),
        (lambda corpus: corpus.update(family="json-transform"), "family"),
        (lambda corpus: corpus.update(profile="medium"), "profile"),
        (lambda corpus: corpus["cases"][0].pop("input"), "input"),
    ],
)
def test_invalid_corpus_metadata_is_rejected(mutate, message):
    corpus = _word_corpus()
    mutate(corpus)

    with pytest.raises(ValueError, match=message):
        validate_corpus(corpus, "word-stats", "small")


def test_word_scale_outside_tolerance_is_rejected():
    with pytest.raises(ValueError, match="word count"):
        validate_corpus(_word_corpus(words=20), "word-stats", "small")


def test_json_record_count_is_exact():
    with pytest.raises(ValueError, match="record count"):
        validate_corpus(_json_corpus(rows=9), "json-transform", "small")


def test_roman_input_must_be_in_range():
    corpus = _roman_corpus()
    corpus["cases"][0]["input"]["number"] = 0

    with pytest.raises(ValueError, match="between 1 and 3999"):
        validate_corpus(corpus, "roman-numeral", "small")


def test_generation_is_byte_deterministic_and_valid():
    first = generate_all()
    second = generate_all()

    assert first == second
    assert len(first) == 9
    for path, serialized in first.items():
        corpus = json.loads(serialized)
        assert serialized.endswith("\n")
        assert path.as_posix().endswith(
            f"{corpus['family']}/performance-{corpus['profile']}.json"
        )
        validate_corpus(corpus, corpus["family"], corpus["profile"])


def test_each_json_profile_covers_all_operations():
    generated = generate_all()

    for profile in ("small", "medium", "large"):
        path = next(
            path
            for path in generated
            if path.as_posix().endswith(
                f"json-transform/performance-{profile}.json"
            )
        )
        corpus = json.loads(generated[path])
        assert {case["input"]["operation"] for case in corpus["cases"]} == {
            "count",
            "sum",
            "avg",
            "min",
            "max",
        }


def test_roman_profiles_are_unique_and_in_range():
    generated = generate_all()

    for profile, expected_count in PROFILE_SCALES["roman-numeral"].items():
        path = next(
            path
            for path in generated
            if path.as_posix().endswith(
                f"roman-numeral/performance-{profile}.json"
            )
        )
        corpus = json.loads(generated[path])
        numbers = [case["input"]["number"] for case in corpus["cases"]]
        assert len(numbers) == expected_count
        assert len(set(numbers)) == expected_count
        assert min(numbers) == 1
        assert max(numbers) == 3999


def test_catalog_samples_match_each_family_first_small_input():
    corpora = generate_all()
    samples = generate_catalog_samples()

    assert {path.as_posix() for path in samples} == {
        f"tools/controlplane/scenarios/payloads/{family}-sample.json"
        for family in PROFILE_SCALES
    }
    for family in PROFILE_SCALES:
        corpus_path = next(
            path
            for path in corpora
            if path.as_posix().endswith(f"{family}/performance-small.json")
        )
        sample_path = next(
            path
            for path in samples
            if path.as_posix().endswith(f"{family}-sample.json")
        )
        first_input = json.loads(corpora[corpus_path])["cases"][0]["input"]
        assert json.loads(samples[sample_path]) == first_input
