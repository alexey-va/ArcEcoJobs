# Real Paper EcoJobs menu tests

GitHub runs this suite separately from JVM and MySQL integration tests. It starts
an empty Redis 7.4 service on localhost:6385, builds the pinned upstream EcoJobs
source, then runs `./gradlew plugwrightTest` with Java 25. Paper binds to
localhost:25565 and uses disposable world/plugin data.

The suite opens the ArcEcoJobs inventory, selects the job catalog, verifies the
real Miner entry and its level lore, and opens the profession card. It also runs
the real EcoJobs kill trigger through a disposable slayer fixture: the support
plugin registers an in-memory eco AFK provider, joins the test player to the
job, reports XP and Vault balance, and spawns one-hit entities for real
mineflayer melee kills. The fixture covers AFK denial, moving valid kills,
stationary cooldown timing, spawner metadata and passive-entity exclusions.
The fixture writes the complete runtime-owned catalog used by the E2E server:
the eleven jobs mirrored from the ops catalog (`beekeeper`, `builder`,
`enchanter`, `explorer`, `farmer`, `fisherman`, `lumberjack`, `miner`,
`slayer`, `smelter`, and `toolsmith`). Every XP gain method and every
`give_money` effect carries its job-specific pre-action work gate, so startup
validation cannot fall back to an unguarded bundled default. The disposable
slayer fixture adds the real entity filters used by this test. Its AFK
provider, command and jar exist only under `src/test/e2e/support`; no production
plugin or server data is changed.

Dependencies are actual plugins: eco/EcoJobs/libreforge 2026.33, LuckPerms
5.5.71, PlaceholderAPI 2.12.3, Vault 1.7.3 and RedisEconomy 4.5.12. The fixture
economy starts with zero balances. Production code and economic configuration
are unchanged. The E2E harness intentionally runs Paper 26.1.2, while
production targets Paper/Purpur 1.21.11. Paper 26.1.2 is used because eco's
runtime did not load on the Paper 1.21.11 build supplied by Plugwright 2.0.4;
this does not change the production target. The runner and Node 22.14.0
are pinned, and CI rejects npm lockfile changes.

## Upstream runtime packaging

The [official EcoJobs source](https://github.com/Auxilor/EcoJobs/tree/110b9597ec93d78685b8e63ee136d75d63f8c97e)
publishes an API-only JAR to public Maven. The `libreforgeJar` task produces
`bin/EcoJobs v2026.33.jar`; the ordinary `build/libs/*-all.jar` is not that
distribution. This tag's distribution also omits the nested
`libreforge-2026.33-shadow.jar` resource required by its loader.
The public libreforge payload also uses unrelocated Kotlin signatures.
`prepareLibreforgeRuntime` applies the same two Kotlin relocations defined by
upstream's Gradle plugin 2.0.0; `prepareEcoJobsRuntime` embeds that payload under
the required name. It preserves EcoJobs runtime classes and plugin identity;
it does not replace providers or merge the two plugins' class trees.

For a local run, prepare an empty disposable Redis instance on localhost:6385
and build the same upstream source:

```bash
git clone https://github.com/Auxilor/EcoJobs.git e2e-ecojobs
git -C e2e-ecojobs checkout --detach 110b9597ec93d78685b8e63ee136d75d63f8c97e
cd e2e-ecojobs
./gradlew --no-daemon libreforgeJar
cd ..
./gradlew --no-daemon plugwrightTest
```

An already built upstream distribution can be supplied with
`-Pe2eEcoJobsJar=/absolute/path/to/runtime.jar`. Do not point fixtures at shared
Redis or a live server. Runner output, Paper logs and crash reports are retained
as GitHub artifacts even when tests fail.


## Paired ARC work-time check

CI builds ARC at the immutable source revision in `.github/workflows/e2e.yml`
and stages the real plugin with `-Pe2eArcJar=/absolute/path/to/ARC.jar`.
The generated support config marks ARC as required: missing/disabled telemetry
fails the paired scenario. Without this property, the existing native jobs
checks run with no ARC dependency. Runtime hash lines are retained in CI output.

The paired scenario opens menus and evaluates placeholders, then makes real
melee kills under the native EcoJobs filters. Its fixture pays money every third
accepted kill while XP arrives on each; ARC must observe both earlier actions.
Spawner/passive targets and AFK actions cannot increase time or observations.
After AFK, the first observation has zero duration and the next starts adding
time. Aggregate expectations use deltas so earlier test players cannot satisfy
the assertions. The fixture rates are test-only; production rates are unchanged.
