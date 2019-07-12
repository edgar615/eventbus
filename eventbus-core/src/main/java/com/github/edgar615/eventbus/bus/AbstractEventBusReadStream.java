package com.github.edgar615.eventbus.bus;

import com.github.edgar615.eventbus.dao.EventConsumerDao;
import com.github.edgar615.eventbus.event.Event;
import com.github.edgar615.eventbus.utils.EventQueue;
import com.github.edgar615.eventbus.utils.LoggingMarker;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractEventBusReadStream implements EventBusReadStream {

  private static final Logger LOGGER = LoggerFactory.getLogger(EventBusReadStream.class);

  private final EventQueue queue;

  private volatile boolean pause = false;

  private final AtomicInteger pauseCount = new AtomicInteger();

  private volatile long latestPaused = 0L;

  private final EventConsumerDao consumerDao;

  public AbstractEventBusReadStream(EventQueue queue, EventConsumerDao consumerDao) {
    this.queue = queue;
    this.consumerDao = consumerDao;
  }

  @Override
  public void pause() {
    pause = true;
    int count = pauseCount.incrementAndGet();
    latestPaused = System.currentTimeMillis();
    LOGGER.info("pause poll, pause times:{}", count);
  }

  @Override
  public void resume() {
    pause = false;
    LOGGER.info("resume poll, paused {}ms", System.currentTimeMillis() - latestPaused);
  }

  @Override
  public boolean paused() {
    return pause;
  }

  /**
   * 对于从MQ读取消息的主线程应该在while循环中调用这个pollAndEnqueue，否则可能会出现无法从暂停状态恢复的问题
   */
  public final void pollAndEnqueue() {
    List<Event> events = poll();
    for (Event event : events) {
      //先入库
      boolean duplicated = false;
      if (consumerDao != null) {
        duplicated = consumerDao.insert(event);
      }
      if (duplicated) {
        LOGGER.info(LoggingMarker.getLoggingMarker(event, true), "duplicate event, do nothing");
      } else {
        queue.enqueue(event);
        LOGGER.info(LoggingMarker.getLoggingMarker(event, true), "pull and enqueue");
      }
    }
    //暂停和恢复，避免过多的消息造成内存溢出
    if (pause) {
      //队列中等待的消息降到一半才恢复
      if (checkResumeCondition()) {
        resume();
      }
    } else {
      if (checkPauseCondition()) {
        pause();
      }
    }
  }

  private final boolean checkPauseCondition() {
    return queue.isFull();
  }

  private final boolean checkResumeCondition() {
    return queue.isLowWaterMark();
  }
}
