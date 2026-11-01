# Temporary staging compatibility verifier

Build with:

```sh
python3 ops/plugin-patches/staging-verifier/build.py <staging-server> .context/staging-verifier
```

The resulting `StagingCompatProbe.jar` is a Paper plugin with explicit dependencies
on the four patched plugins. It runs once on the server thread after startup,
records per-plugin PASS/FAIL results under `plugins/StagingCompatProbe/results.txt`,
then disables itself. Remove its JAR after collecting results. It is test tooling
and must not be included in a production deployment.

Checks use actual enabled plugin instances/classloaders and server libraries:

- CarbonMessageDelete: configured signed/unsigned button rendering, actual callback
  registration, and safe unsigned callback execution. Never executes signed deletion
  against connected players.
- MusicBox: detached real Bukkit item conversion, modern PDC write/read and old
  migrated item data. Never plays sound or changes player inventories.
- Families: configured background choices, explicit paths, and fallback rendering
  for malformed/unknown persisted values. Never changes configuration or records.
- BountyHunters: detached real Bukkit inventories, four navigation-button renders,
  and forward/back pagination for empty, single-page and multi-page cases. Never
  opens a player's inventory or changes bounty records.

This establishes server-side behavior, not visual client verification. Remaining
manual checks are signed-message removal on a client, audible jukebox playback,
family-tree texture appearance, and complete player command/click journeys.
