package com.github.edgar615.message.vertx;

import com.github.edgar615.message.core.Message;
import com.github.edgar615.message.repository.ConsumeMessageState;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import java.util.List;

/**
 * Created by Edgar on 2018/5/12.
 *
 * @author Edgar  Date 2018/5/12
 */
public interface VertxMessageConsumerRepository {

  /**
   * 插入一个事件，插入的过程中需要判断任务是否是重复消息，重复消息不在继续处理
   *
   * @return 重复消息false，非重复消息 true
   */
  void insert(Message message, Handler<AsyncResult<Boolean>> resultHandler);

  /**
   * 从数据库中取出十条未处理的事件
   *
   * @return 待发送的事件列表
   */
  default void waitingForConsume(Handler<AsyncResult<List<Message>>> resultHandler) {
    waitingForConsume(10, resultHandler);
  }

  /**
   * 从数据库中取出未处理的事件
   *
   * @param fetchCount 最大数量
   * @return 事件列表
   */
  void waitingForConsume(int fetchCount, Handler<AsyncResult<List<Message>>> resultHandler);

  /**
   * 标记事件
   *
   * @param eventId 事件ID
   * @param state 1-待处理，2-处理成功 3-处理失败 4-过期
   */
  void mark(String eventId, ConsumeMessageState state, Handler<AsyncResult<Void>> resultHandler);
}
