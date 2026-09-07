# AGENTS.md — ArcEcoJobs

Standalone Kotlin/Paper addon for EcoJobs on RusCrafting.

- Target Purpur/Paper 1.21.11, EcoJobs/eco/libreforge 2026.33, LuckPerms 5.5,
  PlaceholderAPI 2.12.3, Java 25, and Kotlin 2.3.0.
- Use the pinned public `arc-core` release by default; opt into a local
  composite only with `-ParcCoreDir=/absolute/path/to/arc-core`. Paper tests use
  `ru.ruscrafting.arc:arc-core-paper-testing:2.0.3` and
  `MockBukkitTestRuntime`; never pin MockBukkit directly.
- All MySQL contours use `arc-core-sql` for connection settings, pool/executor
  lifecycle, and checksum-protected migrations. Voucher redemption uses the
  shared `OneTimeUseLedger`/`MySqlOneTimeUseLedger` contract and the single
  `arc_one_time_uses` table under purpose `arcecojobs.voucher`; ArcEcoJobs owns
  only its payload mapping and legacy-row migration. The core ledger retains a
  connection-scoped advisory lock across the LuckPerms write and isolates its
  completion executor so exhausted claim capacity cannot starve finalization.
- EcoJobs owns professions, levels, XP, join/leave prices, and persistent job
  data. Do not duplicate or mutate those stores outside its public API.
- LuckPerms direct expiring nodes own boost state. Do not dispatch LuckPerms
  console commands or introduce another boost database.
- Signed voucher items must remain non-forgeable, replay-safe, and idempotent
  across servers. Nodes that accept the same vouchers must share `secret.key`
  through the private secret workflow. The file is newline-terminated Base64
  text encoding at least 32 random bytes, never raw binary. Never log the key
  or signed payload.
- Vouchers are bearer items: transfer is allowed before redemption and must
  never be restricted by an issued-to UUID. Permanent replay identity belongs
  to the MySQL `arc_one_time_uses` (`purpose`, `use_id`) primary key; never
  purge committed rows. `arcecojobs_voucher_redemptions` is compatibility
  import input only. This ledger records claims only and is not a second boost
  store—LuckPerms remains the sole owner of active boost nodes.
- All player text belongs in `lang/ru.yml` and `lang/en.yml`; keys stay equal
  and dynamic values use non-parsing Adventure placeholders.
- Inventory titles do not include the chat prefix. Item names and lore are
  explicitly non-italic.
- Runtime state (`secret.key`) belongs under `plugins/ArcEcoJobs/` and is never
  tracked or deployed as configuration. Back up the key before production
  rollout or previously issued vouchers become invalid.
- On a developer workstation run `./gradlew shadowJar`. Run a focused unit test
  with `./gradlew test --tests '<fully-qualified-test-pattern>' shadowJar` when
  the change needs it. Full `clean check shadowJar` is opt-in and includes the
  disposable MySQL `integrationTest`; the Docker-capable CI gate owns that
  lane.
