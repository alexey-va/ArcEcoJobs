# AGENTS.md — ArcEcoJobs

Standalone Kotlin/Paper addon for EcoJobs on RusCrafting.

- Target Purpur/Paper 1.21.11, EcoJobs/eco/libreforge 2026.33, LuckPerms 5.5,
  PlaceholderAPI 2.12.3, Java 25, and Kotlin 2.3.0.
- EcoJobs owns professions, levels, XP, join/leave prices, and persistent job
  data. Do not duplicate or mutate those stores outside its public API.
- LuckPerms direct expiring nodes own boost state. Do not dispatch LuckPerms
  console commands or introduce another boost database.
- Signed voucher items must remain non-forgeable, replay-safe, and idempotent
  across servers. Nodes that accept the same vouchers must share `secret.key`
  through the private secret workflow. Never log the key or signed payload.
- All player text belongs in `lang/ru.yml` and `lang/en.yml`; keys stay equal
  and dynamic values use non-parsing Adventure placeholders.
- Inventory titles do not include the chat prefix. Item names and lore are
  explicitly non-italic.
- Runtime state (`secret.key`) belongs under `plugins/ArcEcoJobs/` and is never
  tracked or deployed as configuration. Back up the key before production
  rollout or previously issued vouchers become invalid.
- Build and test with `../arc-core/gradlew -p . clean check shadowJar`.
