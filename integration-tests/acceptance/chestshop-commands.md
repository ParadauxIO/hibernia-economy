# ChestShop command assertion review (PAR-499)

Reviewed against the 29 annotated routes in `entry-points.json` at
`b7dfa4a`. A handler's coverage does not prove that an alias/delegating route is
registered or permission-gated correctly. “Partial” means useful live evidence
exists but the listed branches still require acceptance. None of these rows is
an assertion of exhaustive coverage.

Test names below refer to `tests/src/test/java/net/democracycraft/inttests/chestshop/`.

| Handler and route | Live assertion evidence | Remaining acceptance |
| --- | --- | --- |
| BypassCommand `bypass` | `ShopLifecycleCommandsTest.adminBypassOptOutEnforcesOwnershipAndCanBeRestored`: admin changes another owner's registry visibility, opt-out rejects mutation, restore permits it; both `cs` and `chestshop` | Non-admin denial; trade/container/digging bypass; restart default. Source documents restart reset, not logout reset |
| ChestShopCommand root | `HelpVersionCommandsTest`: both roots show permitted ordinary commands, omit admin routes, and reflect live permission changes | Empty/no-route sender configurations |
| ChestShopCommand `help [page]` | `HelpVersionCommandsTest`: both aliases, default and explicit pages, admin page contents, zero/high-page clamping, revoked permissions | Invalid nonnumeric parsing, console sender |
| FindCommand root | `FindHeldItemTest`: empty-hand guidance, selected hotbar Diamond rather than Emerald, unchanged quantities | Permission denial, custom items |
| FindCommand `<item>` | `FindDialogTest`: real callback submission; visible/hidden shops, exact result count, price/stock/quantity, cheapest-first order, visibility read-after-write, inspection and Back | `FindPaginationTest` additionally covers two pages, Previous/Next, empty-stock filtering and saved descending numeric-priority sorting; remaining: all filter/sort combinations and custom items; `FindHeldItemTest` also covers unknown-code rejection through both roots |
| FindCommand `toggle visibility <visible>` | `ShopLifecycleCommandsTest`: owner hide/show roundtrip and outsider denial through `/find` | Finder permission denial, business roles |
| FindCommand `toggle hologram <visible>` | `ShopLifecycleCommandsTest`: outsider denial through `/find` | Owner roundtrip and finder permission denial |
| FindCommand `toggle preview <visible>` | `PreviewPreferenceCommandTest`: false/true, reconnect, isolation and live permission revocation | Actual preview rendering and server restart |
| GiveCommand `give <code> [quantity] [player]` | `GiveCommandTest`: default one item/self, explicit 65 items/self, explicit recipient gets exactly 17 and sender zero; permission revoked online blocks further gift; zero/negative/unknown item leave seven existing diamonds intact | Offline/unknown target, full inventory/overflow, custom items, console sender, oversized/invalid numeric parsing. Invalid-item test counts diamonds only, not a full inventory snapshot |
| ImportShopsCommand `importlegacy` | `LegacyShopImportTest`: real JSONL/Bukkit item decoding, personal/admin owners, exact terms/stock/flags, replay preservation, malformed-row rejection, missing-world stop/resume, missing-file recovery, permission denial | Business owners, concurrent triggers, database failure/recovery, wider custom item metadata |
| ImportShopsCommand `importpreferences` | `PreferenceImportTest`: real JSONL import/replay preserves existing choices; malformed-line stop and corrected-file resume; missing-file recovery; unprivileged denial | Concurrent triggers, database failure/recovery, more malformed value types |
| ItemInfoCommand root | `InformationCommandsTest`: hotbar slot selection, canonical Diamond sign name, four items preserved; both aliases | Empty hand, permissions, custom item metadata |
| ItemInfoCommand `<item>` | `InformationCommandsTest`: canonical Redstone Block sign name for parsed code, no item creation; both aliases | Unknown/custom items, permission denial |
| LegacyFindCommand `find` | `FindHeldItemTest`: same held-item and empty-hand contract through `/csdb find` | Permission denial, custom items |
| LegacyFindCommand `find <item>` | `FindDialogTest`: same search/inspection/sorting/visibility journey through `/csdb find` | Held-item variant, finder permission denial, advanced filters/pagination |
| LegacyFindCommand `find toggle preview <visible>` | `PreviewPreferenceCommandTest`: same persistence/isolation/revocation journey | Actual preview rendering and server restart |
| LegacyFindCommand `find toggle visibility <visible>` | `ShopLifecycleCommandsTest`: owner hide/show and outsider denial | Admin and business role branches |
| LegacyFindCommand `find toggle hologram <visible>` | `ShopLifecycleCommandsTest`: outsider denial | Owner/admin roundtrip and rendering |
| LegacyFindCommand `reload` | `ReloadAliasesTest`: real file alias changes affect next granted item; revoked admin cannot reload | Other configuration branches, malformed file recovery |
| LegacyFindCommand `resync <chunks>` | `ResyncCommandsTest`: changed stock/terms, orphan deactivation, unloaded-row preservation, repeated scan, live admin revocation/restoration through `/csdb` | Finder permission denial, budgets, concurrent scans and failure recovery |
| MetricsCommand `metrics` | `MetricsCommandTest`: both aliases, buy/sell counter deltas and exact item counts after real trades, revoked permission | 30-minute window rollover, account count |
| ResyncCommand `resync <chunks>` | `ResyncCommandsTest`: both aliases, exact changed stock/terms, stable identity/visibility, orphan deactivation, unloaded-row preservation, repeated scan and live permission revocation/restoration; existing orphan test | Budget boundaries, concurrent scans and database failure/recovery |
| ShopInfoCommand root | `InformationCommandsTest`: all three aliases report actual owner, 36 stock, quantity 3 and price 37; inventory and money unchanged | Container targeting, no shop, permission denial, richer item types |
| ShopToggleCommand `toggle visibility <visible>` | `ShopLifecycleCommandsTest`: owner hide/show retains ID/owner/price/quantity; outsider cannot mutate row; admin bypass opt-out/restoration | Finder inclusion/exclusion, invalid boolean, no target, business owner roles |
| ShopToggleCommand `toggle hologram <visible>` | `ShopLifecycleCommandsTest`: persisted false/true; outsider cannot mutate; real digging deactivates row | Visual entity rendering, reload persistence, no target, business owner roles |
| ShopToggleCommand `toggle preview <visible>` | Existing persisted-false check; `PreviewPreferenceCommandTest` adds both aliases, true roundtrip/reconnect/isolation and availability without finder permission | Server restart and actual preview entities |
| ToggleCommand `notify` | `NotificationCommandTest`: sale notifications on/off, persistence across reconnect, restoration through alias; four trades conserve money and move exact stock | Stock notifications, seller direction, player isolation, permission denial, server restart |
| VersionCommand `version` | `HelpVersionCommandsTest`: both aliases match live plugin metadata; revoked admin permission prevents output | Console sender |
| VersionCommand `reload` | `ReloadAliasesTest`: `/chestshop` and `/cs` change a real alias from diamond to emerald; revoked permission leaves prior mapping active | Other config branches, malformed file recovery |

This review deliberately records missing evidence rather than counting imports,
handler delegation, synthetic Bukkit events, or a successful command reply as
complete behavioral coverage. See `results.md` for executed run evidence and
`matrix.md` for non-command game behavior and external integration gaps.
