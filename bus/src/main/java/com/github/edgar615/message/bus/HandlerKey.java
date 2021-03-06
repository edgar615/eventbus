package com.github.edgar615.message.bus;

import java.util.Objects;

class HandlerKey {

  private final String topic;

  private final String resource;

  HandlerKey(String topic, String resource) {
    this.topic = topic;
    this.resource = resource;
  }

  public String topic() {
    return topic;
  }

  public String resource() {
    return resource;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    HandlerKey that = (HandlerKey) o;
    return Objects.equals(topic, that.topic) &&
        Objects.equals(resource, that.resource);
  }

  @Override
  public int hashCode() {
    return Objects.hash(topic, resource);
  }
}
