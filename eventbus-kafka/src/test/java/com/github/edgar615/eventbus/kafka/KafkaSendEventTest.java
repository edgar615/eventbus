package com.github.edgar615.eventbus.kafka;

import com.github.edgar615.eventbus.bus.EventBusProducer;
import com.github.edgar615.eventbus.bus.ProducerOptions;
import com.github.edgar615.eventbus.event.Event;
import com.github.edgar615.eventbus.event.Message;
import com.google.common.collect.ImmutableMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerConfig;

/**
 * Created by Edgar on 2017/3/22.
 *
 * @author Edgar  Date 2017/3/22
 */
public class KafkaSendEventTest {

  public static void main(String[] args) {
    Map<String, Object> configs = new HashMap<>();
    configs.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "192.168.1.203:9092");
    KafkaWriteOptions options = new KafkaWriteOptions(configs);
    KafkaEventBusWriteStream writeStream = new KafkaEventBusWriteStream(options);
    EventBusProducer producer = EventBusProducer.create(new ProducerOptions(), writeStream);
    producer.start();
    for (int i = 0; i < 10; i++) {
      Message message = Message.create("" + i, ImmutableMap.of("foo", "bar"));
      Event event = Event.create("DeviceControlEvent", message, 1);
      producer.send(event);
    }
    try {
      TimeUnit.SECONDS.sleep(3);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }

    producer.close();

  }

}
