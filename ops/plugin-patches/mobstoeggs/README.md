# MobsToEggs 1.7.22 / Adventure 5 compatibility

The plugin's `TextFormatManager.parseFormat` calls `LegacyComponentSerializer.toBuilder`
with the erased Adventure 4 return descriptor `Buildable$Builder`. Adventure 5.2
exposes the concrete `LegacyComponentSerializer$Builder` descriptor instead.
This causes `NoSuchMethodError` before either legacy or MiniMessage text is parsed.

`patch.py` changes that one constant-pool descriptor in the known input binary.
It checks the source SHA-256, refuses to overwrite output, and verifies every
other JAR member is unchanged. Plugin metadata and licensing are preserved.
This directory contains patch tooling, not the upstream plugin or its source.

```sh
python3 ops/plugin-patches/mobstoeggs/patch.py ORIGINAL.jar PATCHED.jar
python3 ops/plugin-patches/mobstoeggs/verify.py SERVER/libraries ORIGINAL.jar PATCHED.jar .context/mobstoeggs/probe
```

The probe must reproduce the original linkage failure, then pass actual formatter
calls for plain text, permission-denial text, two legacy hex formats, and MiniMessage.
It does not simulate a player capturing a mob or verify unrelated plugin features.

Input SHA-256: `a4135b441af8c4b571d9b4dcfa5598c95a3fb73652d15151713324ac59fb7295`

Patched SHA-256: `59b9e4838e3982fac68b873c05cd92fa2b38c92de7bb026f1c27d3438db98266`

Deploy by stopping staging through its deploy agent, backing up the original outside
the plugins directory, replacing the JAR, and resuming the agent. Confirm plugin
enablement and server readiness. Roll back with the same stop/replace/resume sequence
using the backup. Do not use a plugin hot reload.

## Staging verification, 2026-10-24 UTC

Installed in `<staging-server>/plugins/MobsToEggs - 1.7.22.jar`.
The original is retained under
`<staging-server>/dev-deploy/manual/mobstoeggs-adventure5-20261024T175218Z/`.
Staging runs Paper 26.3-206; the reported production exception was on UniverseSpigot 26.3.

The baseline probe reproduced the exact production error and the patched probe
passed all five cases. MobsToEggs enabled at 17:55:37 and the server reported ready
at 17:55:58. At 17:56:09, the console command
`mobstoeggs:mte give 1 __mte_compat_probe_no_player__` successfully rendered the
player-not-found message through `parseFormat`, without giving items or raising a
linkage error. The deploy agent reported running, unpaused, and idle afterward.
An actual player capture interaction remains untested.

## Production upload, 2026-10-24 UTC

The production original matched the input hash above. Uploaded the verified patch
to `/plugins/update/MobsToEggs - 1.7.22.jar`, matching `bukkit.yml`'s configured
`update-folder: update`. Downloaded the final remote file and confirmed its SHA-256
matches the staging-tested patch. This queues activation for the next server
restart; no production restart or live activation was performed.

Production rollback copy:
`/plugin-patch-backups/mobstoeggs-20261024/MobsToEggs-1.7.22.original.jar`.
