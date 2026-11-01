# Families background guard

Patch input: installed Families 1.0.3-SNAPSHOT,
SHA-256 `af934df430cba1ce04abbc881b70dc759fd12ec630a34379b6c8e296dfb25c3b`.
The patcher refuses other inputs. No source repository was found in the scoped local workspace search; the change uses the installed classes and ASM.

`/family background` saves its arbitrary word argument without validating it; the configured `backgrounds` list supplies suggestions only. `TreeRendering.backgroundId` then prefixes every unknown bare string with `block/`. Thus stored `ExamplePlayer` and `OtherPlayer_` become invalid resource keys, while simply lowercasing them creates nonexistent block textures. Constructor/field inspection found no name/background swap in the current player and root-advancement wiring. The historical route by which these particular stored values entered the database has not been independently established.

The patch replaces only `TreeRendering.backgroundId`, and inserts a configuration hook at successful `FamiliesConfig.load` completion. The helper captures `backgrounds` and `default-background` atomically, including reloads. Invalid values render the configured default (or `block/moss_block` if the default has malformed syntax). Persisted data is not rewritten. The command still accepts arbitrary words; this patch protects rendering of both existing and future invalid values.

Bare texture names now must appear in `backgrounds` or equal `default-background`; vanilla painted-tab aliases remain supported. This deliberately narrows the previous arbitrary-word behavior. Add an existing custom bare texture to `backgrounds`, or use its explicit `block/<texture>` path. Explicit valid paths and legacy `minecraft:textures/...png` syntax retain their previous meaning. Resource existence for custom explicit paths remains the administrator's responsibility. The renderer already fixes the namespace to `minecraft`; this patch does not add custom namespace support. An administrator can still configure a syntactically valid but missing texture.

Run from this repository:

```sh
bash ops/plugin-patches/families/build-and-test.sh
```

The script compiles the helper and patcher using local ASM 9.9.1, builds `.context/families/Families-1.0.3-SNAPSHOT.jar`, and tests original/patched JARs in separate JVMs with `-Xverify:all`. The original fails 6 of 13 cases, including both reported names and a lowercase username; the patched artifact passes all 13. Both reported original names reproduce actual PacketEvents `InvalidKeyException`; patched fallback keys succeed. A separate test runs real `FamiliesConfig.load` using disposable configuration and verifies its hook through rendering 3 inputs. ZIP comparison confirms exactly two existing classes changed and two helper classes added.

Current artifact SHA-256: `7dffe629f15ce8743b9c72140f258923a9b2055c7043af942761675d0a471cb8`.

`LiveFamiliesProbe.run(Plugin)` is intended for the temporary staging verification plugin. It reads the active configuration and calls the actual loaded renderer without changing configuration or player data. In-game family-tree display remains a separate visual verification, and offline checks alone do not establish that display is correct.
