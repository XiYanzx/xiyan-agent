// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

import java.util.Objects;
import java.util.function.Consumer;

/** 截图异步请求的交付门。清除返回后，旧请求只能释放资源，不能再次显示。 */
final class CaptureRequestTracker<T> {
    private final Consumer<T> dispose;
    private long generation;

    CaptureRequestTracker(Consumer<T> dispose) { this.dispose = Objects.requireNonNull(dispose); }

    synchronized long begin() { return ++generation; }

    /** 用户清除、停止及页面退出共用同一撤销操作。 */
    synchronized void cancel() { generation++; }

    synchronized boolean isCurrent(long token) { return token == generation; }

    synchronized boolean deliver(long token, T resource, Consumer<T> display) {
        Objects.requireNonNull(resource);
        Objects.requireNonNull(display);
        if (token <= 0 || token != generation) {
            dispose.accept(resource);
            return false;
        }
        generation++; // 同一回调最多交付一次；重复回调仍需释放资源。
        try {
            display.accept(resource);
        } catch (RuntimeException | Error failure) {
            dispose.accept(resource);
            throw failure;
        }
        return true;
    }

    void discard(T resource) { dispose.accept(resource); }
}
