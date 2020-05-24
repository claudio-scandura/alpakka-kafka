/*
 * Copyright (C) 2014 - 2016 Softwaremill <https://softwaremill.com>
 * Copyright (C) 2016 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.kafka.testkit.javadsl;

import akka.Done;
import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.kafka.ConsumerMessage.CommittableMessage;
import akka.kafka.Subscriptions;
import akka.kafka.javadsl.Consumer;
import akka.kafka.javadsl.Producer;
import akka.kafka.testkit.internal.KafkaTestKitChecks;
import akka.kafka.testkit.internal.KafkaTestKitClass;
import akka.stream.Materializer;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.testkit.javadsl.TestSink;
import akka.stream.testkit.javadsl.TestSource;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.compat.java8.functionConverterImpls.FromJavaPredicate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.IntStream;

public abstract class BaseKafkaTest extends KafkaTestKitClass {

  public static final int partition0 = 0;

  public final Logger log = LoggerFactory.getLogger(getClass());

  protected final Materializer materializer;

  private final List<KafkaProbe> probes = new ArrayList<>();

  protected BaseKafkaTest(ActorSystem system, Materializer materializer, String bootstrapServers) {
    super(system, bootstrapServers);
    this.materializer = materializer;
  }

  @Override
  public Logger log() {
    return log;
  }

  /** Tests using probes can call this method after every test to automatically clear any probe */
  protected void afterEach() throws Exception {
    resultOf(
        CompletableFuture.allOf(
            probes.stream()
                .map(KafkaProbe::clear)
                .map(CompletionStage::toCompletableFuture)
                .toArray(CompletableFuture[]::new)));
  }

  /** Tests using probes can call this method before every test to automatically reset any probe */
  protected void beforeEach() {
    probes.forEach(KafkaProbe::reset);
  }

  /**
   * Overwrite to set different default timeout for
   * [[resultOf[T](stage:java\.util\.concurrent\.CompletionStage[T])* resultOf]].
   */
  protected Duration resultOfTimeout() {
    return Duration.ofSeconds(5);
  }

  protected CompletionStage<Done> produceString(String topic, int messageCount, int partition) {
    return Source.fromIterator(() -> IntStream.range(0, messageCount).iterator())
        .map(Object::toString)
        .map(n -> new ProducerRecord<String, String>(topic, partition, DefaultKey(), n))
        .runWith(Producer.plainSink(producerDefaults()), materializer);
  }

  protected CompletionStage<Done> produceString(String topic, String message) {
    return produce(
        topic, StringSerializer(), StringSerializer(), Pair.create(DefaultKey(), message));
  }

  protected <K, V> CompletionStage<Done> produce(
      String topic,
      Serializer<K> keySerializer,
      Serializer<V> valueSerializer,
      Pair<K, V>... messages) {
    return Source.from(Arrays.asList(messages))
        .map(pair -> new ProducerRecord<>(topic, pair.first(), pair.second()))
        .runWith(producingSink(keySerializer, valueSerializer), materializer);
  }

  <K, V> Sink<ProducerRecord<K, V>, CompletionStage<Done>> producingSink(
      Serializer<K> keySerializer, Serializer<V> valueSerializer) {
    return Producer.plainSink(producerDefaults(keySerializer, valueSerializer));
  }

  protected <K, V> KafkaProbe.Publisher<K, V> kafkaPublisherProbe(
      Serializer<K> keySerializer, Serializer<V> valueSerializer) {
    KafkaProbe.Publisher<K, V> publisher =
        new KafkaProbe.Publisher<>(
            () ->
                TestSource.<ProducerRecord<K, V>>probe(system())
                    .toMat(producingSink(keySerializer, valueSerializer), Keep.left())
                    .run(materializer));
    probes.add(publisher);
    return publisher;
  }

  protected <K, V> KafkaProbe.Subscriber<K, V> kafkaSubscriberProbe(
      String topic, Deserializer<K> keyDeserializer, Deserializer<V> valueDeserializer) {

    KafkaProbe.Subscriber<K, V> subscriber =
        new KafkaProbe.Subscriber<>(
            () ->
                consumingSource(topic, keyDeserializer, valueDeserializer)
                    .toMat(TestSink.probe(system()), Keep.both())
                    .run(materializer));
    probes.add(subscriber);
    return subscriber;
  }

  protected Consumer.DrainingControl<List<ConsumerRecord<String, String>>> consumeString(
      String topic, long take) {
    return consume(topic, take, StringDeserializer(), StringDeserializer());
  }

  protected <K, V> Consumer.DrainingControl<List<ConsumerRecord<K, V>>> consume(
      String topic, long take, Deserializer<K> keyDeserializer, Deserializer<V> valueDeserializer) {
    return consumingSource(topic, keyDeserializer, valueDeserializer)
        .take(take)
        .toMat(Sink.seq(), Consumer::createDrainingControl)
        .run(materializer);
  }

  <K, V> Source<ConsumerRecord<K, V>, Consumer.Control> consumingSource(
      String topic, Deserializer<K> keyDeserializer, Deserializer<V> valueDeserializer) {
    return Consumer.plainSource(
        consumerDefaults(keyDeserializer, valueDeserializer)
            .withGroupId(createGroupId(1))
            .withStopTimeout(Duration.ZERO),
        Subscriptions.topics(topic));
  }

  <K, V> Source<CommittableMessage<K, V>, Consumer.Control> committableConsumingSource(
      String topic,
      Deserializer<K> keyDeserializer,
      Deserializer<V> valueDeserializer,
      boolean fromBeginning) {
    return Consumer.committableSource(
        consumerDefaults(keyDeserializer, valueDeserializer)
            .withGroupId(createGroupId(1))
            .withStopTimeout(Duration.ZERO)
            .withProperty(
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, fromBeginning ? "earliest" : "latest"),
        Subscriptions.topics(topic));
  }

  /**
   * Periodically checks if a given predicate on cluster state holds.
   *
   * <p>If the predicate does not hold after configured amount of time, throws an exception.
   */
  public void waitUntilCluster(Predicate<DescribeClusterResult> predicate) {
    KafkaTestKitChecks.waitUntilCluster(
        settings().clusterTimeout(),
        settings().checkInterval(),
        adminClient(),
        new FromJavaPredicate(predicate),
        log());
  }

  /**
   * Periodically checks if the given predicate on consumer group state holds.
   *
   * <p>If the predicate does not hold after configured amount of time, throws an exception.
   */
  public void waitUntilConsumerGroup(
      String groupId, Predicate<ConsumerGroupDescription> predicate) {
    KafkaTestKitChecks.waitUntilConsumerGroup(
        groupId,
        settings().consumerGroupTimeout(),
        settings().checkInterval(),
        adminClient(),
        new FromJavaPredicate(predicate),
        log());
  }

  /**
   * Periodically checks if the given predicate on consumer summary holds.
   *
   * <p>If the predicate does not hold after configured amount of time, throws an exception.
   */
  public void waitUntilConsumerSummary(
      String groupId, Predicate<Collection<MemberDescription>> predicate) {
    waitUntilConsumerGroup(
        groupId,
        group -> {
          try {
            return group.state() == ConsumerGroupState.STABLE && predicate.test(group.members());
          } catch (Exception ex) {
            return false;
          }
        });
  }

  protected <T> T resultOf(CompletionStage<T> stage) throws Exception {
    return resultOf(stage, resultOfTimeout());
  }

  protected <T> T resultOf(CompletionStage<T> stage, Duration timeout) throws Exception {
    return stage.toCompletableFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }
}
