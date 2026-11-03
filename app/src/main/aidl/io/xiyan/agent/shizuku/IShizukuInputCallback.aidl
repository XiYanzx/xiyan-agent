package io.xiyan.agent.shizuku;

oneway interface IShizukuInputCallback {
    void onResult(long generation, long requestId, int resultCode, int exitCode);
}
