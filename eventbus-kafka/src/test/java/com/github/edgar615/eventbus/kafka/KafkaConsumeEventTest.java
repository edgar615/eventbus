package com.github.edgar615.eventbus.kafka;

import com.github.edgar615.eventbus.bus.ConsumerOptions;
import com.github.edgar615.eventbus.bus.EventBusConsumer;
import com.github.edgar615.eventbus.bus.EventBusConsumerImpl;
import com.github.edgar615.eventbus.utils.DefaultEventQueue;
import com.github.edgar615.eventbus.utils.EventQueue;
import com.github.edgar615.eventbus.utils.NamedThreadFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by Edgar on 2017/3/22.
 *
 * @author Edgar  Date 2017/3/22
 */
public class KafkaConsumeEventTest {

  private static Logger logger = LoggerFactory.getLogger(KafkaConsumeEventTest.class);

  public static void main(String[] args) {
    Map<String, Object> configs = new HashMap<>();
    configs.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "192.168.1.212:9092");
    configs.put(ConsumerConfig.GROUP_ID_CONFIG, "user");
    KafkaReadOptions options = new KafkaReadOptions(configs)
        .addTopic("DeviceControlEvent");
    EventQueue eventQueue = new DefaultEventQueue(100);
    KafkaEventBusReadStream readStream = new KafkaEventBusReadStream(eventQueue, null, options);
    ConsumerOptions consumerOptions = new ConsumerOptions()
        .setWorkerPoolSize(5)
        .setBlockedCheckerMs(1000)
        .setMaxQuota(100);
    ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor(
        NamedThreadFactory.create("scheduler"));

    EventBusConsumer consumer = new EventBusConsumerImpl(consumerOptions, eventQueue, null, scheduledExecutor);
//    consumer.setPartitioner(event -> Integer.parseInt(event.action().resource()) % 3);
    consumer.consumer(null, null, e -> {
      logger.info("---| handle {}", e);
    });
    consumer.start();
    readStream.start();
  }

}