# ArcEcoJobs

ArcEcoJobs is the RusCrafting interface addon for EcoJobs 2026.33. EcoJobs
continues to own profession progression; ArcEcoJobs adds the player and admin
menus, global rankings, and LuckPerms-backed XP/money boosts.

Build:

```bash
../arc-core/gradlew -p . clean check shadowJar
```

The production artifact is `build/libs/ArcEcoJobs-0.1.5.jar`.

Read-only lab GUI acceptance:

```bash
cd ../scripts/player-bot
npm run qa:arcecojobs
ARC_ECOJOBS_QA_LOCALE=en_US npm run qa:arcecojobs
ARC_ECOJOBS_QA_ROLE=player npm run qa:arcecojobs
```

The scenario uses only `/jobs` and fixed read-only menu routes. It never joins
or leaves a job, grants a voucher, clicks a voucher preset, or changes
LuckPerms. The default admin run validates the reload control without clicking
it. A lab-only mutation run can explicitly include reload and a cold cache
rebuild with `ARC_ECOJOBS_QA_ALLOW_MUTATIONS=true`. The admin run covers every
screen and both pages of the 50-level scale; player mode additionally proves
that the management entry stays hidden.

## Money integration

Every EcoJobs `give_money.args.amount` expression for job `<id>` must multiply
its original expression by:

```text
%arcecojobs_boost_<id>_money_multiplier%
```

The PlaceholderAPI expansion returns a plain number such as `1.5`. XP boosts
are applied independently through `PlayerJobExpGainEvent`.

## Voucher safety

The first start creates `plugins/ArcEcoJobs/secret.key`. The file is a
newline-terminated Base64 encoding of at least 32 random bytes; do not write
raw binary key bytes to it. Keep it private and back it up with the runtime
secrets. Every ArcEcoJobs node that accepts the same vouchers must receive the
same file through the secret workflow. Losing or replacing it invalidates all
previously issued voucher items. Voucher format v2 binds every signed item to
the recipient UUID, while LuckPerms stores its redemption marker across the
network. Version 1 vouchers are rejected because they were not recipient-bound
and therefore could not be made safely replay-resistant.

Voucher configuration supports Bukkit persistent data, which is the stable
namespaced NBT surface; arbitrary raw NBT
is deliberately unsupported because it is version-sensitive and could replace
the plugin's signed fields.

## Leaderboard consistency

Ranking builds run asynchronously through Eco's public profile API. Eco 2026.33
caches profile values after their first read, so an offline player's progress
written on another server can remain stale on a node that already cached that
profile. Cache invalidation in ArcEcoJobs cannot force a fresh public Eco read.
A fully fresh network leaderboard requires a supported bulk/fresh snapshot API
from EcoJobs or a separately designed event-fed aggregate; do not bypass this
boundary with reflection or direct reads from EcoJobs storage.
