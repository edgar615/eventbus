package com.github.edgar615.eventbus.bus;

import com.github.edgar615.eventbus.event.Event;
import com.github.edgar615.eventbus.metrics.ConsumerMetrics;
import com.github.edgar615.eventbus.metrics.DummyMetrics;
import com.github.edgar615.eventbus.utils.DefaultEventQueue;
import com.github.edgar615.eventbus.utils.EventQueue;
import com.github.edgar615.eventbus.utils.NamedThreadFactory;
import com.github.edgar615.eventbus.utils.SequentialEventQueue;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.BiPredicate;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Created by Edgar on 2017/4/18.
 *
 * @author Edgar  Date 2017/4/18
 */
public abstract class EventConsumerImplV2 implements EventConsumer {

  private static final Logger LOGGER = LoggerFactory.getLogger(EventConsumer.class);

  private final ExecutorService workerExecutor;

  private final BlockedEventChecker checker;

  private final ConsumerMetrics metrics;

  private final EventQueue eventQueue;

  private Function<Event, Boolean> blackListFilter = event -> false;

  private volatile boolean running = false;

  private ConsumerStorage consumerStorage;

  private EventBusReadStream readStream;

  private final int workerCount;

  EventConsumerImplV2(ConsumerOptions options) {
    this(options, null, null, null);
  }

  EventConsumerImplV2(ConsumerOptions options, EventBusReadStream readStream, EventQueue queue,
      EventBusScheduler eventBusScheduler) {
    this.metrics = createMetrics();
    this.workerCount = options.getWorkerPoolSize();
    this.workerExecutor = Executors.newFixedThreadPool(options.getWorkerPoolSize(),
        NamedThreadFactory.create
            ("event-consumer-worker"));
    if (options.getBlockedCheckerMs() > 0) {
      this.checker = BlockedEventChecker
          .create(options.getBlockedCheckerMs(),
              eventBusScheduler.executor());
    } else {
      checker = null;
    }
    running = true;
    this.consumerStorage = consumerStorage;
    if (blackListFilter == null) {
      this.blackListFilter = e -> false;
    } else {
      this.blackListFilter = blackListFilter;
    }
  }

  public void start() {
    for (int i = 0; i < workerCount; i ++) {
      workerExecutor.submit(() -> {

      });
    }
  }

  private final void handle() {
    workerExecutor.submit(() -> {
      Event event = null;
      try {
        event = eventQueue.dequeue();
        EventIdTracing eventIdTracing = new EventIdTracing(event.head().id());
        EventIdTracingHolder.set(eventIdTracing);
        MDC.put("x-request-id", event.head().id());
        long start = System.currentTimeMillis();
        BlockedEventHolder holder = BlockedEventHolder.create(event.head().id(), blockedCheckerMs);
        if (checker != null) {
          checker.register(holder);
        }
        metrics.consumerStart();
        if (isConsumed(event) || isBlackList(event)) {
          //忽略 handle
        } else {
          doHandle(event);
        }
        holder.completed();
        long duration = System.currentTimeMillis() - start;
        metrics.consumerEnd(duration);
        eventQueue.complete(event);
        LOGGER.info("[{}] [EC] [OK] [{}ms]", event.head().id(), duration);
      } catch (InterruptedException e) {
        LOGGER.error("[EC] [Interrupted]");
//        因此中断一个运行在线程池中的任务可以起到双重效果，一是取消任务，二是通知执行线程线程池正要关闭。如果任务生吞中断请求，则 worker
// 线程将不知道有一个被请求的中断，从而耽误应用程序或服务的关闭
        Thread.currentThread().interrupt();
      } catch (Exception e) {
        LOGGER.error("[{}] [EC] [failed]", event.head().id(), e);
      } finally {
        EventIdTracingHolder.clear();
        MDC.remove("x-request-id");
      }
    });
  }

  @Override
  public void close() {
    //关闭消息订阅
    running = false;
    LOGGER.info("closing consumer, remaining:{}", waitForHandle());
    workerExecutor.shutdown();
    if (scheduledExecutor != null) {
      scheduledExecutor.shutdown();
    }
  }

  @Override
  public long waitForHandle() {
    ThreadPoolExecutor poolExecutor = (ThreadPoolExecutor) workerExecutor;
    return poolExecutor.getTaskCount() - poolExecutor.getCompletedTaskCount();
  }

  @Override
  public Map<String, Object> metrics() {
    return metrics.metrics();
  }

  @Override
  public void consumer(BiPredicate<String, String> predicate, EventHandler handler) {
    HandlerRegistration.instance().registerHandler(predicate, handler);
  }

  @Override
  public void consumer(String topic, String resource, EventHandler handler) {
    final BiPredicate<String, String> predicate = (t, r) -> {
      boolean topicMatch = true;
      if (topic != null) {
        topicMatch = topic.equals(t);
      }
      boolean resourceMatch = true;
      if (resource != null) {
        resourceMatch = resource.equals(r);
      }
      return topicMatch && resourceMatch;
    };
    consumer(predicate, handler);
  }

  protected void enqueue(List<Event> events) {
    eventQueue.enqueue(events);
    //只提交任务不属于黑名单的消息
    for (Event event : events) {
      handle();
    }
  }

  private boolean isBlackList(Event event) {
    if (blackListFilter != null && blackListFilter.apply(event)) {
      LOGGER.warn("[{}] [EC] [blacklist]", event.head().id());
      if (consumerStorage != null && consumerStorage.shouldStorage(event)) {
        consumerStorage.mark(event, 3);
      }
      return true;
    }
    return false;
  }

  /**
   * 入队，如果入队后队列中的任务数量超过了最大数量，暂停消息的读取
   *
   * @return 如果队列中的长度超过最大数量，返回false
   */
  protected void enqueue(Event event) {
    //先入队
    eventQueue.enqueue(event);
    //提交任务，这里只是创建了一个runnable交由线程池处理，而这个runnable并不一定真正的处理的是当前的event（根据队列的实现来）
    handle();
  }

  protected boolean isFull() {
    return eventQueue.isFull();
  }

  protected boolean isLowWaterMark() {
    return eventQueue.isLowWaterMark();
  }

  protected int size() {
    return eventQueue.size();
  }

  public boolean isRunning() {
    return running;
  }

  private boolean isConsumed(Event event) {
    if (consumerStorage != null && consumerStorage.shouldStorage(event)
        && consumerStorage.isConsumed(event)) {
      LOGGER.warn("[{}] [EC] [repeated]", event.head().id());
      return true;
    }
    return false;
  }

  private void doHandle(Event event) {
    try {
      List<EventHandler> handlers =
          HandlerRegistration.instance()
              .getHandlers(event);
      if (handlers == null || handlers.isEmpty()) {
        LOGGER.warn("[{}] [EC] [no handler]", event.head().id());
      } else {
        for (EventHandler handler : handlers) {
          handler.handle(event);
        }
      }
      if (consumerStorage != null && consumerStorage.shouldStorage(event)) {
        consumerStorage.mark(event, 1);
      }
    } catch (Exception e) {
      LOGGER.error("[{}] [EC] [failed]", event.head().id(), e);
      if (consumerStorage != null && consumerStorage.shouldStorage(event)) {
        consumerStorage.mark(event, 2);
      }
    }
  }

  private ConsumerMetrics createMetrics() {
    ServiceLoader<ConsumerMetrics> metrics = ServiceLoader.load(ConsumerMetrics.class);
    Iterator<ConsumerMetrics> iterator = metrics.iterator();
    if (!iterator.hasNext()) {
      return new DummyMetrics();
    } else {
      return iterator.next();
    }
  }

}
