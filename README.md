# ArcEcoJobs

ArcEcoJobs is the RusCrafting interface addon for EcoJobs 2026.33. EcoJobs
continues to own profession progression; ArcEcoJobs adds the player and admin
menus, global rankings, and LuckPerms-backed XP/money boosts.

Local unit/MockBukkit and package gate (no Docker required):

```bash
./gradlew clean test shadowJar
```

The production artifact is `build/libs/ArcEcoJobs-0.1.13.jar`. The test suite
uses public `arc-core 2.0.3` dependencies by default. Pass
`-ParcCoreDir=/absolute/path/to/arc-core` only when intentionally testing an
unpublished local core checkout. GitHub CI additionally runs `integrationTest`
against a disposable MySQL 8.0.46 service to prove concurrent redemption,
discovery ordering, migration replay, and idempotent hourly aggregation.

The three MySQL contours share `arc-core-sql` connection validation, Hikari
lifecycle, bounded executors, and checksum-protected migrations. Voucher
redemption keeps a separate core-managed completion executor because its
advisory locks retain JDBC sessions across the LuckPerms write; this prevents
pool exhaustion from starving the acknowledgement that releases capacity.

## Explorer job

ArcEcoJobs supplies the anti-abuse discovery boundary for EcoJobs' `explorer`
job. EcoJobs 2026.33 exposes ordinary chunk-change and custom triggers, but it
does not remember which players discovered a chunk. ArcEcoJobs records up to
five different active explorers per world UUID and chunk in MySQL, assigns
their rank transactionally across server nodes, and dispatches the
`custom_arcecojobs_discover_chunk` trigger. The trigger value preserves the old
Jobs XP curve (`1.0`, `1.0`, `0.8`, `0.5`, or `0.1`); its alternate value
preserves the money curve (`1.0`, `0.8`, `0.6`, `0.4`, or `0.1`).

Teleportation, creative/spectator movement, and ordinary flight do not count;
Elytra gliding remains eligible like it was in Jobs. A player cannot earn twice
from the same chunk, and a claimed discovery is never retried automatically
after an uncertain reward outcome. This deliberately prefers a possible missed
reward over a duplicate monetary payout. The feature requires
`exploration.enabled: true` and the shared MySQL profile under
`redemptions.mysql`.

The job definition is tracked for spawn, survival, and the isolated lab.
Production enables exploration on both gameplay nodes and shares one MySQL
ledger, so a chunk keeps the same five discovery ranks across the network.
The discovery migration stays within the provisioned least-privilege grant set
and does not require MySQL `REFERENCES`.

Read-only lab GUI acceptance:

```bash
cd ../scripts/player-bot
npm run qa:arcecojobs
ARC_ECOJOBS_QA_LOCALE=en_US npm run qa:arcecojobs
ARC_ECOJOBS_QA_ROLE=player npm run qa:arcecojobs
ARC_ECOJOBS_QA_ALLOW_MUTATIONS=true npm run qa:arcecojobs:vouchers
```

The scenario uses only `/jobs` and fixed read-only menu routes. It never joins
or leaves a job, grants a voucher, clicks a voucher preset, or changes
LuckPerms. The default admin run validates the reload control without clicking
it. The preset view verifies all nine distinct vanilla voucher previews. A
lab-only mutation run can explicitly include reload and a cold cache
rebuild with `ARC_ECOJOBS_QA_ALLOW_MUTATIONS=true`. The admin run covers every
screen and both pages of the 50-level scale; player mode additionally proves
that the management entry stays hidden.

The separate voucher smoke is mutation-gated and lab-only. It gives two
30-minute XP vouchers to the disposable QA player, activates both while looking
into the air, verifies a one-hour total, rejects a money voucher without
spending it, then revokes the boost and clears the QA inventory.

The explorer reward smoke is likewise hard-gated to the public isolated lab
and its disposable OP QA identities:

```bash
npm run qa:arcecojobs:explorer
```

It joins `explorer`, moves the QA actor across multiple chunk boundaries, and
requires a positive isolated-currency delta. Because it changes QA player and
economy state, run it only under the current mutation authorization boundary.

## Money integration

Every EcoJobs `give_money.args.amount` expression for job `<id>` must multiply
its original expression by:

```text
%arcecojobs_boost_<id>_money_multiplier% * %arcecojobs_earnings_<id>_money_marker%
```

The boost placeholder returns a plain number such as `1.5`. The earnings marker
always returns `1`, carries the exact job into the immediately following Vault
deposit, and records the provider-confirmed amount only after that deposit is
accepted. XP boosts and earned XP are observed independently through
`PlayerJobExpGainEvent`.

## Earnings history

Each job card shows the player's money and XP earned today, during the current
hour, and across the last seven calendar days. The history screen contains one
entry per day; selecting a day opens its 24-hour breakdown. This is personal
analytics, not an authoritative economy ledger, and it begins collecting only
after the feature is deployed.

Events are combined into one in-memory bucket per player, job, and UTC hour,
then written to the shared MySQL database every 10 seconds on a dedicated
single-thread pool. Batch IDs make retries idempotent across uncertain commit
outcomes. GUI reads are asynchronous and cached briefly. Production retains 30
days, prunes older buckets every six hours, and caps the pending buffer at 4096
unique buckets. If analytics storage is unavailable, jobs and Vault payouts
continue while the GUI reports that history is temporarily unavailable.

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

Replay protection is global rather than player-bound. Core's single MySQL
table `arc_one_time_uses` owns the permanent unique claim under purpose
`arcecojobs.voucher`; the former `arcecojobs_voucher_redemptions` table is
compatibility-import input only. LuckPerms continues to own the actual boost
and keeps a deterministic per-player application marker for crash
reconciliation. A redemption moves from `CLAIMED` to `COMMITTED` only after
LuckPerms saves. If the final MySQL acknowledgement is lost, retry detects the
LuckPerms marker and completes the same claim without issuing a second boost.
Committed rows must never be expired or purged. A MySQL named lock serializes
the same voucher across nodes while LuckPerms is being updated; an uncertain
pending claim remains reserved to its
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

A signed voucher activates on right-click in air or on a block. ArcEcoJobs also
cancels vanilla item consumption and projectile launch as a compatibility
fallback for previously issued bottle-shaped vouchers. Matching boosts (same
type, multiplier, and job scope) add their remaining durations and are saved as
one LuckPerms instance. A different type or effect is rejected without spending
the voucher. `boosts.maximum-stacked-duration` bounds the accumulated duration.

## Leaderboard consistency

Ranking builds run asynchronously through Eco's public profile API. Eco 2026.33
caches profile values after their first read, so an offline player's progress
written on another server can remain stale on a node that already cached that
profile. Cache invalidation in ArcEcoJobs cannot force a fresh public Eco read.
A fully fresh network leaderboard requires a supported bulk/fresh snapshot API
from EcoJobs or a separately designed event-fed aggregate; do not bypass this
boundary with reflection or direct reads from EcoJobs storage.
