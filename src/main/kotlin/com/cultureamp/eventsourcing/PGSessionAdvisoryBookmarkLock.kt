package com.cultureamp.eventsourcing

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

private val logger = LoggerFactory.getLogger(PGSessionAdvisoryBookmarkLock::class.java)
class PGSessionAdvisoryBookmarkLock(
    private val connectionProvider: () -> Connection,
) : BookmarkLock {
    private val mutex = Any()
    internal var connection = getConnection()
    /**
     * The set of bookmarks that we believe we hold. Note that this is only for diagnostic purposes,
     * Postgres is the source of truth. Currently we just use this to detect when locks change hands
     * so we can log it
     */
    private var locksHeld = mutableSetOf<String>()

    override fun tryLock(bookmark: Bookmark): Boolean {
        synchronized(mutex) {
            try {
                return refreshLock(connection, bookmark.name)
            } catch (e: SQLException) {
                logger.info("Failed to refresh session lock for ${bookmark.name}, resetting connection to try again", e)
                close()
                connection = getConnection()
                return refreshLock(connection, bookmark.name)
            }
        }
    }

    override fun close() {
        synchronized(mutex) {
            try {
                connection.close()
            } catch (_: SQLException) {
                // ignore
            }
            locksHeld.clear()
        }
    }

    private fun getConnection(): Connection {
        val connection = connectionProvider().also { con ->
            con.autoCommit = true
        }
        logger.debug("Got connection $connection, autocommit=${connection.autoCommit}")
        return connection
    }

    private fun refreshLock(connection: Connection, bookmarkName: String): Boolean {
        val key = createKey(bookmarkName)
        val lockNowHeld = tryLock(connection, key)
        if (lockNowHeld) {
            if (!this.locksHeld.contains(bookmarkName)) {
                logger.info("I ($connection) took the lock for $bookmarkName (key=$key)")
                this.locksHeld += bookmarkName
            }
        }
        else {
            this.locksHeld -= bookmarkName
        }
        return lockNowHeld
    }

    private fun tryLock(connection: Connection, key: Long): Boolean {
        return connection.prepareStatement("SELECT pg_try_advisory_lock(?)").use {ps ->
            ps.setLong(1, key)
            ps.executeQuery().let { rs ->
                rs.next()
                rs.getBoolean(1)
            }
        }
    }

    // this is essentially MD5 hash and throwing away 1st half of result
    private fun createKey(bookmarkName: String): Long {
        return UUID.nameUUIDFromBytes(bookmarkName.toByteArray()).leastSignificantBits
    }
}