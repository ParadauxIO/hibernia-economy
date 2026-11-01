# Upstream contributions — 2026-10-24

Tracked in [PAR-526](https://app.taskl.ie/w/paradaux/issues/PAR-526).

| Plugin | Contribution | Validation |
| --- | --- | --- |
| CarbonMessageDelete | [Draycia/CarbonMessageDelete#2](https://github.com/Draycia/CarbonMessageDelete/pull/2), targeting `main`; commit `074e6ff` | Java 21 Gradle build and 4 regression tests pass |
| MusicBox | [MCCitiesNetwork/MusicBox#2](https://github.com/MCCitiesNetwork/MusicBox/pull/2), targeting `feature/paper-26.3-modernization`; commit `1dcadc3` | Java 25 build and all 24 tests pass; packaged-JAR regression fails on original packaging and passes after fix |
| BountyHunters | Submission skipped at the user's explicit request because the current upstream is on GitLab and no account is available | Common-module build and 4 regression cases pass; original factories fail all 4 |

MusicBox's original Spliterash `master` branch does not contain the Adventure NBT
reader, so the fix targets the public branch that introduced the affected code.
Families has no public source target. MobsToEggs' public repository explicitly
contains only wiki/issue-reporting material and downloads, not plugin source.

The BountyHunters source diff against public upstream commit `41a82207` is retained
as [upstream-source.patch](bountyhunters/upstream-source.patch). It includes the
four factory fixes and reproducible JUnit regressions; apply with `git apply` and
run `./gradlew :BountyHunters-Common:test`.

PRs include only source changes, synthetic tests and sanitized failure details.
Private logs, player names, infrastructure and deployment records were excluded.
Their exact heads, target branches and file lists were verified after publication.
As checked on 2026-11-01, MusicBox #2 is merged and CarbonMessageDelete #2 remains open. Production restart/runtime verification is separate.
