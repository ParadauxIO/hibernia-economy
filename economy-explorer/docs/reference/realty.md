---
title: Realty
order: 4
description: Every /realty command — buying, renting, offers, auctions, and managing your plots.
---

# Realty

**Realty** handles land — buying, renting, auctioning, and managing plots. It's an
open-source plugin created by **md5sha256**, run on our servers and wired into the
economy: purchases and rent move real money through
[Treasury](/docs/reference/treasury), including property tax.

The commands below cover everything from finding a plot to managing one you own. The
base command is `/realty`.

> [!NOTE]
> Most commands act on the plot you're **standing in**. Where a command lists an
> optional `[region]`, you can name a specific plot instead of using your location.

## Looking around

| Command | What it does |
|---|---|
| `/realty info [region]` | Show a plot's type, owner, price, and members. |
| `/realty list [owned\|authority\|landlord\|rented] [name] [--page <n>]` | List your plots or the named party's; append a type flag for an account/group. The old `--player` flag is removed. |
| `/realty me [--page <n>]` | List all the plots you own or rent. |
| `/realty search` | Open the search dialog to browse available plots (filter by price, tags, type, occupancy). |
| `/realty history [region]` | Show a plot's transaction history (filter with `--player`, `--event`, `--time`; paginate with `--page`). |
| `/realty help` | Show in-game help. |
| `/realty version` | Show the plugin version. |

## Getting a plot

| Command | What it does |
|---|---|
| `/realty buy [region]` | Buy a plot outright at its sale price. |
| `/realty rent [region]` | Rent a plot for its set period. |
| `/realty extend [region]` | Extend your rental before it expires. |
| `/realty unrent [region]` | End **your own** rental early and get a prorated refund. |

```text
/realty info
/realty buy
```

> [!WARNING]
> When a rental expires you lose access and the plot returns to the landlord. Use
> `/realty extend` in good time.

## Offers

Propose your own price when a plot isn't on sale at a fixed price.

| Command | What it does |
|---|---|
| `/realty offer send <price> [region]` | Offer to buy a plot at your price. |
| `/realty offer pay <amount> [region]` | Pay toward an offer you've made. |
| `/realty offer withdraw [region]` | Take back an offer. |
| `/realty offer outbox` | List offers you've made. |
| `/realty offer inbox` | List offers made on your plots. |
| `/realty offer accept <player> [region]` | Accept an offer (you're the owner). |
| `/realty offer reject <player> [region]` | Reject one player's offer. |
| `/realty offer rejectall [region]` | Reject every outstanding offer on your plot. |
| `/realty offer toggle <enabled> [region]` | Turn offers on or off for your plot. |

```text
/realty offer send 50000
/realty offer pay 50000
```

## Auctions

| Command | What it does |
|---|---|
| `/realty auction info [region]` | Show an auction's bids and deadline. |
| `/realty auction bid <amount> [region]` | Place a bid. |
| `/realty auction paybid <amount> [region]` | Pay toward your winning bid. |
| `/realty auction <bidDuration> <paymentDuration> <minBid> <minBidStep> [region]` | Start an auction (landlord). |
| `/realty auction cancel [region]` | Cancel an auction (landlord). |

Times use a short format like `30m`, `1h`, or `2d`.

```text
/realty auction info
/realty auction bid 12000
```

## Managing your plot

| Command | What it does |
|---|---|
| `/realty set price <value> [region]` | Set the sale price. |
| `/realty set duration <value> [region]` | Set the rental period. |
| `/realty set landlord <player> [region]` | Set the landlord. |
| `/realty set authority <player> [region]` | Set who can manage the plot. |
| `/realty set titleholder <player> [region]` | Set the owner. |
| `/realty set tenant <player> [region]` | Set the tenant. |
| `/realty set maxextensions <value> [region]` | Limit how many times a rental can be extended. |
| `/realty unset <price\|titleholder\|tenant> [region]` | Clear one of those settings. |
| `/realty add <player> [region]` | Add a player to the plot. |
| `/realty remove <player> [region]` | Remove a player from the plot. |
| `/realty transfer <player> [region]` | Transfer ownership. |

> [!NOTE]
> These are for plot owners and authorities; some are limited to staff.

### Agents (sanctioned auctioneers)

An **agent** is authorised to run auctions and handle offers on your plot for you. Agents
are *not* added as plot members — they can't build or change the plot, only manage its
auctions and offers.

| Command | What it does |
|---|---|
| `/realty agent invite <player> [region]` | Invite a player to act as your agent. |
| `/realty agent invite accept [region]` | Accept an agent invite. |
| `/realty agent invite reject [region]` | Reject an agent invite. |
| `/realty agent invite withdraw <player> [region]` | Withdraw an invite you sent. |
| `/realty agent remove <player> [region]` | Remove an agent. |

### Tags & signs

| Command | What it does |
|---|---|
| `/realty tag add <tag> [region]` | Tag a plot (for searching). |
| `/realty tag remove <tag> [region]` | Remove a tag. |
| `/realty tag list` | List the tags you can apply. |
| `/realty tag clear [region]` | Clear all tags. |
| `/realty sign place <region>` | Place an info sign for a plot. |
| `/realty sign remove` | Remove an info sign (the one you're looking at). |
| `/realty sign list [region]` | List a plot's signs. |

## Staff & admin

| Command | What it does |
|---|---|
| `/realty create leasehold <name> <price> <duration> <maxExtensions> [--landlord <player>]` | Create and register a rentable plot. |
| `/realty create freehold <name> [--price <price>] [--titleholder <player>] [--authority <player>]` | Create and register a for-sale plot. |
| `/realty register leasehold <price> <duration> <maxExtensions> [--landlord <player>] [region]` | Register an existing region as a rentable plot. |
| `/realty register freehold [--price <price>] [--titleholder <player>] [--authority <player>] [region]` | Register an existing region as a for-sale plot. |
| `/realty delete <region> [includeworldguard]` | Remove a plot from Realty (optionally delete the WorldGuard region too). |
| `/realty subregion wand` | Get the footprint selection wand. |
| `/realty subregion clear` | Clear your selection. |
| `/realty subregion confirm` | Open the height/creation dialog for the selected footprint. |
| `/realty tp <region>` | Teleport to a plot. |
| `/realty cleanup tags` | Remove unused tags. |
| `/realty reload` | Reload configuration. |

## Permission nodes

Realty nodes follow the command path: **`realty.command.<path>`**. For example
`/realty buy` → `realty.command.buy`, `/realty offer send` → `realty.command.offer.send`,
`/realty agent invite accept` → `realty.command.agent.invite.accept`. (`/realty version`
is the only command with no permission — it's open to all.)

| Area | Nodes |
|---|---|
| Looking around | `realty.command.info` · `.list` · `.search` · `.history` · `.help` |
| Getting a plot | `realty.command.buy` · `.rent` · `.extend` · `.unrent` |
| Offers | `realty.command.offer.send` · `.pay` · `.withdraw` · `.inbox` · `.outbox` · `.accept` · `.reject` · `.toggle` *(`rejectall` uses `.reject`)* |
| Auctions | `realty.command.auction` *(start)* · `.auction.info` · `.bid` · `.paybid` · `.cancel` |
| Managing a plot | `realty.command.set.price` · `.duration` · `.landlord` · `.authority` · `.titleholder` · `.tenant` · `.maxextensions`; `realty.command.unset.price` · `.titleholder` · `.tenant`; `realty.command.add` · `.remove` · `.transfer` |
| Agents | `realty.command.agent.invite` · `.invite.accept` · `.invite.reject` · `.invite.withdraw` · `.remove` |
| Tags & signs | `realty.command.tag.add` · `.remove` · `.list` · `.clear`; `realty.command.sign.place` · `.remove` · `.list` |
| Staff & admin | `realty.command.create.leasehold` · `.create.freehold` · `.register.leasehold` · `.register.freehold` · `.delete` · `.subregion.wand` · `.subregion.confirm` · `.tp` · `.cleanup.tags` · `.reload` |

> [!NOTE]
> Several commands have an **`.others`** variant that lets staff act on plots they
> don't own — e.g. `realty.command.set.price.others`, `realty.command.add.others`,
> `realty.command.transfer.others` (where declared for the specific `set`/`unset` action). `set authority` is
> gated by `realty.command.set.authority` alone; its old `.others` node is removed.
> There are also bypass nodes: `realty.command.offer.toggle.bypass`,
> `realty.command.subregion.confirm.bypass`, and
> `realty.command.delete.includeworldguard`.

## Parties: players, accounts and groups

Landlords and freehold authorities may be players, Treasury government/business/
system accounts, or permission groups mapped to Treasury accounts. Tenants and
titleholders remain players. A plain name is a player; append exactly one of
`--government`, `--business`, `--system`, `--group` after the name and optional
region to select another kind. For example:

```text
/realty set landlord GovSecurity myregion --government
/realty list landlord GovSecurity --government
/realty add builders myregion --group
```

The type flags also apply to create/register `--landlord` and `--authority`
arguments. Government accounts resolve by name; business/system accounts can use
`#<id>` or a name among the sender's accounts. The old `g:` group prefix is removed.
Accounts must be live, non-personal and not require per-transfer authorization.
Direct Treasury members/authorizers act according to `account-managers`;
Treasury LP-group grants are not included in this Realty check.

| Command | Permission / behavior |
|---|---|
| `/realty group map <group> <account> --government\|--business\|--system` | `realty.command.group`; map a permission group to its funding account. |
| `/realty group unmap <group>` | Same permission; refused while contracts **or history** reference it. |
| `/realty group list` | Same permission; inspect mappings and contract counts. |

Acting for an authority can prevent buying, bidding, offering or acting as agent
on its land. `realty.bypass.conflict-of-interest` is a staff override; dealing with
oneself is still refused. See [Realty operations](/docs/admin/realty-operations)
for upgrade/default-party recovery.

## Changing occupied leases

`set price`, `set duration` and `set maxextensions` accept `[--now]`. A landlord's
normal change on an occupied lease waits until renewal; a tenant's change is a
proposal requiring landlord acceptance. On vacant/freehold land the holder's
change applies immediately. Holdership comes from Realty, not merely WorldGuard's
owner list.

| Command | Permission / behavior |
|---|---|
| `/realty modify accept [region]` | `realty.command.modify.accept`; landlord accepts a tenant proposal. |
| `/realty modify reject [region]` | `.modify.reject`; reject the proposal. |
| `/realty modify withdraw [region]` | `.modify.withdraw`; proposer withdraws. |
| `/realty modify inbox` / `outbox` | `.modify.inbox` / `.modify.outbox`; inspect pending proposals. |
| `/realty terminate [region] [--now]` | `realty.command.terminate`; schedule the configured notice, or immediate termination with `.terminate.now`. |
| `/realty terminate cancel [region]` | `.terminate`; initiator cancels pending termination (staff override `.terminate.others`). |
| `/realty rentable <true\|false> [region]` | `realty.command.rentable`; control future tenants without evicting the current one (`.rentable.others` overrides ownership). |

`--now` on set/unset needs `realty.command.set.now`. A `.others` grant bypasses
holdership, not the renewal wait. Occupied holder changes (`set landlord`,
`tenant`, `titleholder`, `unset tenant`, `unset titleholder`) and unlimited
`set maxextensions -1` require `--now`. `set authority` takes no `--now`.
The removed `modify price|duration|maxextensions` commands are replaced by `set`.

## Modules and property previews

| Command | Permission / behavior |
|---|---|
| `/realty module list` | `realty.command.module.list`; list loaded modules. |
| `/realty module reload <module>` | `.module.reload`; reload a module. Use matching versions. |
| `/realty schematic capture [region] [--force]` | `realty.command.schematic.capture`; player-only region preview. `.capture.others` bypasses membership; `.capture.force` bypasses cooldown, not volume limits. |

Schematic capture stores a hollow exterior preview in Realty's own format. It
omits interior/world-position data, cannot be loaded by WorldEdit and is **not a
backup or a restore facility**.
