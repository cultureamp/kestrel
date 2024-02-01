package com.cultureamp.eventsourcing

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.jodatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.vendors.PostgreSQLDialect
import org.joda.time.DateTime
import java.io.Closeable
import java.sql.Connection

interface BookmarkStore {
    fun bookmarkFor(bookmarkName: String): Bookmark
    fun bookmarksFor(bookmarkNames: Set<String>): Set<Bookmark>
    fun save(bookmark: Bookmark)

    /**
     * Finds the bookmark given by bookmarkName and attempts to lock it.
     * If lock is obtained (or retained) the bookmark is returned, otherwise LockNotObtained
     */
    fun checkoutBookmark(bookmarkName: String): Either<LockNotObtained, Bookmark>
}

class RelationalDatabaseBookmarkStore(
    val db: Database,
    val table: Bookmarks = Bookmarks(),
    private val bookmarkLock: BookmarkLock = if (db.dialect is PostgreSQLDialect) createPGSessionLock(db) else NoOpBookmarkLock
) : BookmarkStore {
    override fun bookmarkFor(bookmarkName: String): Bookmark = bookmarksFor(setOf(bookmarkName)).first()

    override fun checkoutBookmark(bookmarkName: String): Either<LockNotObtained, Bookmark> =
        bookmarkFor(bookmarkName).let {
            if (bookmarkLock.tryLock(it))
                Right(it)
            else
                Left(LockNotObtained)
        }

    override fun bookmarksFor(bookmarkNames: Set<String>): Set<Bookmark> = transaction(db) {
        val matchingRows = rowsForBookmarks(bookmarkNames)
        val foundBookmarks = matchingRows.map { Bookmark(it[table.name], it[table.sequence]) }.toSet()
        val emptyBookmarks = (bookmarkNames - foundBookmarks.map { it.name }.toSet()).map { Bookmark(it, 0) }.toSet()
        foundBookmarks + emptyBookmarks
    }

    override fun save(bookmark: Bookmark): Unit = transaction(db) {
        if (!isExists(bookmark.name)) {
            table.insert {
                it[name] = bookmark.name
                it[sequence] = bookmark.sequence
                it[createdAt] = DateTime.now()
                it[updatedAt] = DateTime.now()
            }
        } else {
            table.update({ table.name eq bookmark.name }) {
                it[sequence] = bookmark.sequence
                it[updatedAt] = DateTime.now()
            }
        }
    }

    fun createSchemaIfNotExists() {
        transaction(db) {
            SchemaUtils.create(table)
        }
    }

    private fun rowsForBookmarks(bookmarkNames: Set<String>) = table.select { table.name.inList(bookmarkNames) }
    private fun isExists(bookmarkName: String) = !rowsForBookmarks(setOf(bookmarkName)).empty()
}

private fun createPGSessionLock(db: Database) = PGSessionAdvisoryBookmarkLock {
    db.connector().connection.let {
        it as? Connection ?: throw RuntimeException("Got a connection of an unknown type: $it")
    }
}

class Bookmarks(tableName: String = "bookmarks") : Table(tableName) {
    val name = varchar("name", 160)
    val sequence = long("value")
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    override val primaryKey = PrimaryKey(name)
}

data class Bookmark(val name: String, val sequence: Long)

interface BookmarkLock : Closeable {
    /**
     * Attempts to acquire the lock for this bookmark.
     * @return true If the lock is now held (whether it was freshly obtained or not), false otherwise
     */
    fun tryLock(bookmark: Bookmark): Boolean
}

object NoOpBookmarkLock: BookmarkLock {
    override fun tryLock(bookmark: Bookmark) = true
    override fun close() = Unit
}

object LockNotObtained