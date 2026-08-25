package com.cultureamp.eventsourcing

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime
import java.util.UUID

class EntityPositionTest : DescribeSpec({
    val baseTime = LocalDateTime.of(2026, 8, 10, 9, 0, 0, 0)
    val id = UUID.randomUUID()

    describe("compareTo") {
        it("orders on updated-at first") {
            val earlier = EntityPosition(baseTime, UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"))
            val later = EntityPosition(baseTime.plusSeconds(1), UUID.fromString("00000000-0000-0000-0000-000000000000"))

            (earlier < later) shouldBe true
        }

        it("distinguishes positions differing only below the millisecond") {
            val earlier = EntityPosition(baseTime.plusNanos(123_000_000), id)
            val later = EntityPosition(baseTime.plusNanos(123_456_000), id)

            (earlier < later) shouldBe true
            earlier shouldBe earlier.copy()
        }

        it("tiebreaks on id as an unsigned 128-bit value, matching how Postgres orders uuids") {
            // 0x7f... is signed-positive and 0x80... signed-negative, so a signed comparison would order these the
            // other way around
            val lower = EntityPosition(baseTime, UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff"))
            val higher = EntityPosition(baseTime, UUID.fromString("80000000-0000-0000-0000-000000000000"))

            (lower < higher) shouldBe true
        }

        it("tiebreaks on the least significant bits when the most significant are equal") {
            val lower = EntityPosition(baseTime, UUID.fromString("00000000-0000-0000-7fff-ffffffffffff"))
            val higher = EntityPosition(baseTime, UUID.fromString("00000000-0000-0000-8000-000000000000"))

            (lower < higher) shouldBe true
        }
    }

    describe("equality") {
        it("agrees with compareTo, which is what bookmark comparisons rely on") {
            val position = EntityPosition(baseTime.plusNanos(123_456_000), id)
            val same = EntityPosition(baseTime.plusNanos(123_456_000), id)

            position shouldBe same
            position.compareTo(same) shouldBe 0
            position.hashCode() shouldBe same.hashCode()
        }

        it("is not equal when only the id differs") {
            EntityPosition(baseTime, id) shouldBe EntityPosition(baseTime, id)
            (EntityPosition(baseTime, id) == EntityPosition(baseTime, UUID.randomUUID())) shouldBe false
        }

        it("is not equal when only the updated-at differs") {
            (EntityPosition(baseTime, id) == EntityPosition(baseTime.plusNanos(1), id)) shouldBe false
        }
    }
})
