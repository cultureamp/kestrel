package com.cultureamp.eventsourcing

/**
 * Processes entities read from an [EntitySource], analogous to an [EventProcessor].
 *
 * As with event-processors, implementations should be idempotent and re-runnable from the beginning of the stream,
 * since delivery is at-least-once: a row can be delivered more than once if a processor dies after processing it but
 * before its bookmark is saved, and it will be delivered again every time it is touched (its `updated_at` moves).
 *
 * Note that, unlike an event stream, an entity table only ever exposes the *current* state of a row. A row updated
 * twice in quick succession may only be seen once, and rows are seen in `updated_at` order rather than in the order
 * they were created.
 */
interface EntityProcessor<in E> {
    fun process(entity: E, position: EntityPosition)

    companion object {
        fun <E> from(process: (E) -> Any?) = object : EntityProcessor<E> {
            override fun process(entity: E, position: EntityPosition) {
                process(entity)
            }
        }

        fun <E> from(process: (E, EntityPosition) -> Any?) = object : EntityProcessor<E> {
            override fun process(entity: E, position: EntityPosition) {
                process(entity, position)
            }
        }

        /**
         * Wraps several entity-processors up as one, so that they can share a single bookmark.
         */
        fun <E> compose(first: EntityProcessor<E>, vararg remainder: EntityProcessor<E>) = CompositeEntityProcessor(listOf(first) + remainder)
    }
}

class CompositeEntityProcessor<in E>(private val entityProcessors: List<EntityProcessor<E>>) : EntityProcessor<E> {
    override fun process(entity: E, position: EntityPosition) = entityProcessors.forEach { it.process(entity, position) }
}
