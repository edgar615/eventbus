package com.edgar.util.eventbus;

import com.google.common.collect.ImmutableMap;

import com.edgar.util.concurrent.NamedThreadFactory;
import com.edgar.util.event.Event;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * kafka的consumer对象不是线程安全的，如果在不同的线程里使用consumer会抛出异常.
 * <p>
 * 消息的消费有两种方式：每个线程维护一个KafkaConsumer 或者 维护一个或多个KafkaConsumer，同时维护多个事件处理线程(worker thread)
 * <p>
 * <b>每个线程维护一个KafkaConsumer</b>
 * 一个或多个Consumer线程，Consumer除了读取消息外，还包括具体的业务逻辑处理，同一个Consumer线程里对事件串行处理，
 * 每个事件完成之后再commit.
 * <p>
 * 同一个主题的线程数受限于主题的分区数，多余的线程不会接收任何消息。
 * <p>
 * 如果对消息的处理比较耗时，容易导致消费者的rebalance，因为如果在一段事件内没有收到Consumer的poll请求，会触发kafka的rebalance.
 * <p>
 * <p>
 * <b>维护一个或多个KafkaConsumer，同时维护多个事件处理线程(worker thread)</b>
 * 一个或者多个Consumer线程，Consumer只用来从kafka读取消息，并不涉及具体的业务逻辑处理, 具体的业务逻辑由Consumer转发给工作线程来处理.
 * <p>
 * 使用工作线程处理事件的时候，需要注意commit的正确的offset。
 * 如果有两个工作线程处理事件，工作线程A，处理事件 1，工作线程B，处理事件2. 如果工作线程的2先处理完，不能立刻commit。
 * 否则有可能导致1的丢失.所以这种模式需要一个协调器来检测各个工作线程的消费状态，来对合适的offset进行commit
 * <p>
 * <p>
 * Eventbus采用第二种方案消费消息.
 *
 * @author Edgar  Date 2017/4/5
 */
public class KafkaEventConsumer extends EventConsumerImpl implements Runnable {

  private static final Logger LOGGER = LoggerFactory.getLogger(KafkaEventConsumer.class);

  private final KafkaConsumerOptions options;

  private final ExecutorService consumerExecutor;

  /**
   * 正在处理的消息（不包括已经处理完成或者还在线程池排队的任务）
   */
  private Map<TopicPartition, TreeSet<RecordFuture>> process = new HashMap<>();

  private KafkaConsumer<String, Event> consumer;

  private volatile boolean running = true;

  private volatile boolean started = false;

  public KafkaEventConsumer(KafkaConsumerOptions options) {
    super(options);
    this.consumerExecutor =
            Executors.newFixedThreadPool(1, NamedThreadFactory.create("eventbus-consumer"));
    this.options = options;
    consumerExecutor.submit(this);
  }

  /**
   * 将消息标记为完成.
   *
   * @param record
   */
  private synchronized void complete(ConsumerRecord<String, Event> record) {
    TopicPartition tp = new TopicPartition(record.topic(), record.partition());
    Set<RecordFuture> metas = process.get(tp);
    metas.stream()
            .filter(m -> m.offset() == record.offset())
            .forEach(m -> m.completed());
  }

  /**
   * 将消息入队
   *
   * @param record
   */
  private synchronized void enqueue(ConsumerRecord<String, Event> record) {
    TopicPartition tp = new TopicPartition(record.topic(), record.partition());
    RecordFuture meta = RecordFuture.create(record);
    if (process.containsKey(tp)) {
      Set<RecordFuture> metas = process.get(tp);
      metas.add(meta);
    } else {
      TreeSet<RecordFuture> metas = new TreeSet<>();
      metas.add(meta);
      process.put(tp, metas);
    }
  }

  /**
   * commit完成的消息
   */
  private synchronized void commit() {
    if (process.isEmpty()) {
      return;
    }
    Map<TopicPartition, OffsetAndMetadata> commited = new HashMap<>();
    for (TopicPartition tp : process.keySet()) {
      long offset = -1;
      Set<RecordFuture> metas = process.get(tp);
      for (RecordFuture meta : metas) {
        if (meta.isCompleted()) {
          offset = meta.offset();
        } else {
          break;
        }
      }
      if (offset > -1) {
        commited.put(tp, new OffsetAndMetadata(offset + 1));
      }
    }
    if (commited.isEmpty()) {
      return;
    }
    consumer.commitAsync(
            ImmutableMap.copyOf(commited),
            (offsets, exception) -> {
              if (exception != null) {
                LOGGER.error("[consumer] [commit: {}]", commited, exception);
                exception.printStackTrace();
              } else {
                for (TopicPartition tp : offsets.keySet()) {
                  OffsetAndMetadata data = offsets.get(tp);
                  Set<RecordFuture> metas = process.get(tp);
                  metas.removeIf(m -> m.offset() < data.offset());
                }
                if (!offsets.isEmpty()) {
                  LOGGER.info("[consumer] [commit: {}]", offsets);
                }
              }
            });
    //https://issues.apache.org/jira/browse/KAFKA-3412
    consumer.poll(0);
  }

  @Override
  public void run() {
    try {
      startConsumer();
    } catch (Exception e) {
      LOGGER.error("[consumer] [Starting]", e);
    }
  }

  private void startConsumer() {
    consumer = new KafkaConsumer<>(options.consumerProps());
    List<PartitionInfo> partitions;
    for (String topic : options.getTopics()) {
      while ((partitions = consumer.partitionsFor(topic)) == null) {
        try {
          LOGGER.info("[consumer] [topic {} since no metadata is available, wait 5s]",
                      topic);
          TimeUnit.SECONDS.sleep(5);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
      LOGGER.info("[consumer] [topic:{} is available] [partitions:{}]",
                  topic, partitions);
    }

    consumer.subscribe(options.getTopics(), new ConsumerRebalanceListener() {
      @Override
      public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        LOGGER.info(
                "[consumer] [onPartitionsRevoked] [partitions:{}]",
                partitions);
      }

      @Override
      public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        Iterator<TopicPartition> topicPartitionIterator = partitions.iterator();
        while (topicPartitionIterator.hasNext()) {
          TopicPartition topicPartition = topicPartitionIterator.next();
          long position = consumer.position(topicPartition);
          OffsetAndMetadata lastCommitedOffsetAndMetadata = consumer.committed(topicPartition);
          LOGGER.info(
                  "[consumer] [onPartitionsAssigned] [topic:{}, parition:{}, offset:{}, "
                  + "commited:{}]",
                  topicPartition.topic(),
                  topicPartition.partition(),
                  position,
                  lastCommitedOffsetAndMetadata);

          if (!started) {
            setStartOffset(topicPartition);
          }
        }
        started = true;
      }
    });
    try {
      while (running) {
        ConsumerRecords<String, Event> records = consumer.poll(100);
        if (records.count() > 0) {
          LOGGER.info(
                  "[consumer] [poll {} messages]",
                  records.count());
        }

        for (ConsumerRecord<String, Event> record : records) {
          Event event = record.value();
          LOGGER.info("<====== [{}] [{},{},{}] [{}] [{}] [{}]",
                      event.head().id(),
                      record.topic(),
                      record.partition(),
                      record.offset(),
                      event.head().action(),
                      Helper.toHeadString(event),
                      Helper.toActionString(event));
          handle(event, () -> enqueue(record), () -> complete(record));
        }
        commit();

      }
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      consumer.close();
    }
  }

  public void close() {
    running = false;
  }

  private void setStartOffset(TopicPartition topicPartition) {
    long startingOffset = options.getStartingOffset(topicPartition);
    if (startingOffset == -2) {
      LOGGER.info(
              "[consumer] [StartingOffset] [topic:{}, parition:{}, offset:{}]",
              topicPartition.topic(),
              topicPartition.partition(),
              "none");
    } else if (startingOffset == 0) {
      consumer.seekToBeginning(topicPartition);
      LOGGER.info(
              "[consumer] [StartingOffset] [topic:{}, parition:{}, offset:{}]",
              topicPartition.topic(),
              topicPartition.partition(),
              "beginning");
    } else if (startingOffset == -1) {
      consumer.seekToEnd(topicPartition);
      LOGGER.info(
              "[consumer] [StartingOffset] [topic:{}, parition:{}, offset:{}]",
              topicPartition.topic(),
              topicPartition.partition(),
              "end");
    } else {
      consumer.seek(topicPartition, startingOffset);
      LOGGER.info(
              "[consumer] [StartingOffset] [topic:{}, parition:{}, offset:{}]",
              topicPartition.topic(),
              topicPartition.partition(),
              startingOffset);
    }
  }
}