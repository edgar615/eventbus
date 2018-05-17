package com.github.edgar615.util.eventbus.vertx;

import com.google.common.collect.ImmutableMap;

import com.github.edgar615.util.event.Event;
import com.github.edgar615.util.event.Message;
import com.github.edgar615.util.eventbus.EventProducer;
import com.github.edgar615.util.eventbus.KafkaEventProducer;
import com.github.edgar615.util.eventbus.KafkaProducerOptions;
import io.vertx.core.Vertx;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;

/**
 * Created by Edgar on 2018/5/17.
 *
 * @author Edgar  Date 2018/5/17
 */
public class KafkaVertxProducerTest {

  public static void main(String[] args) throws InterruptedException {
    Vertx vertx = Vertx.vertx();
    KafkaProducerOptions options = new KafkaProducerOptions();
    options.setServers("120.76.158.7:9092");
    KafkaVertxEventbusProducer producer = KafkaVertxEventbusProducer.create(vertx, options);
    for (int i = 0; i < 10; i++) {
      Message message = Message.create("" + i, ImmutableMap.of("foo", "bar"));
      Event event = Event.create("DeviceControlEvent_1_3", message, 1);
      producer.send(event, ar ->{
        System.out.println(ar.result());
      });
//      TimeUnit.SECONDS.sleep(1);
    }
    try {
      TimeUnit.SECONDS.sleep(3);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }

    producer.close();

    System.out.println(producer.metrics());
  }

}
