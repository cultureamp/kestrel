package com.cultureamp.eventsourcing

import org.jetbrains.exposed.sql.statements.api.ExposedConnection
import org.slf4j.LoggerFactory
import java.sql.SQLException

private val logger = LoggerFactory.getLogger(PGSessionAdvisoryBookmarkLock::class.java)
class PGSessionAdvisoryBookmarkLock(
    private val connectionProvider: () -> ExposedConnection<Any>,
) {
    private var connection = connectionProvider()

    fun lock(bookmarkName: String) {
        try {
            acquireLock(connection, bookmarkName)
        } catch (e: Exception) {
            logger.info("Failed to refresh session lock for $bookmarkName, resetting connection to try again", e)
            connection = connectionProvider()
            acquireLock(connection, bookmarkName)
        }
    }

    private fun acquireLock(connection: ExposedConnection<Any>, bookmarkName: String) {
        val lockTimeoutMilliseconds = 10_000
        val key = createKey(connection, bookmarkName)
        while (!tryLock(connection, key, lockTimeoutMilliseconds)) {
            logger.debug("Timed out trying to acquire lock on $bookmarkName after ${lockTimeoutMilliseconds}ms. Trying again")
        }
    }

    private fun tryLock(connection: ExposedConnection<Any>, key: Long, lockTimeoutMs: Int): Boolean {
        try {
            connection.prepareStatement("SET LOCAL lock_timeout = ?", false).also {
                it[1] = "${lockTimeoutMs}ms"
                it.executeUpdate()
            }
            connection.prepareStatement("SELECT pg_advisory_lock(?)", false).also {
                it[1] = key
                it.executeQuery()
            }
        } catch (e: SQLException) {
            if (e.message.orEmpty().contains("canceling statement due to lock timeout")) {
                return false
            } else {
                throw e
            }
        }
        return true
    }

    // TODO this should be done gooder, perhaps by looking up the bookmark in db?
    private fun createKey(connection: ExposedConnection<Any>, bookmarkName: String): Long = bookmarkName.hashCode().toLong()
}