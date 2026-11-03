# P0 架构与实现边界

当前版本是本机控制技术验证入口。Android 界面和控制器已有实现；纯 Java 安全协议与模型元数据模块已有独立实现，**尚未接入 Android 执行路径**。P0 尚未验收，P1–P6 均未验收；本文件不代表账号、运行时或真机测试通过。

完整目标见[需求任务书](REQUIREMENTS.zh-CN.md)，技术路线与验收门槛见 [P0 可行性报告](P0-feasibility.md)。

## 当前组件

| 组件 | 已实现内容 | 当前边界 |
| --- | --- | --- |
| [MainActivity](../app/src/main/java/io/xiyan/agent/MainActivity.java) | 中文首页、模式偏好、权限设置入口、停止入口、诊断导出 | 模式选择只保存偏好，不启动通用或游戏引擎；模型和额度显示未连接或未知 |
| [TestPadActivity](../app/src/main/java/io/xiyan/agent/TestPadActivity.java) | 本应用内观察、内存截图、单步点击和滑动验证 | 坐标来自本页真实布局；用目标点击计数和滚动位置复核，不涉及第三方 App 或真实游戏 |
| [CaptureRequestTracker](../app/src/main/java/io/xiyan/agent/CaptureRequestTracker.java) | 清除、停止、退出与新截图请求使旧回调失效，迟到或重复图像被释放 | 实际接线 TestPad；单元测试验证交付语义，未替代设备截图测试 |
| [XiyanAccessibilityService](../app/src/main/java/io/xiyan/agent/control/XiyanAccessibilityService.java) | 系统无障碍连接、窗口元数据与有界节点计数、截图、短手势、自有授权撤销逻辑 | 截图与动作限于默认显示器上解锁、前台且有焦点的 TestPad；没有任务执行引擎 |
| [Diagnostics](../app/src/main/java/io/xiyan/agent/Diagnostics.java) | SharedPreferences 保存最近 40 条应用生成事件、JSON 导出 | 全进程共享锁下重新读写，避免不同界面覆盖事件；不是完整执行审计、任务数据库或检查点 |
| [RuntimeProbe](../app/src/main/java/io/xiyan/agent/RuntimeProbe.java) | 显示 API、ABI、系统页大小及运行时缺失状态 | 应用未打包 Codex app-server 或 code-mode-host，不下载或执行它们 |
| [ActionGate](../core/src/main/java/io/xiyan/agent/core/action/ActionGate.java) | 纯 Java 设备租约、动作队列、观察校验和结果确认协议 | 仅有 JVM 契约测试；Android 控制器使用自己的锁、generation 和 operation，尚未调用本类 |
| [ModelCatalog](../core/src/main/java/io/xiyan/agent/core/model/ModelCatalog.java) | 从已解码元数据建立目录并校验模型参数 | 无网络、OAuth、账户目录或推理调用；尚未接入界面，详见[模型兼容说明](model-compatibility.zh-CN.md) |

`app` 对 `core` 的 Gradle 依赖只是模块引用，不证明运行时接线已经完成。

## Android 控制路径

当前实际路径为 `TestPadActivity → XiyanAccessibilityService → Android 截图 / 手势 API → 系统回调 → TestPad 复核`。

- 用户通过系统无障碍设置授权服务。进入 TestPad 或服务重新连接不会自动开启动作权限；每次点击验证按钮只授权一个短手势。
- 控制器自行维护 generation、最新观察和单一 operation。观察使用 `SystemClock.elapsedRealtime()`，有效期为 1500 ms；派发前复核观察身份、代次、当前包、窗口、显示器、方向、尺寸和窗口边界，坐标必须位于窗口内。
- 点击使用短手势；滑动时长限制为 50–500 ms。观察节点采样有数量、深度和时间上限，只统计节点，不读取或保存节点文本。
- 停止与最终手势提交共享控制器自己的锁。停止递增 generation 并撤销授权，旧代次不能继续派发；系统已经接收的手势仍可能产生触点，不能宣称物理动作被撤回。
- 操作超时为 2 秒。停止或超时不能作为平台动作结束的证据；已被平台接受的操作保留占位等待回调，服务断开另行清理状态。迟到回调不会恢复旧授权。
- TestPad 离开前台会撤销控制，清理界面等待回调和内存截图；旋转或显示配置变化需要重新启用。系统完成回调只说明手势结束，TestPad 还会检查实际计数或滚动位置。
- 截图独立使用请求 token。清除、停止和退出会使待返回图像失效，迟到或重复回调只释放资源，不重新显示；图像只作预览，不直接作为动作坐标图。

窗口指纹能检查窗口和几何变化，不能保证识别同一窗口内的全部画面语义变化；当前没有截图视觉规划或连续动作批次。最低 SDK 设为 Android 10；当前 P0 在 API29–33 禁止截图，API34+ 仅调用 takeScreenshotOfWindow。窗口截图边界仍需真实设备的分屏、覆盖层与权限撤销测试。空节点游戏画面的纯视觉路径尚未实现。

## 纯 Java 协议的验证范围

[ActionGateTest](../core/src/test/java/io/xiyan/agent/core/action/ActionGateTest.java) 覆盖双引擎竞争同一实例、停止与派发竞态、旧 token、观察过期、未来时间、屏幕指纹变化、单一在途动作及结果失效。[ModelCatalogTest](../core/src/test/java/io/xiyan/agent/core/model/ModelCatalogTest.java) 覆盖动态参数和未知值拒绝。

`ActionGate` 的租约绑定 taskId 和 generation，动作绑定观察 ID、序号与截止时间；屏幕状态包含 windowId、screenEpoch、尺寸和方向。派发和结果确认都会重新检查。平台适配器必须真实维护 screenEpoch、观察可用性、权限事件和后置条件；它们不会由纯 Java 核心自行识别。

这些测试只能验证内存中的协议行为。它们没有运行 Android 控制器，不能证明真实截图新鲜度、设备坐标、系统手势取消或停止后的触点行为。`ActionGate` 也不是跨进程设备锁或持久任务监督器。

应用模块另有实际接线的 [CaptureRequestTrackerTest](../app/src/test/java/io/xiyan/agent/CaptureRequestTrackerTest.java) 与 [WindowCapturePolicyTest](../app/src/test/java/io/xiyan/agent/control/WindowCapturePolicyTest.java)，共12项，覆盖迟到/重复图像释放、撤销与交付竞争，以及窗口截图的平台、包名、显示器和图像预算限制。它们不证明系统返回像素的设备隔离行为。

## 后续接线与验收

后续先将平台观察和执行适配器接到共享 `ActionGate`，避免两套授权状态长期并存，再用真实设备验证旧帧、旋转、后台切换、权限撤销和停止竞态。接线之前，控制器验证与核心协议测试必须分别记录。

Codex 运行时、官方授权、账户模型目录和真实工具回合属于剩余 P0 工作，进展见[运行时 spike](runtime/P0-RUNTIME-SPIKE.zh-CN.md)。当前没有 OAuth、账户令牌存储、app-server 客户端、额度读取或真实模型调用。

P1–P6 的目标依次包括完整基础应用、持久任务状态机与检查点 / Watchdog / 恢复、通用跨 App 执行、基于真实画面的游戏策略、30 / 60 / 120 分钟稳定性验证和正式交付。它们是后续阶段目标，不能从当前测试台或协议测试推定已实现、已验收。
