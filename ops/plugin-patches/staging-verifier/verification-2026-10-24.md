# Staging patch verification — 2026-10-24

## Subsequent production promotion

After the staging pause below, the user authorized production promotion and
creation of [PAR-526](https://app.taskl.ie/w/paradaux/issues/PAR-526).
The four verified JARs were uploaded to production `plugins/update`, with
readback-verified originals retained under
`/plugin-patch-backups/four-plugin-compat-20261024-PAR-526/`.
Every final remote JAR was downloaded and matched its staging-tested SHA-256.
See [recovered release manifest](../release-manifest.json).
Activation remains pending the next production restart; no production restart
or runtime verification was performed. This is the historical rollout status; current production activation is unverified.

## Staging outcome before promotion

Four patched plugins are installed and running on staging. No production upload
or deployment was performed for these four changes. Promotion is paused pending
the user's next instruction; the staging server itself remains running.

| Plugin | Change | Verification |
| --- | --- | --- |
| CarbonMessageDelete | Guard absent signatures before rendering an active delete button or deleting | Original signature failure reproduced; patched regression checks pass; live signed/unsigned rendering and unsigned callbacks pass |
| MusicBox | Bundle and privately relocate Adventure NBT | 23 source tests pass; original missing-class failure reproduced; packaged modern/legacy/malformed item checks pass; live modern and legacy disc reads pass |
| Families | Validate background texture identifiers and fall back safely | Original fails 6 of 13 cases; patched passes all; actual PacketEvents key regression and config load checks pass; live 20 cases pass |
| BountyHunters | Give navigation placeholders their actual inventory context | Original null-context failure reproduced; patched four button factories and page bounds pass; live eight inventory renders and empty/single/multiple page navigation pass |

Final live verifier run passed all four at **18:27:07 UTC**. Staging is Paper
26.3-206; production uses UniverseSpigot, so this does not establish production
runtime behavior. Carbon's first live test had an incorrect expected leading
space; inspection confirmed the plugin uses an empty root. The exact comparison
was corrected and passed without changing the patched plugin JAR.

All four installed SHA-256 hashes were rechecked against
[staging-manifest.json](staging-manifest.json). Original JARs and deployment
metadata are backed up at:

```
<staging-server>/dev-deploy/manual/four-plugin-compat-20261024T181604Z
```

The temporary verifier disabled itself and its JAR was removed. Results remain
in staging `plugins/StagingCompatProbe/results.txt` and local
`.context/staging-verifier/results.txt`. Final controller status: server running,
not paused, not busy, no failed deployment.

Tests used detached items/inventories and actual enabled plugin instances without
changing player inventories, bounty records, family records, or configuration.
Signed-message deletion on a client, audible jukebox playback, family-tree texture
appearance, and complete player command/click journeys still require an in-game
check. Families preserves configured bare texture names and valid explicit paths;
unknown bare names now fall back to the configured default.
