package com.cultureamp.eventsourcing

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.jodatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime

interface BookmarkStore {
    fun bookmarkFor(bookmarkName: BookmarkName): Bookmark
    fun bookmarksFor(bookmarkNames: Set<BookmarkName>): Set<Bookmark>
    fun save(bookmark: Bookmark)
}

class RelationalDatabaseBookmarkStore(val db: Database, val table: Bookmarks = Bookmarks()) : BookmarkStore {
    override fun bookmarkFor(bookmarkName: BookmarkName): Bookmark = bookmarksFor(setOf(bookmarkName)).first()

    override fun bookmarksFor(bookmarkNames: Set<BookmarkName>): Set<Bookmark> = transaction(db) {
        val matchingRows = rowsForBookmarks(bookmarkNames)
        val foundBookmarks = matchingRows.map { Bookmark(BookmarkName(it[table.name]), it[table.sequence]) }.toSet()
        val emptyBookmarks = (bookmarkNames - foundBookmarks.map { it.name }.toSet()).map { Bookmark(it, 0) }.toSet()
        foundBookmarks + emptyBookmarks
    }

    override fun save(bookmark: Bookmark): Unit = transaction(db) {
        if (!isExists(bookmark.name)) {
            table.insert {
                it[name] = bookmark.name.toString()
                it[sequence] = bookmark.sequence
                it[createdAt] = DateTime.now()
                it[updatedAt] = DateTime.now()
            }
        } else {
            table.update({ table.name eq bookmark.name.toString() }) {
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

    private fun rowsForBookmarks(bookmarkNames: Set<BookmarkName>) =
        table.select { table.name.inList(bookmarkNames.map(BookmarkName::toString)) }

    private fun isExists(bookmarkName: BookmarkName) = !rowsForBookmarks(setOf(bookmarkName)).empty()
}

class CachingBookmarkStore(private val delegate: BookmarkStore) : BookmarkStore {
    private val cache = HashMap<BookmarkName, Bookmark>()

    override fun bookmarkFor(bookmarkName: BookmarkName): Bookmark {
        return cache[bookmarkName] ?: delegate.bookmarkFor(bookmarkName).apply { cache[bookmarkName] = this }
    }

    override fun bookmarksFor(bookmarkNames: Set<BookmarkName>) = bookmarkNames.map { bookmarkFor(it) }.toSet()

    override fun save(bookmark: Bookmark) {
        cache[bookmark.name] = bookmark
        delegate.save(bookmark)
    }
}

class Bookmarks(tableName: String = "bookmarks") : Table(tableName) {
    val name = varchar("name", 160)
    val sequence = long("value")
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    override val primaryKey = PrimaryKey(name)
}

data class Bookmark(val name: BookmarkName, val sequence: Long)
