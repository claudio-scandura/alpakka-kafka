package akka.kafka.testkit.javadsl;

import akka.Done;
import akka.japi.Pair;
import akka.kafka.javadsl.Consumer;
import akka.stream.testkit.TestPublisher;
import akka.stream.testkit.TestSubscriber;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import scala.collection.JavaConverters;
import scala.concurrent.duration.FiniteDuration;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public interface KafkaProbe {

  CompletionStage<Done> clear();

  void reset();

  class Publisher<K, V> implements KafkaProbe {

    private final Supplier<TestPublisher.Probe<ProducerRecord<K, V>>> probeSupplier;
    private TestPublisher.Probe<ProducerRecord<K, V>> probe;

    Publisher(Supplier<TestPublisher.Probe<ProducerRecord<K, V>>> probeSupplier) {
      this.probeSupplier = probeSupplier;
      probe = probeSupplier.get();
    }

    public void publish(Collection<ProducerRecord<K, V>> messages) {
      messages.forEach(probe::sendNext);
    }

    public void publish(ProducerRecord<K, V>... messages) {
      publish(Arrays.asList(messages));
    }

    public void publish(ProducerRecord<K, V> message) {
      publish(Collections.singleton(message));
    }

    @Override
    public CompletionStage<Done> clear() {
      probe.sendComplete();
      probe = null;
      return CompletableFuture.completedFuture(Done.done());
    }

    @Override
    public void reset() {
      if (null == probe) {
        probe = probeSupplier.get();
      }
    }
  }

  class Subscriber<K, V> implements KafkaProbe {

    private final AtomicLong offset = new AtomicLong(0);
    private final Supplier<Pair<Consumer.Control, TestSubscriber.Probe<ConsumerRecord<K, V>>>>
        probeSupplier;
    private Pair<Consumer.Control, TestSubscriber.Probe<ConsumerRecord<K, V>>> controlAndProbe;

    Subscriber(
        Supplier<Pair<Consumer.Control, TestSubscriber.Probe<ConsumerRecord<K, V>>>>
            probeSupplier) {
      this.probeSupplier = probeSupplier;
      controlAndProbe = probeSupplier.get();
    }

    public ConsumerRecord<K, V> expectNext() {
      return incrementOffset(1, () -> controlAndProbe.second().requestNext());
    }

    public ConsumerRecord<K, V> expectNext(Duration duration) {
      return incrementOffset(
          1,
          () ->
              controlAndProbe
                  .second()
                  .requestNext(FiniteDuration.apply(duration.toNanos(), TimeUnit.NANOSECONDS)));
    }

    public List<ConsumerRecord<K, V>> expectN(int n) {
      return incrementOffset(
          n,
          () -> JavaConverters.seqAsJavaList(controlAndProbe.second().request(n).expectNextN(n)));
    }

    public List<ConsumerRecord<K, V>> expectN(int n, Duration duration) {
      // TODO: This doesn't really work
      return incrementOffset(
          n,
          () ->
              JavaConverters.seqAsJavaList(
                  controlAndProbe
                      .second()
                      .request(n)
                      .receiveWithin(
                          FiniteDuration.apply(duration.toNanos(), TimeUnit.NANOSECONDS), n)));
    }

    public void expectNoMsg(Duration duration) {
      controlAndProbe.second().expectNoMessage(duration);
    }

    @Override
    public CompletionStage<Done> clear() {
      Consumer.Control control = controlAndProbe.first();
      controlAndProbe = null;
      return control.shutdown();
    }

    @Override
    public void reset() {
      if (null == controlAndProbe) {
        controlAndProbe = probeSupplier.get();
        long messagesAlreadyRequested = offset.get();
        controlAndProbe
            .second()
            .request(messagesAlreadyRequested)
            .expectNextN(messagesAlreadyRequested);
      }
    }

    private <T> T incrementOffset(long inc, Supplier<T> block) {
      T result = block.get();
      offset.addAndGet(inc);
      return result;
    }
  }
}
