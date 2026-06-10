package io.kotest.extensions.wirespec.dsl

import community.flock.wirespec.integration.kotest.KotestWirespecGeneratorBuilder
import community.flock.wirespec.kotlin.Wirespec
import io.kotest.extensions.wirespec.runtime.CallExecutor
import io.kotest.extensions.wirespec.validation.ChannelReflection
import io.kotest.property.Arb
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@WirespecScenarioDsl
class ChannelCallBuilder<MessageT : Any> internal constructor(
    channelClass: KClass<out Wirespec.Channel>,
) {

    @PublishedApi
    internal val reflection: ChannelReflection = ChannelReflection.of(channelClass)

    internal var topicInput: Input<String>? = null
    internal var keyInput: Input<String>? = null
    internal var sendInput: Input<Any>? = null
    internal var sendOverrides: (KotestWirespecGeneratorBuilder.() -> Unit)? = null
    internal var direction: Direction? = null
    internal var expectedClass: KClass<*>? = null
    internal var customAssertion: ((Any) -> Unit)? = null
    internal var collectMode: CollectMode? = null

    fun topic(value: String): ChannelCallBuilder<MessageT> = apply { topicInput = Input.Literal(value) }
    fun topic(builder: () -> String): ChannelCallBuilder<MessageT> = apply { topicInput = Input.Lazy(builder) }
    fun key(value: String): ChannelCallBuilder<MessageT> = apply { keyInput = Input.Literal(value) }

    // ---- send terminals (eager, suspend) — return the sent payload ----

    suspend fun send(value: MessageT): MessageT {
        requireNotExpecting()
        @Suppress("UNCHECKED_CAST")
        sendInput = Input.Literal(value as Any)
        direction = Direction.Send
        @Suppress("UNCHECKED_CAST")
        return CallExecutor.executeChannel(this) as MessageT
    }

    suspend fun send(arb: Arb<MessageT>): MessageT {
        requireNotExpecting()
        @Suppress("UNCHECKED_CAST")
        sendInput = Input.FromArb(arb as Arb<Any>)
        direction = Direction.Send
        @Suppress("UNCHECKED_CAST")
        return CallExecutor.executeChannel(this) as MessageT
    }

    suspend fun send(overrides: KotestWirespecGeneratorBuilder.() -> Unit): MessageT {
        requireNotExpecting()
        sendInput = null
        sendOverrides = overrides
        direction = Direction.Send
        @Suppress("UNCHECKED_CAST")
        return CallExecutor.executeChannel(this) as MessageT
    }

    suspend fun send(): MessageT {
        requireNotExpecting()
        sendInput = null
        sendOverrides = {}
        direction = Direction.Send
        @Suppress("UNCHECKED_CAST")
        return CallExecutor.executeChannel(this) as MessageT
    }

    // ---- receive terminals (eager, suspend) ----

    suspend fun expecting(): MessageT {
        requireNotSending()
        direction = Direction.Expect
        customAssertion = null
        @Suppress("UNCHECKED_CAST")
        return CallExecutor.executeChannel(this) as MessageT
    }

    suspend inline fun <reified R : MessageT> expecting(noinline block: (R) -> Unit): R = expecting(R::class, block)

    suspend fun <R : MessageT> expecting(messageClass: KClass<R>, block: (R) -> Unit): R {
        requireNotSending()
        direction = Direction.Expect
        expectedClass = messageClass
        @Suppress("UNCHECKED_CAST")
        customAssertion = { msg -> block(msg as R) }
        @Suppress("UNCHECKED_CAST")
        return CallExecutor.executeChannel(this) as R
    }

    suspend inline fun <reified R : MessageT> collecting(count: Int, noinline block: (List<R>) -> Unit): List<R> =
        collecting(R::class, CollectMode.ByCount(count), block)

    suspend inline fun <reified R : MessageT> collecting(duration: Duration, noinline block: (List<R>) -> Unit): List<R> =
        collecting(R::class, CollectMode.ByDuration(duration), block)

    suspend fun <R : MessageT> collecting(messageClass: KClass<R>, mode: CollectMode, block: (List<R>) -> Unit): List<R> {
        requireNotSending()
        direction = Direction.Collect
        expectedClass = messageClass
        collectMode = mode
        @Suppress("UNCHECKED_CAST")
        customAssertion = { list -> block(list as List<R>) }
        @Suppress("UNCHECKED_CAST")
        return CallExecutor.executeChannel(this) as List<R>
    }

    suspend inline fun <reified R : MessageT, T> returning(noinline projection: (R) -> T): T = returning(R::class, projection)

    suspend fun <R : MessageT, T> returning(messageClass: KClass<R>, projection: (R) -> T): T {
        if (direction == null) {
            direction = Direction.Expect
            expectedClass = messageClass
        }
        val subject = CallExecutor.executeChannel(this)
        @Suppress("UNCHECKED_CAST")
        return projection(subject as R)
    }

    /** Compute (atLeast, within) — used by the executor. */
    internal fun receivePolicy(): Pair<Int, Duration> = when (val m = collectMode) {
        is CollectMode.ByCount -> m.count to (m.count.coerceAtLeast(1).seconds)
        is CollectMode.ByDuration -> 0 to m.duration
        null -> 1 to 2.seconds
    }

    @PublishedApi
    internal fun requireNotSending() = check(direction != Direction.Send) {
        "Channel ${reflection.channelName}: cannot set both `send` and `expecting`/`collecting` on the same call."
    }

    @PublishedApi
    internal fun requireNotExpecting() {
        val d = direction
        check(d != Direction.Expect && d != Direction.Collect) {
            "Channel ${reflection.channelName}: cannot set both `send` and `expecting`/`collecting` on the same call."
        }
    }

    enum class Direction { Send, Expect, Collect }

    sealed class CollectMode {
        data class ByCount(val count: Int) : CollectMode()
        data class ByDuration(val duration: Duration) : CollectMode()
    }
}
