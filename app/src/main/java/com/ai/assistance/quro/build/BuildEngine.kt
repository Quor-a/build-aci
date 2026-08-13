package com.ai.assistance.quro.build

import android.content.Context
import android.os.Build
import java.io.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 端侧 APK 构建编排引擎（BuildAci 核心）。
 *
 * 真实在 Android 设备上把「源码」编成「可安装 APK」所需的工具链：
 *   - aapt2   （原生二进制，Google 不发布 Android 版 → 需用户在 assets/libs/<abi>/ 放入对应 ABI 的移植版）
 *   - ecj.jar （Eclipse 编译器，纯 Java，可经 dalvikvm 运行）
 *   - d8.jar  （dexer，纯 Java）
 *   - apksigner.jar（签名器，纯 Java）
 *   - android.jar / framework-res.apk（平台框架，用于编译/链接时解析 android.* 资源）
 *   - debug.keystore（本仓库已内置，alias=androiddebugkey / pass=android）
 *
 * 设计原则（绝不假装能编）：
 *   1. 工具链缺失时，检测函数如实报告「缺哪个、放哪」，构建步骤直接报错退出，不伪造成功。
 *   2. 工具全部以「子进程」方式运行：原生 aapt2 直接 exec；纯 Java 工具经 /system/bin/dalvikvm 运行，
 *      避免这些工具内部 System.exit 杀掉宿主 App 进程。
 *   3. 构建是长任务，故采用「后台 job + 轮询」模型：build_assemble 立即返回 jobId，UI/ACI 用
 *      build_status / build_logs 拉进度，契合控制端 15s 调用预算（单步 ACI 调用必须快速返回）。
 */

object BuildEngine {

    // ── 状态模型 ──────────────────────────────────────────────
    enum class Level { INFO, OK, WARN, ERR }

    data class LogLine(val ts: Long, val level: Level, val msg: String)

    data class StepResult(val name: String, val ok: Boolean, val ms: Long, val message: String)

    data class State(
        val jobId: String,
        val running: Boolean,
        val step: String,
        val progress: Int,          // 0..100
        val apkPath: String?,
        val steps: List<StepResult>,
        val logs: List<LogLine>,
        val finished: Boolean,
        val success: Boolean
    )

    data class ToolInfo(
        val id: String,
        val label: String,
        val kind: String,           // NATIVE / JAR / RES / KEY
        val present: Boolean,
        val path: String?,
        val note: String
    )

    // ── 内部 ──────────────────────────────────────────────────
    private lateinit var appCtx: Context
    private val jobs = ConcurrentHashMap<String, Job>()
    private val seq = AtomicLong(1)
    private val dexCacheLock = Any()

    data class Job(
        val id: String,
        val root: File,
        val logs: MutableList<LogLine> = mutableListOf(),
        val steps: MutableList<StepResult> = mutableListOf(),
        @Volatile var running: Boolean = false,
        @Volatile var finished: Boolean = false,
        @Volatile var success: Boolean = false,
        @Volatile var step: String = "",
        @Volatile var progress: Int = 0,
        @Volatile var apkPath: String? = null,
        @Volatile var thread: Thread? = null
    ) {
        fun log(level: Level, msg: String) {
            synchronized(logs) { logs.add(LogLine(System.currentTimeMillis(), level, msg)) }
        }

        fun snapshot(): State = synchronized(this) {
            State(id, running, step, progress, apkPath, ArrayList(steps), ArrayList(logs), finished, success)
        }
    }

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    private val abi: String get() = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    private val toolchainDir: File get() = File(appCtx.filesDir, "toolchain")

    // ── 工具链解析 ────────────────────────────────────────────

    /**
     * 按优先级解析某个工具文件：
     *   1. toolchain/<abi>/<name>
     *   2. toolchain/common/<name>
     *   3. assets/libs/<abi>/<name>（首次使用时解压到 toolchain/<abi>/）
     *   4. assets/libs/common/<name>
     * 找不到返回 null。
     */
    private fun resolveTool(name: String): File? {
        val candidates = listOf(
            File(toolchainDir, "$abi/$name"),
            File(toolchainDir, "common/$name"),
            null // 占位：下面处理 asset
        )
        for (f in candidates) {
            if (f != null && f.isFile) return f
        }
        // asset: libs/<abi>/<name> 或 libs/common/<name>
        for (rel in listOf("libs/$abi/$name", "libs/common/$name")) {
            try {
                appCtx.assets.open(rel).use { inp ->
                    val dest = File(toolchainDir, "$abi/$name")
                    dest.parentFile?.mkdirs()
                    FileOutputStream(dest).use { out -> inp.copyTo(out) }
                    if (name == "aapt2") dest.setExecutable(true, false)
                    return dest
                }
            } catch (_: Throwable) { /* 该路径无此 asset，尝试下一个 */ }
        }
        return null
    }

    /** 解压内置 debug.keystore 到 toolchain 目录（若尚未存在）。 */
    private fun ensureKeystore(): File? {
        val dest = File(toolchainDir, "debug.keystore")
        if (dest.isFile) return dest
        try {
            appCtx.assets.open("debug.keystore").use { inp ->
                dest.parentFile?.mkdirs()
                FileOutputStream(dest).use { out -> inp.copyTo(out) }
            }
            return dest
        } catch (_: Throwable) { return null }
    }

    /** 框架资源 APK：优先用 toolchain 里的 framework-res.apk，否则用设备自带。 */
    private fun frameworkRes(): File? {
        resolveTool("framework-res.apk")?.let { return it }
        val sys = File("/system/framework/framework-res.apk")
        return if (sys.isFile) sys else null
    }

    /** 运行时：优先 /system/bin/dalvikvm，其次 app_process。 */
    private fun findRuntime(): String? {
        for (p in listOf("/system/bin/dalvikvm", "/system/bin/app_process", "/system/bin/app_process64", "/system/bin/app_process32")) {
            if (File(p).canExecute()) return p
        }
        return null
    }

    /** 检测全部工具链状态（UI / ACI build_tools 用）。 */
    fun detectTools(): List<ToolInfo> {
        val list = mutableListOf<ToolInfo>()
        fun add(id: String, label: String, kind: String, file: File?, note: String) {
            list.add(ToolInfo(id, label, kind, file != null, file?.absolutePath, note))
        }
        val base = resolveTool("base.apk")
        add("base", "base.apk（预编译清单骨架）", "RES", base,
            if (base == null) "缺失：把预编译的 base.apk 放到 assets/libs/common/ 或 filesDir/toolchain/common/" else "就绪")

        val ecj = resolveTool("ecj.jar")
        add("ecj", "ecj.jar（Java 编译器）", "JAR", ecj,
            if (ecj == null) "缺失：把 ecj.jar 放到 assets/libs/common/ 或 filesDir/toolchain/common/" else "就绪")

        val d8 = resolveTool("d8.jar")
        add("d8", "d8.jar（dex 转换器）", "JAR", d8,
            if (d8 == null) "缺失：把 d8.jar 放到 assets/libs/common/ 或 filesDir/toolchain/common/" else "就绪")

        val signer = resolveTool("apksigner.jar")
        add("apksigner", "apksigner.jar（签名器）", "JAR", signer,
            if (signer == null) "缺失：把 apksigner.jar 放到 assets/libs/common/ 或 filesDir/toolchain/common/" else "就绪")

        val androidJar = resolveTool("android.jar")
        add("android", "android.jar（平台框架桩）", "JAR", androidJar,
            if (androidJar == null) "缺失：把对应 SDK platform 的 android.jar 放到 assets/libs/common/ 或 filesDir/toolchain/common/" else "就绪")

        val ks = ensureKeystore()
        add("keystore", "debug.keystore（内置签名密钥）", "KEY", ks,
            if (ks == null) "缺失：内置 keystore 解压失败" else "就绪（androiddebugkey / android）")

        val rt = findRuntime()
        add("runtime", "dalvikvm / app_process 运行时", "NATIVE", if (rt != null) File(rt) else null,
            if (rt == null) "缺失：设备上找不到 /system/bin/dalvikvm" else "就绪（$rt）")

        return list
    }

    // ── 进程执行（含超时 + 输出捕获） ──────────────────────────

    data class ExecResult(val out: String, val code: Int, val error: String?)

    private fun exec(cmd: List<String>, timeoutMs: Long, workDir: File? = null): ExecResult {
        return try {
            val pb = ProcessBuilder(cmd).apply {
                if (workDir != null && workDir.isDirectory) directory(workDir)
                redirectErrorStream(true)
                // 让 dalvikvm 把 dalvik-cache 写到可写目录，避免 /data 不可写
                environment()["ANDROID_DATA"] = appCtx.filesDir.absolutePath
            }
            val p = pb.start()
            val sb = StringBuilder()
            val reader = p.inputStream.bufferedReader(Charsets.UTF_8)
            val drain = Thread {
                try {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) sb.append(line).append('\n')
                } catch (_: Throwable) { }
            }
            drain.start()
            val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!done) {
                p.destroyForcibly()
                drain.join(2000)
                ExecResult(sb.toString(), -1, "TIMEOUT(${timeoutMs}ms)")
            } else {
                drain.join(2000)
                ExecResult(sb.toString(), p.exitValue(), null)
            }
        } catch (e: Throwable) {
            ExecResult("", -1, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun runNative(bin: File, args: List<String>, timeoutMs: Long, workDir: File? = null): ExecResult {
        val cmd = mutableListOf(bin.absolutePath) + args
        return exec(cmd, timeoutMs, workDir)
    }

    private fun runJar(jar: File, mainClass: String, args: List<String>, timeoutMs: Long, workDir: File? = null): ExecResult {
        val rt = findRuntime() ?: return ExecResult("", -1, "未找到 dalvikvm/app_process 运行时")
        val cmd = mutableListOf(rt, "-Xmx512m", "-cp", jar.absolutePath, mainClass) + args
        return exec(cmd, timeoutMs, workDir)
    }

    // ── 工程脚手架 ────────────────────────────────────────────

    /** 缺省最小可编译工程模板（Java 单 Activity）。force=true 时覆盖重写。 */
    fun scaffoldProject(root: File, force: Boolean = false): Boolean {
        return try {
            root.mkdirs()
            val manifest = File(root, "AndroidManifest.xml")
            if (force || !manifest.isFile) {
                manifest.writeText(MANIFEST_TEMPLATE)
            }
            val srcDir = File(root, "src/com/example/buildapp")
            srcDir.mkdirs()
            val main = File(srcDir, "MainActivity.java")
            if (force || !main.isFile) main.writeText(MAIN_ACTIVITY_TEMPLATE)
            File(root, "build").mkdirs()
            true
        } catch (e: Throwable) {
            false
        }
    }

    /** 向工程写入一个源/资源文件（相对于工程根的路径）。 */
    fun writeSource(root: File, relPath: String, content: String): Boolean {
        return try {
            val f = File(root, relPath)
            f.parentFile?.mkdirs()
            f.writeText(content, Charsets.UTF_8)
            true
        } catch (e: Throwable) {
            false
        }
    }

    /** 列出工程内源/资源文件（相对路径）。 */
    fun listProject(root: File): List<String> {
        if (!root.isDirectory) return emptyList()
        val out = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it != root }
            .forEach { f ->
                val rel = f.relativeTo(root).path.replace('\\', '/')
                if (!rel.startsWith("build/")) out.add(rel)
            }
        return out.sorted()
    }

    // ── 构建（异步 job） ──────────────────────────────────────

    fun build(root: File, onUpdate: ((State) -> Unit)? = null): String {
        val id = "build-${seq.getAndIncrement()}"
        val job = Job(id, root)
        jobs[id] = job
        val th = Thread({
            try {
                runBuild(job)
            } finally {
                job.running = false
                job.finished = true
                onUpdate?.invoke(job.snapshot())
            }
        }, "BuildAci-$id")
        job.thread = th
        job.running = true
        th.start()
        return id
    }

    fun getState(jobId: String): State? = jobs[jobId]?.snapshot()
    fun getLogs(jobId: String, tail: Int): String {
        val job = jobs[jobId] ?: return "(任务不存在)"
        synchronized(job.logs) {
            val lines = if (job.logs.size <= tail) job.logs else job.logs.takeLast(tail)
            return lines.joinToString("\n") { l ->
                val lv = when (l.level) {
                    Level.OK -> "✓"; Level.WARN -> "!"; Level.ERR -> "✗"; else -> "·"
                }
                val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(l.ts))
                "[$t $lv] ${l.msg}"
            }
        }
    }

    fun stop(jobId: String): Boolean {
        val job = jobs[jobId] ?: return false
        job.thread?.interrupt()
        job.running = false
        return true
    }

    private fun runBuild(job: Job) {
        val root = job.root
        val buildDir = File(root, "build")
        buildDir.mkdirs()
        val classesDir = File(buildDir, "classes")
        val dexDir = File(buildDir, "dex")
        val unsigned = File(buildDir, "unsigned.apk")
        val signed = File(buildDir, "app-debug.apk")

        fun step(name: String, progress: Int, block: () -> ExecResult): Boolean {
            job.step = name
            job.progress = progress
            job.log(Level.INFO, "▶ $name")
            val r = block()
            val ok = r.code == 0 && r.error == null
            val msg = if (ok) "完成" else "失败(exit=${r.code}${r.error?.let { ", $it" } ?: ""})"
            job.steps.add(StepResult(name, ok, 0, msg))
            if (!r.out.isBlank()) r.out.lineSequence().take(40).forEach { job.log(Level.INFO, "  $it") }
            job.log(if (ok) Level.OK else Level.ERR, "${if (ok) "✓" else "✗"} $name · $msg")
            return ok
        }

        // 0. 工具链自检
        job.log(Level.INFO, "设备 ABI = $abi")
        val tools = detectTools()
        val missing = tools.filter { !it.present }
        if (missing.isNotEmpty()) {
            job.log(Level.ERR, "工具链缺失，无法构建：")
            missing.forEach { job.log(Level.ERR, "  - ${it.label}（${it.note}）") }
            job.step = "工具链缺失"
            job.progress = 100
            return
        }
        job.progress = 3

        // 1. 脚手架（若缺）
        if (!File(root, "AndroidManifest.xml").isFile) {
            if (!step("生成默认工程", 5) { ExecResult("", if (scaffoldProject(root)) 0 else -1, if (scaffoldProject(root)) null else "脚手架失败") }) return
        } else {
            job.log(Level.INFO, "· 工程已存在，跳过脚手架")
        }

        val baseApk = resolveTool("base.apk")!!
        val ecj = resolveTool("ecj.jar")!!
        val d8 = resolveTool("d8.jar")!!
        val signer = resolveTool("apksigner.jar")!!
        val androidJar = resolveTool("android.jar")!!
        val ks = ensureKeystore()!!

        // 2. ecj 编译 Java（无资源路径，纯源码编译）
        classesDir.mkdirs()
        if (!step("ecj 编译（Java→class）", 40) {
                runJar(ecj, "org.eclipse.jdt.internal.compiler.batch.Main", listOf(
                    "-d", classesDir.absolutePath,
                    "-cp", androidJar.absolutePath,
                    "-source", "1.8", "-target", "1.8",
                    "-proc:none", "-nowarn",
                    "src"
                ), 240_000L, root)
            }) return

        // 3. d8 转 dex
        dexDir.mkdirs()
        if (!step("d8 转 dex（class→dex）", 75) {
                runJar(d8, "com.android.tools.r8.D8", listOf(
                    "--output", dexDir.absolutePath,
                    "--lib", androidJar.absolutePath,
                    "--min-api", "21",
                    classesDir.absolutePath
                ), 240_000L, root)
            }) return

        val dexFile = File(dexDir, "classes.dex")
        if (!dexFile.isFile) {
            job.log(Level.ERR, "d8 未产出 classes.dex")
            job.step = "d8 失败"; job.progress = 100
            return
        }

        // 4. 组装未签名 APK（base.apk + classes.dex + 工程 assets/lib）
        if (!step("组装 APK（注入 classes.dex）", 92) {
                val r = try { assembleApk(baseApk, dexFile, root, unsigned); ExecResult("", 0, null) }
                catch (e: Throwable) { ExecResult("", -1, e.message) }
                r
            }) return

        // 5. apksigner 签名（仅 v2/v3，免去 zipalign 对齐）
        if (!step("apksigner 签名", 100) {
                runJar(signer, "com.android.apksigner.ApkSignerTool", listOf(
                    "sign",
                    "--ks", ks.absolutePath,
                    "--ks-key-alias", "androiddebugkey",
                    "--ks-pass", "pass:android",
                    "--key-pass", "pass:android",
                    "--v1-signing-enabled", "false",
                    "--out", signed.absolutePath,
                    unsigned.absolutePath
                ), 180_000L, root)
            }) return

        job.apkPath = signed.absolutePath
        job.success = true
        job.step = "完成"
        job.log(Level.OK, "🎉 构建成功 → ${signed.absolutePath}（${signed.length()} 字节）")
    }

    /** 以 base.apk 为基础，拷入 classes.dex 与工程 assets/lib，产出未签名 APK。 */
    private fun assembleApk(baseApk: File, dexFile: File, projectRoot: File, out: File) {
        val extraDirs = listOf("assets", "lib").map { File(projectRoot, it) }.filter { it.isDirectory }
        ZipInputStream(baseApk.inputStream().buffered()).use { zin ->
            ZipOutputStream(FileOutputStream(out).buffered()).use { zout ->
                var entry = zin.nextEntry
                while (entry != null) {
                    if (entry.name != "classes.dex") {
                        zout.putNextEntry(ZipEntry(entry.name))
                        zin.copyTo(zout)
                        zout.closeEntry()
                    }
                    entry = zin.nextEntry
                }
                // 写入 classes.dex（不压缩，STORED，规避 dex 压缩兼容问题）
                val dexBytes = dexFile.readBytes()
                val dexEntry = ZipEntry("classes.dex").apply { method = ZipEntry.STORED; size = dexBytes.size.toLong(); compressedSize = dexBytes.size.toLong(); crc = crc32(dexBytes) }
                zout.putNextEntry(dexEntry)
                zout.write(dexBytes)
                zout.closeEntry()
                // 拷入工程 assets / lib
                for (dir in extraDirs) {
                    dir.walkTopDown().filter { it.isFile }.forEach { f ->
                        val rel = f.relativeTo(projectRoot).path.replace('\\', '/')
                        val bytes = f.readBytes()
                        val e = ZipEntry(rel).apply { method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = bytes.size.toLong(); crc = crc32(bytes) }
                        zout.putNextEntry(e); zout.write(bytes); zout.closeEntry()
                    }
                }
            }
        }
    }

    private fun crc32(data: ByteArray): Long {
        val c = java.util.zip.CRC32()
        c.update(data)
        return c.value
    }

    // ── 模板 ──────────────────────────────────────────────────
    private val MANIFEST_TEMPLATE = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.buildapp"
    android:versionCode="1"
    android:versionName="1.0.0">
    <uses-sdk android:minSdkVersion="21" android:targetSdkVersion="34" />
    <application
        android:allowBackup="true"
        android:label="BuildApp"
        android:supportsRtl="true">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
"""

    private val MAIN_ACTIVITY_TEMPLATE = """package com.example.buildapp;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("Hello from BuildAci!");
        tv.setTextSize(24f);
        setContentView(tv);
    }
}
"""
}
