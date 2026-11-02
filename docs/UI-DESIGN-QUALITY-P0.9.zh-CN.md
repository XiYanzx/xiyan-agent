# P0.9 界面设计与质量记录

本文件记录 P0.9 采用的视觉方案、实现范围、素材来源和分阶段证据。R2 开发构建的 assemble 与 lint 已通过，最终完整测试、干净提交产物与独立视觉复审仍待完成。本文暂不指定最终源码提交或安装包散列。

设计规范以 [Xiyan Design System 1.0](DESIGN-SYSTEM.zh-CN.md) 为准。[P0.8 界面改版与用量设计](UI-REFRESH-MOBILE-CODEX.zh-CN.md) 保留此前的 Mobile Codex 研究和页面拆分历史；P0.9 继续使用原生 Java / Android View、独立页面和本应用自己的官方授权路线。

## 视觉方向与取舍

| 方向 | 特征 | 取舍 |
| --- | --- | --- |
| 青瓷 · 墨色工作台（采用） | 暖白阅读底、墨绿深色底、青瓷主操作，细分隔线与紧凑工具 | 兼顾中文阅读和任务操作，以克制的青瓷色形成辨识度 |
| 蓝墨 · 精密工作台 | 冷白、深蓝中性底与钴蓝主操作 | 操作语言熟悉，与 P0.8 的蓝紫方案差异较小 |
| 深空 · 指挥台 | 石墨底、紫色强调与较强的控制台气质 | 适合游戏监看，日常对话与长文阅读需要更轻的表达 |

采用青瓷方向后，输入区、实际消息和真实数字分别成为对应页面的中心。大欢迎图形、重复标题、满宽辅助按钮和多层卡片收敛到必要的信息分组。手机与游戏共享组件语言，游戏紫保留给相关场景的小型标记。

## 统一设计系统

颜色、字体、尺寸与动效统一定义在 [DesignTokens.java](../app/src/main/java/io/xiyan/agent/DesignTokens.java) 和 [Ui.java](../app/src/main/java/io/xiyan/agent/Ui.java)，完整规范见 [Design System](DESIGN-SYSTEM.zh-CN.md)。

| 项目 | 采用方式 |
| --- | --- |
| 色彩 | 浅色使用暖白与墨绿文字，深色使用墨绿底与浅色文字；青瓷表示主操作和选择，红色表示停止与失败，琥珀提示需要处理的状态 |
| 字体 | Android 使用系统 sans 与系统中文字体，跟随字号缩放；以 400、500、600 字重分层，数字采用等宽特性 |
| 空间 | 4、8、12、16、20、24、32dp 间距阶梯，页面边距随窄屏调整，交互目标至少 48×48dp |
| 操作 | 青瓷实色主操作、轮廓或浅底次操作、透明文字操作三级；普通按钮移除平台默认阴影，保留按压与焦点反馈 |
| 表面 | 列表、输入区与弹层使用统一边界和圆角；单选弹窗沿用原生选择语义，使用统一的深浅主题 |
| 动效 | 页面进入与侧栏使用短时反馈，尊重系统动画设置；流式回复保持输入区稳定 |

中文文档按 humanizer-zh 核对表达，默认排版字体为微软雅黑；Markdown 的实际字体由阅读器设置决定。

## 主要页面变化

| 页面 | P0.9 实现 |
| --- | --- |
| 聊天 | 短欢迎内容与输入栏同轴。按真实可用高度平衡位置，有草稿时收成轻量引导，空间不足时收起。固定输入区保留模式和模型摘要，默认思考值放在面板及无障碍完整说明中。复制与编辑使用弱操作 |
| 回复与恢复 | 正文增量更新，跟随滚动使用纯位置操作；用户上滚阅读时保留位置。停止与失败后保留原提问，由用户选择继续、编辑或重试；重试确认前后检查当前账号、目录和回合状态。思考参数被接口拒绝时提供“更改思考程度”，进入已有模型面板 |
| 任务 | 两种草稿入口集中呈现，账号、模型和手机控制显示真实准备状态。当前自主执行状态用简短说明表达 |
| 模型 | 搜索、当前使用和待应用选择分清层级。思考程度使用原生单选下拉，默认值清楚标注；已应用选择显示正常状态文字，候选变化后显示应用动作。加载反馈只对应实际目录读取阶段 |
| 用量 | 最近一次完成调用的总计居首，输入与输出并列，缓存和思考等明细为次级数据。周期百分比缺少有效读数时显示 `—` 和原因，保留官方用量入口及原采集时间 |
| 侧栏与账号 | 目的地以紧凑单行为主，当前页突出；账号摘要呈现实际会话状态。连接和续期为主要动作，退出与其他操作分组 |
| 设置与外观 | 偏好、账号和开发入口采用紧凑分组；浅深主题预览与实际选择相对应 |
| 手机控制与开发者 | 权限与管理入口集中呈现；测试台继续保留测量画布、单步验证和诊断用途，沿用统一文字与控件 |

小高度键盘模式隐藏欢迎和辅助消息区，保留完整的菜单、同一个输入框及 48dp 发送/停止工具行；有足够高度时补一行当前语境。模型表单整体滚动，搜索、选项和应用操作采用同一条滚动路径。

## 图标、品牌与许可

功能图标采用 [Lucide](https://lucide.dev/guide/static) 固定修订 `a529c28a8782e7954dde3d1a5e4409e829242b86` 的 33 项 SVG，转换为 Android VectorDrawable，统一 24 单位画布、2 单位圆端轮廓及运行时语义着色。逐项来源、原图散列、派生资源和派生散列见 [PROVENANCE.json](../third_party/lucide/PROVENANCE.json)。

Lucide 使用 ISC 许可，其完整许可还列出 Feather 派生图标的 MIT 声明。仓库保留 [上游完整 LICENSE](../third_party/lucide/LICENSE)，应用资源保留 [随包许可文本](../app/src/main/assets/licenses/lucide-LICENSE.txt)，项目声明见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。上游授权条件可对照 [Lucide 官方许可](https://lucide.dev/license)。

启动、导航和关于页使用 Xiyan 原创交汇式 X 品牌。Mobile Codex 与商业产品页面用于研究布局、密度和交互；固定研究版本和来源保留在 [P0.8 研究记录](UI-REFRESH-MOBILE-CODEX.zh-CN.md) 与 [Design System 研究来源](DESIGN-SYSTEM.zh-CN.md#研究来源)。

## 业务与安全行为保留

- 保留 `MainActivity` 名称，以及原有草稿、模式、模型和主题存储键；切页、旋转和主题恢复继续维护草稿与选区。
- 流式更新复用同一个输入框；导航和页面停止继续按生命周期退订观察者，尚在进行的官方浏览器授权保持原路线。
- 模型选择保留目录引用、账号范围、连接与忙碌状态检查；服务拒绝某个思考选项时显示实际安全原因，由用户决定下一次选择。
- 停止继续同时撤销手机后续动作并取消当前账号操作。手动重试每次由用户触发，有其他输入时先确认保存草稿。
- 账号凭据保护、刷新轮换和用量账号隔离沿用现有实现；用量数值、时间和未知状态继续来自实际数据模型。
- 指定窗口截图、清除、停止和离开页面后的回调失效与图像释放路径保留，控制测试与运行时探针继续独立。

对应实现见 [MainActivity](../app/src/main/java/io/xiyan/agent/MainActivity.java)、[ChatScreen](../app/src/main/java/io/xiyan/agent/ChatScreen.java)、[ModelPickerDialog](../app/src/main/java/io/xiyan/agent/ModelPickerDialog.java) 和 [TestPadActivity](../app/src/main/java/io/xiyan/agent/TestPadActivity.java)。账号与数据依据见 [SIWC 接入](runtime/SIWC-INTEGRATION.zh-CN.md) 和 [官方用量](runtime/OFFICIAL-USAGE.zh-CN.md)。完整自主手机任务、Galaxy 策略及长周期恢复继续按原阶段验收。

## 已有证据与待完成项目

| 项目 | 当前记录 |
| --- | --- |
| R1 开发构建真实模型目录 | 模拟器取得八个账号模型 |
| R1 开发构建真实文本请求 | `gpt-6.1-sol`、服务默认思考、标准服务等级请求完成；输入 36、输出 18、合计 54 token |
| R1 独立截图审阅 | 提出欢迎区与输入栏距离、已应用状态对比和窄屏摘要等具体问题，R2 已据此修改源码 |
| R2 开发构建 | assemble 与 lint 已通过 |
| R2 流式输入与停止实测 | 生成期间在键盘内持续输入，焦点保持且草稿完整；约 6.25 秒手动停止后保留部分回复。当前有不同草稿时，继续请求出现保存确认；取消可保留当前输入 |
| R2 最终视觉结论 | 待独立审阅实际修订截图 |
| 最终完整测试及干净提交产物 | 待统一执行并记录 |
| 最终安装包身份、签名、散列与对应源码 | 待最终提交构建后核对 |

这些证据分别属于对应轮次的开发构建。发布时需要将完整测试、安装包、截图与真实请求记录绑定到最终干净提交，并保留各阶段原有来源。当前文档为设计和阶段状态记录。

## 设计依据

- [Android 官方无障碍指南](https://developer.android.com/guide/topics/ui/accessibility/apps)：触控目标、控件描述与文字对比度。Android 建议触控目标至少 48×48dp，小字号正文对比度至少 4.5:1。
- [Android 移动色彩](https://developer.android.com/design/ui/mobile/guides/styles/color)：颜色角色及深浅主题的研究来源，详见 [Design System](DESIGN-SYSTEM.zh-CN.md)。
- [Material 动效](https://m3.material.io/styles/motion/overview/how-it-works)：官方研究索引；本轮网页读取仅返回 JavaScript 提示。实际采用的时长和规则见 [动效规范](DESIGN-SYSTEM.zh-CN.md#动效)。
- [Lucide 官方静态资产](https://lucide.dev/guide/static)与[官方许可](https://lucide.dev/license)：图标原始格式及授权；本项目实际打包版本以 [固定来源清单](../third_party/lucide/PROVENANCE.json) 为准。
- [既有 Mobile Codex 研究](UI-REFRESH-MOBILE-CODEX.zh-CN.md#参考与许可)：对话主屏、侧栏、模型选择和用量组织的历史参考。

官方账号、目录和用量协议依据统一保留在 [可行性研究](P0-feasibility.md)、[SIWC 接入](runtime/SIWC-INTEGRATION.zh-CN.md) 与 [官方用量](runtime/OFFICIAL-USAGE.zh-CN.md)，本轮视觉修改继续沿用这些业务接口。
