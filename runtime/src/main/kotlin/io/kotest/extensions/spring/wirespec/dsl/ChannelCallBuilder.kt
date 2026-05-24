package io.kotest.extensions.spring.wirespec.dsl

import community.flock.wirespec.integration.kotest.KotestWirespecGeneratorBuilder
import community.flock.wirespec.kotlin.Wirespec
import io.kotest.extensions.spring.wirespec.validation.ChannelReflection
import io.kotest.property.Arb
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@WirespecScenarioDsl
class ChannelCallBuilder<MessageT : Any> internal constructor(
    private val scenario: ScenarioBuilder,
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

    internal var returningProjection: ((Any) -> Any?)? = null
    internal var returnedRef: ResultRef<Any?>? = null

    init {
        scenario.register(this)
    }

    fun topic(value: String): ChannelCallBuilder<MessageT> = apply {
        topicInput = Input.Literal(value)
    }

    fun topic(builder: () -> String): ChannelCallBuilder<MessageT> = apply {
        topicInput = Input.Lazy(builder)
    }

    fun key(value: String): ChannelCallBuilder<MessageT> = apply {
        keyInput = Input.Literal(value)
    }

    fun send(value: MessageT): ChannelCallBuilder<MessageT> = apply {
        requireNotExpecting()
        @Suppress("UNCHECKED_CAST")
        sendInput = Input.Literal(value as Any)
        direction = Direction.Send
    }

    fun send(arb: Arb<MessageT>): ChannelCallBuilder<MessageT> = apply {
        requireNotExpecting()
        @Suppress("UNCHECKED_CAST")
        sendInput = Input.FromArb(arb as Arb<Any>)
        direction = Direction.Send
    }

    /**
     * Generate the payload by walking the Wirespec IR with [overrides]
     * applied — same machinery as `EndpointCallBuilder.body { ... }`. The
     * lambda's receiver exposes `registerPath(...)` so individual fields
     * can be pinned to Arbs.
     */
    fun send(overrides: KotestWirespecGeneratorBuilder.() -> Unit): ChannelCallBuilder<MessageT> = apply {
        requireNotExpecting()
        sendInput = null
        sendOverrides = overrides
        direction = Direction.Send
    }

    inline fun <reified R : MessageT> expecting(noinline block: (R) -> Unit): ChannelCallBuilder<MessageT> =
        expecting(R::class, block)

    fun <R : MessageT> expecting(messageClass: KClass<R>, block: (R) -> Unit): ChannelCallBuilder<MessageT> = apply {
        requireNotSending()
        direction = Direction.Expect
        expectedClass = messageClass
        @Suppress("UNCHECKED_CAST")
        customAssertion = { msg -> block(msg as R) }
    }

    /** Single-message expect without an assertion block. */
    fun expecting(): ChannelCallBuilder<MessageT> = apply {
        requireNotSending()
        direction = Direction.Expect
        customAssertion = null
    }

    inline fun <reified R : MessageT> collecting(count: Int, noinline block: (List<R>) -> Unit): ChannelCallBuilder<MessageT> =
        collecting(R::class, CollectMode.ByCount(count), block)

    inline fun <reified R : MessageT> collecting(duration: Duration, noinline block: (List<R>) -> Unit): ChannelCallBuilder<MessageT> =
        collecting(R::class, CollectMode.ByDuration(duration), block)

    fun <R : MessageT> collecting(messageClass: KClass<R>, mode: CollectMode, block: (List<R>) -> Unit): ChannelCallBuilder<MessageT> = apply {
        requireNotSending()
        direction = Direction.Collect
        expectedClass = messageClass
        collectMode = mode
        @Suppress("UNCHECKED_CAST")
        customAssertion = { list -> block(list as List<R>) }
    }

    inline fun <reified R : MessageT, T> returning(noinline projection: (R) -> T): ResultRef<T> =
        returning(R::class, projection)

    fun <R : MessageT, T> returning(messageClass: KClass<R>, projection: (R) -> T): ResultRef<T> {
        if (direction == null) {
            direction = Direction.Expect
            expectedClass = messageClass
        }
        val ref = ResultRef<T>(label = "${reflection.channelName}.${messageClass.simpleName ?: "msg"}")
        @Suppress("UNCHECKED_CAST")
        returnedRef = ref as ResultRef<Any?>
        returningProjection = { msg ->
            @Suppress("UNCHECKED_CAST")
            projection(msg as R)
        }
        return ref
    }

    /** Compute (atLeast, within) — used by the runner. */
    internal fun receivePolicy(): Pair<Int, Duration> = when (val m = collectMode) {
        is CollectMode.ByCount -> m.count to (m.count.coerceAtLeast(1).seconds)
        is CollectMode.ByDuration -> 0 to m.duration
        null -> 1 to 2.seconds
    }

    private fun requireNotSending() = check(direction != Direction.Send) {
        "Channel ${reflection.channelName}: cannot set both `send` and `expecting`/`collecting` on the same call."
    }

    private fun requireNotExpecting() {
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
