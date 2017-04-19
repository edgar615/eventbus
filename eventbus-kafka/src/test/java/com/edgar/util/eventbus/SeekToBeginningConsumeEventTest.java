package com.edgar.util.eventbus;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Created by Edgar on 2017/3/22.
 *
 * @author Edgar  Date 2017/3/22
 */
public class SeekToBeginningConsumeEventTest extends EventbusTest {

  private static Logger logger = LoggerFactory.getLogger(SeekToBeginningConsumeEventTest.class);

  public static void main(String[] args) {
    String server = "10.11.0.31:9092";
    KafkaConsumerOptions options = new KafkaConsumerOptions();
    options.setServers(server)
//            .addStartingOffset(new TopicPartition("test", 0), 11834794l)
            .setPartitioner(event -> Integer.parseInt(event.action().resource()) % 3)
            .setGroup("test-consumer")
            .addTopic("test");
    EventConsumer consumer = new KafkaEventConsumer(options);
    consumer.consumer(null, null, e -> {
      logger.info("---| handle {}", e);
      if (e.action().resource().equals("5")) {
        try {
          TimeUnit.SECONDS.sleep(5);
        } catch (InterruptedException e1) {
          e1.printStackTrace();
        }
      }
    });

  }


}
