package com.edgar.util.eventbus.kafka;

import com.google.common.base.Preconditions;

import com.edgar.util.base.MorePreconditions;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

public class ConsumerRunnable implements Runnable {

  private static final Logger LOGGER = LoggerFactory.getLogger(EventbusImpl.class);

  private String kafkaConnect;

  private String groupId;

  private String clientId;

  private List<String> topics = new ArrayList<>();

  private long startingOffset;

  public void setStartingOffset(long startingOffset) {
    this.startingOffset = startingOffset;
  }

  public void setKafkaConnect(String kafkaConnect) {
    this.kafkaConnect = kafkaConnect;
  }

  public void setGroupId(String groupId) {
    this.groupId = groupId;
  }

  public void setClientId(String clientId) {
    this.clientId = clientId;
  }

  public void addTopic(String topic) {
    Preconditions.checkNotNull(topic);
    topics.add(topic);
  }

  @Override
  public void run() {
    MorePreconditions.checkNotEmpty(topics);
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConnect);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    props.put(ConsumerConfig.CLIENT_ID_CONFIG, clientId);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
              "org.apache.kafka.common.serialization.StringDeserializer");
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
              "com.edgar.util.eventbus.kafka.EventDeserializer");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

    final KafkaConsumer<String, Map> consumer = new KafkaConsumer<>(props);
    for (String topic : topics) {
      List<PartitionInfo> partitions;
      while ((partitions = consumer.partitionsFor(topic)) == null) {
        try {
          LOGGER.info("---@ [KAFKA] [topic {} since no metadata is available, wait 5s]",
                      topic);
          TimeUnit.SECONDS.sleep(5);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
      LOGGER.info("---@ [KAFKA] [topic:{} is available] [partitions:{}]",
                  topic, partitions);
    }
    consumer.subscribe(new ArrayList<>(topics), new ConsumerRebalanceListener() {
      @Override
      public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        LOGGER.info(
                "---@ [KAFKA] [onPartitionsRevoked] [partitions:{}]",
                partitions);
      }

      @Override
      public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        Iterator<TopicPartition> topicPartitionIterator = partitions.iterator();
        while (topicPartitionIterator.hasNext()) {
          TopicPartition topicPartition = topicPartitionIterator.next();

          long position = consumer.position(topicPartition);
          OffsetAndMetadata lastCommitedOffsetAndMetadata = consumer.committed(topicPartition);
          if (lastCommitedOffsetAndMetadata != null) {
//            lastConsumedMessages.put(topicPartition.topic() + "-" + topicPartition.partition(),
//                                     lastCommitedOffsetAndMetadata.offset());
          }

          LOGGER.info(
                  "---@ [KAFKA] [onPartitionsAssigned] [topic:{}, parition:{}, offset:{}, "
                  + "commited:{}]",
                  topicPartition.topic(),
                  topicPartition.partition(),
                  position,
                  lastCommitedOffsetAndMetadata);

          if (startingOffset == -2) {
            LOGGER.info(
                    "---@ [KAFKA] [Not setting offset]");
          } else if (startingOffset == 0) {
            LOGGER.info(
                    "---@ [KAFKA] [Setting offset to begining]");
            consumer.seekToBeginning(topicPartition);
          } else if (startingOffset == -1) {
            LOGGER.info(
                    "---@ [KAFKA] [Setting offset to end]");
            consumer.seekToEnd(topicPartition);
          } else {
            LOGGER.info(
                    "---@ [KAFKA] [Setting offset to {}]", startingOffset);
            consumer.seek(topicPartition, startingOffset);
          }
        }
      }
    });
    try {
      while (true) {
        ConsumerRecords<String, Map> records = consumer.poll(100);
        if (records.count() > 0) {
          LOGGER.info(
                  "---@ [KAFKA] [poll message] [count:{}]",
                  records.count());
        }

        for (ConsumerRecord<String, Map> record : records) {
          LOGGER.info(
                  "Receive message: time->{} topic->{}, partition->{}, offset->{}, key->{}, "
                  + "value->{}",
                  Instant.now().getEpochSecond(),
                  record.topic(), record.partition(), record.offset(), record.key(),
                  record.value());
        }

//        if(startingOffset == -2) {
//          kafkaConsumer.commitSync();
//        }
      }
    } finally {
      consumer.close();
    }
  }
}
