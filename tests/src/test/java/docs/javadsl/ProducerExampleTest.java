/*
 * Copyright (C) 2014 - 2016 Softwaremill <https://softwaremill.com>
 * Copyright (C) 2016 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.javadsl;

import akka.Done;
import akka.actor.ActorSystem;
import akka.kafka.KafkaPorts;
import akka.kafka.ProducerSettings;
import akka.kafka.javadsl.Consumer;
import akka.kafka.javadsl.Producer;
import akka.kafka.testkit.javadsl.EmbeddedKafkaTest;
import akka.kafka.tests.javadsl.LogCapturingExtension;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Source;
import akka.testkit.javadsl.TestKit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;

// #testkit
// #testkit
// #testkit
// #testkit
// #testkit
// #testkit

// #testkit

@TestInstance(Lifecycle.PER_CLASS)
// #testkit
@ExtendWith(LogCapturingExtension.class)
// #testkit
class ProducerExampleTest extends EmbeddedKafkaTest {

  private static final ActorSystem system = ActorSystem.create("ProducerExampleTest");
  private static final Materializer materializer = ActorMaterializer.create(system);
  // #testkit

  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final ProducerSettings<String, String> producerSettings = producerDefaults();

  // #testkit

  ProducerExampleTest() {
    super(system, materializer, KafkaPorts.ProducerExamplesTest());
  }

  @AfterAll
  void shutdownActorSystem() {
    TestKit.shutdownActorSystem(system);
    executor.shutdown();
  }

  // #testkit
  @Test
  void plainSink() throws Exception {
    String topic = createTopic();
    CompletionStage<Done> done =
        Source.range(1, 100)
            .map(number -> number.toString())
            .map(value -> new ProducerRecord<String, String>(topic, value))
            .runWith(Producer.plainSink(producerSettings), materializer);

    Consumer.DrainingControl<List<ConsumerRecord<String, String>>> control =
        consumeString(topic, 100);
    assertEquals(Done.done(), resultOf(done));
    assertEquals(Done.done(), resultOf(control.isShutdown()));
    CompletionStage<List<ConsumerRecord<String, String>>> result =
        control.drainAndShutdown(executor);
    assertEquals(100, resultOf(result).size());
  }

  // #testkit
}
// #testkit
