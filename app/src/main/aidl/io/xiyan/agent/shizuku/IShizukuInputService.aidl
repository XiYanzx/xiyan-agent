package io.xiyan.agent.shizuku;

import io.xiyan.agent.shizuku.IShizukuInputCallback;

interface IShizukuInputService {
    int getProtocolVersion() = 0;
    int getServiceUid() = 1;
    void attach(IBinder owner, long generation, String signerDigest) = 2;
    oneway void execute(long generation, long requestId, int action, int x1, int y1,
        int x2, int y2, int durationMillis, int displayId, int width, int height,
        int rotation, IShizukuInputCallback callback) = 3;
    boolean cancel(long nextGeneration) = 4;
    void destroy() = 16777114;
}
