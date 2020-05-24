package docs.javadsl;

import akka.actor.ActorSystem;
import akka.kafka.KafkaPorts;
import akka.kafka.testkit.javadsl.EmbeddedKafkaTest;
import akka.kafka.testkit.javadsl.KafkaProbe;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import java.time.Duration;
import java.util.UUID;

import static org.junit.Assert.assertEquals;

// #testkit
@TestInstance(Lifecycle.PER_CLASS)
public class KafkaProbesTest extends EmbeddedKafkaTest {

  private static final ActorSystem system = ActorSystem.create("ProducerExampleTest");
  private static final Materializer materializer = ActorMaterializer.create(system);
  // #testkit

  private static final String TEST_TOPIC = UUID.randomUUID().toString();
  private final KafkaProbe.Publisher<String, String> kafkaPublisher;
  private final KafkaProbe.Subscriber<String, String> kafkaSubscriber;

  // #testkit
  KafkaProbesTest() {
    super(system, KafkaPorts.KafkaProbesSpec());
    kafkaSubscriber = kafkaSubscriberProbe(TEST_TOPIC, StringDeserializer(), StringDeserializer());
    kafkaPublisher = kafkaPublisherProbe(StringSerializer(), StringSerializer());
  }

  @Test
  void shouldPublishAndSubscribeWithProbesAsInstanceFields() throws Exception {
    resultOf(
        Source.range(1, 100)
            .map(Object::toString)
            .map(value -> new ProducerRecord<String, String>(TEST_TOPIC, value))
            .runForeach(kafkaPublisher::publish, materializer));

    assertEquals("1", kafkaSubscriber.expectNext().value());

    assertEquals(99, kafkaSubscriber.expectN(99).size());
  }

  @Test
  void shouldBeAbleToReuseProbes() {
    kafkaSubscriber.expectNoMsg(Duration.ofMillis(200));

    String oneMoreMessage = "Single message";
    kafkaPublisher.publish(new ProducerRecord<>(TEST_TOPIC, oneMoreMessage));

    assertEquals(oneMoreMessage, kafkaSubscriber.expectNext().value());
  }

  @Disabled
  @Test
  void shouldPublishAndSubscribeWithProbesAsLocalFields() throws Exception {
    KafkaProbe.Publisher<String, String> kafkaPublisher =
        kafkaPublisherProbe(StringSerializer(), StringSerializer());
    KafkaProbe.Subscriber<String, String> kafkaSubscriber =
        kafkaSubscriberProbe(TEST_TOPIC, StringDeserializer(), StringDeserializer());
    resultOf(
        Source.range(1, 100)
            .map(Object::toString)
            .map(value -> new ProducerRecord<String, String>(TEST_TOPIC, value))
            .runWith(Sink.seq(), materializer)
            .thenAccept(kafkaPublisher::publish));

    assertEquals("1", kafkaSubscriber.expectNext(Duration.ofSeconds(5)).value());

    assertEquals(99, kafkaSubscriber.expectN(99).size());

    kafkaSubscriber.expectNoMsg(Duration.ofMillis(200));

    String oneMoreMessage = "Single message";
    kafkaPublisher.publish(new ProducerRecord<>(TEST_TOPIC, oneMoreMessage));

    assertEquals(1, kafkaSubscriber.expectN(1, Duration.ofSeconds(5)).size());
  }
}
