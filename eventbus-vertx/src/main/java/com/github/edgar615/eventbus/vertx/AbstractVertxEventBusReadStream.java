package com.github.edgar615.eventbus.vertx;

import com.github.edgar615.eventbus.bus.EventBusReadStream;
import com.github.edgar615.eventbus.event.Event;
import com.github.edgar615.eventbus.utils.EventQueue;
import com.github.edgar615.eventbus.utils.LoggingMarker;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractVertxEventBusReadStream implements VertxEventBusReadStream {

  private static final Logger LOGGER = LoggerFactory.getLogger(EventBusReadStream.class);

  private final EventQueue queue;

  private final AtomicBoolean pause = new AtomicBoolean(false);

  private final AtomicInteger pauseCount = new AtomicInteger();

  private volatile long latestPaused = 0L;

  private final VertxEventConsumerRepository consumerRepository;

  public AbstractVertxEventBusReadStream(Vertx vertx, EventQueue queue, VertxEventConsumerRepository consumerRepository) {
    this.queue = queue;
    this.consumerRepository = consumerRepository;
    // 启动一个定时任务，定时检查是否应该从暂停状态恢复
    vertx.setPeriodic(100L, l -> {
      if (pause.get() && checkResumeCondition()) {
          resume();
      }
    });
  }

  @Override
  public boolean pause() {
    boolean result = pause.compareAndSet(false, true);
    int count = pauseCount.incrementAndGet();
    latestPaused = System.currentTimeMillis();
    LOGGER.info("pause poll, pause times:{}", count);
    return result;
  }

  @Override
  public boolean resume() {
    boolean result = pause.compareAndSet(true, false);
    LOGGER.info("resume poll, paused {}ms", System.currentTimeMillis() - latestPaused);
    return result;
  }

  @Override
  public boolean paused() {
    return pause.get();
  }

  /**
   * 对于从MQ读取消息的主线程应该在while循环中调用这个pollAndEnqueue，否则可能会出现无法从暂停状态恢复的问题
   */
  public final void enqueue(List<Event> events, Handler<AsyncResult<Integer>> handler) {
    if (events.size() > 0) {
      LOGGER.info("poll {} records", events.size());
    }
    for (Event event : events) {
      //先入库
      if (consumerRepository != null) {
        Future<Boolean> duplicated = Future.future();
        consumerRepository.insert(event, duplicated);
        duplicated.setHandler(dar -> {
          if (dar.succeeded() && dar.result()) {
            LOGGER.info(LoggingMarker.getLoggingMarker(event, true), "duplicate event, do nothing");
          } else if (dar.succeeded() && !dar.result()) {
            queue.enqueue(event);
            LOGGER.info(LoggingMarker.getLoggingMarker(event, true), "poll and enqueue");
          }
        });
      } else {
        queue.enqueue(event);
        LOGGER.info(LoggingMarker.getLoggingMarker(event, true), "poll and enqueue");
      }
    }
    //暂停和恢复，避免过多的消息造成内存溢出
    if (pause.get()) {
      //队列中等待的消息降到一半才恢复
      if (checkResumeCondition()) {
        resume();
      }
    } else {
      if (checkPauseCondition()) {
        pause();
        handler.handle(Future.succeededFuture(0));
        return;
      }
    }
    handler.handle(Future.succeededFuture(events.size()));

  }

  protected final boolean checkPauseCondition() {
    return queue.isFull();
  }

  protected final boolean checkResumeCondition() {
    return queue.isLowWaterMark();
  }
}