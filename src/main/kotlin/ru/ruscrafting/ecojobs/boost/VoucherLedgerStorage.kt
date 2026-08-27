package ru.ruscrafting.ecojobs.boost

import ru.arc.sql.SqlMigration
import ru.arc.sql.onetime.MySqlOneTimeUseLedger
import ru.arc.sql.onetime.MySqlOneTimeUsePartition
import ru.ruscrafting.ecojobs.config.RedemptionStorageSettings
import ru.ruscrafting.ecojobs.config.toSqlConnectionConfig

/** ArcEcoJobs schema ownership and compatibility import for the shared ledger. */
object VoucherLedgerStorage {
    const val SCHEMA_VERSION = 2

    private val partition = MySqlOneTimeUsePartition("arcecojobs.voucher")

    private val legacyMigration = SqlMigration(
        version = 1,
        description = "create voucher redemption ledger",
        statements = listOf(
            """
            CREATE TABLE IF NOT EXISTS `arcecojobs_voucher_redemptions` (
                `voucher_id` BINARY(16) NOT NULL,
                `payload_hash` BINARY(32) NOT NULL,
                `redeemer_id` BINARY(16) NOT NULL,
                `claim_token` BINARY(16) NOT NULL,
                `status` VARCHAR(16) NOT NULL,
                `claimed_at` TIMESTAMP(3) NOT NULL,
                `applied_at` TIMESTAMP(3) NULL,
                PRIMARY KEY (`voucher_id`),
                CONSTRAINT `arcecojobs_voucher_status_chk` CHECK (`status` IN ('CLAIMED', 'APPLIED'))
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """.trimIndent(),
        ),
    )

    private val sharedLedgerMigration = SqlMigration(
        version = SCHEMA_VERSION,
        description = "adopt shared one-time-use ledger",
        statements = listOf(
            MySqlOneTimeUseLedger.createTableSql(),
            """
            INSERT INTO `arc_one_time_uses`
                (`purpose`, `use_id`, `fingerprint`, `claimant_id`, `claim_id`, `claim_scope`, `status`, `claimed_at`, `committed_at`)
            SELECT
                'arcecojobs.voucher', legacy.`voucher_id`, legacy.`payload_hash`, legacy.`redeemer_id`,
                legacy.`voucher_id`, NULL,
                CASE legacy.`status` WHEN 'APPLIED' THEN 'COMMITTED' ELSE 'CLAIMED' END,
                legacy.`claimed_at`, legacy.`applied_at`
            FROM `arcecojobs_voucher_redemptions` AS legacy
            ON DUPLICATE KEY UPDATE
                `purpose` = IF(
                    `arc_one_time_uses`.`use_id` = VALUES(`use_id`) AND
                    `arc_one_time_uses`.`fingerprint` = VALUES(`fingerprint`) AND
                    `arc_one_time_uses`.`claimant_id` = VALUES(`claimant_id`) AND
                    `arc_one_time_uses`.`claim_id` = VALUES(`claim_id`) AND
                    `arc_one_time_uses`.`status` = VALUES(`status`),
                    `arc_one_time_uses`.`purpose`,
                    NULL
                )
            """.trimIndent(),
        ),
    )

    fun open(settings: RedemptionStorageSettings): MySqlOneTimeUseLedger {
        require(settings.enabled) { "voucher redemption MySQL is disabled" }
        return MySqlOneTimeUseLedger.open(
            connectionConfig = settings.toSqlConnectionConfig(settings.maximumPoolSize),
            runtimeName = "ArcEcoJobs-redemptions",
            migrationNamespace = "arcecojobs",
            migrations = listOf(legacyMigration, sharedLedgerMigration),
            partition = partition,
        )
    }
}
