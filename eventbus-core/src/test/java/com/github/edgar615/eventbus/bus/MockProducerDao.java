package com.github.edgar615.eventbus.bus;

import com.github.edgar615.eventbus.dao.EventProducerDao;
import com.github.edgar615.eventbus.dao.SendEventState;
import com.github.edgar615.eventbus.event.Event;
import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Created by Edgar on 2017/3/29.
 *
 * @author Edgar  Date 2017/3/29
 */
public class MockProducerDao implements EventProducerDao {

  private final List<Event> events = new ArrayList<>();

  private AtomicInteger pendCount = new AtomicInteger();

  public List<Event> getEvents() {
    return ImmutableList.copyOf(events);
  }

  public MockProducerDao addEvent(Event event) {
    events.add(event);
    return this;
  }

  public int getPendCount() {
    return pendCount.get();
  }

  @Override
  public void insert(Event event) {
    events.add(event);
  }

  @Override
  public List<Event> waitingForSend() {
    pendCount.incrementAndGet();
    List<Event> plist = events.stream().filter(e -> !e.head().ext().containsKey("state"))
        .collect(Collectors.toList());
    return new ArrayList<>(plist);
  }

  @Override
  public List<Event> waitingForSend(int fetchCount) {
    return null;
  }

  @Override
  public void mark(String eventId, SendEventState state) {
    events.stream().filter(e -> e.head().id().equalsIgnoreCase(eventId))
        .forEach(e -> e.head().addExt("state", state.value() + ""));
  }
}
