# P0 云端产物暂存

此分支通过 Git 保存已在 Codex Cloud 构建并校验的产物，供下载和后续 GitHub Release 上传。它不是已创建的 GitHub Release，也不是完整自主 Agent 交付。

APK 的真实源码 buildCommit：`a2ac08dafdd33ca2dd8d408e10a78b89472cec74`。本分发分支后续仅添加产物和证据，其分支 HEAD 不能当作 APK buildCommit。以 APK 内来源、BUILD-INFO.json、签名及 SHA256SUMS.txt 为准。

文件在 `p0-a2ac08d/`：调试测试签名 APK、构建日志、35 项单元测试与来源证明、4 类打包拒绝试验、签名/对齐报告和许可。APK SHA256：`827d1bd801d1c6de5681671ef30cc6a47908196c0310afe13709ca373091bdd2`。

P0 尚未验收。未内嵌 Codex runtime、未接入官方账号或真实推理，未通过 iQOO/Galaxy 与长周期验收。窗口截图只支持 Android14+，低版本不做整屏回退，分屏/覆盖层像素隔离尚待设备验证。模拟器补测结果单独记录，不当作真机结果。

当前用户要求全部编译、测试和打包仅在 Codex Cloud。此分支没有 GitHub 构建工作流；GitHub 仅保存/分发产物。Release API 上传仍待环境网络配置实际生效，尚未创建发行。无需为下载提供账号令牌或签名私钥。
