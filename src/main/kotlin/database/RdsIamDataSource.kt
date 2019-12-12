import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.services.rds.auth.GetIamAuthTokenRequest
import com.amazonaws.services.rds.auth.RdsIamAuthTokenGenerator
import mu.KotlinLogging
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import javax.sql.DataSource

private val logger = KotlinLogging.logger {}

/**
 * DataSource which performs IAM-based authentication on a Postgres DataSource.
 *
 * @param dataSource the Postgres DataSource to use to create actual connections
 * @param username The Postgres user name to connect as
 * @param region AWS region - defaulted to environment variable AWS_REGION
 */
class RdsIamDataSource(
    private val dataSource: PGSimpleDataSource,
    private val username: String,
    region: String = System.getenv("AWS_REGION")
) : DataSource by dataSource {

    override fun getConnection(): Connection {
        // Update username and password
        dataSource.user = username
        dataSource.password = iamToken
        logger.info { "Retrieved auth token for $username on ${dataSource.serverName}:${dataSource.portNumber}" }
        return dataSource.connection
    }

    private val iamToken: String
        // NOTE: Must remain a getter as authentication tokens expire.
        get() = authTokenGenerator.getAuthToken(authTokenRequest)

    private val authTokenGenerator: RdsIamAuthTokenGenerator =
        RdsIamAuthTokenGenerator.builder().credentials(DefaultAWSCredentialsProviderChain()).region(region).build()

    private val authTokenRequest = dataSource.run {
        GetIamAuthTokenRequest.builder().hostname(serverName).port(portNumber).userName(username).build()
    }
}