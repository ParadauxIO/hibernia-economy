---
title: TreasuryAPI
order: 5
description: In-game API-key, website-linking, password-recovery and Explorer access commands.
---

# TreasuryAPI

The **TreasuryAPI plugin** issues credentials for the REST API and connects your
Minecraft identity to the website. It is separate from the Treasury Java API
library used by plugins. The base command is `/treasuryapi`; use
`/treasuryapi help [page]` for permission-filtered help.

## Personal and business keys

| Command | Purpose |
|---|---|
| `/treasuryapi personal issue` | Issue a token for your personal account. |
| `/treasuryapi personal list` | List your personal key IDs and states. |
| `/treasuryapi personal reissue <keyId>` | Replace the token for your key; the old token stops working. |
| `/treasuryapi personal revoke <keyId>` | Permanently revoke your key. |
| `/treasuryapi business issue <firmName>` | Proprietor: issue a token scoped to the whole firm, including its live linked accounts. |
| `/treasuryapi business list` | List business keys you issued. |
| `/treasuryapi business list access` | List keys for firms you are employed at; listing does not reveal tokens or grant management rights. |
| `/treasuryapi business reissue <keyId>` | Reissue a business token after ownership/management checks. |
| `/treasuryapi business revoke <keyId>` | Revoke a business key after ownership/management checks. |

These commands require a player sender. Tokens are shown once, valid for 180 days,
and never stored for retrieval. Personal/account and business/firm scope still
apply even if the issuer has website staff permissions. Follow
[Using the economy API](/docs/guides/api) for requests and safe retries.

## Website identity

| Command | Purpose |
|---|---|
| `/treasuryapi ui link <code>` | Link your Minecraft UUID to the website identity that generated the five-minute code. |
| `/treasuryapi ui resetpassword` | Show instructions for resetting your already-linked website password. |
| `/treasuryapi ui resetpassword confirm` | Replace it with a temporary password, then change it at web sign-in. |
| `/treasuryapi ui sync` | Reconcile your LP-derived web access immediately. |

These are player-only and operate on the authenticated sender, not a supplied
username. [Password recovery](/docs/guides/using-the-explorer#recover-your-website-password)
requires server configuration and a unique existing link; it needs no email.

## Staff access recovery

| Command | Purpose |
|---|---|
| `/treasuryapi ui user add <role> <player>` | Assign an existing configured Explorer role. |
| `/treasuryapi ui user remove <role> <player>` | Remove the direct assignment; other grant paths may remain. |
| `/treasuryapi ui user list <player>` | Inspect the player's Explorer roles. |

Trusted console can run these. `treasuryapi.ui.user` is a privileged in-game
recovery authority; a website role cannot replace this node. Stored permissions
remain authoritative even for a role named `admin`.

## Permission nodes

Every command needs `treasuryapi.command` (default `true`). Each personal/business
operation also needs `treasuryapi.personal.<action>` or
`treasuryapi.business.<action>` (`issue`, `list`, `reissue`, `revoke`), all default
`true`; business `list access` uses `.list`. Resource/ownership checks still apply.

`treasuryapi.ui.link`, `treasuryapi.ui.sync` and `treasuryapi.ui.resetpassword`
default to `true`; `treasuryapi.ui.user` defaults to `op`. Explicit denials apply.
There is no player SERVICE-key issuance or TreasuryAPI reload command.
