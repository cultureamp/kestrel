package com.cultureamp.eventsourcing

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.jodatime.datetime
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.vendors.PostgreSQLDialect
import org.joda.time.DateTime

val defaultEntityBookmarksTableName = "entity_bookmarks"

/**
 * Stores the [EntityPosition] of the last processed row for a given [EntityProcessor], analogous to [BookmarkStore].
 */
interface EntityBookmarkStore {
    fun bookmarkFor(bookmarkName: String): EntityBookmark
    fun bookmarksFor(bookmarkNames: Set<String>): Set<EntityBookmark>
    fun save(bookmark: EntityBookmark)

    /**
     * Finds the bookmark given by bookmarkName and attempts to lock it.
     * If lock is obtained (or retained) the bookmark is returned, otherwise LockNotObtained
     */
    fun checkoutBookmark(bookmarkName: String): Either<LockNotObtained, EntityBookmark>
}

/**
 * Note that bookmark locking shares a key-space with [RelationalDatabaseBookmarkStore], so an entity-bookmark and an
 * event-bookmark of the same name will lock each other out. Give them distinct names.
 */
class RelationalDatabaseEntityBookmarkStore(
    val db: Database,
    val table: EntityBookmarks = EntityBookmarks(),
    private val bookmarkLock: BookmarkLock = if (db.dialect is PostgreSQLDialect) createPGSessionLock(db) else NoOpBookmarkLock,
) : EntityBookmarkStore {
    override fun bookmarkFor(bookmarkName: String): EntityBookmark = bookmarksFor(setOf(bookmarkName)).first()

    override fun checkoutBookmark(bookmarkName: String): Either<LockNotObtained, EntityBookmark> =
        bookmarkFor(bookmarkName).let {
            if (bookmarkLock.tryLock(it.name))
                Right(it)
            else
                Left(LockNotObtained)
        }

    override fun bookmarksFor(bookmarkNames: Set<String>): Set<EntityBookmark> = transaction(db) {
        val matchingRows = rowsForBookmarks(bookmarkNames)
        val foundBookmarks = matchingRows.map { EntityBookmark(it[table.name], it.toPosition()) }.toSet()
        val emptyBookmarks = (bookmarkNames - foundBookmarks.map { it.name }.toSet()).map { EntityBookmark(it, null) }.toSet()
        foundBookmarks + emptyBookmarks
    }

    override fun save(bookmark: EntityBookmark): Unit = transaction(db) {
        if (!isExists(bookmark.name)) {
            table.insert {
                it[name] = bookmark.name
                it[lastUpdatedAt] = bookmark.position?.updatedAt
                it[lastId] = bookmark.position?.id
                it[createdAt] = DateTime.now()
                it[updatedAt] = DateTime.now()
            }
        } else {
            table.update({ table.name eq bookmark.name }) {
                it[lastUpdatedAt] = bookmark.position?.updatedAt
                it[lastId] = bookmark.position?.id
                it[updatedAt] = DateTime.now()
            }
        }
    }

    fun createSchemaIfNotExists() {
        transaction(db) {
            SchemaUtils.create(table)
        }
    }

    private fun ResultRow.toPosition(): EntityPosition? {
        val lastUpdatedAt = this[table.lastUpdatedAt]
        val lastId = this[table.lastId]
        return if (lastUpdatedAt != null && lastId != null) EntityPosition(lastUpdatedAt, lastId) else null
    }

    private fun rowsForBookmarks(bookmarkNames: Set<String>) = table.select { table.name.inList(bookmarkNames) }
    private fun isExists(bookmarkName: String) = !rowsForBookmarks(setOf(bookmarkName)).empty()
}

class EntityBookmarks(tableName: String = defaultEntityBookmarksTableName) : Table(tableName) {
    val name = varchar("name", 160)
    val lastUpdatedAt = datetime("last_updated_at").nullable()
    val lastId = uuid("last_id").nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    override val primaryKey = PrimaryKey(name)
}

/**
 * A null [position] means nothing has been processed yet, i.e. the processor will start from the beginning of the
 * table. It is the [EntityBookmark] equivalent of a [Bookmark] with sequence `0`.
 */
data class EntityBookmark(val name: String, val position: EntityPosition?)
