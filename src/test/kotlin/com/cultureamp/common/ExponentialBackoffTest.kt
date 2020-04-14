package com.cultureamp.common

import com.cultureamp.common.Action.*
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class ExponentialBackoffTest : DescribeSpec({
	describe("ExponentialBackoff") {
		it("Does not retry a successful task") {
			val worker = ExponentialBackoff(idleTimeMs = 0,
				failureBackoffMs = { 0 },
				sleeper = {},
				onFailure = { _: Throwable, _: Int -> Unit })

			var runCount = 0
			worker.run {
				runCount += 1

				Stop
			}

			runCount shouldBe 1
		}

		it("Will rerun the task without sleeping on Continue") {
			var sleepCount = 0
			val worker = ExponentialBackoff(idleTimeMs = 0,
				failureBackoffMs = { 0 },
				sleeper = { sleepCount += 1 },
				onFailure = { _: Throwable, _: Int -> Unit })

			var runCount = 0
			worker.run {
				runCount += 1

				if (runCount < 5) {
					Continue
				} else {
					Stop
				}
			}

			runCount shouldBe 5
			sleepCount shouldBe 0
		}

		it("Will rerun the task after sleeping on Wait") {
			var sleepCount = 0
			val worker = ExponentialBackoff(idleTimeMs = 0,
				failureBackoffMs = { 0 },
				sleeper = { sleepCount += 1 },
				onFailure = { _: Throwable, _: Int -> Unit })

			var runCount = 0
			worker.run {
				runCount += 1

				if (runCount < 5) {
					Wait
				} else {
					Stop
				}
			}

			runCount shouldBe 5
			sleepCount shouldBe 4
		}
	}

	describe("Failed task") {
		it("Will log the reason for failure") {
			var errorMessage = ""
			val worker = ExponentialBackoff(idleTimeMs = 0,
				failureBackoffMs = { 0 },
				sleeper = {},
				onFailure = { e: Throwable, _: Int -> errorMessage = e.message.toString() })

			var shouldContinue = true
			worker.run {
				if (shouldContinue) {
					shouldContinue = false
					throw Throwable("boom")
				} else {
					Stop
				}
			}

			errorMessage shouldBe "boom"
		}

		it("Will retry the failed task") {
			val worker = ExponentialBackoff(idleTimeMs = 0,
				failureBackoffMs = { 0 },
				sleeper = {},
				onFailure = { _: Throwable, _: Int -> Unit })

			var runCount = 0
			worker.run {
				runCount += 1

				if (runCount < 3) {
					throw Throwable("boom")
				} else {
					Stop
				}
			}

			runCount shouldBe 3
		}

		it("Will sleep on failed task") {
			var sleepCount = 0
			val worker = ExponentialBackoff(idleTimeMs = 0,
				failureBackoffMs = { 0 },
				sleeper = { sleepCount += 1 },
				onFailure = { _: Throwable, _: Int -> Unit })

			var runCount = 0
			worker.run {
				runCount += 1
				if (runCount < 3) {
					throw Throwable("boom")
				} else {
					Stop
				}
			}
			sleepCount shouldBe 2
		}
	}
})
