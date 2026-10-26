# Forecasting and corpus regression fixes

> Execute the Java changes together: provider selection and computation must share the same immutable query context. The corpus fix is independent.

**Goal:** fix the three confirmed defects from the newly pulled code and retain regression coverage.

**Architecture:** capture provider/model once at the selected forecast entry point. Copy the window state and its revision/time under the observation monitor, then estimate outside it. EWMA and trend sources share that snapshot boundary; trend calculations carry their model as a local value. Restore parent-directory creation in the corpus generator.

**Tech stack:** Java 25, JUnit/Mockito, Python 3.12/pytest.

**Spec:** the user's approval to fix the ARI provider-switch race, observation blocking/loss and missing corpus output directories.

## Tasks and verification

- [x] Add `ForecastConcurrencyTest`: block a selected ARI query before its snapshot, switch to oracle/EWMA or change ARI order, and require the original provider/parameters to finish that query.
- [x] In the same test class, pause the real ARI computation with a test-only static spy. Require arrivals for two functions and a later forecast to complete before releasing it; assert original rate/provider/revision/time remain stable and both arrivals reach the completed window. All four Java cases failed before production edits and pass after the fix.
- [x] Add a detached-state snapshot boundary to `WindowedForecastSource`; adapt `EwmaForecastSource`, `TrendForecastSource` and `ForecastingConfiguration`. Remove shared mutable active-model state. No new dependencies, model algorithms or timeout changes.
- [x] Execute the actual corpus CLI in a temporary checkout without family directories; verify `--check` fails without writes, generation recreates all nine committed corpora, and `--check` then passes. Restore `mkdir` only in write mode. RED observed, then 17 corpus tests passed.
- [x] Run forecasting, runtime-config and offload suites (227 tests passed), plus scripts/payload tests (148 passed, 1 skipped, 9 subtests passed). Independent review found no actionable issues. Diff whitespace check passed; refreshed GitNexus change analysis covered all 9 files and 33 symbols. Its zero indexed affected processes does not override the pre-edit HIGH impact or replace downstream regression tests.

## Review focus

- Query model, provider label and parameters must come from one settings read.
- Copy history while locked; never expose mutable series state to an unlocked estimator.
- Preserve MISSING/STALE handling, generation separation and revision/production-time identity.
- Concurrent calculations must not share mutable model fields.
- Use synchronization barriers for regressions, not machine-speed thresholds or large benchmark inputs.

Pre-edit graph analysis: HIGH for both forecast entry points (including one-shot preparation consumers), LOW for EWMA and generator changes. Spring selection lookup was UNKNOWN and corroborated with source/bean consumers.
