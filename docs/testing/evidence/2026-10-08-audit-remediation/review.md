# Independent whole-branch review

Reviewed range: `fed1be9c..5572e55a`. Read-only review covered all changed paths, the plan, ledger decisions and validation evidence. Root implemented the three required fixes in one pass after receiving the complete report. No second review was requested.

The reviewer classified three findings as Important, none as Critical or Minor, and declined to judge none. Root accepted all three as required corrections, including the two pre-existing defects in paths whose guarantees this intervention covers. No findings are deferred.

1. Scheduled-to-scheduled configuration before the first automatic claim could bypass the manual epoch fence. The HTTP regression now performs both updates before any tick, expects 409 and unchanged revision, then verifies preparation on the accepted grid.
2. The real CLI adapter hid failed removal and discovery outcomes. Provider regressions use that adapter with scripted commands: r3 is removed, r2 removal fails, state and proxy retain r2, and retry removes it. Failed discovery keeps deprovision pending. Unsuccessful removal is idempotent only after successful exact-name absence confirmation.
3. A tick blocked in input pinning could resume after stop or stop/start because its subscription was not published. Barrier tests now cover both lifecycle cases, assert no coordinator/actuator work from the old claim, and verify eventual unpin and busy release. An additional case verifies cancellation of an already subscribed preparation.

The two original scheduling regressions and three CLI regressions failed against the previous behavior, then passed with their affected suites. The first HTTP regression required pausing the real periodic timer to avoid an unrelated automatic claim masking the defect; the deterministic pre-fix run demonstrated 200 instead of 409. No final regression uses extracted classpaths or files under `/tmp`.

The reviewer accepted every recorded implementation decision, including the 6 GiB native retry, bounded callback cleanup grace, and final SDK test dependency isolation. [Decisions and their costs](decisions.md) preserve the complete ledger in chronological order. [Integrated verification](README.md) records the final gates after these fixes.
