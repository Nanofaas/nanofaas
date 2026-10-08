# Module boundaries and repository layout — execution evidence

Executed in Native mode on 2026-10-08. Runtime behavior, Java packages, public module/backend IDs, plugin ID and Gradle project paths are unchanged. No API-only projects were introduced solely to mirror Gradle modules.

## Local commits and integration order

| Repository | Commit | Change |
| --- | --- | --- |
| NanoFaaS | `c1ebfdc46bc3670d0d4bbf0eaeaed9f09294cdd2` | Artifact ownership independent of directory layout. |
| NanoFaaS | `0f2520789bfb897f48ba6d1635f5a437d8fe38a4` | Optional module boundaries and exact sync composition exceptions. |
| NanoLab | `f00e71cded7dbe0109c226b54164857f2f327bd7` | Per-plan immutable native Dockerfile resolution; old/new layouts supported. |
| NanoFaaS | `2c018ed538ba4aec7e51b559eeb20a9e36924a5c` | Seven shared libraries under `platform/libs/`. |
| NanoFaaS | `ff1230b53189ba96e42d01c271c2b632c29598de` | Build tooling under `tools/`, explicit source Dockerfile, live consumers corrected. |

Branches: NanoFaaS `work/module-boundaries-layout`, NanoLab `work/nanofaas-layout-compatibility`. Integrate NanoLab compatibility before the NanoFaaS moves. Both are local; shared-branch integration remains a separate decision. Issue #236 stays open while real cross-architecture validation and integration are outstanding.

## Verification

- Final `./gradlew build -PcontrolPlaneModules=all --continue`: PASS (2m 11s). Final `./gradlew releaseChecks -PcontrolPlaneModules=all --continue`: PASS (3s). Commands set `BUILDX_BUILDER=default` and `NANOFAAS_ONE_SHOT_WORKLOAD_BINARY` to the locally built Rust workload. Cargo was invoked from `/home/michele/.cargo/bin` because the shell PATH omitted it.
- All, none, async-queue, sync-queue and default: each 26 composition/source architecture tests, zero failures. Each selection matches its pre-move baseline; runtime forbidden-dependency verification passes.
- Gradle hierarchy and included-build identities are identical. Eight compile/runtime dependency reports for core, sync queue, Docker and containerd match baseline.
- SPI, sync, forecasting, P2P, build metadata and runtime architecture suites pass. Temporary forbidden gateway, new core candidate and direct enclosing-enqueuer field mutations each fail; mutations were restored. Shared-package foreign SPI fixtures fail.
- Gradle plugin focused and full suites pass, including command/provenance coverage for custom templates, named contexts, native target selection and multi-architecture publication command generation. No registry publication was performed.
- All script tests: **116 PASS**. Both Compose configurations parse. All three tracked recipes validate; local-demo assembly preserves staging filenames, launch/config/Python SDK bytes and JAR entry sets. Source identity/report checksums legitimately change; templates remain outside staging. Final task-done `build releaseChecks` PASS (3m51s); current Gradle XML contains **2747 tests, zero failures/errors**.
- Historical `docs/experiments`, completed `docs/plans`, archived review/evidence and provenance bytes remain unchanged against base `748ab0d0c85ff5be610a3958fa5c7252a485254b`. Live guide, SDK mirror and manuscript paths were updated.
- GitNexus refreshed before tooling commit; full detect-changes reports 67 files, 34 symbols, 243 affected processes, CRITICAL risk without a partial/truncated result. Real packaging validation and final review address the affected producers/consumers. Its whole-flow enumeration and FTS have tool limitations; missing graph results were confirmed in source and never used as proof of no callers.

## Real images and native artifacts

All builds used the local ARM64 host and existing toolchain. Containers created for validation were removed.

| Path | Real result |
| --- | --- |
| Three Java-lite function Dockerfiles | Build PASS; original `/app/<function>` entrypoints; `/health` HTTP 200. |
| Prebuilt control-plane JVM Dockerfile | Build PASS with context `platform/control-plane`; `/actuator/health` HTTP 200. |
| `Dockerfile.from-source` | Build PASS with repository root context; `/actuator/health` HTTP 200. |
| Native wrapper / `native-executable` export | Build PASS; extracted application passes executable-format assertion; health HTTP 200. |
| one-shot-local-native recipe assembly | Real container builder/export PASS; exported application passes executable assertion; packaged startup/error smoke PASS (404/400). |
| Actual `recipe-native` stage | Build PASS with generated recipe report inputs and both `recipe`/`containerd_maven_repo` contexts; packaged startup/error smoke PASS (404/400). |
| Combined watchdog + Java image | Build PASS; `/health` HTTP 200; Java builder consumes the complete Gradle root inputs. |

Actual amd64 build: **pending**. The working default builder exposes ARM64 only; the separately selected NanoLab builder cannot start due to its NVIDIA runtime hook. Command/provenance tests do not substitute for a real amd64 image. No privileged emulation or unrelated builder reconfiguration was introduced.

## NanoLab verification and known baseline failures

Focused image plan/bake/soak/recipe observation: 114 PASS before tooling move; ruff lint/format PASS; basedpyright zero errors/warnings. A final source-contract run uses `NANOFAAS_ROOT` pointing at the committed relocated NanoFaaS worktree, since the contract setup archives HEAD rather than the dirty directory. Final committed-source contract run: **114 PASS in 37.43s** against NanoFaaS `ff1230b5`.

Full NanoLab suite before tooling commit: branch 3277 PASS, 193 FAIL, 19 ERROR; original branch 3270 PASS, 193 FAIL, 19 ERROR. All **212 failing/error identifiers are identical**, with no added/removed failures. The guarded release recipe/module-selection mismatch is independent of native-path compatibility; no policy check was relaxed. These existing failures prevent claiming a green full NanoLab suite. Final stable clean-source run against `55fca0aa`: **3277 PASS, 193 FAIL, 19 ERROR in 184.62s**, with exactly the same 212 identifiers as original baseline. A preceding run during documentation changes added two dirty/moving-source failures; both targeted tests pass on a clean source, and neither appears in this stable run.

The reviewer also confirmed the generic NanoLab Bake renderer already omits `containerd_maven_repo` at baseline `8313e6e`. This native-build integration limitation is unchanged by path resolution; generic native Bake execution can fail until that context is supplied. The existing NanoFaaS wrapper/recipe producers retain and exercise both required contexts. Resolve this separately before treating generic NanoLab native Bake as verified.

Local logs: `/tmp/nanofaas-layout-build-final.log`, `/tmp/nanofaas-layout-release-final.log`, `/tmp/nanofaas-layout-final-{profile}.log`, `/tmp/nanofaas-layout-scripts-final.log`, `/tmp/nanofaas-layout-plugin-full.log`, `/tmp/nanolab-layout-new-source-contract.log`, `/tmp/nanolab-layout-full.log`, `/tmp/nanolab-layout-full-baseline.log`, `/tmp/nanolab-layout-full-stable.log`. Profile XML and ledger snapshots are preserved at `/tmp/nanofaas-native-plan-evidence-2026-10-08`. Logs are session evidence, not committed provenance artifacts.

## Execution rulings

- Ruling: approved plans already specify isolated worktrees; no repeated consent question — user authorized their execution — no shared branch changes.
- Task 1: Ruling: CoreArchitectureTest scans production only via DoNotIncludeTests — code-source ownership distinguishes test output and new API marker creates test-only dependencies; production boundaries are the intended contract — costs lost incidental policing of integration-test dependencies, which are explicitly legitimate. Failure evidence: R6 203 test classes and R2 two marker references, /tmp/nanofaas-boundary-task1-green.log.
- Task 2: Ruling: SPI regression uses existing JUnit assertions, not AssertJ — SPI test classpath lacks AssertJ and no new dependency is warranted — no functional compatibility cost.
- Task 2: Ruling: fix existing selected-profile subject guard to split module IDs before contains — async-queue incorrectly matched sync-queue as substring, revealed by expanded profile run — cost minimal test-only policy correction. RED /tmp/nanofaas-boundary-async-queue.log; the exact-profile matrix is the regression.
- Task 1: Ruling: pre-existing guarded all-selection recipe failures remain outside this layout change — original and modified suites have identical failures — cost: full NanoLab suite remains red until the independent release-recipe/policy mismatch is fixed.
- Task 3: Ruling: exclude .gitnexus and .superpowers from Docker contexts — local index and execution scratch are not build inputs, and this session introduced substantial scratch — cost: an undeclared build dependency on agent metadata would no longer work; none is present.
- Task 3: Ruling: update IssueCoverageTest live structure assertion missed by initial inventory — full build proves old platform/common assertion now wrong; tested structure must follow approved library mapping — no behavior cost. RED /tmp/nanofaas-layout-build-full.log, IssueCoverageTest.issue001_structureExists. Cargo exists at /home/michele/.cargo/bin but is absent from shell PATH; use that toolchain explicitly for physical Rust test prerequisite.
- Task 3: Ruling: use BUILDX_BUILDER=default only in validation commands — selected NanoLab builder fails NVIDIA prestart hook (driver not loaded); CLI tests pass with local builder — cost: selected docker-container builder remains unverified; global settings unchanged.
- Task 3: Ruling: include watchdog combined image in live consumer corrections and give its Java builder the root context — final scan found obsolete COPY platform/common and root settings already require omitted plugin/library inputs — cost: larger build-context copy, filtered by existing .dockerignore; stages/runtime semantics unchanged. RED watchdog COPY source test /tmp/nanofaas-layout-watchdog-red.log.
- Task 3: Ruling: update current path descriptions in seven manuscript chapters — these explain today’s source/build structure rather than pinning experiment provenance — cost: a reader comparing directory names with older revisions must use those revisions; historical claims and commit references remain unchanged.
- Final: Ruling: real amd64 execution set aside by reviewer — retain explicit pending acceptance, because builder is ARM64 only — cost: cross-architecture failures remain undetected.
- Final: Ruling: shared-branch/deployed integration set aside by reviewer — keep both local branches and integrate compatible NanoLab first — cost: deployed compatibility remains unproven until integration.
- Final: Ruling: overall NanoLab suite health set aside by reviewer — do not call full suite green; retain baseline failures and complete stable-source rerun — cost: independent release-recipe guard mismatch still blocks full-suite health.
- Final: Ruling: two dirty-source failures set aside by reviewer — clean targeted checks pass; require stable-source full rerun before final conclusion — cost: a persistent failure would remain unresolved if only targeted results were used.
- Final: Ruling: NanoLab Bake omits containerd_maven_repo at baseline — preserve renderer behavior in this path-compatibility change and document separate existing native-build limitation — cost: generic native Bake builds can fail until the context integration is addressed; #236 is not closed.
- Final: Ruling: registry publication and NVIDIA builder set aside by reviewer — retain local-image validation only, with no publication or unrelated builder reconfiguration — cost: published/deployed images and that builder remain unverified.
- Final: Ruling: benchmark/website/runtime redesign set aside by reviewer — honor explicit exclusions; no unrelated changes — cost: those independent tasks remain outstanding.

## Final independent review

Fresh-context, read-only review by `gpt-6-astra` of NanoFaaS `748ab0d0..55fca0aa` and NanoLab `8313e6e..f00e71cd`: **no Critical, Important or Minor findings in introduced changes**. All ten plan review-focus items were checked explicitly. No fix pass or second review was needed. The reviewer approved code integration in prerequisite order while distinguishing it from issue-closure acceptance or a green full NanoLab suite.

Deferred minors: none. Reviewer set-aside behaviors and the executor's decisions/costs are included exhaustively in the rulings above.

`finishing-a-development-branch` requires “If tests fail, report the failures and stop”. Full NanoLab retains its documented baseline failures, so no integration menu/action is performed here. Local branches are preserved for review; merge/push/publication and issue closure have not occurred.

## Existing NanoLab failing/error test identifiers

```text
packages/nanolab/tests/cli/test_release_command.py::test_generic_release_failure_releases_resources_and_records_metadata
packages/nanolab/tests/cli/test_release_command.py::test_generic_release_fresh_run_supersedes_the_previous_one
packages/nanolab/tests/cli/test_release_command.py::test_generic_release_interrupt_still_releases_infrastructure
packages/nanolab/tests/cli/test_release_command.py::test_generic_release_plan_stays_offline
packages/nanolab/tests/cli/test_release_command.py::test_generic_release_preflight_rejection_removes_the_extracted_tree
packages/nanolab/tests/cli/test_release_command.py::test_generic_release_resume_names_the_journal_it_wanted
packages/nanolab/tests/cli/test_release_command.py::test_generic_release_resume_requires_an_existing_journal
packages/nanolab/tests/cli/test_release_command.py::test_generic_release_resume_reuses_the_verified_journal
packages/nanolab/tests/cli/test_release_command.py::test_generic_release_run_forwards_keep_and_selection
packages/nanolab/tests/cli/test_release_command.py::test_generic_release_run_journals_and_passes
packages/nanolab/tests/cli/test_release_command.py::test_generic_release_run_removes_the_extracted_tree
packages/nanolab/tests/cli/test_release_command.py::test_generic_release_run_starts_without_provision_acknowledgement
packages/nanolab/tests/cli/test_release_command.py::test_generic_release_run_uses_a_versioned_default_run_directory
packages/nanolab/tests/cli/test_release_command.py::test_locked_release_does_not_supersede_the_active_journal
packages/nanolab/tests/cli/test_release_command.py::test_release_recipe_prefix_until_staging_push_compiles_without_cloud[build-arm64-images-Build
packages/nanolab/tests/cli/test_release_command.py::test_release_recipe_prefix_until_staging_push_compiles_without_cloud[push-amd64-images-to-local-registry-Push
packages/nanolab/tests/cli/test_release_command.py::test_release_recipe_prefix_until_staging_push_compiles_without_cloud[push-arm64-images-to-local-registry-Push
packages/nanolab/tests/cli/test_release_command.py::test_release_recipe_prefix_until_staging_push_compiles_without_cloud[test-arm64-images-Test
packages/nanolab/tests/cli/test_release_command.py::test_teardown_finds_a_kept_superseded_journal
packages/nanolab/tests/cli/test_release_command.py::test_teardown_is_idempotent
packages/nanolab/tests/cli/test_release_command.py::test_teardown_releases_what_a_kept_run_left_behind
packages/nanolab/tests/cli/test_release_command.py::test_teardown_reports_in_vm_resources_without_failing
packages/nanolab/tests/cli/test_release_command.py::test_teardown_works_after_a_preflight_that_would_reject
packages/nanolab/tests/metrics/test_catalogue_coverage.py::test_every_module_is_classified
packages/nanolab/tests/plans/test_release.py::test_amd64_build_phase_records_the_commands_it_will_run
packages/nanolab/tests/plans/test_release.py::test_amd64_buildx_builder_replaces_a_surviving_builder
packages/nanolab/tests/plans/test_release.py::test_amd64_recipe_dag_preserves_release_boundaries
packages/nanolab/tests/plans/test_release.py::test_arm_profile_drift_fails_before_acquisition
packages/nanolab/tests/plans/test_release.py::test_attest_phase_records_one_signature_per_pinned_digest
packages/nanolab/tests/plans/test_release.py::test_build_release_request_is_offline_and_builds_current_matrix
packages/nanolab/tests/plans/test_release.py::test_build_release_request_plans_from_the_commit_not_the_worktree
packages/nanolab/tests/plans/test_release.py::test_build_release_workflow_compiles_without_cloud_discovery
packages/nanolab/tests/plans/test_release.py::test_build_release_workflow_plans_arm64_and_publish_from_the_source_tree
packages/nanolab/tests/plans/test_release.py::test_direct_request_requires_frozen_arm_inputs[cells]
packages/nanolab/tests/plans/test_release.py::test_direct_request_requires_frozen_arm_inputs[hash]
packages/nanolab/tests/plans/test_release.py::test_direct_request_requires_frozen_arm_inputs[missing]
packages/nanolab/tests/plans/test_release.py::test_direct_request_requires_frozen_recipe_inputs
packages/nanolab/tests/plans/test_release.py::test_missing_execution_credentials_fail_before_any_provider_call[None]
packages/nanolab/tests/plans/test_release.py::test_missing_execution_credentials_fail_before_any_provider_call[selection1]
packages/nanolab/tests/plans/test_release.py::test_missing_execution_credentials_fail_before_any_provider_call[selection2]
packages/nanolab/tests/plans/test_release.py::test_new_arm_source_transfer_failure_compensates_all_acquired_resources
packages/nanolab/tests/plans/test_release.py::test_new_arm_workflow_failures_cleanup_and_never_publish[build-individual
packages/nanolab/tests/plans/test_release.py::test_new_arm_workflow_failures_cleanup_and_never_publish[digest-invalid
packages/nanolab/tests/plans/test_release.py::test_new_arm_workflow_failures_cleanup_and_never_publish[push-push
packages/nanolab/tests/plans/test_release.py::test_new_arm_workflow_failures_cleanup_and_never_publish[smoke-smoke
packages/nanolab/tests/plans/test_release.py::test_preflight_freezes_archive_and_inventory_before_acquisition
packages/nanolab/tests/plans/test_release.py::test_preflight_freezes_arm64_matrix_and_profiles
packages/nanolab/tests/plans/test_release.py::test_preflight_freezes_release_recipe_groups
packages/nanolab/tests/plans/test_release.py::test_recipe_phase_identity_ignores_temporary_planning_directory
packages/nanolab/tests/plans/test_release.py::test_release_dag_has_symmetric_recipe_build_and_push
packages/nanolab/tests/plans/test_release.py::test_release_source_outlives_the_benchmarks
packages/nanolab/tests/plans/test_release.py::test_release_verifiers_reject_missing_or_conflicting_matrices[ambiguous]
packages/nanolab/tests/plans/test_release.py::test_release_verifiers_reject_missing_or_conflicting_matrices[duplicate]
packages/nanolab/tests/plans/test_release.py::test_release_verifiers_reject_missing_or_conflicting_matrices[empty-arm]
packages/nanolab/tests/plans/test_release.py::test_release_verifiers_reject_missing_or_conflicting_matrices[missing-amd]
packages/nanolab/tests/plans/test_release.py::test_release_verifiers_reject_missing_or_conflicting_matrices[missing-arm]
packages/nanolab/tests/plans/test_release.py::test_release_verifiers_reject_missing_or_conflicting_matrices[wrong-arch]
packages/nanolab/tests/plans/test_release.py::test_release_verifiers_route_complete_frozen_matrices
packages/nanolab/tests/plans/test_release.py::test_resumed_attest_retries_a_failed_prelude_step
packages/nanolab/tests/plans/test_release.py::test_resumed_attest_skips_the_digests_it_already_signed
packages/nanolab/tests/release/test_build.py::test_amd64_commands_delegate_all_image_builds_to_root_recipes
packages/nanolab/tests/release/test_recipe.py::test_arm_profile_freeze_uses_raw_bytes
packages/nanolab/tests/release/test_recipe.py::test_artifact_only_control_plane_is_explicitly_validated[cp-image]
packages/nanolab/tests/release/test_recipe.py::test_artifact_only_control_plane_is_explicitly_validated[cp-mode]
packages/nanolab/tests/release/test_recipe.py::test_artifact_only_control_plane_is_explicitly_validated[cp-native]
packages/nanolab/tests/release/test_recipe.py::test_artifact_only_control_plane_is_explicitly_validated[missing-cp]
packages/nanolab/tests/release/test_recipe.py::test_artifact_only_control_plane_is_explicitly_validated[missing-image]
packages/nanolab/tests/release/test_recipe.py::test_artifact_only_control_plane_is_explicitly_validated[null-function]
packages/nanolab/tests/release/test_recipe.py::test_matrix_catalog_change_fails_preflight
packages/nanolab/tests/release/test_recipe.py::test_null_archive_source_is_not_commit_evidence
packages/nanolab/tests/release/test_recipe.py::test_profile_freeze_uses_raw_bytes
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_maps_all_component_kinds[0-9]
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_maps_all_component_kinds[1-12]
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_maps_all_component_kinds[2-23]
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_rejects_identity_or_matrix_mismatch[artifact]
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_rejects_identity_or_matrix_mismatch[builder]
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_rejects_identity_or_matrix_mismatch[digest]
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_rejects_identity_or_matrix_mismatch[distribution]
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_rejects_identity_or_matrix_mismatch[duplicate]
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_rejects_identity_or_matrix_mismatch[extra]
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_rejects_identity_or_matrix_mismatch[failed]
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_rejects_identity_or_matrix_mismatch[foreign-image]
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_rejects_identity_or_matrix_mismatch[gc]
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_rejects_identity_or_matrix_mismatch[hash]
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_rejects_identity_or_matrix_mismatch[jfr]
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_rejects_identity_or_matrix_mismatch[malformed-id]
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_rejects_identity_or_matrix_mismatch[missing-id]
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_rejects_identity_or_matrix_mismatch[missing-image]
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_rejects_identity_or_matrix_mismatch[missing-source]
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_rejects_identity_or_matrix_mismatch[missing]
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_rejects_identity_or_matrix_mismatch[mode]
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_rejects_identity_or_matrix_mismatch[modules]
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_rejects_identity_or_matrix_mismatch[name]
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_rejects_identity_or_matrix_mismatch[native-optimization]
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_rejects_identity_or_matrix_mismatch[null-image]
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_rejects_identity_or_matrix_mismatch[optimization]
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_rejects_identity_or_matrix_mismatch[published]
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_rejects_identity_or_matrix_mismatch[recipe-schema]
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_rejects_identity_or_matrix_mismatch[schema]
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_rejects_identity_or_matrix_mismatch[source]
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_rejects_identity_or_matrix_mismatch[tag]
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_rejects_identity_or_matrix_mismatch[unknown-metadata]
packages/nanolab/tests/release/test_recipe.py::test_release_distribution_rejects_identity_or_matrix_mismatch[variant]
packages/nanolab/tests/release/test_recipe.py::test_release_profiles_cover_both_architectures
packages/nanolab/tests/release/test_recipe.py::test_release_profiles_cover_exact_guarded_matrix
packages/nanolab/tests/release/test_recipe_execution.py::test_acquired_input_digest_mismatch_prevents_assembly[amd64-archive]
packages/nanolab/tests/release/test_recipe_execution.py::test_acquired_input_digest_mismatch_prevents_assembly[amd64-config]
packages/nanolab/tests/release/test_recipe_execution.py::test_acquired_input_digest_mismatch_prevents_assembly[arm64-archive]
packages/nanolab/tests/release/test_recipe_execution.py::test_acquired_input_digest_mismatch_prevents_assembly[arm64-config]
packages/nanolab/tests/release/test_recipe_execution.py::test_archive_inventory_rejects_source_mutation[amd64-bytes]
packages/nanolab/tests/release/test_recipe_execution.py::test_archive_inventory_rejects_source_mutation[amd64-delete]
packages/nanolab/tests/release/test_recipe_execution.py::test_archive_inventory_rejects_source_mutation[amd64-escape]
packages/nanolab/tests/release/test_recipe_execution.py::test_archive_inventory_rejects_source_mutation[amd64-ignored]
packages/nanolab/tests/release/test_recipe_execution.py::test_archive_inventory_rejects_source_mutation[amd64-link]
packages/nanolab/tests/release/test_recipe_execution.py::test_archive_inventory_rejects_source_mutation[amd64-mode]
packages/nanolab/tests/release/test_recipe_execution.py::test_archive_inventory_rejects_source_mutation[amd64-non-project-build]
packages/nanolab/tests/release/test_recipe_execution.py::test_archive_inventory_rejects_source_mutation[amd64-output-link]
packages/nanolab/tests/release/test_recipe_execution.py::test_archive_inventory_rejects_source_mutation[amd64-tracked-output]
packages/nanolab/tests/release/test_recipe_execution.py::test_archive_inventory_rejects_source_mutation[arm64-bytes]
packages/nanolab/tests/release/test_recipe_execution.py::test_archive_inventory_rejects_source_mutation[arm64-delete]
packages/nanolab/tests/release/test_recipe_execution.py::test_archive_inventory_rejects_source_mutation[arm64-escape]
packages/nanolab/tests/release/test_recipe_execution.py::test_archive_inventory_rejects_source_mutation[arm64-ignored]
packages/nanolab/tests/release/test_recipe_execution.py::test_archive_inventory_rejects_source_mutation[arm64-link]
packages/nanolab/tests/release/test_recipe_execution.py::test_archive_inventory_rejects_source_mutation[arm64-mode]
packages/nanolab/tests/release/test_recipe_execution.py::test_archive_inventory_rejects_source_mutation[arm64-non-project-build]
packages/nanolab/tests/release/test_recipe_execution.py::test_archive_inventory_rejects_source_mutation[arm64-output-link]
packages/nanolab/tests/release/test_recipe_execution.py::test_archive_inventory_rejects_source_mutation[arm64-tracked-output]
packages/nanolab/tests/release/test_recipe_execution.py::test_archive_staging_matches_planning_permissions_despite_vm_umask
packages/nanolab/tests/release/test_recipe_execution.py::test_arm_cancellation_after_first_group_retains_diagnostics
packages/nanolab/tests/release/test_recipe_execution.py::test_arm_cleanup_preserves_amd64_inputs_and_diagnostics
packages/nanolab/tests/release/test_recipe_execution.py::test_arm_evidence_invalidation_preserves_verified_amd64_phase[config-delete]
packages/nanolab/tests/release/test_recipe_execution.py::test_arm_evidence_invalidation_preserves_verified_amd64_phase[config-tamper]
packages/nanolab/tests/release/test_recipe_execution.py::test_arm_evidence_invalidation_preserves_verified_amd64_phase[image-id]
packages/nanolab/tests/release/test_recipe_execution.py::test_arm_evidence_invalidation_preserves_verified_amd64_phase[inventory-delete]
packages/nanolab/tests/release/test_recipe_execution.py::test_arm_evidence_invalidation_preserves_verified_amd64_phase[inventory-tamper]
packages/nanolab/tests/release/test_recipe_execution.py::test_arm_evidence_invalidation_preserves_verified_amd64_phase[profile-delete]
packages/nanolab/tests/release/test_recipe_execution.py::test_arm_evidence_invalidation_preserves_verified_amd64_phase[profile-tamper]
packages/nanolab/tests/release/test_recipe_execution.py::test_arm_evidence_invalidation_preserves_verified_amd64_phase[receipt]
packages/nanolab/tests/release/test_recipe_execution.py::test_arm_evidence_invalidation_preserves_verified_amd64_phase[report-delete]
packages/nanolab/tests/release/test_recipe_execution.py::test_arm_evidence_invalidation_preserves_verified_amd64_phase[report-tamper]
packages/nanolab/tests/release/test_recipe_execution.py::test_arm_failed_resume_invalidates_prior_receipts_and_preserves_amd64
packages/nanolab/tests/release/test_recipe_execution.py::test_arm_final_union_detects_earlier_tag_replacement
packages/nanolab/tests/release/test_recipe_execution.py::test_arm_native_platform_disagreement_prevents_evidence[options0]
packages/nanolab/tests/release/test_recipe_execution.py::test_arm_native_platform_disagreement_prevents_evidence[options1]
packages/nanolab/tests/release/test_recipe_execution.py::test_arm_native_platform_disagreement_prevents_evidence[options2]
packages/nanolab/tests/release/test_recipe_execution.py::test_arm_native_platform_disagreement_prevents_evidence[options3]
packages/nanolab/tests/release/test_recipe_execution.py::test_arm_native_platform_disagreement_prevents_evidence[options4]
packages/nanolab/tests/release/test_recipe_execution.py::test_arm_native_platform_disagreement_prevents_evidence[options5]
packages/nanolab/tests/release/test_recipe_execution.py::test_arm_native_platform_disagreement_prevents_evidence[options6]
packages/nanolab/tests/release/test_recipe_execution.py::test_arm_reacquisition_does_not_repair_retained_evidence[False]
packages/nanolab/tests/release/test_recipe_execution.py::test_arm_reacquisition_does_not_repair_retained_evidence[True]
packages/nanolab/tests/release/test_recipe_execution.py::test_arm_recipe_reports_match_independent_daemon[aarch64]
packages/nanolab/tests/release/test_recipe_execution.py::test_arm_recipe_reports_match_independent_daemon[arm64]
packages/nanolab/tests/release/test_recipe_execution.py::test_both_architecture_resume_performs_no_build_or_push
packages/nanolab/tests/release/test_recipe_execution.py::test_complete_reports_match_independent_daemon_inspection
packages/nanolab/tests/release/test_recipe_execution.py::test_daemon_disagreement_prevents_receipt[linux|amd64|sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff]
packages/nanolab/tests/release/test_recipe_execution.py::test_daemon_disagreement_prevents_receipt[linux|arm64|sha256:1111111111111111111111111111111111111111111111111111111111111111]
packages/nanolab/tests/release/test_recipe_execution.py::test_daemon_disagreement_prevents_receipt[windows|amd64|sha256:1111111111111111111111111111111111111111111111111111111111111111]
packages/nanolab/tests/release/test_recipe_execution.py::test_failed_group_cannot_reuse_stale_output[amd64]
packages/nanolab/tests/release/test_recipe_execution.py::test_failed_group_cannot_reuse_stale_output[arm64]
packages/nanolab/tests/release/test_recipe_execution.py::test_final_inspection_rejects_earlier_tag_replaced_by_later_group
packages/nanolab/tests/release/test_recipe_execution.py::test_inventory_allows_guarded_included_build_cache
packages/nanolab/tests/release/test_recipe_execution.py::test_inventory_allows_only_source_derived_gradle_outputs
packages/nanolab/tests/release/test_recipe_execution.py::test_inventory_rejects_build_outputs_from_undeclared_gradle_project
packages/nanolab/tests/release/test_recipe_execution.py::test_legacy_arm_journal_requires_recipe_assembly_and_new_push
packages/nanolab/tests/release/test_recipe_execution.py::test_partial_recipe_failure_compensates_owned_inputs[amd64-cancel]
packages/nanolab/tests/release/test_recipe_execution.py::test_partial_recipe_failure_compensates_owned_inputs[amd64-cleanup]
packages/nanolab/tests/release/test_recipe_execution.py::test_partial_recipe_failure_compensates_owned_inputs[amd64-transfer]
packages/nanolab/tests/release/test_recipe_execution.py::test_partial_recipe_failure_compensates_owned_inputs[arm64-cancel]
packages/nanolab/tests/release/test_recipe_execution.py::test_partial_recipe_failure_compensates_owned_inputs[arm64-cleanup]
packages/nanolab/tests/release/test_recipe_execution.py::test_partial_recipe_failure_compensates_owned_inputs[arm64-transfer]
packages/nanolab/tests/release/test_recipe_execution.py::test_partial_recipe_outcome_removes_prior_complete_receipt
packages/nanolab/tests/release/test_recipe_execution.py::test_recipe_commands_bind_architecture_role_and_paths[amd64-stack]
packages/nanolab/tests/release/test_recipe_execution.py::test_recipe_commands_bind_architecture_role_and_paths[arm64-arm-builder]
packages/nanolab/tests/release/test_recipe_execution.py::test_recipe_execution_retains_input_copies_only_when_build_runs
packages/nanolab/tests/release/test_recipe_execution.py::test_recipe_input_release_preserves_local_evidence
packages/nanolab/tests/release/test_recipe_execution.py::test_recipe_log_does_not_preclaim_producer_output[amd64]
packages/nanolab/tests/release/test_recipe_execution.py::test_recipe_log_does_not_preclaim_producer_output[arm64]
packages/nanolab/tests/release/test_recipe_execution.py::test_recipe_phase_fingerprint_binds_all_inputs[archive]
packages/nanolab/tests/release/test_recipe_execution.py::test_recipe_phase_fingerprint_binds_all_inputs[builder]
packages/nanolab/tests/release/test_recipe_execution.py::test_recipe_phase_fingerprint_binds_all_inputs[cell-options]
packages/nanolab/tests/release/test_recipe_execution.py::test_recipe_phase_fingerprint_binds_all_inputs[configuration]
packages/nanolab/tests/release/test_recipe_execution.py::test_recipe_phase_fingerprint_binds_all_inputs[cwd]
packages/nanolab/tests/release/test_recipe_execution.py::test_recipe_phase_fingerprint_binds_all_inputs[driver]
packages/nanolab/tests/release/test_recipe_execution.py::test_recipe_phase_fingerprint_binds_all_inputs[env]
packages/nanolab/tests/release/test_recipe_execution.py::test_recipe_phase_fingerprint_binds_all_inputs[inventory]
packages/nanolab/tests/release/test_recipe_execution.py::test_recipe_phase_fingerprint_binds_all_inputs[parallelism]
packages/nanolab/tests/release/test_recipe_execution.py::test_recipe_phase_fingerprint_binds_all_inputs[profile]
packages/nanolab/tests/release/test_recipe_execution.py::test_recipe_phase_fingerprint_binds_all_inputs[tag]
packages/nanolab/tests/release/test_recipe_execution.py::test_recipe_resume_verifies_files_and_images_without_build[image-id]
packages/nanolab/tests/release/test_recipe_execution.py::test_recipe_resume_verifies_files_and_images_without_build[inventory]
packages/nanolab/tests/release/test_recipe_execution.py::test_recipe_resume_verifies_files_and_images_without_build[none]
packages/nanolab/tests/release/test_recipe_execution.py::test_recipe_resume_verifies_files_and_images_without_build[profile]
packages/nanolab/tests/release/test_recipe_execution.py::test_recipe_resume_verifies_files_and_images_without_build[receipt]
packages/nanolab/tests/release/test_recipe_execution.py::test_recipe_resume_verifies_files_and_images_without_build[report-delete]
packages/nanolab/tests/release/test_recipe_execution.py::test_recipe_resume_verifies_files_and_images_without_build[report-tamper]
packages/nanolab/tests/release/test_recipe_execution.py::test_recipe_wrong_architecture_or_role_fails_before_remote_work[amd64-arm-builder-False]
packages/nanolab/tests/release/test_recipe_execution.py::test_recipe_wrong_architecture_or_role_fails_before_remote_work[amd64-stack-True]
packages/nanolab/tests/release/test_recipe_execution.py::test_recipe_wrong_architecture_or_role_fails_before_remote_work[arm64-arm-builder-True]
packages/nanolab/tests/release/test_recipe_execution.py::test_recipe_wrong_architecture_or_role_fails_before_remote_work[arm64-loadgen-False]
packages/nanolab/tests/release/test_recipe_execution.py::test_recipe_wrong_architecture_or_role_fails_before_remote_work[arm64-stack-False]
packages/nanolab/tests/release/test_recipe_execution.py::test_recipe_wrong_architecture_or_role_fails_before_remote_work[ppc64-stack-False]
packages/nanolab/tests/release/test_recipe_execution.py::test_release_recipe_commands_use_owned_builder_and_paths
packages/nanolab/tests/release/test_recipe_execution.py::test_stale_arm_smoke_cannot_publish
packages/nanolab/tests/release/test_recipe_execution.py::test_transfer_truncation_prevents_receipt[amd64]
packages/nanolab/tests/release/test_recipe_execution.py::test_transfer_truncation_prevents_receipt[arm64]
packages/nanolab/tests/release/test_tasks.py::test_arm_assembly_and_push_have_distinct_receipts
packages/nanolab/tests/release/test_tasks.py::test_changed_local_image_blocks_push_before_first_command[amd64]
packages/nanolab/tests/release/test_tasks.py::test_changed_local_image_blocks_push_before_first_command[id]
packages/nanolab/tests/release/test_tasks.py::test_changed_local_image_blocks_push_before_first_command[windows]
packages/nanolab/tests/release/test_tasks.py::test_old_combined_arm_receipt_is_not_local_assembly_proof
packages/nanolab/tests/test_javascript_example_packaging.py::test_javascript_example_images_copy_local_sdk_dependency_target
```
