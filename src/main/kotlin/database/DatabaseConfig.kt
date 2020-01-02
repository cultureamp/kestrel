import arrow.core.Option
import arrow.core.Some
import arrow.core.extensions.option.monad.binding
import arrow.core.orElse
import system.env

/**
 * Complete set of information required to connect to a Postgres database
 */
data class DatabaseConfig(
    val host: String,
    val port: Int,
    val ssl: Boolean,
    val dbName: String,
    val schema: String,
    val userName: String,
    val password: String?,
    val authentication: AuthenticationMethod
) {

    companion object {
        private const val DEFAULT_POSTGRES_PORT = "5432"
        private const val NO_SCHEMA = ""
        private const val DEFAULT_AUTHENTICATION = "PASSWORD"
        private const val DEFAULT_SSL = "true"

        /**
         * Load a DatabaseConfig from environment variables of the form
         * <PREFIX>_HOST
         * <PREFIX>_PORT
         * <PREFIX>_DBNAME
         * <PREFIX>_SCHEMA
         * <PREFIX>_USERNAME
         * <PREFIX>_PASSWORD
         * <PREFIX>_AUTHENTICATION
         * <PREFIX>_SSL
         */
        fun fromEnvironment(variablePrefix: String): Option<DatabaseConfig> {
            return binding {
                val (host) = env("${variablePrefix}_HOST")
                val (port) = env("${variablePrefix}_PORT").orElse{ Some(DEFAULT_POSTGRES_PORT) }.map(String::toInt)
                val (dbName) = env("${variablePrefix}_DBNAME")
                val (schema) = env("${variablePrefix}_SCHEMA").orElse{ Some(NO_SCHEMA) }
                val (userName) = env("${variablePrefix}_USERNAME")
                val password = env("${variablePrefix}_PASSWORD").orNull()
                val (authentication) = env("${variablePrefix}_AUTHENTICATION").orElse { Some(DEFAULT_AUTHENTICATION) }.map { AuthenticationMethod.valueOf(it) }
                val (ssl) = env("${variablePrefix}_SSL").orElse { Some(DEFAULT_SSL) }.map { it.toBoolean() }
                DatabaseConfig(host, port, ssl, dbName, schema, userName, password, authentication)
            }
        }
    }

    override fun toString(): String {
        return "DatabaseConfig(host='$host', port=$port, dbName='$dbName', schema='$schema', userName='$userName', password='${password?.map{ "*" }?.joinToString("")}', authentication=$authentication)"
    }
}
