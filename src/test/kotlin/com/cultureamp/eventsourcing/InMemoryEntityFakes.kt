package com.cultureamp.eventsourcing

import org.joda.time.DateTime

data class Widget(val id: java.util.UUID, val name: String, val updatedAt: DateTime) {
    val position = EntityPosition(updatedAt, id)
}

class InMemoryEntityBookmarkStore(private val lockObtainable: Boolean = true) : EntityBookmarkStore {
    private val positions = mutableMapOf<String, EntityPosition?>()
    val saved = mutableListOf<EntityBookmark>()

    override fun bookmarkFor(bookmarkName: String) = EntityBookmark(bookmarkName, positions[bookmarkName])

    override fun bookmarksFor(bookmarkNames: Set<String>) = bookmarkNames.map { bookmarkFor(it) }.toSet()

    override fun save(bookmark: EntityBookmark) {
        positions[bookmark.name] = bookmark.position
        saved += bookmark
    }

    override fun checkoutBookmark(bookmarkName: String): Either<LockNotObtained, EntityBookmark> =
        if (lockObtainable) Right(bookmarkFor(bookmarkName)) else Left(LockNotObtained)
}

/**
 * A well-behaved [EntitySource] over a fixed list of rows, honouring the ordering, `after` and `upTo` contract.
 */
class InMemoryEntitySource<E>(private val rows: List<PositionedEntity<E>>) : EntitySource<E>, EntityUpdatedAtStats {
    override fun getAfter(after: EntityPosition?, upTo: DateTime, batchSize: Int) = rows
        .sortedBy { it.position }
        .filter { (after == null || it.position > after) && !it.position.updatedAt.isAfter(upTo) }
        .take(batchSize)

    override fun lastUpdatedAt() = rows.maxByOrNull { it.position }?.position?.updatedAt
}
