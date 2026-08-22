# ArcEcoJobs

ArcEcoJobs is the RusCrafting interface addon for EcoJobs 2026.33. EcoJobs
continues to own profession progression; ArcEcoJobs adds the player and admin
menus, global rankings, and LuckPerms-backed XP/money boosts.

Build:

```bash
../arc-core/gradlew -p . clean check shadowJar
```

The production artifact is `build/libs/ArcEcoJobs-0.1.6.jar`. The test suite
starts a disposable MySQL 8.0.46 container to prove concurrent redemption.

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
previously issued voucher items. New vouchers use bearer format v3 and can be
transferred freely. Correctly signed v1 and v2 items remain compatible; the v2
recipient field is verified as part of its historical signature but no longer
restricts who can redeem the item.

Replay protection is global rather than player-bound. MySQL table
`arcecojobs_voucher_redemptions` owns the permanent unique `voucher_id` claim;
LuckPerms continues to own the actual boost and keeps a deterministic
per-player application marker for crash reconciliation. A redemption moves
from `CLAIMED` to `APPLIED` only after LuckPerms saves. If the final MySQL
acknowledgement is lost, retry detects the LuckPerms marker and completes the
same claim without issuing a second boost. Applied rows must never be expired
or purged. A MySQL named lock serializes the same voucher across nodes while
LuckPerms is being updated; an uncertain pending claim remains reserved to its
first redeemer for safe retry. Every pending claim for a signed v1/v2 item also
searches LuckPerms for a historical use marker, including after a failed MySQL
acknowledgement, so a voucher redeemed before this ledger existed cannot
become usable again after transfer.

Provision the dedicated least-privilege production account interactively with
`../scripts/provision-arcecojobs-mysql`, then commit and deploy both reviewed
runtime configs. Without an enabled, reachable ledger, voucher redemption
fails closed and the item is not consumed.

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
