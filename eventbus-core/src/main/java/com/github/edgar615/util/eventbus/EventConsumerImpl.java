package com.github.edgar615.util.eventbus;

import com.github.edgar615.util.concurrent.NamedThreadFactory;
import com.github.edgar615.util.event.Event;
import com.github.edgar615.util.log.Log;
import com.github.edgar615.util.metrics.ConsumerMetrics;
import com.github.edgar615.util.metrics.DummyMetrics;
import com.github.edgar615.util.metrics.Metrics;
import com.github.edgar615.util.metrics.ProducerMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.*;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Created by Edgar on 2017/4/18.
 *
 * @author Edgar  Date 2017/4/18
 */
public abstract class EventConsumerImpl implements EventConsumer {
  private static final Logger LOGGER = LoggerFactory.getLogger(EventConsumer.class);

  private final ExecutorService workerExecutor;

  private final ScheduledExecutorService scheduledExecutor;

  private final BlockedEventChecker checker;

  private final int blockedCheckerMs;

  private final ConsumerMetrics metrics;

  private final EventQueue eventQueue;

  protected Function<Event, Boolean> blackListFilter = event -> false;

  private int maxQuota;

  private volatile boolean running = true;


  EventConsumerImpl(ConsumerOptions options) {
    this.metrics = createMetrics();
    this.workerExecutor = Executors.newFixedThreadPool(options.getWorkerPoolSize(),
            NamedThreadFactory.create
                    ("eventbus-worker"));
    if (options.getIdentificationExtractor() == null) {
      this.eventQueue = new DefaultEventQueue(options.getMaxQuota());
    } else {
      this.eventQueue = new SequentialEventQueue(options.getIdentificationExtractor(),
              options.getMaxQuota());
    }
    this.blockedCheckerMs = options.getBlockedCheckerMs();
    if (options.getBlockedCheckerMs() > 0) {
      this.scheduledExecutor =
              Executors.newSingleThreadScheduledExecutor(
                      NamedThreadFactory.create("eventbus-blocker-checker"));
      this.checker = BlockedEventChecker
              .create(options.getBlockedCheckerMs(),
                      scheduledExecutor);
    } else {
      checker = null;
      this.scheduledExecutor = null;
    }
    maxQuota = options.getMaxQuota();
    //注册一个关闭钩子
//    一个shutdown hook就是一个初始化但没有启动的线程。 当虚拟机开始执行关闭程序时，它会启动所有已注册的shutdown hook（不按先后顺序）并且并发执行。
    Runtime.getRuntime().addShutdownHook(createHookTask());
  }


  @Override
  public void close() {
    //关闭消息订阅
    running = false;
    Log.create(LOGGER)
            .setLogType("eventbus-consumer")
            .setEvent("close")
            .info();
    workerExecutor.shutdown();
    if (scheduledExecutor != null) {
      scheduledExecutor.shutdown();
    }
  }

  /**
   * 入队，如果入队后队列中的任务数量超过了最大数量，暂停消息的读取
   *
   * @param event
   * @return 如果队列中的长度超过最大数量，返回false
   */
  protected synchronized void enqueue(Event event) {
    //先入队
    eventQueue.enqueue(event);
    //提交任务，这里只是创建了一个runnable交由线程池处理，而这个runnable并不一定真正的处理的是当前的event（根据队列的实现来）
    handle();
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

  private Thread createHookTask() {
    return new Thread() {
      @Override
      public void run() {
        close();
        //等待任务处理完成
        ThreadPoolExecutor poolExecutor = (ThreadPoolExecutor) workerExecutor;
        long start = System.currentTimeMillis();
        while (poolExecutor.getTaskCount() - poolExecutor.getCompletedTaskCount() > 0) {
          try {
            TimeUnit.SECONDS.sleep(1);
          } catch (InterruptedException e) {
            e.printStackTrace();
          }
          Log.create(LOGGER)
                  .setLogType("eventbus-consumer")
                  .setEvent("close.waiting")
                  .addData("remaing", poolExecutor.getTaskCount() - poolExecutor.getCompletedTaskCount())
                  .addData("duration", System.currentTimeMillis() - start)
                  .info();
        }
        Log.create(LOGGER)
                .setLogType("eventbus-consumer")
                .setEvent("closed")
                .info();
      }
    };
  }

  protected boolean isRunning() {
    return running;
  }

  protected synchronized boolean isFull() {
    return eventQueue.size() >= maxQuota;
  }

  protected synchronized int size() {
    return eventQueue.size();
  }

  protected synchronized void enqueue(List<Event> events) {
    eventQueue.enqueue(events);
    //提交任务
    for (Event event : events) {
      handle();
    }
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


  private void handle() {
    workerExecutor.execute(() -> {
      Event event = null;
      try {
        event = eventQueue.dequeue();
        long start = Instant.now().getEpochSecond();
        BlockedEventHolder holder = BlockedEventHolder.create(event, blockedCheckerMs);
        if (checker != null) {
          checker.register(holder);
        }
        metrics.consumerStart();
        try {
          List<EventHandler> handlers =
                  HandlerRegistration.instance()
                          .getHandlers(event);
          if (handlers == null || handlers.isEmpty()) {
            Log.create(LOGGER)
                    .setLogType("eventbus-consumer")
                    .setEvent("handle")
                    .setTraceId(event.head().id())
                    .setMessage("NO HANDLER")
                    .warn();
          } else {
            for (EventHandler handler : handlers) {
              handler.handle(event);
            }
          }
        } catch (Exception e) {
          Log.create(LOGGER)
                  .setLogType("eventbus-consumer")
                  .setEvent("handle")
                  .setTraceId(event.head().id())
                  .setThrowable(e)
                  .error();
        }
        metrics.consumerEnd(Instant.now().getEpochSecond() - start);
        holder.completed();
//        completeFuture.complete();
      } catch (InterruptedException e) {
        Log.create(LOGGER)
                .setLogType("eventbus-consumer")
                .setEvent("handle")
                .setThrowable(e)
                .error();
//        因此中断一个运行在线程池中的任务可以起到双重效果，一是取消任务，二是通知执行线程线程池正要关闭。如果任务生吞中断请求，则 worker 线程将不知道有一个被请求的中断，从而耽误应用程序或服务的关闭
        Thread.currentThread().interrupt();
      } catch (Exception e) {
        Log.create(LOGGER)
                .setLogType("eventbus")
                .setEvent("handle")
                .setTraceId(event.head().id())
                .setThrowable(e)
                .error();
      }
    });
  }

}
