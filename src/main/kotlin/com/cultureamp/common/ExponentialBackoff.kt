package com.cultureamp.common

import arrow.core.Try
import arrow.core.getOrElse
import kotlin.math.min
import kotlin.math.pow

class ExponentialBackoff(
	val idleTimeMs: Long = 5_000,
	val failureBackoffMs: (attempt: Int) -> Long = exponentialBackoffAlgorithm(
		600_000, 2
	),
	val sleeper: (millis: Long) -> Unit = { Thread.sleep(it) },
	val onFailure: (error: Throwable, consecutiveFailures: Int) -> Unit
) {
	companion object {
		val exponentialBackoffAlgorithm = { upperBound: Long, base: Int ->
			{ consecutiveFailures: Int ->
				min(upperBound, base.toDouble().pow(consecutiveFailures).toLong())
			}
		}
	}

	fun run(task: () -> Action) {
		tailrec fun _run(consecutiveFailures: Int) {
			val nextAction = Try {
				task()
			}.getOrElse { Action.Error(it) }

			return when (nextAction) {
				is Action.Wait -> {
					sleeper(idleTimeMs)
					_run(0)
				}

				is Action.Continue -> {
					_run(0)
				}

				is Action.Error -> {
					onFailure(nextAction.exception, consecutiveFailures + 1)
					sleeper(failureBackoffMs(consecutiveFailures + 1))
					_run(consecutiveFailures + 1)
				}

				is Action.Stop -> {
					//Do nothing. This ends the recursive task loop.
				}
			}
		}

		return _run(0)
	}
}

sealed class Action {
	object Wait : Action()
	object Continue : Action()
	object Stop : Action()
	data class Error(val exception: Throwable) : Action()
}
