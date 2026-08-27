package com.cultureamp.eventsourcing

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.javatime.datetime
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDateTime
import java.time.ZoneOffset

val defaultEntityBookmarksTableName = "entity_bookmarks"

/** Stores the [EntityPosition] of the last processed row for an [EntityProcessor], analogous to [BookmarkStore]. */
interface EntityBookmarkStore {
    fun bookmarkFor(bookmarkName: String): EntityBookmark
    fun bookmarksFor(bookmarkNames: Set<String>): Set<EntityBookmark>

    fun save(bookmarkName: String, position: EntityPosition)

    /**
     * Finds the bookmark of this name and attempts to lock it, returning it if the lock is obtained or retained.
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

    override fun save(bookmarkName: String, position: EntityPosition): Unit = transaction(db) {
        if (!isExists(bookmarkName)) {
            table.insert {
                it[name] = bookmarkName
                it[entityLastUpdatedAt] = position.updatedAt
                it[entityLastId] = position.id
                it[bookmarkCreatedAt] = nowUtc()
                it[bookmarkUpdatedAt] = nowUtc()
            }
        } else {
            table.update({ table.name eq bookmarkName }) {
                it[entityLastUpdatedAt] = position.updatedAt
                it[entityLastId] = position.id
                it[bookmarkUpdatedAt] = nowUtc()
            }
        }
    }

    fun createSchemaIfNotExists() {
        transaction(db) {
            SchemaUtils.create(table)
        }
    }

    private fun ResultRow.toPosition() = EntityPosition(this[table.entityLastUpdatedAt], this[table.entityLastId])

    private fun rowsForBookmarks(bookmarkNames: Set<String>) = table.selectAll().where { table.name.inList(bookmarkNames) }
    private fun isExists(bookmarkName: String) = !rowsForBookmarks(setOf(bookmarkName)).empty()
}

class EntityBookmarks(tableName: String = defaultEntityBookmarksTableName) : Table(tableName) {
    val name = varchar("name", 160)

    /** Naive, like the source column a position is read from, so a position is stored exactly as it was read. */
    val entityLastUpdatedAt = datetime("entity_last_updated_at")
    val entityLastId = javaUUID("entity_last_id")
    val bookmarkCreatedAt = datetime("bookmark_created_at")
    val bookmarkUpdatedAt = datetime("bookmark_updated_at")
    override val primaryKey = PrimaryKey(name)
}

/**
 * A null [position] means nothing has been processed yet and the processor starts from the beginning of the table, the
 * equivalent of a [Bookmark] with sequence `0`. The stored columns are not nullable — a row only exists once something
 * has been processed — so the null lives in this type rather than in the table.
 */
data class EntityBookmark(val name: String, val position: EntityPosition?)

private fun nowUtc(): LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
