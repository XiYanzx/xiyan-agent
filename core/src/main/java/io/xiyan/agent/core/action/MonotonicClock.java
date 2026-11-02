package io.xiyan.agent.core.action;

/** 同一设备启动周期内的单调毫秒时钟；Android 适配器使用 SystemClock.elapsedRealtime()。 */
@FunctionalInterface
public interface MonotonicClock {
    long nowMillis();
}
