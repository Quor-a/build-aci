package com.ai.assistance.quro.build

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * ACI 唤醒 Receiver（手册 §4.6）。
 * 收到 ACTION_WAKE 广播后拉起主 Activity，使受控进程从 stopped 态变为活跃态，
 * 后续 bindService 才能成功（修复 ColorOS / Android 11+ 停止态绑不上）。
 */
class BuildAciWakeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BuildACI-Wake"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == "ai.aci.core.ACTION_WAKE") {
            try {
                val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launch)
                    Log.d(TAG, "已拉起主 Activity ✓")
                } else {
                    Log.w(TAG, "getLaunchIntentForPackage 返回 null")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "启动 Activity 失败: ${e.message}", e)
            }
        }
    }
}
