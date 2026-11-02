# 最新需求补充

日期：2026-11-02 UTC。本文件记录原任务书之后的用户指示；冲突时以较新的明确指示为准。原始 `REQUIREMENTS.zh-CN.md` 保留原文作为历史依据。

## 构建位置

用户最新指示：**“不要在 github 上构建”**。编译、测试、打包全部在用户选择的 **Codex Cloud** 环境进行，不使用用户电脑，也不使用 GitHub Actions 或 GitHub 托管 runner 构建。GitHub 保留源码、分支、PR、文档和云端产物的 Release 下载。

此要求覆盖原任务书第九节中的 GitHub Actions 自动构建交付项及此前相关授权。后续不得添加自动触发或手动触发的 GitHub 构建/测试/打包工作流；Release 上传不得在 Actions 中二次编译。

本任务早先在当时授权下创建工作流并触发 push 与 pull_request 两次运行；均在准备阶段失败。最新要求收到后已从 `feature/p0-runtime` 删除 `.github/workflows/android.yml`（commit `7e3c3e5`），核对两个运行均 `completed/failure`，没有需取消的活动运行。历史记录保留，不作为有效构建证据。当前有效 APK 与测试来自 Codex Cloud。

## 持续授权与验收边界

- 开发模型使用 GPT-6.1 Sol / Ultra。
- 公开仓库为 `XiYanzx/xiyan-agent`；源码提交、分支、draft PR 和已构建 APK 的 GitHub Release 上传仍获授权，不自动批准 PR。
- 遇到常规构建与测试故障自主诊断修复。账号登录、手机系统权限与真机验收需用户在对应环境操作，不能伪造结果。
- 按 P0–P6 逐阶段验收。首个 prerelease 只交付技术验证工具，不能宣称完整自主 Agent；正式 P6 Release 需达到原任务书全部阶段和真机门槛。

发布前核对：云端构建及 lint/测试日志对应源码 commit；APK 签名与 SHA256；Release 为明确标识的 P0 prerelease；未测试项与手机操作步骤齐备；仓库无会触发 GitHub 构建的工作流；不包含令牌、私钥、真实用户屏幕数据。
