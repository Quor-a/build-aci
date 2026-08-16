package com.ai.assistance.quro.build

import ai.aidl.aci.core.AidlAciError
import ai.aidl.aci.core.AidlAciRequest
import ai.aidl.aci.core.AidlAciResponse
import ai.aidl.aci.core.BaseAidlAciService
import ai.aidl.aci.core.Capability
import android.os.Bundle
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * ACI 受控端 Service：向 Zorv AI（控制端）暴露「端侧 APK 构建」能力。
 * 继承 BaseAidlAciService，onCreate 自动启动 LocalSocket 高速通道 + AIDL 双通道；
 * 仅放行持有 ai.aci.permission.CALL 且包名为 ZorvAI（com.ai.assistance.quro）的调用方。
 */
class BuildAciService : BaseAidlAciService() {
    companion object {
        private const val TAG = "BuildACI"
        private const val ZORV_PKG = "com.ai.assistance.quro"
        /** ZorvAI 工作区（沙箱）相对路径：<主外部存储>/Android/data/com.ai.assistance.quro/files/QuroWorkspace */
        private const val WORKSPACE_REL = "Android/data/$ZORV_PKG/files/QuroWorkspace"
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
                "build_project",
                "编译 buildproject/src 下的整个多文件 Java 工程为 classes.dex（ecj→class，d8→dex）。需要设备提供 dalvikvm/app_process 运行时。"
            )
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
        caps.add(
            Capability.create(
                "create_project",
                "在 ZorvAI 工作区（QuroWorkspace）下创建一个工程文件夹，并写入默认入口源 src/Main.java，返回该文件夹的绝对路径。ZorvAI 的 AI 随后可在工作区里写代码，构建台再据此 build_apk。"
            )
                .addParam("project_name", "string", true, "工程文件夹名（仅单个名字，不含路径；会落在 QuroWorkspace 下）")
                .addResult("ok", "string", "true/false")
                .addResult("path", "string", "工程文件夹绝对路径（如 /…/QuroWorkspace/MyApp）")
                .addResult("log", "string", "创建结果说明")
                .addFlag(Capability.FLAG_BACKGROUND)
        )
        caps.add(
            Capability.create(
                "build_apk",
                "编译 ZorvAI 工作区里某个工程（project_dir 或其下的 src/）的全部 Java 源码为 DEX，注入 base.apk 模板并签名，产出可直接安装的 APK 回写到该工程目录。结果日志通过 ACI 回传 ZorvAI 由 AI 读取。"
            )
                .addParam("project_dir", "string", true, "工程文件夹绝对路径，或 QuroWorkspace 下的相对文件夹名")
                .addParam("package_name", "string", false, "APK 包名，默认 com.example.buildapp")
                .addParam("app_label", "string", false, "APK 应用名，默认取工程文件夹名")
                .addParam("version_name", "string", false, "版本名，默认 1.0.0")
                .addResult("ok", "string", "true/false")
                .addResult("apk_path", "string", "产物 APK 绝对路径（成功时）")
                .addResult("log", "string", "编译+打包日志")
                .addFlag(Capability.FLAG_BACKGROUND)
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
            "build_project" -> doBuildProject()
            "build_toolchain" -> doToolchain()
            "create_project" -> doCreateProject(params)
            "build_apk" -> doBuildApk(params)
            else -> AidlAciResponse.error(AidlAciError.CAPABILITY_NOT_FOUND, "unknown: $cap")
        }
    }

    /** ZorvAI 工作区根目录：<主外部存储>/Android/data/com.ai.assistance.quro/files/QuroWorkspace。
     *  构建台持有 MANAGE_EXTERNAL_STORAGE，可直接读写该 ZorvAI 沙箱目录（不经 AIDL 私有通道）。 */
    private fun workspaceRoot(): File =
        File(Environment.getExternalStorageDirectory(), WORKSPACE_REL)

    /** 在工作区创建工程目录 + 默认入口源。 */
    private fun doCreateProject(params: Bundle): AidlAciResponse {
        val name = (params.getString("project_name") ?: "").trim()
        if (name.isBlank()) {
            return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "project_name 为空")
        }
        // 防目录穿越：只接受单个文件夹名
        if (name.contains("/") || name.contains("\\") || name == ".." || name.startsWith(".")) {
            return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "project_name 只能为单个文件夹名，不能含路径分隔符")
        }
        return try {
            val root = workspaceRoot().apply { mkdirs() }
            val projDir = File(root, name).apply { mkdirs() }
            val srcDir = File(projDir, "src").apply { mkdirs() }
            val main = File(srcDir, "Main.java")
            if (!main.exists()) {
                main.writeText(DEFAULT_WORKSPACE_MAIN)
            }
            val b = Bundle()
            b.putString("ok", "true")
            b.putString("path", projDir.absolutePath)
            b.putString("log", "已在工作区创建工程：${projDir.absolutePath}\n默认入口源：${main.absolutePath}\n（ZorvAI 可在工作区里继续写/改代码，完成后调用 build_apk 构建）")
            AidlAciResponse.success(b)
        } catch (e: Throwable) {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "创建工程失败：${e.message}")
        }
    }

    /** 编译工程 src/ → 注入 base.apk 模板 → 签名 → 回写 APK 到工程目录。 */
    private fun doBuildApk(params: Bundle): AidlAciResponse {
        val dir = (params.getString("project_dir") ?: "").trim()
        if (dir.isBlank()) {
            return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "project_dir 为空")
        }
        var projDir = File(dir)
        if (!projDir.isAbsolute) {
            projDir = File(workspaceRoot(), dir)
        }
        val srcDir = File(projDir, "src")
        if (!srcDir.isDirectory) {
            return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "工程目录不存在或没有 src/ 子目录：${projDir.absolutePath}")
        }
        val outDir = File(projDir, "out")
        val compile = BuildEngine.compileProject(applicationContext, srcDir, outDir)
        if (!compile.ok) {
            val b = Bundle()
            b.putString("ok", "false")
            b.putString("apk_path", "")
            b.putString("log", compile.log)
            return AidlAciResponse.success(b)
        }
        val pkg = (params.getString("package_name") ?: "").trim()
        val label = (params.getString("app_label") ?: "").trim()
        val version = (params.getString("version_name") ?: "").trim()
        val config = BuildEngine.BuildConfig(
            packageName = pkg.ifBlank { "com.example.buildapp" },
            appLabel = label.ifBlank { projDir.name },
            versionName = version.ifBlank { "1.0.0" }
        )
        val dexPath = compile.dexPath
        if (dexPath.isNullOrBlank()) {
            val b = Bundle()
            b.putString("ok", "false")
            b.putString("apk_path", "")
            b.putString("log", compile.log + "\n[DEX 路径缺失，无法打包 APK]")
            return AidlAciResponse.success(b)
        }
        val outApk = File(projDir, "${projDir.name}.apk")
        val assemble = BuildEngine.assembleApk(applicationContext, dexPath, outApk, config)
        val b = Bundle()
        b.putString("ok", assemble.ok.toString())
        b.putString("apk_path", assemble.apkPath ?: "")
        b.putString("log", assemble.log)
        return AidlAciResponse.success(b)
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

    private fun doBuildProject(): AidlAciResponse {
        val project = File(applicationContext.filesDir, "buildproject")
        val srcDir = File(project, "src")
        val outDir = File(project, "out")
        val r = BuildEngine.compileProject(applicationContext, srcDir, outDir)
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
        b.putString("can_assemble_apk", BuildEngine.canAssembleApk(applicationContext).toString())
        return AidlAciResponse.success(b)
    }
}

/**
 * 工作区工程默认入口源（src/Main.java）。
 * 与既有 buildproject 示例同款 run() 约定（宿主 base.apk 的 MainActivity 通过反射调用 Main.run()），
 * 保证 create_project 产出的工程直接 build_apk 即可得到可运行 APK；ZorvAI 的 AI 会按需求改写它。
 */
private val DEFAULT_WORKSPACE_MAIN = """package com.example.workspace;

public class Main {
    public static String run() {
        StringBuilder sb = new StringBuilder();
        sb.append("Hello from BuildAci workspace project!").append("\n");
        for (int i = 1; i <= 5; i++) {
            sb.append("line ").append(i).append("\n");
        }
        return sb.toString();
    }
}
""".trimIndent()
