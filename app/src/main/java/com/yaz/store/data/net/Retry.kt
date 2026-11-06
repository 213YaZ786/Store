package com.yaz.store.data.net

// A call tried again after a failure, with a growing wait between the tries. Ported from michaelbull's kotlin-retry (ISC,
// https://github.com/michaelbull/kotlin-retry), the library GrapheneOS' App Store retries its repo update with (core/PackageStates.kt,
// requestRepoUpdateRetrying): retry, RetryPolicy, binaryExponentialBackoff and stopAtAttempts, with the few types they stand on
// (Attempt, FailedAttempt, RetryInstruction and its three instructions, saturatedMultiply, binaryExponential, stopIf).
// Kept in one file because Store uses only these; the library's names are kept.
// not ported: saturatedAdd (only the jitter and cumulative delay policies use it)
// not ported: fullJitterBackoff, equalJitterBackoff, decorrelatedJitterBackoff (Store retries with binaryExponentialBackoff, as GrapheneOS)
// not ported: stopAtRetries, stopAtDelay, stopAtCumulativeDelay (stopAtAttempts is the stop GrapheneOS uses)
// not ported: constantDelay, delayAtLeast, delayAtMost, delayIn (Delay.kt: no caller)
// not ported: continueIf, continueUnless, stopUnless (Predicate.kt: only stopIf, which stopAtAttempts needs)
// not ported: @IgnorableReturnValue (an annotation of Kotlin 2.2 for the library's own warnings)
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.cancellation.CancellationException
import kotlin.jvm.JvmInline
import kotlin.math.min
import kotlinx.coroutines.delay

// https://github.com/michaelbull/kotlin-retry/blob/master/kotlin-retry/src/commonMain/kotlin/com/github/michaelbull/retry/Math.kt#saturatedMultiply
/**
 * Multiplies [this] value by the [other] value, unless it would overflow in which case [Long.MAX_VALUE] is returned.
 *
 * @throws IllegalArgumentException if either [this] or the [other] value are negative.
 */
infix fun Long.saturatedMultiply(other: Long): Long {
    require(this >= 0 && other >= 0) { "saturatedMultiply is optimized for non-negative longs: $this x $other" }

    return if (this == 0L || other <= Long.MAX_VALUE / this) {
        this * other
    } else {
        Long.MAX_VALUE
    }
}

// https://github.com/michaelbull/kotlin-retry/blob/master/kotlin-retry/src/commonMain/kotlin/com/github/michaelbull/retry/Math.kt#binaryExponential
/** Returns 2 to the power of [this], unless it would overflow in which case [Long.MAX_VALUE] is returned. */
fun Int.binaryExponential(): Long {
    return if (this < Long.SIZE_BITS - 1) {
        1L shl this
    } else {
        Long.MAX_VALUE
    }
}

// https://github.com/michaelbull/kotlin-retry/blob/master/kotlin-retry/src/commonMain/kotlin/com/github/michaelbull/retry/instruction/RetryInstruction.kt#RetryInstruction
/** An instruction for [retry] to follow after evaluating a [RetryPolicy]. */
@JvmInline
value class RetryInstruction internal constructor(
    val delayMillis: Long,
) {
    operator fun component1(): Long = delayMillis
}

// https://github.com/michaelbull/kotlin-retry/blob/master/kotlin-retry/src/commonMain/kotlin/com/github/michaelbull/retry/instruction/RetryAfter.kt#RetryAfter
/**
 * Instructs [retry] to retry the operation after [delayMillis].
 *
 * @throws IllegalArgumentException if [delayMillis] is not positive.
 */
@Suppress("FunctionName")
fun RetryAfter(delayMillis: Long): RetryInstruction {
    require(delayMillis > 0) { "delayMillis must be positive, but was: $delayMillis" }
    return RetryInstruction(delayMillis)
}

// https://github.com/michaelbull/kotlin-retry/blob/master/kotlin-retry/src/commonMain/kotlin/com/github/michaelbull/retry/instruction/ContinueRetrying.kt#ContinueRetrying
/** Instructs [retry] to continue attempting. */
val ContinueRetrying: RetryInstruction = RetryInstruction(0L)

// https://github.com/michaelbull/kotlin-retry/blob/master/kotlin-retry/src/commonMain/kotlin/com/github/michaelbull/retry/instruction/StopRetrying.kt#StopRetrying
/** Instructs [retry] to stop attempting retries. */
val StopRetrying: RetryInstruction = RetryInstruction(-1L)

// https://github.com/michaelbull/kotlin-retry/blob/master/kotlin-retry/src/commonMain/kotlin/com/github/michaelbull/retry/attempt/FailedAttempt.kt#FailedAttempt
data class FailedAttempt<E>(
    /** The attempt failure. */
    val failure: E,
    /** The zero-based attempt number. */
    val number: Int,
    /** The delay between this attempt and the previous. */
    val previousDelay: Long,
    /** The cumulative delay across all attempts. */
    val cumulativeDelay: Long,
)

// https://github.com/michaelbull/kotlin-retry/blob/master/kotlin-retry/src/commonMain/kotlin/com/github/michaelbull/retry/attempt/Attempt.kt#firstAttempt
fun firstAttempt(): Attempt {
    return Attempt(
        number = 0,
        previousDelay = 0,
        cumulativeDelay = 0,
    )
}

// https://github.com/michaelbull/kotlin-retry/blob/master/kotlin-retry/src/commonMain/kotlin/com/github/michaelbull/retry/attempt/Attempt.kt#Attempt
data class Attempt(
    /** The zero-based attempt number. */
    private var number: Int,
    /** The delay between this attempt and the previous. */
    private var previousDelay: Long,
    /** The cumulative delay across all attempts. */
    private var cumulativeDelay: Long,
) {

    init {
        require(number >= 0) { "number must be non-negative, but was $number" }
        require(previousDelay >= 0) { "previousDelay must be non-negative, but was $previousDelay" }
        require(cumulativeDelay >= 0) { "cumulativeDelay must be non-negative, but was $cumulativeDelay" }
    }

    fun <E> failedWith(failure: E): FailedAttempt<E> {
        return FailedAttempt(
            failure = failure,
            number = number,
            previousDelay = previousDelay,
            cumulativeDelay = cumulativeDelay,
        )
    }

    fun retryImmediately() {
        number += 1
        previousDelay = 0
    }

    fun retryAfter(delayMillis: Long) {
        number += 1
        previousDelay = delayMillis
        cumulativeDelay += delayMillis
    }
}

// https://github.com/michaelbull/kotlin-retry/blob/master/kotlin-retry/src/commonMain/kotlin/com/github/michaelbull/retry/policy/RetryPolicy.kt#RetryPolicy
fun interface RetryPolicy<E> {
    operator fun invoke(attempt: FailedAttempt<E>): RetryInstruction
}

// https://github.com/michaelbull/kotlin-retry/blob/master/kotlin-retry/src/commonMain/kotlin/com/github/michaelbull/retry/policy/RetryPolicy.kt#RetryPolicy
/**
 * Merges the [previous](prev) and [current](curr) [RetryPolicy] into a single [RetryPolicy].
 *
 * If either policy returns an instruction to [StopRetrying], the combined policy will return an instruction to [StopRetrying].
 *
 * If both policies return an instruction to [RetryAfter] a given delay, the combined policy will return an instruction to
 * [RetryAfter] the larger delay.
 */
fun <E> RetryPolicy(prev: RetryPolicy<E>, curr: RetryPolicy<E>): RetryPolicy<E> {
    return RetryPolicy { attempt ->
        val prevInstruction = prev(attempt)
        val currInstruction = curr(attempt)

        val eitherStopping = prevInstruction == StopRetrying || currInstruction == StopRetrying
        val bothContinuing = prevInstruction == ContinueRetrying && currInstruction == ContinueRetrying

        when {
            eitherStopping -> StopRetrying
            bothContinuing -> ContinueRetrying
            else -> {
                val prevDelay = prevInstruction.delayMillis
                val currDelay = currInstruction.delayMillis
                val greatestDelay = maxOf(prevDelay, currDelay)
                RetryAfter(greatestDelay)
            }
        }
    }
}

fun <E> RetryPolicy(first: RetryPolicy<E>, second: RetryPolicy<E>, vararg rest: RetryPolicy<E>): RetryPolicy<E> {
    return listOf(first, second, *rest).reduce { prev, curr -> RetryPolicy(prev, curr) }
}

operator fun <E> RetryPolicy<E>.plus(other: RetryPolicy<E>): RetryPolicy<E> {
    return RetryPolicy(this, other)
}

// https://github.com/michaelbull/kotlin-retry/blob/master/kotlin-retry/src/commonMain/kotlin/com/github/michaelbull/retry/policy/Backoff.kt#binaryExponentialBackoff
/**
 * Creates a [RetryPolicy] that returns an instruction to [RetryAfter] an amount of milliseconds between [min] and [max]
 * inclusive, increasing the delay by 2 to the power of the number of attempts made.
 *
 * @throws IllegalArgumentException if [min] or [max] are not positive.
 */
fun <E> binaryExponentialBackoff(
    min: Long,
    max: Long,
): RetryPolicy<E> {
    require(min > 0) { "min must be positive, but was $min" }
    require(max > 0) { "max must be positive, but was $max" }

    return RetryPolicy { attempt ->
        val delay = min(max, min saturatedMultiply attempt.number.binaryExponential())
        RetryAfter(delay)
    }
}

fun <E> binaryExponentialBackoff(range: LongRange): RetryPolicy<E> {
    return binaryExponentialBackoff(range.first, range.last)
}

// https://github.com/michaelbull/kotlin-retry/blob/master/kotlin-retry/src/commonMain/kotlin/com/github/michaelbull/retry/policy/Predicate.kt#RetryPredicate
fun interface RetryPredicate<E> {
    operator fun invoke(attempt: FailedAttempt<E>): Boolean
}

// https://github.com/michaelbull/kotlin-retry/blob/master/kotlin-retry/src/commonMain/kotlin/com/github/michaelbull/retry/policy/Predicate.kt#stopIf
/**
 * Creates a [RetryPolicy] that returns an instruction to [StopRetrying] if the [FailedAttempt] satisfies the given [predicate], or an
 * instruction to [ContinueRetrying] if it doesn't.
 */
fun <E> stopIf(predicate: RetryPredicate<E>): RetryPolicy<E> {
    return RetryPolicy { attempt ->
        if (predicate(attempt)) {
            StopRetrying
        } else {
            ContinueRetrying
        }
    }
}

// https://github.com/michaelbull/kotlin-retry/blob/master/kotlin-retry/src/commonMain/kotlin/com/github/michaelbull/retry/policy/Stop.kt#stopAtAttempts
/**
 * Creates a [RetryPolicy] that returns an instruction to [StopRetrying] after the number of attempts reaches the specified [count].
 * The first invocation is counted towards the limit: stopAtAttempts(3) runs the block three times.
 *
 * @throws IllegalArgumentException if [count] is not positive.
 */
fun <E> stopAtAttempts(count: Int): RetryPolicy<E> {
    require(count > 0) { "count must be positive, but was $count" }

    return stopIf { attempt ->
        attempt.number + 1 >= count
    }
}

// https://github.com/michaelbull/kotlin-retry/blob/master/kotlin-retry/src/commonMain/kotlin/com/github/michaelbull/retry/Retry.kt#retry
/**
 * Calls the specified function [block] and returns its result if invocation was successful, catching any [Throwable]
 * exception that was thrown from the [block] function execution and retrying the invocation according to
 * instructions from the [policy].
 */
@OptIn(ExperimentalContracts::class)
suspend inline fun <T> retry(policy: RetryPolicy<Throwable>, block: () -> T): T {
    contract {
        callsInPlace(block, InvocationKind.AT_LEAST_ONCE)
    }

    var attempt: Attempt? = null

    while (true) {
        try {
            return block()
        } catch (failure: Throwable) {
            /* avoid swallowing CancellationExceptions */
            if (failure is CancellationException) {
                throw failure
            } else {
                if (attempt == null) {
                    attempt = firstAttempt()
                }

                val failedAttempt = attempt.failedWith(failure)

                when (val instruction = policy(failedAttempt)) {
                    StopRetrying -> {
                        throw failure
                    }

                    ContinueRetrying -> {
                        attempt.retryImmediately()
                    }

                    else -> {
                        val (delayMillis) = instruction

                        @Suppress("ConvertLongToDuration")
                        delay(delayMillis)

                        attempt.retryAfter(delayMillis)
                    }
                }
            }
        }
    }
}
