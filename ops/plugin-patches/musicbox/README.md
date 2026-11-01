# MusicBox Adventure NBT packaging repair

MusicBox 3.0.0-SNAPSHOT declares Adventure NBT `compileOnly`, so its historical music-disc reader calls BinaryTagIO without bundling it. Its tests explicitly supply NBT and therefore did not expose the runtime packaging failure.

Apply `packaging.patch` to MusicBox source revision `78e237052d3e571f766668acf611747ae96f7faf`, then run `JAVA_HOME=<JDK25> ./gradlew test shadowJar`. The patch packages NBT and relocates its package under MusicBox's private namespace. The handler logic and legacy fallback are unchanged. Source was built in `.context/musicbox/source`; `<musicbox-source>` was not modified.

Local evidence (2026-10-24):

- Full source build: 23 tests, zero failures/errors.
- `PackagingProbe.java` loads the actual JAR through a classloader which denies external `net.kyori.adventure.nbt` visibility. The installed baseline fails at BukkitNbtHandler.java:32 with `NoClassDefFoundError: net/kyori/adventure/nbt/BinaryTagIO`.
- The patched JAR passes that same probe: PDC precedence, migrated custom_data, historical tag, empty data, malformed data, null item, writing on a clone without mutating the original.
- Of 668 existing MusicBox/private classes, only BukkitNbtHandler.class changes (its NBT references are relocated). NBT classes are added.
- `LiveMusicBoxProbe.java` is a helper for a temporary staging verification plugin. Run `run(actualMusicBoxPlugin)` on the server thread. It constructs detached real ItemStacks and tests the active plugin's classloader, PDC and migrated legacy custom_data paths without changing world or player data. It does not verify audible playback or a player jukebox interaction.

Artifact: `.context/musicbox/artifacts/MusicBox-3.0.0-SNAPSHOT.jar`

SHA-256:

- Original: `886a1d2c9d897799b63a3cae65a2da2902932ed89bdbfe2fb32e8d38bba55b23`
- Patched: `6929c059fb45117b9dce9e42620b9329d28a05c10ddaaa0fc3d6c1145aef14f2`

Historical build and probe logs were under `.context/musicbox/` and are not included in this recovery. No deployment is performed by these scripts. Preserve the original deployed JAR for rollback and stop after staging verification pending production authorization.
