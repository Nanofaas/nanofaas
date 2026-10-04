# SpotBugs baseline

`exclude.xml` documents narrowly scoped false positives. `baseline.xml` freezes
228 unique findings from NanoFaaS `ef856960` with SpotBugs 4.10.4. It is debt, not a
claim that these findings are safe. Exact instance hashes ensure a new finding
is not admitted merely because its bug type or package already exists.

Follow-up: issue #238. Review nullability and concurrency findings first, then
exposed representation and constructor warnings. For each removal, reproduce
the finding, fix or explain it, and run `releaseChecks` before removing its hash.
Do not regenerate the baseline on each build. If a finding changes, review its
new report; do not refresh hashes merely to obtain a green gate.

The baseline retains class/method/field identifiers for review. The full-composition inventory includes two containerd findings from unchanged
source after the pinned dependency bootstrap.

The live `MeterRegistry`, `P2pSettings` and `PeerTable` collaborators introduced by node-information exchange are intentionally shared. Their constructor-field `EI_EXPOSE_REP2` matches are narrowly excluded in `exclude.xml`; this does not baseline or suppress synchronization findings. Polling acquires its messaging transport under the instance monitor and retains it for deferred subscriptions in that round.
