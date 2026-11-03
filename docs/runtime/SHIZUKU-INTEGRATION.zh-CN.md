# Shizuku 有限输入后端

文档展示字体：微软雅黑。核对日期：2026-11-03。

本阶段新增应用内 Shizuku 控制器、UserService 和四种有限输入动作。登录、模型调用和现有无障碍控制保持各自路径。本文先记录代码行为与待验项目，构建和模拟器实测结果由对应阶段报告补充。

## 官方依赖与许可

Maven Central 的 API、provider 元数据当前均以 **13.1.5** 为稳定版本。固定依赖：

```groovy
implementation 'dev.rikka.shizuku:api:13.1.5'
implementation 'dev.rikka.shizuku:provider:13.1.5'
```

API 库采用 MIT License，版权所有者为 RikkaW；分发时随包保留完整许可。Shizuku 管理器是独立应用，安装包版本和许可另行记录。

| 公开 Maven 产物 | 本次下载的 SHA256 |
| --- | --- |
| api-13.1.5.aar | `4def9bde498ef8626614c2fc5db9af4749c86f16f6c33e3f5658d35e70bab59b` |
| provider-13.1.5.aar | `b0f18cd9812464ec171c53cac93a819fe411718a3965c311f01eb4de265381b3` |
| aidl-13.1.5.aar | `33fe7191cdd69fcb66d649264f3b0c47acb2f3d6343afc05b98dbbff6f221963` |
| shared-13.1.5.aar | `4659642c9339be0a26e9c65bb8648f7ad6d8f4a465f557993ccbc78802381635` |

完整 MIT 原文的本次 SHA256 为 `64870037f294b6f2b75c8ebe77cc0702acb3b64f86b4b0e58bf8558525303686`。AAR 与许可来自公开地址，记录中无需账号凭据。

官方来源：

- [Shizuku-API 接入说明](https://github.com/RikkaApps/Shizuku-API/blob/master/README.md)
- [API 稳定版本元数据](https://repo.maven.apache.org/maven2/dev/rikka/shizuku/api/maven-metadata.xml)、[provider 元数据](https://repo.maven.apache.org/maven2/dev/rikka/shizuku/provider/maven-metadata.xml)
- [API 13.1.5 POM](https://repo.maven.apache.org/maven2/dev/rikka/shizuku/api/13.1.5/api-13.1.5.pom)、[provider 13.1.5 POM](https://repo.maven.apache.org/maven2/dev/rikka/shizuku/provider/13.1.5/provider-13.1.5.pom)
- [MIT 许可](https://github.com/RikkaApps/Shizuku-API/blob/master/LICENSE)
- [官方 UserService 示例](https://github.com/RikkaApps/Shizuku-API/blob/master/demo/src/main/java/rikka/shizuku/demo/service/UserService.java)
- [官方 Shizuku 类与权限查询实现](https://github.com/RikkaApps/Shizuku-API/blob/master/api/src/main/java/rikka/shizuku/Shizuku.java)
- [Shizuku 启动指南](https://shizuku.rikka.app/guide/setup/)
- [Android 10 官方 input 实现](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-10.0.0_r1/cmds/input/src/com/android/commands/input/Input.java)

## 启动方式与实际身份

应用按真实 UID 展示运行方式：`2000` 为 ADB／shell，`0` 为 ROOT；其他 UID 拒绝接入。两种已识别身份使用相同的有限动作集，界面持续展示实际身份。本应用的无 root 路线使用 ADB 模式。

Android 11+ 可按官方指南通过无线调试在手机上启动 Shizuku；设备重启后需要重新启动。Android 10 的无 root 启动仍需电脑执行一次官方启动步骤。管理器和系统权限的确认由用户完成，应用提供手动授权与连接入口。

## 根工程接入契约

应用需要开启 `buildFeatures.aidl = true`，添加受系统权限保护的 provider：

```xml
<provider
    android:name="rikka.shizuku.ShizukuProvider"
    android:authorities="${applicationId}.shizuku"
    android:multiprocess="false"
    android:enabled="true"
    android:exported="true"
    android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
```

provider 依赖的 manifest 提供 `moe.shizuku.manager.permission.API_V23` 和 `moe.shizuku.client.V3_SUPPORT`。合并清单时应核对这些条目及 provider 的保护权限。`XiyanInputService` 是供 Shizuku 创建的 Binder 类，应用清单无需注册 Android Service。

当前关闭 R8 混淆；后续启用时，应保留 `XiyanInputService` 类及其无参、`Context` 构造器，并检查生成 AIDL 接口的可达性。

公共入口是 `ShizukuController.getInstance(Context)`：

| 方法 | 行为 |
| --- | --- |
| `snapshot()` | 返回 binder、实时授权、绑定、忙碌、UID、服务版本、代次、身份及固定状态码。 |
| `addListener(Runnable)` / `removeListener(Runnable)` | 主线程状态观察；页面退出时移除自身订阅。 |
| `refresh()` | 后台查询状态；保持手动授权流程。 |
| `requestPermission()` | 仅由用户明确点击触发官方授权请求。 |
| `connect()` | 已授权后绑定非 daemon UserService，校验协议、实际 UID 和应用身份。 |
| `execute(long expectedGeneration, InputAction, ResultListener)` | 占用单一动作槽，返回是否接受。终态带代次、请求 ID、固定结果码及退出码。 |
| `stop()` | 立即失效本地代次，独立通道发送远程取消，等待实际终态释放动作槽。 |
| `disconnect()` | 失效代次、取消并解绑服务。 |

`Snapshot.mode` 为 `UNKNOWN`、`ADB` 或 `ROOT`。`Snapshot.status` 仅包含本地固定状态码。`ActionResult.commandCompleted()` 要求当前代次、命令结果完成和退出码 0。页面效果由上层真实观察单独确认，自动任务仍应经过现有 ActionGate。

## 动作与取消约束

`InputAction` 仅构造点击、滑动、返回和主页。坐标为默认显示器的绝对像素；桥接层负责从已观察窗口正确映射到显示器。动作带显示宽高及 `Surface` 旋转值 0～3，远端重新读取显示器几何，尺寸或方向变化即拒绝。当前支持 display 0。

宽高上限为 16384；坐标逐项检查边界，滑动时长为 50～1500ms。命令通过固定 `/system/bin/input` 和独立参数数组执行，参数只由通过校验的整数及有限常量生成。IPC 提供结构化动作，没有自由命令、应用启动或文本输入字段。

服务在创建时从可信包 Context 与 PackageManager 取得应用 UID、包名和安装签名。每次入口检查 Binder caller UID 与唯一包名，握手再匹配两端取得的签名摘要。默认构造路径缺少可信 Context 时拒绝控制。UserService 跟踪应用 owner Binder；应用死亡、服务死亡、授权撤销及解绑都会失效连接。

官方便捷权限方法会缓存已授予状态。此处通过同版本依赖的类型化 `IShizukuService.checkSelfPermission()` 实时查询，每次动作前检查，绑定期间每秒查询一次。撤销或查询失败会取消并解绑。

动作单槽串行，取消代次严格递增；旧代次及重复请求拒绝执行，旧终态只释放自身槽位，当前成功状态经过再次代次检查。同步 cancel 走独立 Binder 通道，执行工作线程负责进程等待。绑定与命令均有看门狗，命令等待上限 6000ms，超时后终止进程并有界等待；终止情况仍不确定时，服务停止接受替代动作。

Stop 无法撤回已经注入系统的事件。不同 Android 版本的 input 可能把工作转交 system_server；结束客户端进程后，已开始的系统手势仍可能运行到有界时长。停止后应通过实际观察确认设备终态，保留占位及失败恢复策略。取消 ACK、进程退出和画面效果分别记录，验证报告应采用实际结果。

有活动输入时，控制器额外保留至少 1750ms 的忙碌窗口，使用单调时钟计时；提前收到终态或 idle ACK 也保留这一等待。重复 Stop 保持原截止时间，旧代次的定时器只刷新显示。等待结束且远端终态明确后，用户才可发起下一次显式动作。这一保守等待用于减少紧邻动作重叠，物理终态继续按实际观察核验。

## 验证安排

新增 19 项纯 Java 测试，覆盖固定参数向量、显示边界、时长与非法字段、几何变化、单槽占用、重复请求、停止代次、迟到结果、owner 失效、取消竞争，以及提前 ACK／终态、重复 Stop 与单调时钟截止边界。当前实现提交给主 Agent 统一执行构建与测试，本文暂未记为已通过。

模拟器阶段应分别验证：

1. 管理器未运行、未授权、用户拒绝、允许、绑定中、绑定成功及 ADB／ROOT 实际身份。
2. 本应用测试台点击计数、实际滑动位移、返回和主页；派发／完成与画面效果分别留证。
3. 默认显示器尺寸和旋转匹配，旧几何、越界坐标、其他 display 及非法时长拒绝。
4. 派发前停止、滑动中停止、等待迟到结果、解绑和服务死亡；确认终态后才开放后续动作。
5. 管理器撤销许可、应用退出和重新连接；当前回合失效，连接与权限状态按实际结果更新。
6. 检查最终合并清单、AIDL 编译、许可入包、APK 来源及签名；物理设备验证另行安排。
