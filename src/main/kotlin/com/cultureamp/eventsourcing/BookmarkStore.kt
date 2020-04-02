package com.cultureamp.eventsourcing

interface BookmarkStore {
    fun findOrCreate(bookmarkName: String): Bookmark
    fun save(bookmarkName: String, bookmark: Bookmark)

}

class InMemoryBookmarkStore : BookmarkStore {
    private val map = hashMapOf<String, Bookmark>().withDefault { Bookmark(0) }

    override fun findOrCreate(bookmarkName: String) = map.getValue(bookmarkName)

    override fun save(bookmarkName: String, bookmark: Bookmark) {
        map[bookmarkName] = bookmark
    }
}

data class Bookmark(val sequence: Long)
