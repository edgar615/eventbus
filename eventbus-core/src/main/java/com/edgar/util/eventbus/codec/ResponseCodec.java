package com.edgar.util.eventbus.codec;

import com.edgar.util.eventbus.event.EventAction;
import com.edgar.util.eventbus.event.Response;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by Edgar on 2017/3/22.
 *
 * @author Edgar  Date 2017/3/22
 */
public class ResponseCodec implements EventActionCodec {
  @Override
  public EventAction decode(Map<String, Object> map) {
    Integer result = (Integer) map.get("result");
    Map<String, Object> content = (Map<String, Object>) map.get("content");
    String reply = (String) map.get("reply");
    return Response.create(result, reply, content);
  }

  @Override
  public Map<String, Object> encode(EventAction action) {
    Response response = (Response) action;
    Map<String, Object> map = new HashMap<>();
    map.put("result", response.result());
    map.put("reply", response.reply());
    map.put("content", response.content());
    return map;
  }
}
