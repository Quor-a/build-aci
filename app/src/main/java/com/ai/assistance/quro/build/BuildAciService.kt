package com.ai.assistance.quro.build

import ai.aidl.aci.core.AidlAciError
import ai.aidl.aci.core.AidlAciRequest
import ai.aidl.aci.core.AidlAciResponse
import ai.aidl.aci.core.BaseAidlAciService
import ai.aidl.aci.core.Capability
import android.os.Bundle
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ACI 受控端 Service：向 Zorv AI（控制端）暴露「端侧 APK 构建」能力。
 *
 * 接入遵循《ACI 开发者手册》§4 + §16/§20，继承 BaseAidlAciService，自动获得
 * AIDL + LocalSocket 双通道。所有能力以受控方式暴露；构建是长任务，故 build_assemble
 * 立即返回 jobId，UI/ACI 通过 build_status / build_logs 轮询进度（契合控制端 15s 调用预算）。
 */
class BuildAciService : BaseAidlAciService() {

    companion object {
        private const val TAG = "BuildACI"
        private const val ZORV_PKG = "com.ai.assistance.quro"
        private const val HARD_TIMEOUT_S = 14L
        private val executor = Executors.newCachedThreadPool()
    }

    override fun onCreate() {
        try {
            BuildAciConsoleBackend.attachContext(applicationContext)
            super.onCreate()
            BuildEngine.init(applicationContext)
            Log.i(TAG, "onCreate 完成（AIDL + LocalSocket 双通道已就绪）")
        } catch (e: Throwable) {
            Log.e(TAG, "super.onCreate() 失败: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    /** 解析工程根目录：优先用参数 path，否则用默认工程目录。 */
    private fun resolveRoot(req: AidlAciRequest): File {
        val p = req.params?.getString("path")?.takeIf { it.isNotBlank() }
        return if (p != null) File(p) else File(applicationContext.filesDir, "buildproject")
    }

    override fun onCreateCapabilities(caps: MutableList<Capability>) {
        caps.add(
            Capability.create(
                "build_tools",
                "返回端侧构建工具链状态：aapt2 / ecj / d8 / apksigner / android.jar / framework-res / keystore / 运行时。缺哪个、放哪都会写明。构建前应先调用本能力确认工具齐备。"
            )
                .addResult("tools", "string", "工具状态 JSON 数组")
                .addResult("allReady", "string", "是否全部就绪：true/false")
                .addResult("summary", "string", "可读摘要")
                .addFlag(Capability.FLAG_BACKGROUND)
                .addFlag(Capability.FLAG_NO_UI)
        )

        caps.add(
            Capability.create(
                "build_init",
                "在工程目录生成「最小可编译 Android 工程」模板（单 Java Activity + 资源 + Manifest）。force=true 时覆盖重写。"
            )
                .addParam("path", "string", false, "工程根目录（可空则用默认 buildproject）")
                .addParam("force", "string", false, "是否覆盖已有文件（可空默认 false）")
                .addResult("root", "string", "工程根目录绝对路径")
                .addResult("ok", "string", "是否成功：true/false")
                .addResult("summary", "string", "可读摘要")
                .addFlag(Capability.FLAG_BACKGROUND)
                .addFlag(Capability.FLAG_NO_UI)
        )

        caps.add(
            Capability.create(
                "build_set_source",
                "向工程写入一个源/资源文件（相对工程根的路径）。可用来替换 MainActivity.java 或新增资源。"
            )
                .addParam("path", "string", false, "工程根目录（可空则用默认 buildproject）")
                .addParam("relPath", "string", true, "相对工程根的路径，如 src/com/example/buildapp/MainActivity.java")
                .addParam("content", "string", true, "文件内容")
                .addResult("ok", "string", "是否成功：true/false")
                .addResult("summary", "string", "可读摘要")
                .addFlag(Capability.FLAG_BACKGROUND)
                .addFlag(Capability.FLAG_NO_UI)
        )

        caps.add(
            Capability.create(
                "build_list",
                "列出工程内源/资源文件（相对路径，排除 build/）。"
            )
                .addParam("path", "string", false, "工程根目录（可空则用默认 buildproject）")
                .addResult("files", "string", "文件相对路径 JSON 数组")
                .addResult("count", "string", "文件数")
                .addResult("summary", "string", "可读摘要")
                .addFlag(Capability.FLAG_BACKGROUND)
                .addFlag(Capability.FLAG_NO_UI)
        )

        caps.add(
            Capability.create(
                "build_assemble",
                "启动一次完整构建（aapt2 compile→link → ecj 编译 → d8 转 dex → 组装 → apksigner 签名），立即返回 jobId；用 build_status / build_logs 轮询进度与产物。"
            )
                .addParam("path", "string", false, "工程根目录（可空则用默认 buildproject）")
                .addResult("jobId", "string", "构建任务 id")
                .addResult("summary", "string", "可读摘要")
                .addFlag(Capability.FLAG_BACKGROUND)
                .addFlag(Capability.FLAG_NO_UI)
        )

        caps.add(
            Capability.create(
                "build_status",
                "查询某构建任务状态：running / step / progress / apkPath / finished / success / 各步结果。"
            )
                .addParam("jobId", "string", true, "构建任务 id")
                .addResult("state", "string", "状态 JSON")
                .addResult("summary", "string", "可读摘要")
                .addFlag(Capability.FLAG_BACKGROUND)
                .addFlag(Capability.FLAG_NO_UI)
        )

        caps.add(
            Capability.create(
                "build_logs",
                "获取某构建任务的累计日志（尾部截断，默认 200 行）。"
            )
                .addParam("jobId", "string", true, "构建任务 id")
                .addParam("tail", "string", false, "尾部保留行数（可空默认 200）")
                .addResult("logs", "string", "日志文本")
                .addResult("summary", "string", "可读摘要")
                .addFlag(Capability.FLAG_BACKGROUND)
                .addFlag(Capability.FLAG_NO_UI)
        )

        caps.add(
            Capability.create(
                "build_stop",
                "终止一个正在运行的构建任务。"
            )
                .addParam("jobId", "string", true, "构建任务 id")
                .addResult("stopped", "string", "是否成功终止：true/false")
                .addResult("summary", "string", "可读摘要")
                .addFlag(Capability.FLAG_BACKGROUND)
                .addFlag(Capability.FLAG_NO_UI)
        )

        // 9. SDUI 控制台（受控端只暴露快照与动作，由控制端纯本地渲染）
        caps.add(
            Capability.create(
                "console_ui",
                "获取控制台 UI 描述 JSON（组件化，由控制端 AciConsoleScreen 纯本地渲染）。"
            )
                .addResult("snapshot", "string", "UI 描述 JSON 字符串（aci-sdui-v1 组件树）")
                .addResult("title", "string", "控制台标题")
                .addFlag(Capability.FLAG_BACKGROUND)
                .addFlag(Capability.FLAG_NO_UI)
        )
        caps.add(
            Capability.create(
                "console_action",
                "处理控制台前端（控制端）回传的动作：工具链状态/生成工程/列文件/构建/查状态/看日志。"
            )
                .addParam("action", "string", true, "动作 id（tools/init/list/assemble/status/logs）")
                .addParam("payload", "string", false, "动作参数 JSON 字符串（input 回传含 {value,key}）")
                .addResult("ok", "boolean", "是否成功")
                .addResult("action", "string", "实际处理的动作")
                .addFlag(Capability.FLAG_BACKGROUND)
                .addFlag(Capability.FLAG_NO_UI)
        )
    }

    override fun onCheckPermission(request: AidlAciRequest?, callerPkg: String?): Boolean {
        return callerPkg == ZORV_PKG || callerPkg == packageName
    }

    override fun onCall(request: AidlAciRequest?): AidlAciResponse {
        if (request == null) return AidlAciResponse.error(AidlAciError.REQUEST_NULL, "null")
        return when (request.capability) {
            "build_tools" -> handleTools(request)
            "build_init" -> handleInit(request)
            "build_set_source" -> handleSetSource(request)
            "build_list" -> handleList(request)
            "build_assemble" -> handleAssemble(request)
            "build_status" -> handleStatus(request)
            "build_logs" -> handleLogs(request)
            "build_stop" -> handleStop(request)
            "console_ui" -> handleConsoleUi()
            "console_action" -> handleConsoleAction(request)
            else -> AidlAciResponse.error(AidlAciError.CAPABILITY_NOT_FOUND, "unknown: ${request.capability}")
        }
    }

    // ── 能力处理 ──────────────────────────────────────────────

    private fun handleTools(@Suppress("UNUSED_PARAMETER") req: AidlAciRequest): AidlAciResponse = runNet {
        val tools = BuildEngine.detectTools()
        val arr = JSONArray()
        val sb = StringBuilder()
        tools.forEachIndexed { i, t ->
            arr.put(JSONObject().apply {
                put("id", t.id); put("label", t.label); put("kind", t.kind)
                put("present", t.present); put("path", t.path ?: ""); put("note", t.note)
            })
            if (i > 0) sb.append("\n")
            sb.append("${if (t.present) "✓" else "✗"} ${t.label}")
        }
        val allReady = tools.all { it.present }
        AidlAciResponse.success()
            .putResult("tools", arr.toString())
            .putResult("allReady", allReady.toString())
            .putResult("summary", if (allReady) "工具链齐备，可构建" else "工具链缺失 ${tools.count { !it.present }} 项：\n$sb")
    }

    private fun handleInit(req: AidlAciRequest): AidlAciResponse = runNet {
        val root = resolveRoot(req)
        val force = req.params?.getString("force")?.toBooleanStrictOrNull() ?: false
        val ok = BuildEngine.scaffoldProject(root, force)
        AidlAciResponse.success()
            .putResult("root", root.absolutePath)
            .putResult("ok", ok.toString())
            .putResult("summary", if (ok) "已在 ${root.absolutePath} 生成默认工程" else "脚手架失败")
    }

    private fun handleSetSource(req: AidlAciRequest): AidlAciResponse = runNet {
        val root = resolveRoot(req)
        val rel = req.params?.getString("relPath")
        val content = req.params?.getString("content")
        if (rel.isNullOrBlank() || content == null) {
            return@runNet AidlAciResponse.error(AidlAciError.BAD_REQUEST, "missing param: relPath / content")
        }
        val ok = BuildEngine.writeSource(root, rel, content)
        AidlAciResponse.success()
            .putResult("ok", ok.toString())
            .putResult("summary", if (ok) "已写入 $rel（${content.toByteArray(Charsets.UTF_8).size} 字节）" else "写入失败: $rel")
    }

    private fun handleList(req: AidlAciRequest): AidlAciResponse = runNet {
        val root = resolveRoot(req)
        val files = BuildEngine.listProject(root)
        val arr = JSONArray()
        files.forEach { arr.put(it) }
        AidlAciResponse.success()
            .putResult("files", arr.toString())
            .putResult("count", files.size.toString())
            .putResult("summary", if (files.isEmpty()) "工程为空" else "${files.size} 个文件：\n" + files.joinToString("\n"))
    }

    private fun handleAssemble(req: AidlAciRequest): AidlAciResponse = runNet {
        val root = resolveRoot(req)
        if (!root.isDirectory && !BuildEngine.scaffoldProject(root)) {
            return@runNet AidlAciResponse.error(AidlAciError.BAD_REQUEST, "工程目录不可用且脚手架失败: ${root.absolutePath}")
        }
        val jobId = BuildEngine.build(root)
        AidlAciResponse.success()
            .putResult("jobId", jobId)
            .putResult("summary", "已启动构建任务 $jobId（用 build_status / build_logs 跟踪）")
    }

    private fun handleStatus(req: AidlAciRequest): AidlAciResponse = runNet {
        val jobId = req.params?.getString("jobId")
        if (jobId.isNullOrBlank()) {
            return@runNet AidlAciResponse.error(AidlAciError.BAD_REQUEST, "missing param: jobId")
        }
        val st = BuildEngine.getState(jobId)
        if (st == null) {
            return@runNet AidlAciResponse.error(AidlAciError.BAD_REQUEST, "任务不存在: $jobId")
        }
        val o = JSONObject().apply {
            put("jobId", st.jobId); put("running", st.running); put("step", st.step)
            put("progress", st.progress); put("apkPath", st.apkPath ?: "")
            put("finished", st.finished); put("success", st.success)
            val steps = JSONArray()
            st.steps.forEach { s -> steps.put(JSONObject().apply { put("name", s.name); put("ok", s.ok); put("message", s.message) }) }
            put("steps", steps)
        }
        AidlAciResponse.success()
            .putResult("state", o.toString())
            .putResult("summary", "${if (st.running) "构建中" else if (st.finished) (if (st.success) "成功" else "失败") else "待运行"} · ${st.step} · ${st.progress}%${st.apkPath?.let { " · $it" } ?: ""}")
    }

    private fun handleLogs(req: AidlAciRequest): AidlAciResponse = runNet {
        val jobId = req.params?.getString("jobId")
        if (jobId.isNullOrBlank()) {
            return@runNet AidlAciResponse.error(AidlAciError.BAD_REQUEST, "missing param: jobId")
        }
        val tail = req.params?.getString("tail")?.toIntOrNull() ?: 200
        val logs = BuildEngine.getLogs(jobId, tail)
        AidlAciResponse.success()
            .putResult("logs", logs)
            .putResult("summary", "日志 ${logs.lineSequence().count()} 行")
    }

    private fun handleStop(req: AidlAciRequest): AidlAciResponse = runNet {
        val jobId = req.params?.getString("jobId")
        if (jobId.isNullOrBlank()) {
            return@runNet AidlAciResponse.error(AidlAciError.BAD_REQUEST, "missing param: jobId")
        }
        val ok = BuildEngine.stop(jobId)
        AidlAciResponse.success()
            .putResult("stopped", ok.toString())
            .putResult("summary", if (ok) "已请求终止 $jobId" else "任务不存在: $jobId")
    }

    // ── SDUI 控制台（受控端只下发快照 + 处理动作，由控制端现成 AciConsoleScreen 渲染）──

    /** console_ui：返回后端驱动的 UI 描述 JSON（只读状态，不触盘，可在 Binder 线程直接调用）。 */
    private fun handleConsoleUi(): AidlAciResponse {
        val snap = BuildAciConsoleBackend.buildUiSnapshot()
        return AidlAciResponse.success(Bundle())
            .putResult("snapshot", snap.toString())
            .putResult("title", snap.optString("title", ""))
    }

    /** console_action：处理前端回传的动作（后台线程跑，避免阻塞 Binder/LocalSocket 线程）。 */
    private fun handleConsoleAction(req: AidlAciRequest): AidlAciResponse = runNet {
        val action = req.params?.getString("action") ?: ""
        if (action.isEmpty()) {
            return@runNet AidlAciResponse.error(AidlAciError.BAD_REQUEST, "no action")
        }
        val payloadStr = req.params?.getString("payload") ?: ""
        val payload = if (payloadStr.isNotEmpty()) {
            try { JSONObject(payloadStr) } catch (_: Throwable) { null }
        } else null
        val r = BuildAciConsoleBackend.applyAction(action, payload)
        AidlAciResponse.success(Bundle())
            .putResult("ok", r.optBoolean("ok", false))
            .putResult("action", r.optString("action", action))
    }

    // ── 后台执行 + 限时 ────────────────────────────────────────

    private inline fun runNet(crossinline block: () -> AidlAciResponse): AidlAciResponse {
        val latch = CountDownLatch(1)
        var result: AidlAciResponse? = null
        executor.submit {
            try {
                result = block()
            } catch (e: Throwable) {
                result = AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "build error: ${e.message}")
            } finally {
                latch.countDown()
            }
        }
        val ok = latch.await(HARD_TIMEOUT_S, TimeUnit.SECONDS)
        if (!ok) return AidlAciResponse.error(AidlAciError.TIMEOUT, "build request timed out")
        return result ?: AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "no result")
    }
}
