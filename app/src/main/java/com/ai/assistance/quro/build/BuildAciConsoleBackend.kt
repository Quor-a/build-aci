package com.ai.assistance.quro.build

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * BuildAci「控制台」后端（SDUI 范式）。
 *
 * 控制台是**控制端**功能；受控端只暴露 console_ui / console_action 两个能力，由控制端
 * AciConsoleScreen 纯本地渲染。本后端持有业务状态（最近输出 / 上次操作），
 * buildUiSnapshot() 只读状态成图（不触盘），applyAction() 才真正调 BuildEngine，
 * 由 Service 在后台线程调用（不阻塞 Binder/LocalSocket 线程）。
 *
 * input 提交兼容铁律（§14.3）：控制端回传 {value, key}，applyAction 必须**按 key 读参**。
 */
object BuildAciConsoleBackend {

    @Volatile private var appCtx: Context? = null
    @Volatile private var lastOutput: String = ""
    @Volatile private var lastMsg: String = ""

    fun attachContext(ctx: Context) { appCtx = ctx.applicationContext }

    /** 默认工程根目录（与 Service.resolveRoot 保持一致）。 */
    private fun defaultRoot(): File {
        val ctx = appCtx ?: throw IllegalStateException("attachContext 未调用")
        return File(ctx.filesDir, "buildproject")
    }

    /** 生成当前 UI 快照（只读状态，非阻塞）。 */
    fun buildUiSnapshot(): JSONObject {
        val components = JSONArray()
        components.put(JSONObject().put("type", "heading").put("text", "BuildAci 端侧构建控制台"))
        components.put(
            JSONObject().put("type", "text")
                .put("text", "经 ACI console_ui / console_action 由控制端渲染（后端驱动，前端免发版）")
        )
        components.put(
            JSONObject().put("type", "card")
                .put("title", "最近结果")
                .put("body", if (lastOutput.isNotBlank()) lastOutput.take(1000) else "（暂无操作）")
        )
        components.put(JSONObject().put("type", "button").put("action", "tools").put("label", "工具链状态"))
        components.put(JSONObject().put("type", "button").put("action", "init").put("label", "生成默认工程"))
        components.put(JSONObject().put("type", "button").put("action", "list").put("label", "列出工程文件"))
        components.put(JSONObject().put("type", "button").put("action", "assemble").put("label", "开始构建 APK"))
        components.put(
            JSONObject().put("type", "input")
                .put("key", "jobId").put("label", "查询构建状态(jobId)").put("placeholder", "构建任务 id")
                .put("value", "").put("action", "status")
        )
        components.put(
            JSONObject().put("type", "input")
                .put("key", "jobId").put("label", "查看构建日志(jobId)").put("placeholder", "构建任务 id")
                .put("value", "").put("action", "logs")
        )
        components.put(JSONObject().put("type", "divider"))
        components.put(
            JSONObject().put("type", "text")
                .put("text", if (lastMsg.isNotBlank()) "上次操作：$lastMsg" else "（暂无操作）")
        )
        components.put(JSONObject().put("type", "listitem").put("text", "受控端包名: com.ai.assistance.quro.build"))
        components.put(JSONObject().put("type", "listitem").put("text", "引擎: BuildEngine（端侧 aapt2/ecj/d8/apksigner）"))

        return JSONObject()
            .put("title", "BuildAci 端侧构建控制台")
            .put("subtitle", "后端驱动 · 控制端渲染（ACI）")
            .put("updatedAt", System.currentTimeMillis())
            .put("components", components)
    }

    /** 处理前端回传的 action，真正驱动构建。后台线程调用。 */
    fun applyAction(action: String, payload: JSONObject?): JSONObject {
        val ctx = appCtx
        if (ctx == null) {
            lastMsg = "控制台后端未初始化（attachContext 未调用）"
            return JSONObject().put("ok", true).put("action", action).put("message", lastMsg)
        }
        val p = payload ?: JSONObject()
        val msg = when (action) {
            "tools" -> {
                val tools = BuildEngine.detectTools()
                val sb = StringBuilder()
                tools.forEachIndexed { i, t ->
                    if (i > 0) sb.append("\n")
                    sb.append("${if (t.present) "✓" else "✗"} ${t.label}")
                }
                val allReady = tools.all { it.present }
                lastOutput = if (allReady) "工具链齐备，可构建" else "工具链缺失 ${tools.count { !it.present }} 项：\n$sb"
                if (allReady) "工具链齐备" else "缺失 ${tools.count { !it.present }} 项工具"
            }
            "init" -> {
                val root = defaultRoot()
                val ok = BuildEngine.scaffoldProject(root, false)
                lastOutput = if (ok) "已在 ${root.absolutePath} 生成默认工程" else "脚手架失败"
                if (ok) "已生成默认工程" else "脚手架失败"
            }
            "list" -> {
                val root = defaultRoot()
                val files = BuildEngine.listProject(root)
                lastOutput = if (files.isEmpty()) "工程为空" else "${files.size} 个文件：\n" + files.joinToString("\n")
                "列出 ${files.size} 个文件"
            }
            "assemble" -> {
                val root = defaultRoot()
                if (!root.isDirectory && !BuildEngine.scaffoldProject(root)) {
                    lastOutput = "工程目录不可用且脚手架失败: ${root.absolutePath}"
                    "工程不可用"
                } else {
                    val jobId = BuildEngine.build(root)
                    lastOutput = "已启动构建任务 $jobId（用 status / logs 跟踪）"
                    "已启动构建 $jobId"
                }
            }
            "status" -> {
                val key = p.optString("key", "jobId")
                val jobId = p.optString(key, p.optString("value", "")).trim()
                if (jobId.isEmpty()) "请输入 jobId" else {
                    val st = BuildEngine.getState(jobId)
                    if (st == null) "任务不存在: $jobId" else {
                        lastOutput = "${if (st.running) "构建中" else if (st.finished) (if (st.success) "成功" else "失败") else "待运行"} · ${st.step} · ${st.progress}%${st.apkPath?.let { " · $it" } ?: ""}"
                        "查询 $jobId"
                    }
                }
            }
            "logs" -> {
                val key = p.optString("key", "jobId")
                val jobId = p.optString(key, p.optString("value", "")).trim()
                if (jobId.isEmpty()) "请输入 jobId" else {
                    val logs = BuildEngine.getLogs(jobId, 200)
                    lastOutput = "日志 ${logs.lineSequence().count()} 行：\n" + logs.take(2000)
                    "查看 $jobId 日志"
                }
            }
            else -> "未知 action: $action"
        }
        lastMsg = msg
        return JSONObject().put("ok", true).put("action", action).put("message", msg)
    }
}
