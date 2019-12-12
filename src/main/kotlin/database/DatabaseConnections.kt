import arrow.core.Option
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mu.KotlinLogging
import org.postgresql.ds.PGSimpleDataSource
import javax.sql.DataSource

private val logger = KotlinLogging.logger {}

/**
 * Create a DataSource, either for Postgres (if the DatabaseConfig is present)
 * or h2sql if not. Depending on the Environment, it will create a RdsIamDataSource with
 * authentication done through IAM for production or a PostgresDataSource, with the password
 * pass through for development.
 *
 * @param defaultDbName Provides a name to use for h2sql
 */
fun Option<DatabaseConfig>.toDataSource(defaultDbName: String): DataSource {
    return fold(
        { HikariConfig().h2Sql(defaultDbName) },
        { postgres(it) }
    ).let(::HikariDataSource)
}

fun postgres(databaseConfig: DatabaseConfig): HikariConfig {
    return when (databaseConfig.authentication) {
        AuthenticationMethod.PASSWORD -> HikariConfig().postgresWithPassword(databaseConfig)
        AuthenticationMethod.RDS_IAM -> HikariConfig().postgresWithRdsIam(databaseConfig)
    }
}

fun HikariConfig.postgresWithRdsIam(config: DatabaseConfig): HikariConfig {
    val pgSimpleDataSource = PGSimpleDataSource().also {
        it.setUrl("jdbc:postgresql://${config.host}:${config.port}/${config.dbName}")
        it.sslMode = "require"
        it.currentSchema = config.schema
    }

    dataSource = RdsIamDataSource(pgSimpleDataSource, username = config.userName)

    logger.info { "Connecting to RDS: ${pgSimpleDataSource.getUrl()} as ${config.userName}" }
    return this
}

fun HikariConfig.postgresWithPassword(config: DatabaseConfig): HikariConfig {
    logger.info { "Connecting to RDS: $config" }
    val pgSimpleDataSource = PGSimpleDataSource().also {
        it.setUrl("jdbc:postgresql://${config.host}:${config.port}/${config.dbName}")
        it.sslMode = if (config.ssl) "require" else "disable"
        it.user = config.userName
        it.password = config.password
    }

    dataSource = pgSimpleDataSource

    logger.info { "Connecting to Postgres in development: ${pgSimpleDataSource.getUrl()} as ${config.userName}" }
    return this
}

fun HikariConfig.h2Sql(defaultDbName: String): HikariConfig {
    jdbcUrl = "jdbc:h2:./$defaultDbName;AUTO_SERVER=TRUE;MODE=MySQL;DB_CLOSE_DELAY=-1;"
    driverClassName = "org.h2.Driver"
    logger.info { "Connecting to h2sql: $jdbcUrl" }
    return this
}
