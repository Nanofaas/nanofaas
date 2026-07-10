# Runtime Config PATCH Serialization Design

Runtime configuration updates currently protect the snapshot swap with compare-and-set, but the complete operation also validates the candidate, applies side effects, and may restore the previous snapshot. Those steps can interleave across requests, allowing an older failed update to overwrite a newer successful update or leave mutable components inconsistent with the published snapshot.

The control plane runs as a single pod and administrative configuration changes are rare. The PATCH endpoint will therefore serialize its entire operation on the singleton controller instance. Parsing, validation, snapshot update, side-effect application, and rollback remain unchanged and execute inside one critical section. Reads and validation-only requests remain concurrent.

If two PATCH requests use the same expected revision, the first completes before the second checks the revision. A successful first request makes the second return `409 Conflict`; a failed first request restores the original revision, allowing the second request to proceed. This preserves the existing optimistic-locking API while making side effects and rollback deterministic.

The implementation adds no lock type, coordinator, queue, or dependency. A concurrency regression test blocks the first apply operation, starts a second PATCH, verifies it cannot complete early, then releases the first operation and checks that rollback followed by the second update produces one consistent snapshot and rate-limiter value.
