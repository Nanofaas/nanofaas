# Shared word-statistics runtime contract (B5)

Java, Go, JavaScript, Python and Rust implement the same `word-stats-runtime-v1`
contract. This changes example-handler semantics for punctuation, some Unicode
inputs, invalid topN values and half-way rounding; it changes neither SDK
transport nor scheduler behavior. Older measurements keep their frozen handlers.

Input is a plain string or an object with string `text` and optional `topN`.
Missing, non-string or empty text returns the existing field-required error.
A nonempty input with no tokens returns `No words found in input`.
Tokens are maximal sequences of Unicode letters/numbers (general categories L
and N). Every other character is a separator, including punctuation, underscores,
combining marks and emoji. There is no Unicode normalization. Per scalar, take
the first scalar of its lowercase mapping, with no locale or whole-word context.

Count every token and distinct normalized token. Sort by descending frequency,
then ascending Unicode scalar sequence; never use locale-sensitive collation.
TopN defaults to 10 for absent/non-numeric/null/boolean values; finite numbers
are floored, clamped to zero and the vocabulary size. Zero returns an empty top
list while keeping the other statistics. Length counts Unicode scalars, not UTF8
bytes or UTF16 units. Round the mean to two decimal places, half up, using
integer cents: `(200 * total_scalar_length + word_count) // (2 * word_count)`.

`functions/test-data/word-stats/runtime_contract.py` generates the common fixture
and checks known rounding/Unicode examples. Every handler's native tests read
`runtime-contract.json`, including all medium performance inputs with identical
expected outputs. Generate, then verify with `--check`. The CI tools job runs
this check automatically through `test_runtime_contract.py`. Existing correctness and
performance corpora are not rewritten. Unicode categories follow each runtime's
Unicode tables; the fixtures cover common assigned characters, not equivalence
for characters introduced in different Unicode releases. The performance corpus
is ASCII, so that version boundary does not affect B5's measured workload.

Rust uses the small unicode-general-category classifier because its standard
`is_alphanumeric` also admits alphabetic marks outside the L/N categories:
https://docs.rs/unicode-general-category/1.1.0/unicode_general_category/.
