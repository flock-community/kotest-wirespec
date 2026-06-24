package io.kotest.extensions.wirespec.example.support

import community.flock.wirespec.integration.kotest.ChannelTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import java.time.Duration as JavaDuration
import java.util.Properties
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * A [ChannelTransport] over the (embedded) Kafka broker, spanning every [topics] the
 * example uses. One consumer is assigned per topic and positioned at the log end on
 * construction, so `receive` only observes events produced *during the spec* — both
 * those a scenario `send`s itself and those the application publishes in reaction to an
 * endpoint call. The single shared producer publishes raw JSON (the channel context does
 * the (de)serialization); the app's own `@KafkaListener`/`KafkaTemplate` operate on the
 * same topics independently.
 */
class KafkaChannelTransport(
    bootstrapServers: String,
    topics: List<String>,
) : ChannelTransport, AutoCloseable {

    private val producer = KafkaProducer<String, String>(
        Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
        },
    )

    // One consumer per topic, each pinned to that topic's partitions at the log end —
    // so polling one topic never swallows another topic's records.
    private val consumers: Map<String, KafkaConsumer<String, String>> = topics.associateWith { topic ->
        KafkaConsumer<String, String>(
            Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
                put(ConsumerConfig.GROUP_ID_CONFIG, "scenario-${UUID.randomUUID()}")
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
            },
        ).apply {
            val partitions = partitionsFor(topic).map { TopicPartition(it.topic(), it.partition()) }
            assign(partitions)
            seekToEnd(partitions)
            // Force the lazy seek to resolve now (at the current log end) so events
            // published after construction — but before the first receive — are seen.
            partitions.forEach { position(it) }
        }
    }

    /**
     * Move every consumer to the current log end and resolve the position eagerly. Lets a
     * spec start each scenario "watching from now", so `receive` only returns events
     * published afterwards (not leftovers from earlier tests on the shared broker).
     */
    fun seekToEnd() {
        consumers.values.forEach { consumer ->
            val partitions = consumer.assignment()
            consumer.seekToEnd(partitions)
            partitions.forEach { consumer.position(it) }
        }
    }

    override suspend fun publish(topic: String, key: String?, body: ByteArray): Unit =
        withContext(Dispatchers.IO) {
            producer.send(ProducerRecord(topic, key, String(body))).get()
            Unit
        }

    override suspend fun receive(topic: String, count: Int, timeout: Duration): List<ByteArray> =
        withContext(Dispatchers.IO) {
            val consumer = consumers[topic]
                ?: error("KafkaChannelTransport has no consumer for topic '$topic'")
            val deadline = TimeSource.Monotonic.markNow() + timeout
            val out = mutableListOf<ByteArray>()
            while (out.size < count && deadline.hasNotPassedNow()) {
                consumer.poll(JavaDuration.ofMillis(200)).forEach { out.add(it.value().toByteArray()) }
            }
            out
        }

    override fun close() {
        producer.close()
        consumers.values.forEach { it.close() }
    }
}
