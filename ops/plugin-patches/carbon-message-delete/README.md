# CarbonMessageDelete unsigned-message guard

The installed plugin treats a non-null `SignedMessage` as deletable even when its
`signature()` is null. Its callback then passes that null signature to every
Carbon player, causing the reported exception.

The narrow bytecode patch makes the renderer use the existing
`invalid_button_format` for an absent message or absent signature. It checks the
message/signature again when executing the callback and ignores null signatures
passed directly to `deleteMessage`. Signed messages retain the existing active
button and deletion path. Permission checks, configuration, metadata and all other
JAR members remain unchanged.

The patch tool checks the exact original SHA-256, refuses output overwrite, checks
its three method matches, and verifies all other archive member bytes. It uses
staging's ASM 9.9.1 dependency without adding runtime dependencies to the plugin.

```sh
python3 ops/plugin-patches/carbon-message-delete/patch.py ORIGINAL.jar PATCHED.jar SERVER/libraries .context/carbon-message-delete/build
python3 ops/plugin-patches/carbon-message-delete/verify.py SERVER ORIGINAL.jar PATCHED.jar .context/carbon-message-delete/probe
```

Input SHA-256: `a5c6b5cbcd08f5bfa4c29c93cce4e74319fd5acf73a53024c8b3a51453be4c7a`

Patched SHA-256: `9caa0f0c79789df7d68648fc933701697d830bc6565c272b4a48a037ff79dad3`

## Local verification

The probe invokes the actual original/patched class with Carbon's API and Adventure
5.2, using tiny Bukkit configuration/lifecycle shims and proxy players. The original
callback reproduces `NullPointerException: signature` at the receiving player. The
patched class passes `-Xverify:all` and assertions for:

- Absent and unsigned messages render `invalid_button_format`.
- Callbacks for absent and unsigned messages perform no deletion.
- Signed messages retain `button_format`; the callback sends the exact signature
  to the player.
- Direct deletion with a null signature performs no deletion.

The standalone probe tolerates unavailable Paper Adventure service providers (the
Paper server is not running inside this process). This affects test execution only,
not the patched JAR. It does not establish successful clickable callback dispatch,
client-side message removal, actual configuration formatting, or live plugin startup;
those require staging verification. The verifier uses the locally installed JDK 25.

## Deployment boundary

This preparation does not deploy or restart any server. Back up the existing JAR
outside the plugins directory, stop staging, replace the JAR, restart staging and
check startup. In-game verification should send an unsigned chat message and a
signed chat message to a moderator, confirm the unavailable/active button states,
and click the signed-message delete button. Pause before any production change.

### Live probe correction

The initial live verifier expected a leading space in the root component. Raw
constant-pool inspection confirms the plugin's root string is empty (UTF8 constant
#207 has length zero). The verifier now compares against that exact empty root;
the patched plugin JAR did not change. The offline regression now also reads the
actual staging `plugins/CarbonMessageDelete/config.yml` button formats, parses
those with real MiniMessage, and compares the complete unavailable-button output.
Baseline still reproduces the null-signature failure and patched checks pass.
