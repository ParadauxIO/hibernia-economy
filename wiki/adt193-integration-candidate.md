# ADT-193 combined integration candidate

This isolated candidate combines the following exact reviewed sources from
`develop` base `4339da00a441144ba6bdf1e0d72fa95d04745266`, preserving their commit
histories through explicit merge commits:

| Work | Exact source head |
| --- | --- |
| PR79 transaction formatting | b965e0c19f97beef0246427055ec4875f5c6455b |
| PR80 Minecraft26.3/Java25 and dependency updates | 8847227eb2dd94e858318f14bc769158ff46af58 |
| PR83 stable SYSTEM wallet routing | b5c336a9a269c2146fad1c18aaa521e57b74041b |

An integration-only REST/MariaDB regression verifies SYSTEM label changes,
ID-based transfers, archive/unarchive, and public namespace rejection preserve
the original routing key, account ID, owner and funds. REST has no separate
plugin-by-label lookup: Treasury owns that route, and its normal/locking reads
use the stable key. The complete REST artifact must still be from the compatible
combined commit; do not treat this as permission to keep old replicas running.

## Guarded controller maintenance

The operations-owned proposal adds `schema-guard.mjs` beside `agent.mjs`. A
persistent `schema-compatibility.json` approval file pins the exact four plugin
files, SHA256 hashes, provider names and combined source commit. Arming saves
`state.schemaGuardRequired=true` before writing the marker, so a lost/malformed
marker after arming cannot silently disable the guard. Every start verifies the
complete installed set, including duplicate providers. Unknown rolling releases
are held before stopping the current server; rollback only selects an explicitly
approved complete bundle. A boot failure must not restore old64282acd JARs against
renamed SYSTEM data.

Only the maintenance owner may operate the live controller. The following is the
required command ordering, not staging execution or clearance:

1. Stop/pause the controller and every database writer; include both REST replicas,
   Explorer and jobs. Pin compatible immutable artifacts/images and explicitly
   coordinate Argo self-heal so old replicas cannot return during maintenance.
   Retain consistent database/application backups and original wallet-ID/key maps.
2. Review/copy both controller modules together while stopped. Review an approval
   JSON with `version:1`, `schema:"system-plugin-key-v42"`, and `bundles:[...]`.
   Each bundle has its exact 40-character `sha`, four `jars`, a `hashes` map of
   filename to SHA256, and `providers` mapping Business/ChestShop/Treasury/TreasuryAPI
   to those filenames. Do not approve an old display-keyed build.
3. With the controller paused and server stopped, `ctl maintenance-arm <approvalfile>`.
   Stage the reviewed complete bundle under `dev-deploy/history/<sha>/` and run
   `ctl maintenance-install <sha>`. It records staged intent before copying; an
   interrupted copy/restart cannot auto-start. It stays paused and does not migrate.
4. Follow [SYSTEM preparation](system-wallet-routing.md): capture stable keys
   BEFORE approved SYSTEM label changes and beforeV40; review known failed history
   and migrate normally throughV42. This step belongs to operations and must be
   rehearsed against the actual dataset first. No script guesses collision policy.
5. `ctl maintenance-boot <sha>` starts only approved complete hashes while remaining
   paused. Failure stops the candidate and leaves it installed, with no old-JAR
   rollback. Success records boot smoke only; polling/crash restart remain paused.
6. After boot and agreed acceptance, `ctl maintenance-adopt <sha>` records the
   running boot-verified bundle but remains paused. Explicit `ctl resume` rejects
   unadopted staged data and only permits the approved deployed/installed set.
   Resume compatible external writers only under their own verified artifact/image
   gates. An approved plugin set does not establish REST/Explorer compatibility.

The guard does not roll back schemas, guard external processes, publish images,
or prove dataset/in-game acceptance. A database-consistent rollback after label
changes must restore matching data and all writer versions or follow a separately
reviewed reverse plan. Never use an old-controller/JAR-only fallback after renames.

## Verification and CI

Java25 root `build release -Pci=true --max-workers=2` checks every JVM module and
builds the four-plugin bundle plus REST bootJar. `node --test
ops/dev-server/schema-guard.test.mjs` checks fail-closed marker/hash/provider
validation and paused install/restart/boot/adopt/resume, including failed manual
boot with no rollback. The always-run release metadata CI job includes these
controller fixtures. Actual automatic deployment failure and external orchestration
still require coordinated operational rehearsal.

A draft candidate PR triggers normal PR CI; pushing the feature branch alone does
not publish `dev-latest` or development images. Those workflows target
`develop`/`main`. Local image tooling may be unavailable; a hashed REST bootJar and
Dockerfile are not an image digest. Never move a live development image tag just
to obtain a build artifact. Explorer is also a writer (auth/audit paths) and needs
a compatible reviewed image or must remain stopped during this maintenance.

The bounded isolated live focus is not the full live suite. Upstream's interrupted
complete run captured321passes/12failures before shutdown; parser defects were
repaired and timing causes remain unproven. Source/JVM/focused passes do not imply
staging parity, production parity or full-suite acceptance. Exact final candidate,
artifact hashes, commands and actual verification outcomes belong in the handoff
manifest/report rather than being inferred from mutable tags.
