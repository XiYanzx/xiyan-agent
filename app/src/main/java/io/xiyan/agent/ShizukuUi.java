// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

import io.xiyan.agent.shizuku.ShizukuController;

final class ShizukuUi {
    private ShizukuUi() { }
    static String status(ShizukuController.Snapshot state){
        if(!state.binderAlive)return "Shizuku 尚未运行";
        if(state.serverVersion<13)return "请更新 Shizuku 后连接";
        if(!state.permissionGranted)return "等待你在 Shizuku 中授权";
        if(state.binding)return "正在连接手机操控服务…";
        switch(state.status){
            case "stop_requested":case "waiting_for_terminal":return "已停止后续派发，等待本次输入结束";
            case "bind_failed":case "bind_timeout":case "handshake_failed":return "服务连接未完成，请重新连接";
            case "dispatch_unavailable":case "command_watchdog":case "command_failed":return "本次输入未完成，请检查连接";
            case "user_service_dead":case "user_service_disconnected":return "操控服务已断开";
            case "disconnected":return "已断开操控服务";
            case "stopped":return "后续动作已停止";
            case "unsupported_uid":return "当前运行身份暂未支持";
            default:break;
        }
        if(state.busy)return "正在处理本次输入…";
        return state.bound?"Shizuku 操控已连接":"Shizuku 已授权，等待连接";
    }
}
