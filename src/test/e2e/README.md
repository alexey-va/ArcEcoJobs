# Real Paper EcoJobs menu tests

GitHub runs this suite separately from JVM and MySQL integration tests. It starts
an empty Redis 7.4 service on localhost:6385, builds the pinned upstream EcoJobs
source, then runs `./gradlew plugwrightTest` with Java 25. Paper binds to
localhost:25565 and uses disposable world/plugin data.

The suite opens the ArcEcoJobs inventory, selects the job catalog, verifies the
real Miner entry and its level lore, and opens the profession card. Job rewards,
paid boosts, vouchers, native dialogs and cross-server persistence are outside
this scenario. Existing JVM and storage tests remain in place.

The fixture uses upstream's stock professions and disables only the strict
ArcEcoJobs money-placeholder guard for them. Their production boost integration
is not represented here; the warning about missing money placeholders is
expected. The production guard remains enabled in packaged defaults.

Dependencies are actual plugins: eco/EcoJobs/libreforge 2026.33, LuckPerms
5.5.71, PlaceholderAPI 2.12.3, Vault 1.7.3 and RedisEconomy 4.5.12. The fixture
economy starts with zero balances. Production code and economic configuration
are unchanged. Paper 26.1.2 is used because eco's runtime did not load on the
Paper 1.21.11 build supplied by Plugwright 2.0.4. The runner and Node 22.14.0
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
