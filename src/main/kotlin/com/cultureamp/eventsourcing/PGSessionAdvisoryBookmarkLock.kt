package com.cultureamp.eventsourcing

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

private val logger = LoggerFactory.getLogger(PGSessionAdvisoryBookmarkLock::class.java)
class PGSessionAdvisoryBookmarkLock(
    private val lockTimeoutMs: Int = 60_000,
    private val connectionProvider: () -> Connection,
) {
    private val lock = Any()
    private var connection = getConnection()

    fun lock(bookmarkName: String) {
        synchronized(lock) {
            try {
                refreshLock(connection, bookmarkName)
            } catch (e: Exception) {
                logger.info("Failed to refresh session lock for $bookmarkName, resetting connection to try again", e)
                connection.close()
                connection = getConnection()
                refreshLock(connection, bookmarkName)
            }
        }
    }

    private fun getConnection(): Connection {
        val connection = connectionProvider().also { con ->
            con.autoCommit = true
            con.prepareStatement("SET lock_timeout = '${lockTimeoutMs}ms'").also {
                it.executeUpdate()
                it.close()
            }

        }
        logger.info("Got connection $connection, autocommit=${connection.autoCommit}")
        return connection
    }

    private fun refreshLock(connection: Connection, bookmarkName: String) {
        val key = createKey(bookmarkName)
        while (!tryLock(connection, key)) {
            logger.debug("Timed out trying to acquire lock on $bookmarkName after ${lockTimeoutMs}ms. Trying again")
        }
    }

    private fun tryLock(connection: Connection, key: Long): Boolean {
        try {
            connection.prepareStatement("SELECT pg_advisory_lock(?)").also {
                it.setLong(1, key)
                it.executeQuery().close()
                it.close()
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

    // this is essentially MD5 hash and throwing away 1st half of result
    private fun createKey(bookmarkName: String): Long {
        return UUID.nameUUIDFromBytes(bookmarkName.toByteArray()).leastSignificantBits
    }
}