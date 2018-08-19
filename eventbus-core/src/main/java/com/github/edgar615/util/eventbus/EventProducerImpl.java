package com.github.edgar615.util.eventbus;

import com.github.edgar615.util.concurrent.NamedThreadFactory;
import com.github.edgar615.util.concurrent.OrderQueue;
import com.github.edgar615.util.event.Event;
import com.github.edgar615.util.exception.DefaultErrorCode;
import com.github.edgar615.util.exception.SystemException;
import com.github.edgar615.util.metrics.DummyMetrics;
import com.github.edgar615.util.metrics.ProducerMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.*;

/**
 * Created by Edgar on 2017/4/19.
 *
 * @author Edgar  Date 2017/4/19
 */
public abstract class EventProducerImpl implements EventProducer {
  private static final Logger LOGGER = LoggerFactory.getLogger(EventProducer.class);

  private final ScheduledExecutorService scheduledExecutor =
          Executors.newSingleThreadScheduledExecutor(
                  NamedThreadFactory.create("eventbus-producer-scheduler"));

  private final ExecutorService producerExecutor
          = Executors.newFixedThreadPool(1, NamedThreadFactory.create("eventbus-producer"));

  private final ExecutorService workExecutor;

  private final OrderQueue queue = new OrderQueue();

  private final ProducerMetrics metrics;

  private final EventCallback callback;

  private final long maxQuota;

  private final long fetchPendingPeriod;

  private ProducerStorage producerStorage;

//  private volatile boolean running = false;

  protected EventProducerImpl(ProducerOptions options) {
    this(options, null);
  }

  protected EventProducerImpl(ProducerOptions options, ProducerStorage producerStorage) {
    this.maxQuota = options.getMaxQuota();
    this.fetchPendingPeriod = options.getFetchPendingPeriod();
    this.metrics = createMetrics();
    this.callback = (future) -> {
      Event event = future.event();
      long duration = Instant.now().getEpochSecond() - event.head().timestamp();
      metrics.sendEnd(future.succeeded(), duration);
      mark(event, future.succeeded() ? 1 : 2);
      //当队列中的数量小于最大数量的一半时，从持久层中记载队列
      if (producerStorage != null && queue.size() < maxQuota / 2) {
        schedule(0);
      }
    };
    if (producerStorage != null) {
      //只有开启持久化时才启动工作线程
      registerStorage(producerStorage);
      workExecutor = Executors.newFixedThreadPool(options.getWorkerPoolSize(),
              NamedThreadFactory.create
                      ("eventbus-producer-worker"));
    } else {
      workExecutor = null;
    }
  }

  public abstract EventFuture sendEvent(Event event);

  @Override
  public void send(Event event) {
    String storage = event.head().ext("__storage");
    //持久化的消息，就认为成功，可以直接返回，不在加入发送队列
    if (producerStorage != null && !"1".equals(storage) && producerStorage.shouldStorage(event)) {
      //与调用方在同一个线程处理
      producerStorage.save(event);
      return;
    }
    if (queue.size() > maxQuota && !"1".equals(storage)) {
      LOGGER.warn("[{}] [EP] [throttle] [{}] [{}] [maxQuota:{}]",
              event.head().id(), Helper.toHeadString(event), Helper.toActionString(event), maxQuota);
      throw SystemException.create(DefaultErrorCode.TOO_MANY_REQ)
              .set("maxQuota", maxQuota);
    }
    Runnable command = () -> {
      long current = Instant.now().getEpochSecond();
      if (event.head().duration() > 0
              && current > event.head().timestamp() + event.head().duration()) {
        LOGGER.info("[{}] [EP] [expire] [{}] [{}]",
                event.head().id(), Helper.toHeadString(event), Helper.toActionString(event));
        mark(event, 3);
      } else {
        sendEvent(event)
                .setCallback(callback);
      }
      metrics.sendStart();
    };
    queue.execute(command, producerExecutor);
    metrics.sendEnqueue();
  }

  @Override
  public void close() {
    producerExecutor.shutdown();
    scheduledExecutor.shutdown();
    if (workExecutor != null) {
      workExecutor.shutdown();
    }
  }

  @Override
  public Map<String, Object> metrics() {
    return metrics.metrics();
  }

  @Override
  public long waitForSend() {
    ThreadPoolExecutor poolExecutor = (ThreadPoolExecutor) producerExecutor;
    return poolExecutor.getTaskCount() - poolExecutor.getCompletedTaskCount();
  }

  /**
   * 注册消息的持久化
   *
   * @param producerStorage
   * @return
   */
  private EventProducerImpl registerStorage(ProducerStorage producerStorage) {
    //生产者不像消费者有一个线程在启动
//    if (running) {
//      throw new UnsupportedOperationException("producer has been started");
//    }
    this.producerStorage = producerStorage;
    if (producerStorage != null) {
      schedule(fetchPendingPeriod);
    }
    return this;
  }

  private void schedule(long delay) {
    Runnable scheduledCommand = () -> {
      List<Event> pending = producerStorage.pendingList();
      //如果queue的中有数据，那么schedule会在回调中执行
      if (pending.isEmpty() && queue.size() == 0) {
        schedule(fetchPendingPeriod);
      } else {
        for (Event event : pending) {
          //在消息头加上一个标识符，标明是从存储中读取，不在进行持久化
          event.head().addExt("__storage", "1");
          send(event);
        }
      }

    };
    if (delay > 0) {
      scheduledExecutor.schedule(scheduledCommand, delay, TimeUnit.MILLISECONDS);
    } else {
      //直接运行
      scheduledExecutor.submit(scheduledCommand);
    }
  }

  private ProducerMetrics createMetrics() {
    ServiceLoader<ProducerMetrics> metrics = ServiceLoader.load(ProducerMetrics.class);
    Iterator<ProducerMetrics> iterator = metrics.iterator();
    if (!iterator.hasNext()) {
      return new DummyMetrics();
    } else {
      return iterator.next();
    }
  }

  private void mark(Event event, int status) {
    try {
      if (producerStorage != null && producerStorage.shouldStorage(event)) {
        //使用一个工作线程来存储
        workExecutor.submit(() -> {
          producerStorage.mark(event, status);
          LOGGER.debug("[{}] [EP] [marked] [{}]", event.head().id(), status);
        });
      }
    } catch (Exception e) {
      LOGGER.warn("[{}] [EP]  [failed] [{}]", event.head().id(), status, e);
    }
  }

}
