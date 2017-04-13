package com.edgar.util.eventbus;

import com.google.common.base.MoreObjects;

import com.edgar.util.event.Event;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * Created by Edgar on 2017/4/6.
 *
 * @author Edgar  Date 2017/4/6
 */
class RecordMeta implements Comparable<RecordMeta> {

  private final String topic;

  private final int partition;

  private final long offset;

  private final Event event;

  private boolean completed;

  private long completedOn;

  private long startedOn = System.currentTimeMillis();

  private RecordMeta(String topic, int partition, long offset, Event event) {
    this.topic = topic;
    this.partition = partition;
    this.offset = offset;
    this.event = event;
  }

  static RecordMeta create(String topic, int partition, long offset, Event event) {
    return new RecordMeta(topic, partition, offset, event);
  }

  static RecordMeta create(ConsumerRecord<String, Event> record) {
    return new RecordMeta(record.topic(), record.partition(), record.offset(), record.value());
  }

  public String topic() {
    return topic;
  }

  public int partition() {
    return partition;
  }

  public long offset() {
    return offset;
  }

  public Event event() {
    return event;
  }

  public boolean isCompleted() {
    return completed;
  }

  public void completed() {
    this.completed = true;
    this.completedOn = System.currentTimeMillis();
  }

  public long duration() {
    return System.currentTimeMillis() - startedOn;
  }

  @Override
  public int compareTo(RecordMeta o) {
    return (int) (this.offset - o.offset);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper("RecordMeta")
            .add("topic", topic)
            .add("partition", partition)
            .add("offset", offset)
            .add("event", event)
            .add("completed", completed)
            .toString();
  }
}
