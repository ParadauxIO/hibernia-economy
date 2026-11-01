# PAR-526 compatibility patches

Source recovery for [PAR-526](https://app.taskl.ie/w/paradaux/issues/PAR-526),
originally prepared and queued for production on 2026-10-24. This directory
preserves the patch tooling, source diffs, regression probes, and artifact hashes
so development can continue without the original working directory.

| Plugin | Rebuild and verification | Upstream source |
| --- | --- | --- |
| MobsToEggs 1.7.22 | [Adventure builder ABI patch](mobstoeggs/README.md) | Vendor does not publish source; requires the exact original licensed JAR |
| CarbonMessageDelete | [Unsigned message guards](carbon-message-delete/README.md) | [Source fix and tests, open PR](https://github.com/Draycia/CarbonMessageDelete/pull/2) |
| MusicBox 3.0.0 | [Private Adventure NBT packaging](musicbox/README.md) | [Merged source fix and tests](https://github.com/MCCitiesNetwork/MusicBox/pull/2) |
| Families 1.0.3 | [Background identifier validation](families/README.md) | No public source target; requires the exact original JAR |
| BountyHunters | [Navigation inventory context](bountyhunters/README.md) | [Source diff and four JUnit cases](bountyhunters/upstream-source.patch), based on public upstream `41a82207` |

## Build prerequisites

Use JDK 25 (`JAVA_HOME` and its `bin` directory on `PATH`), Python 3, and Bash.
The binary patch tools use the original October 24 JARs and the matching server
library tree (ASM 9.9.1, Paper API 26.3-206, Adventure 5.2). BountyHunters' binary
probe also uses the versions of Mockito, Byte Buddy and Objenesis listed in
`run.py` from the local Gradle dependency cache. Source builds need their own
Gradle dependencies cached for offline verification.

Use a disposable server-shaped directory containing `libraries/` and `plugins/`,
with the **original** JARs under their original filenames. The installed staging
plugins are already patched and will fail the input-hash guards. Obtain originals
from your authorized rollback backup; original or patched vendor JARs are not
included here. Supporting CarbonChat and PacketEvents plugins are also needed by
the relevant probes. Carbon's probe reads the two quoted button formats from
`plugins/CarbonMessageDelete/config.yml` in the supplied server directory.

Follow each plugin's README. MusicBox's standalone packaging regression can be run
after applying its source diff and building:

```sh
python3 ops/plugin-patches/musicbox/verify.py MUSICBOX_SOURCE ORIGINAL.jar PATCHED.jar .context/musicbox-probe
```

The [staging verifier](staging-verifier/README.md) can be compiled without starting
a server. Installing it executes live probes; this recovery does not install it,
deploy any plugin, or restart a server.

## Recovery verification — 2026-11-01

The original working directory had been removed. Patch sources were recovered
from archived tool inputs, with the missing source diffs reconstructed against
their recorded upstream revisions. Incident player identifiers in tests and
notes were replaced with synthetic values. JDK selection now uses `JAVA_HOME`.

- MobsToEggs and CarbonMessageDelete rebuild to their recorded exact SHA-256.
- Families and BountyHunters rebuild with identical uncompressed archive members
  to the installed October 24 staging artifacts. ZIP metadata can change the full
  archive hash; the release hashes identify the historical artifacts, not every rebuild.
- Original failure and patched success probes pass for all four binary patches.
- MusicBox's original source baseline plus the recovered packaging diff builds
  successfully and passes 23 source tests. Its isolated packaged-JAR probe
  reproduces the original missing NBT failure and passes with the rebuilt JAR.
- The reconstructed BountyHunters source diff passes all four JUnit cases.
- The temporary combined live verifier compiles. It was not installed or run live
  during recovery; [October 24 live results](staging-verifier/verification-2026-10-24.md)
  are historical evidence.

The [release manifest](release-manifest.json) records the original/patched artifact
hashes. The four installed staging JARs still match those historical patched
hashes. Production upload was recorded as queued in `plugins/update`; production
restart and runtime activation remain unverified. A Done ticket is not proof of
activation. Preserve rollback originals outside `plugins/`.
