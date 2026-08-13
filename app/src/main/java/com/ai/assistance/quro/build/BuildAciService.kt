package com.ai.assistance.quro.build

import ai.aidl.aci.core.AidlAciError
import ai.aidl.aci.core.AidlAciRequest
import ai.aidl.aci.core.AidlAciResponse
import ai.aidl.aci.core.BaseAidlAciService
import ai.aidl.aci.core.Capability
import android.os.Bundle
import android.util.Log

/**
 * ACI 受控端 Service：向 Zorv AI（控制端）暴露「端侧 APK 构建」能力。
 * 继承 BaseAidlAciService，onCreate 自动启动 LocalSocket 高速通道 + AIDL 双通道；
 * 仅放行持有 ai.aci.permission.CALL 且包名为 ZorvAI（com.ai.assistance.quro）的调用方。
 */
class BuildAciService : BaseAidlAciService() {
    companion object {
        private const val TAG = "BuildACI"
        private const val ZORV_PKG = "com.ai.assistance.quro"
    }

    override fun onCreate() {
        try {
            super.onCreate()
            Log.i(TAG, "onCreate 完成（AIDL + LocalSocket 双通道就绪）")
        } catch (e: Throwable) {
            Log.e(TAG, "super.onCreate() 失败: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    override fun onCreateCapabilities(caps: MutableList<Capability>) {
        caps.add(
            Capability.create(
                "build_dex",
                "在设备端把一段 Java 源码编译为 classes.dex（ecj→class，d8→dex）。需要设备提供 dalvikvm/app_process 运行时。"
            )
                .addParam("source", "string", true, "Java 源码（建议含 package 与 public class）")
                .addParam("class_name", "string", false, "主类名（不含包名），默认 Main")
                .addResult("ok", "string", "true/false")
                .addResult("log", "string", "编译日志（含 ecj/d8 输出）")
                .addResult("dex_path", "string", "产物 classes.dex 路径（成功时）")
                .addFlag(Capability.FLAG_BACKGROUND)
        )
        caps.add(
            Capability.create(
                "build_toolchain",
                "返回当前端侧工具链自检结果（ecj/d8/apksigner/android.jar/keystore/运行时/aapt2）。"
            )
                .addResult("tools", "string", "JSON 数组：[{name,present,path,note}]")
                .addResult("can_assemble_apk", "string", "true/false（是否可打包完整 APK）")
                .addFlag(Capability.FLAG_BACKGROUND).addFlag(Capability.FLAG_NO_UI)
        )
    }

    override fun onCheckPermission(request: AidlAciRequest, callerPkg: String?): Boolean {
        return callerPkg == ZORV_PKG
    }

    override fun onCall(request: AidlAciRequest): AidlAciResponse {
        val cap = request.capability ?: ""
        val params = request.params ?: Bundle.EMPTY
        return when (cap) {
            "build_dex" -> doBuildDex(params)
            "build_toolchain" -> doToolchain()
            else -> AidlAciResponse.error(AidlAciError.CAPABILITY_NOT_FOUND, "unknown: $cap")
        }
    }

    private fun doBuildDex(params: Bundle): AidlAciResponse {
        val source = params.getString("source") ?: ""
        val className = (params.getString("class_name") ?: "Main").let { if (it.isBlank()) "Main" else it }
        if (source.isBlank()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "source 为空")
        val r = BuildEngine.compileToDex(applicationContext, source, className)
        val b = Bundle()
        b.putString("ok", r.ok.toString())
        b.putString("log", r.log)
        b.putString("dex_path", r.dexPath ?: "")
        return AidlAciResponse.success(b)
    }

    private fun doToolchain(): AidlAciResponse {
        val tools = BuildEngine.detectTools(applicationContext).joinToString(",") {
            "{\"name\":\"${it.name}\",\"present\":${it.present},\"path\":\"${it.path}\",\"note\":\"${it.note}\"}"
        }
        val b = Bundle()
        b.putString("tools", "[$tools]")
        b.putString("can_assemble_apk", BuildEngine.canAssembleApk().toString())
        return AidlAciResponse.success(b)
    }
}
