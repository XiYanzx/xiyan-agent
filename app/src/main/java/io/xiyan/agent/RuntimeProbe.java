// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

import android.os.Build;
import android.system.Os;
import android.system.OsConstants;

/** 本版未打包未经 Android 验证的运行时。报告事实，不静默下载或执行代码。 */
public final class RuntimeProbe {
    private RuntimeProbe() { }
    public static boolean isBundled() { return false; }
    public static String describe() {
        long pageSize = Os.sysconf(OsConstants._SC_PAGESIZE);
        return "Android API " + Build.VERSION.SDK_INT + " · "
                + String.join(", ", Build.SUPPORTED_ABIS) + "\n"
                + "系统页大小 " + pageSize + " B\n"
                + "Codex app-server / code-mode-host：未内嵌\n"
                + "官方运行时 Android 编译与启动验证尚未通过。";
    }
}
