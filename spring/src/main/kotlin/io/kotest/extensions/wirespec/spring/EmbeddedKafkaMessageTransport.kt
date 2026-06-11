package io.kotest.extensions.wirespec.spring

import io.kotest.extensions.wirespec.channel.IncomingRecord
import io.kotest.extensions.wirespec.channel.MessageTransport
import io.kotest.extensions.wirespec.channel.OutgoingRecord
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.context.ApplicationContext
import org.springframework.kafka.test.EmbeddedKafkaBroker
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * [MessageTransport] backed by Spring's [EmbeddedKafkaBroker]. Self-contained:
 *  - owns its own [KafkaProducer]<String, ByteArray>
 *  - opens a short-lived [KafkaConsumer]<String, ByteArray> per receive() call
 *    (random group.id, auto.offset.reset=earliest)
 *
 * Doesn't depend on the application's own `KafkaTemplate` / `ProducerFactory`
 * bean wiring — the only thing needed in the Spring context is the
 * `EmbeddedKafkaBroker` that `@EmbeddedKafka` registers.
 */
class EmbeddedKafkaMessageTransport(
    applicationContext: ApplicationContext,
) : MessageTransport {

    private val brokers: String =
        applicationContext.getBean(EmbeddedKafkaBroker::class.java).brokersAsString

    private val producer: KafkaProducer<String, ByteArray> by lazy {
        KafkaProducer(
            mapOf<String, Any>(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to brokers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to ByteArraySerializer::class.java,
                ProducerConfig.CLIENT_ID_CONFIG to "wirespec-test-${UUID.randomUUID()}",
            )
        )
    }

    override suspend fun publish(record: OutgoingRecord) {
        producer.send(ProducerRecord(record.topic, record.key, record.body))
            .get(5, TimeUnit.SECONDS)
        producer.flush()
    }

    override suspend fun receive(topic: String, atLeast: Int, within: Duration): List<IncomingRecord> {
        val consumer = KafkaConsumer<String, ByteArray>(
            mapOf<String, Any>(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to brokers,
                ConsumerConfig.GROUP_ID_CONFIG to "wirespec-test-${UUID.randomUUID()}",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
            )
        )
        consumer.use { c ->
            c.subscribe(listOf(topic))
            val mark = TimeSource.Monotonic.markNow()
            val collected = mutableListOf<IncomingRecord>()
            while (collected.size < atLeast && mark.elapsedNow() < within) {
                val remaining = (within - mark.elapsedNow()).coerceAtLeast(Duration.ZERO)
                val pollMillis = remaining.inWholeMilliseconds.coerceAtMost(200L)
                val polled = c.poll(java.time.Duration.ofMillis(pollMillis))
                for (r in polled) {
                    if (r.topic() == topic) collected += IncomingRecord(r.topic(), r.key(), r.value())
                }
            }
            return collected.toList()
        }
    }
}
