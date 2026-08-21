# ArcEcoJobs

ArcEcoJobs is the RusCrafting interface addon for EcoJobs 2026.33. EcoJobs
continues to own profession progression; ArcEcoJobs adds the player and admin
menus, global rankings, and LuckPerms-backed XP/money boosts.

Build:

```bash
../arc-core/gradlew -p . clean check shadowJar
```

The production artifact is `build/libs/ArcEcoJobs-0.1.0.jar`.

## Money integration

Every EcoJobs `give_money.args.amount` expression for job `<id>` must multiply
its original expression by:

```text
%arcecojobs_boost_<id>_money_multiplier%
```

The PlaceholderAPI expansion returns a plain number such as `1.5`. XP boosts
are applied independently through `PlayerJobExpGainEvent`.

## Voucher safety

The first start creates `plugins/ArcEcoJobs/secret.key`. Keep it private and
back it up with the runtime secrets. Every ArcEcoJobs node that accepts the
same vouchers must receive the same key through the secret workflow. Losing
or replacing it invalidates all previously issued voucher items. Voucher
configuration supports Bukkit persistent data, which is the stable namespaced
NBT surface; arbitrary raw NBT
is deliberately unsupported because it is version-sensitive and could replace
the plugin's signed fields.
