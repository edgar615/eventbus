package com.github.edgar615.message.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by Edgar on 2017/3/22.
 *
 * @author Edgar  Date 2017/3/22
 */
public class RequestCodec implements MessageBodyCodec {
  @Override
  public MessageBody decode(Map<String, Object> map) {
    String op = (String) map.get("operation");
    String resource = (String) map.get("resource");
    Map<String, Object> content = (Map<String, Object>) map.get("content");
    return Request.create(resource,op, content);
  }

  @Override
  public Map<String, Object> encode(MessageBody action) {
    Request request = (Request) action;
    Map<String, Object> map = new HashMap<>();
    map.put("resource", request.resource());
    map.put("operation", request.operation());
    map.put("content", request.content());
    return map;
  }

  @Override
  public String name() {
    return Request.TYPE;
  }
}
