---
title: Managing Explorer access
order: 2
description: Grant or revoke admin and government roles for the Explorer.
---

# Managing Explorer access

Who can see what on the Explorer is controlled by **roles**. The flat `admin` and
`government` roles are granted in-game with `/treasuryapi ui user`. The **viewer**
role is granted through an [access group](#the-viewer-role) instead — including
automatically from a LuckPerms rank.

## The roles

| Role | Grants |
|---|---|
| **player** | The default. Public pages, plus their own data once linked. |
| **viewer** | Read-only financial oversight: any player's or firm's balances, transactions, and ChestShop drilldowns (sales, volume, quantities). No admin console, no ability to change anything. |
| **government** | Adds the government views (department accounts, fines) and the trade-by-trade ChestShop sales feed. Includes everything **viewer** sees. |
| **admin** | Full access — audit log, API-key management, group management, and these admin docs. Includes everything below it. |

A player with no explicit grant is a **player**. Roles are tied to the player, so they
apply wherever that player is linked.

## Granting and revoking

```text
/treasuryapi ui user add <role> <player>
/treasuryapi ui user remove <role> <player>
/treasuryapi ui user list <player>
```

| Argument | What it is |
|---|---|
| `role` | `admin` or `government` |
| `player` | The player to grant or revoke |

For example, make someone a government viewer, then check what they hold:

```text
/treasuryapi ui user add government Steve
/treasuryapi ui user list Steve
```

> [!NOTE]
> A role only takes effect for a player who has **linked** their account (so the
> Explorer can match the website login to the in-game player). Linking is covered in
> [Using the Explorer](/docs/guides/using-the-explorer).

> [!CAUTION]
> `admin` is full access, including the audit log and API-key controls. Grant it
> sparingly and review it with `/treasuryapi ui user list` when in doubt.

## The viewer role

**Viewer** is a read-only financial-oversight role — the right grant for staff who
need to *see* any player's or firm's money (balances, transactions, ChestShop sales
and volume, the cross-firm figures) but must not be able to change anything and don't
need the admin console.

Unlike `admin`/`government`, the viewer role isn't granted with `/treasuryapi ui user`.
It's granted by **access-group membership**, on the **Groups** admin screen
(`/admin/groups`):

1. Create a group (e.g. *Viewers*) and tick the **Viewer** capability.
2. Add people to it, one of two ways:
   - **Manually** — add a player to the group on its page; or
   - **From a LuckPerms rank/permission** — set the group's **LuckPerms source** to a
     rank (e.g. `group.moderator`) or a permission node. The reconciliation cron then
     keeps the group's membership in sync with who holds that rank in-game. Manual
     members are never touched by the cron, so the two can be mixed. A player can run
     `/treasuryapi ui sync` in-game to pull their current LuckPerms ranks into their
     Explorer groups immediately, instead of waiting for the next cron run.

A capability is the *union* across every group a player belongs to, so a player in any
group that grants **Viewer** has the viewer role.

> [!NOTE]
> As with every role, viewer only takes effect once the player has **linked** their
> Minecraft account to their Explorer login. The cron syncs in-game ranks to the group;
> linking is what lets the Explorer match the website login to that in-game player.
