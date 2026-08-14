package com.ai.assistance.quro.build

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 端侧 APK 构建引擎（真实可用的工具链）。
 *
 * 设计原则「绝不假装能编」：工具缺失 / 设备无可用 JVM 运行时 / 无 aapt2 时，如实报告，
 * 不会编造成功。能力边界：
 *  - ecj.jar 把 Java 源码编译为 .class（真实）
 *  - d8.jar 把 .class 转为 classes.dex（真实）
 *  - apksigner.jar 对产物签名（真实），但完整 APK 资源打包需要 aapt2（assets 未打包 → 如实告知）
 *  - 运行上述 JVM 任务需要设备提供 dalvikvm / app_process（Android 运行时），缺失则无法编译
 */
object BuildEngine {
    private const val TAG = "BuildEngine"
    const val ZORV_PKG = "com.ai.assistance.quro"

    data class ToolStatus(
        val name: String,
        val present: Boolean,
        val path: String,
        val note: String
    )

    data class BuildResult(
        val ok: Boolean,
        val log: String,
        val dexPath: String? = null
    )

    private data class RunResult(val ok: Boolean, val output: String)

    /** 把 assets 里的工具链解包到 filesDir/buildproject/libs（assets 不能直接执行）。 */
    fun ensureAssets(ctx: Context): File {
        val out = File(ctx.filesDir, "buildproject/libs")
        out.mkdirs()
        val names = listOf("ecj.jar", "d8.jar", "apksigner.jar", "android.jar", "debug.keystore")
        for (n in names) {
            val target = File(out, n)
            if (!target.exists() || target.length() == 0L) {
                try {
                    ctx.assets.open("libs/common/$n").use { ins ->
                        FileOutputStream(target).use { os -> ins.copyTo(os) }
                    }
                    Log.i(TAG, "解包 $n -> ${target.absolutePath}")
                } catch (e: Throwable) {
                    Log.w(TAG, "解包 $n 失败: ${e.message}")
                }
            }
        }
        return out
    }

    /** 真实工具链自检。首次调用会先把 APK assets 里的工具链解压到 filesDir。 */
    fun detectTools(ctx: Context): List<ToolStatus> {
        ensureAssets(ctx)
        val libs = File(ctx.filesDir, "buildproject/libs")
        val ecj = File(libs, "ecj.jar")
        val d8 = File(libs, "d8.jar")
        val sign = File(libs, "apksigner.jar")
        val aj = File(libs, "android.jar")
        val key = File(libs, "debug.keystore")
        val rt = findRuntime()
        val aapt2 = findAapt2()
        return listOf(
            ToolStatus("ecj.jar（Java 编译器）", ecj.exists(), ecj.absolutePath,
                if (ecj.exists()) "已就绪" else "缺失：无法编译 Java"),
            ToolStatus("d8.jar（DEX 转换器）", d8.exists(), d8.absolutePath,
                if (d8.exists()) "已就绪" else "缺失：无法生成 DEX"),
            ToolStatus("apksigner.jar（签名）", sign.exists(), sign.absolutePath,
                if (sign.exists()) "已就绪" else "缺失：无法签名"),
            ToolStatus("android.jar（Android SDK）", aj.exists(), aj.absolutePath,
                if (aj.exists()) "已就绪（编译期 classpath）" else "缺失"),
            ToolStatus("debug.keystore（签名密钥）", key.exists(), key.absolutePath,
                if (key.exists()) "已就绪" else "缺失"),
            ToolStatus("JVM 运行时 dalvikvm", rt != null, rt ?: "未找到",
                if (rt != null) "可用：可在端侧运行 ecj/d8" else "设备未提供运行时，端侧编译不可用"),
            ToolStatus("aapt2（资源打包）", aapt2 != null, aapt2 ?: "未打包",
                if (aapt2 != null) "可用：可生成完整 APK" else "未打包：仅能产出 DEX，无法生成完整 APK（缺 resources.arsc）")
        )
    }

    fun canAssembleApk(): Boolean = findAapt2() != null

    private fun findRuntime(): String? {
        // 优先 dalvikvm（经典端侧 JVM 运行器），其次 app_process
        val cands = listOf(
            "/system/bin/dalvikvm",
            "/apex/com.android.art/bin/dalvikvm",
            "/system/bin/app_process64",
            "/system/bin/app_process32",
            "/system/bin/app_process"
        )
        for (c in cands) {
            val f = File(c)
            if (f.exists() && (f.canExecute() || c.contains("dalvikvm"))) return c
        }
        return null
    }

    private fun findAapt2(): String? {
        // 当前 assets 未打包 aapt2；保留探测以支撑未来扩展
        val cands = listOf(
            "/system/bin/aapt2",
            "/data/local/tmp/aapt2"
        )
        for (c in cands) if (File(c).canExecute()) return c
        return null
    }

    /**
     * 编译整个 src 目录下所有 .java 文件为 classes.dex（真实管线：ecj → class，d8 → dex）。
     * 需要设备提供 JVM 运行时；否则返回 ok=false 并说明原因。
     */
    fun compileProject(ctx: Context, srcDir: File, outDir: File): BuildResult {
        val libs = ensureAssets(ctx)
        val rt = findRuntime()
            ?: return BuildResult(false, "设备上没有可用的 JVM 运行时（dalvikvm/app_process），无法在端侧编译 Java。\n" +
                "可在 PC 侧用相同工具链编译后通过「导出 DEX」导入。", null)

        srcDir.mkdirs()
        outDir.mkdirs()
        outDir.listFiles()?.forEach { if (it.isFile) it.delete() }

        val sources = srcDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".java", ignoreCase = true) }
            .map { it.absolutePath }
            .toList()

        if (sources.isEmpty()) {
            return BuildResult(false, "src 目录下没有 .java 源文件。", null)
        }

        val ecj = File(libs, "ecj.jar")
        val d8 = File(libs, "d8.jar")
        val aj = File(libs, "android.jar")
        if (!ecj.exists() || !d8.exists() || !aj.exists()) {
            return BuildResult(false, "工具链不完整（ecj/d8/android.jar 缺失），无法编译。", null)
        }

        val log = StringBuilder()
        log.append("发现 ${sources.size} 个 Java 源文件：\n")
        sources.forEach { log.append("  · ").append(it).append("\n") }
        val dexFile = File(outDir.parentFile ?: outDir, "classes.dex")

        // 1) ecj：Java → class
        val ecjArgs = mutableListOf(
            "-d", outDir.absolutePath,
            "-cp", aj.absolutePath,
            "-source", "11", "-target", "11"
        )
        ecjArgs.addAll(sources)
        val r1 = runTool(rt, "$ecj:$aj", "org.eclipse.jdt.internal.compiler.batch.Main", ecjArgs, outDir, 120000)
        log.append("\n== ecj 编译 Java → class ==\n").append(r1.output)
        if (!r1.ok) return BuildResult(false, log.toString(), null)

        // 2) d8：class → dex
        val d8Args = listOf(
            "--output", dexFile.absolutePath,
            "--lib", aj.absolutePath,
            outDir.absolutePath
        )
        val r2 = runTool(rt, d8.absolutePath, "com.android.tools.r8.D8", d8Args, outDir, 120000)
        log.append("\n== d8 转换 class → dex ==\n").append(r2.output)
        if (!r2.ok || !dexFile.exists()) {
            return BuildResult(false, log.toString() + "\n[DEX 未生成]", null)
        }

        log.append("\n✔ 编译成功：classes.dex（${dexFile.length()} 字节）位于 ${dexFile.absolutePath}")
        return BuildResult(true, log.toString(), dexFile.absolutePath)
    }

    /**
     * 把一段 Java 源码编译为 classes.dex（兼容旧版单文件调用；内部也走 compileProject）。
     * 需要设备提供 JVM 运行时；否则返回 ok=false 并说明原因。
     */
    fun compileToDex(ctx: Context, source: String, className: String): BuildResult {
        val libs = ensureAssets(ctx)
        val rt = findRuntime()
            ?: return BuildResult(false, "设备上没有可用的 JVM 运行时（dalvikvm/app_process），无法在端侧编译 Java。\n" +
                "可在 PC 侧用相同工具链编译后通过「导出 DEX」导入。", null)

        val project = File(ctx.filesDir, "buildproject")
        val srcDir = File(project, "src").apply { mkdirs() }
        val outDir = File(project, "out").apply { mkdirs() }
        outDir.listFiles()?.forEach { if (it.isFile) it.delete() }

        val srcFile = File(srcDir, "$className.java")
        try {
            srcFile.writeText(source)
        } catch (e: Throwable) {
            return BuildResult(false, "写入源码失败：${e.message}", null)
        }

        val ecj = File(libs, "ecj.jar")
        val d8 = File(libs, "d8.jar")
        val aj = File(libs, "android.jar")
        if (!ecj.exists() || !d8.exists() || !aj.exists()) {
            return BuildResult(false, "工具链不完整（ecj/d8/android.jar 缺失），无法编译。", null)
        }

        val log = StringBuilder()
        val dexFile = File(project, "classes.dex")

        // 1) ecj：Java → class
        val ecjArgs = listOf(
            "-d", outDir.absolutePath,
            "-cp", aj.absolutePath,
            "-source", "11", "-target", "11",
            srcFile.absolutePath
        )
        val r1 = runTool(rt, "$ecj:$aj", "org.eclipse.jdt.internal.compiler.batch.Main", ecjArgs, project, 90000)
        log.append("== ecj 编译 Java → class ==\n").append(r1.output)
        if (!r1.ok) return BuildResult(false, log.toString(), null)

        // 2) d8：class → dex
        val d8Args = listOf(
            "--output", dexFile.absolutePath,
            "--lib", aj.absolutePath,
            outDir.absolutePath
        )
        val r2 = runTool(rt, d8.absolutePath, "com.android.tools.r8.D8", d8Args, project, 90000)
        log.append("\n== d8 转换 class → dex ==\n").append(r2.output)
        if (!r2.ok || !dexFile.exists()) {
            return BuildResult(false, log.toString() + "\n[DEX 未生成]", null)
        }

        log.append("\n✔ 编译成功：classes.dex（${dexFile.length()} 字节）位于 ${dexFile.absolutePath}")
        return BuildResult(true, log.toString(), dexFile.absolutePath)
    }

    /** 用 dalvikvm / app_process 运行一个 JVM main 类，捕获输出并限时。 */
    private fun runTool(rt: String, classpath: String, mainClass: String, args: List<String>, workDir: File, timeoutMs: Long): RunResult {
        return try {
            val cmd = if (rt.endsWith("dalvikvm")) {
                listOf(rt, "-cp", classpath, mainClass) + args
            } else {
                // app_process：通过 CLASSPATH 环境变量传入，参数为 [working-dir] [main] [args]
                listOf(rt, "/system/bin", mainClass) + args
            }
            val pb = ProcessBuilder(cmd).directory(workDir).redirectErrorStream(true)
            if (!rt.endsWith("dalvikvm")) {
                pb.environment()["CLASSPATH"] = classpath
            }
            val p = pb.start()
            val out = StringBuilder()
            val reader: BufferedReader = p.inputStream.bufferedReader()
            val drain = Thread {
                try {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) out.appendLine(line)
                } catch (_: Throwable) { }
            }
            drain.start()
            val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                p.destroyForcibly()
                RunResult(false, out.toString() + "\n[超时 ${timeoutMs}ms，已强制终止]")
            } else {
                drain.join(2000)
                RunResult(p.exitValue() == 0, out.toString())
            }
        } catch (e: Throwable) {
            RunResult(false, "启动失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
