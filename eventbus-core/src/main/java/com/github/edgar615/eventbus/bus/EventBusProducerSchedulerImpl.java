package com.github.edgar615.eventbus.bus;

import com.github.edgar615.eventbus.repository.EventProducerRepository;
import com.github.edgar615.eventbus.repository.SendEventState;
import com.github.edgar615.eventbus.event.Event;
import com.github.edgar615.eventbus.utils.LoggingMarker;
import com.github.edgar615.eventbus.utils.NamedThreadFactory;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 定时从存储中拉取未发送任务进行发送
 * @author Edgar
 */
class EventBusProducerSchedulerImpl implements EventBusProducerScheduler {

  private static final Logger LOGGER = LoggerFactory.getLogger(EventBusProducerScheduler.class);

  private static final int DEFAULT_PREIOD = 1000;

  /**
   * 定时从持久层拉取待发送消息的方法
   */
  private long fetchPeriod;

  private final EventProducerRepository eventProducerRepository;

  private final ScheduledExecutorService scheduledExecutor;

  private final EventBusWriteStream writeStream;

  private final AtomicInteger processing = new AtomicInteger(0);

  private volatile boolean closed = false;

  EventBusProducerSchedulerImpl(EventProducerRepository eventProducerRepository,
      EventBusWriteStream writeStream, long fetchPeriod) {
    this.scheduledExecutor = Executors.newSingleThreadScheduledExecutor(
        NamedThreadFactory.create("producer-scheduler"));
    this.writeStream = writeStream;
    this.eventProducerRepository = eventProducerRepository;
    if (fetchPeriod <= 0) {
      this.fetchPeriod = DEFAULT_PREIOD;
    } else {
      this.fetchPeriod = fetchPeriod;
    }

  }

  @Override
  public void start() {
    LOGGER.info("start producer scheduler, period:{}ms", fetchPeriod);
    schedule(fetchPeriod);
  }

  @Override
  public void close() {
    LOGGER.info("close producer scheduler");
    scheduledExecutor.shutdown();
  }

  private void schedule(long delay) {
    Runnable scheduledCommand = () -> {
      // 如果processing大于0说明有任务在执行，直接返回，在任务执行完成后会重新只执行定时任务
      if (closed || processing.get() > 0) {
        LOGGER.trace("skip scheduler, closed:{}, processing:{}", closed, processing.get());
        return;
      }
      List<Event> waitingForSend = eventProducerRepository.waitingForSend();
      //没有数据，等待
      LOGGER.trace("{} events to be send", waitingForSend.size());
      if (waitingForSend.isEmpty()) {
        schedule(fetchPeriod);
        return;
      }
      processing.addAndGet(waitingForSend.size());
      for (Event event : waitingForSend) {
        CompletableFuture<Event> future = writeStream.send(event);
        // 这里是不是应该用一个新的线程处理回调？
        future.thenAccept(this::markSucess)
            .exceptionally(throwable -> {
              markFailed(event, throwable);
              return null;
            }).thenAccept(v -> {
          int len = processing.addAndGet(-1);
          if (len == 0) {
            // 有数据，立即继续执行
            schedule(0);
          }
        });
      }
    };
    if (delay > 0) {
      scheduledExecutor.schedule(scheduledCommand, delay, TimeUnit.MILLISECONDS);
    } else {
      //直接运行
      scheduledExecutor.submit(scheduledCommand);
    }
  }

  private void markSucess(Event event) {
    LOGGER.info(LoggingMarker.getIdLoggingMarker(event.head().id()), "send succeed");
    try {
      eventProducerRepository.mark(event.head().id(), SendEventState.SUCCEED);
    } catch (Exception e) {
      LOGGER.error(LoggingMarker.getIdLoggingMarker(event.head().id()), "mark event failed", e);
    }
  }

  private void markFailed(Event event, Throwable throwable) {
    LOGGER.error(LoggingMarker.getIdLoggingMarker(event.head().id()), "send failed",
        throwable.getMessage());
    try {
      eventProducerRepository.mark(event.head().id(), SendEventState.FAILED);
    } catch (Exception e) {
      LOGGER.error(LoggingMarker.getIdLoggingMarker(event.head().id()), "mark event failed", e);
    }
  }

}
