# BountyHunters navigation placeholders

The installed `BountyHunters-2.6.2.jar` declares version 2.6.1 in plugin.yml.
Its next-page, previous-page, go-back and close buttons create `EmptyPlaceholders`,
whose constructor passes null to `Placeholders`. That superclass now immediately
dereferences the inventory to obtain the plugin's placeholder parser.

The patch changes only those four `getPlaceholders` factories to construct
`Placeholders(inventory)`, matching the existing `SimpleItem` implementation.
It preserves PlaceholderAPI parsing, colour translation, unknown placeholders,
pagination rules, and other JAR members. No global null fallback is introduced.

```sh
python3 ops/plugin-patches/bountyhunters/run.py <staging-server> .context/bountyhunters
```

Uses locally available ASM, JDK 25, Mockito and Paper libraries. The patcher rejects
unknown original artifacts and output overwrite. Tests call the real plugin's
placeholder and pagination methods with mocked environment objects. Original
reproduces the null-inventory constructor error; patched passes all four factories,
parser/colour preservation, and forward/back navigation for 0, 1, 10, 11 and 25
entries with ten entries per page. Bounds and visibility assertions detect skipped
or broken navigation. The archive comparison allows only the four targeted classes
to change.

`LiveBountyProbe` adds staging checks against the actual enabled plugin and real
Bukkit inventories/item metadata. Detached inventories are rendered for all five
entry counts and four button types, without accessing player bounty records or
opening any real player's inventory. This verifies the failing rendering path;
it does not simulate a human clicking the complete `/bounties` interface.

Original SHA-256: `7ae836d097cb48d5a4494948cc66c9847411b25ce23509af22b57f063ca361cf`

Patched SHA-256: `df1d450c9ff76e6124051d185ed409063c76e9cce1f0d241dba037db6b307bee`
