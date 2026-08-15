package com.ai.assistance.quro.build

import android.content.Context
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 端侧 APK 构建引擎（真实可用的工具链）。
 *
 * 设计原则「绝不假装能编」：工具缺失 / dexed 工具 jar 加载失败 / 编译本身报错时，如实报告，
 * 不会编造成功。能力边界：
 *  - ecj（Java → .class）
 *  - d8（.class → classes.dex）
 *  - apksig（对产物签名）
 *  - assembleApk：把 classes.dex 注入 base.apk 模板（已含 AndroidManifest.xml + resources.arsc）再签名，不经过 aapt2
 *
 * 运行模型（v2，本次修复的关键）：
 *  旧版用 `dalvikvm` / `app_process` 拉独立 JVM 子进程跑 ecj/d8/apksigner。Android 14+ 起系统限制
 *  独立 `dalvikvm` 子进程，Android 16（SDK 36）上该二进制在 VM 启动期直接 SIGABRT（退出码 134），
 *  与 Java 源码无关——这就是「build_dex / build_project 全 134 退出」的根因。
 *  新版改为**进程内（in-process）**执行：用 DexClassLoader 把三个「已 dex 化」的工具 jar
 *  （ecj_dex.jar / d8_dex.jar / apksigner_dex.jar）加载进 App 自己的 ART 进程，通过反射调用
 *  ecj 的 `Main.compile(String[])`、d8 的 `D8.run(D8Command)`、apksig 的 `ApkSigner.sign()`。
 *  不再 spawn 任何子进程，从根本上消除 Android 16 的 SIGABRT。
 *
 *  dexed 工具 jar 由 PC 侧用 `d8 --min-api 26` 把原始 ecj.jar/d8.jar/apksigner.jar 转成单个
 *  classes.dex（ecj 额外保留其 .properties/.rsc 资源），随 APK 的 assets 下发，首次运行解包到
 *  filesDir/buildproject/libs。
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
        val dexPath: String? = null,
        val apkPath: String? = null
    )

    // =============================================================================================
    // 进程内工具链：用 DexClassLoader 加载端侧 dexed 工具 jar，并以反射缓存关键入口，避免重复查找。
    // =============================================================================================
    private class InProc(
        val loader: DexClassLoader,
        val ecjCtor: Constructor<*>,
        val ecjCompile: Method,
        val d8Parse: Method,
        val d8Run: Method,
        val d8Build: Method,
        val apkSignerCtor: Constructor<*>,
        val signerCfgCtor: Constructor<*>,
        val signerCfgBuild: Method,
        val apkSetInput: Method,
        val apkSetOutput: Method,
        val apkSetV1: Method,
        val apkSetV2: Method,
        val apkSetV3: Method,
        val apkSetMinSdk: Method,
        val apkBuild: Method,
        val apkSign: Method
    ) {
        /** 进程内跑 ecj。返回 (是否编译成功, 捕获到的 ecj 文本输出)。 */
        fun compileEcj(args: Array<String>): Pair<Boolean, String> {
            val prev = Thread.currentThread().contextClassLoader
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            try {
                Thread.currentThread().contextClassLoader = loader
                val main = ecjCtor.newInstance(pw, pw, false) // systemExitWhenFinished=false
                val ok = ecjCompile.invoke(main, args) as Boolean
                pw.flush()
                return ok to sw.toString()
            } finally {
                Thread.currentThread().contextClassLoader = prev
            }
        }

        /** 进程内跑 d8：用 D8Command.parse 解析 CLI 风格参数，再 D8.run。失败抛异常（调用方捕获）。 */
        fun runD8(args: Array<String>) {
            val prev = Thread.currentThread().contextClassLoader
            try {
                Thread.currentThread().contextClassLoader = loader
                val origin = loader.loadClass("com.android.tools.r8.origin.Origin")
                    .getMethod("unknown").invoke(null)
                val builder = d8Parse.invoke(null, args, origin)
                val cmd = d8Build.invoke(builder)
                d8Run.invoke(null, cmd)
            } finally {
                Thread.currentThread().contextClassLoader = prev
            }
        }

        /** 进程内签名：用 apksig 的 ApkSigner API（不使用会 System.exit 的 ApkSignerTool.main）。 */
        fun signApk(input: File, output: File, keystore: File, storePass: String, keyPass: String, alias: String) {
            val prev = Thread.currentThread().contextClassLoader
            try {
                Thread.currentThread().contextClassLoader = loader
                val ks = loadKeyStore(keystore, storePass)
                val entry = ks.getEntry(alias, KeyStore.PasswordProtection(keyPass.toCharArray()))
                    as? KeyStore.PrivateKeyEntry
                    ?: throw IllegalStateException("密钥库里找不到别名 '$alias' 的私钥条目")
                val priv = entry.privateKey
                @Suppress("UNCHECKED_CAST")
                val certs = entry.certificateChain?.map { it as X509Certificate }
                    ?: throw IllegalStateException("别名 '$alias' 没有证书链")
                val scBuilder = signerCfgCtor.newInstance(alias, priv, certs)
                val sc = signerCfgBuild.invoke(scBuilder)
                val apkBuilder = apkSignerCtor.newInstance(listOf(sc))
                apkSetInput.invoke(apkBuilder, input)
                apkSetOutput.invoke(apkBuilder, output)
                apkSetV1.invoke(apkBuilder, true)
                apkSetV2.invoke(apkBuilder, true)
                apkSetV3.invoke(apkBuilder, true)
                apkSetMinSdk.invoke(apkBuilder, 21)
                val signer = apkBuild.invoke(apkBuilder)
                apkSign.invoke(signer)
            } finally {
                Thread.currentThread().contextClassLoader = prev
            }
        }

        private fun loadKeyStore(ksFile: File, pass: String): KeyStore {
            val exceptions = mutableListOf<Throwable>()
            for (type in listOf("JKS", "PKCS12", "BKS")) {
                try {
                    val ks = KeyStore.getInstance(type)
                    ksFile.inputStream().use { ks.load(it, pass.toCharArray()) }
                    return ks
                } catch (e: Throwable) {
                    exceptions.add(e)
                }
            }
            throw IllegalStateException(
                "无法加载密钥库（已尝试 JKS/PKCS12/BKS）：${ksFile.name}，" +
                    "最后一次错误：${exceptions.lastOrNull()?.message}"
            )
        }
    }

    @Volatile private var cached: InProc? = null

    @Synchronized
    private fun getToolchain(ctx: Context): InProc {
        cached?.let { return it }
        val libs = ensureAssets(ctx)
        val ecjDex = File(libs, "ecj_dex.jar")
        val d8Dex = File(libs, "d8_dex.jar")
        val sigDex = File(libs, "apksigner_dex.jar")
        val ecjOrig = File(libs, "ecj.jar")
        if (!ecjDex.exists() || !d8Dex.exists() || !sigDex.exists()) {
            throw IllegalStateException("dexed 工具 jar 缺失（ecj_dex.jar / d8_dex.jar / apksigner_dex.jar），无法在端侧编译。")
        }
        val oat = File(ctx.filesDir, "buildproject/oat").apply { mkdirs() }
        // dexPath：ecj 的 dexed jar 放最前（类优先从这里加载），原 ecj.jar 随后兜底资源；其余两个 dexed jar 提供 d8/apksig。
        val dexPath = listOf(ecjDex, ecjOrig, d8Dex, sigDex)
            .joinToString(File.pathSeparator) { it.absolutePath }
        val loader = DexClassLoader(dexPath, oat.absolutePath, null, ctx.classLoader)

        val ecjMain = loader.loadClass("org.eclipse.jdt.internal.compiler.batch.Main")
        val d8 = loader.loadClass("com.android.tools.r8.D8")
        val d8Cmd = loader.loadClass("com.android.tools.r8.D8Command")
        val d8CmdBuilder = loader.loadClass("com.android.tools.r8.D8Command\$Builder")
        val apkSigner = loader.loadClass("com.android.apksig.ApkSigner")
        val apkBuilder = loader.loadClass("com.android.apksig.ApkSigner\$Builder")
        val signerCfgBuilder = loader.loadClass("com.android.apksig.ApkSigner\$SignerConfig\$Builder")
        val origin = loader.loadClass("com.android.tools.r8.origin.Origin")

        val ip = InProc(
            loader = loader,
            ecjCtor = ecjMain.getConstructor(
                PrintWriter::class.java, PrintWriter::class.java, Boolean::class.javaPrimitiveType
            ),
            ecjCompile = ecjMain.getMethod("compile", Array<String>::class.java),
            d8Parse = d8Cmd.getMethod("parse", Array<String>::class.java, origin),
            d8Run = d8.getMethod("run", d8Cmd),
            d8Build = d8CmdBuilder.getMethod("build"),
            apkSignerCtor = apkBuilder.getConstructor(List::class.java),
            signerCfgCtor = signerCfgBuilder.getConstructor(
                String::class.java, PrivateKey::class.java, List::class.java
            ),
            signerCfgBuild = signerCfgBuilder.getMethod("build"),
            apkSetInput = apkBuilder.getMethod("setInputApk", File::class.java),
            apkSetOutput = apkBuilder.getMethod("setOutputApk", File::class.java),
            apkSetV1 = apkBuilder.getMethod("setV1SigningEnabled", Boolean::class.javaPrimitiveType),
            apkSetV2 = apkBuilder.getMethod("setV2SigningEnabled", Boolean::class.javaPrimitiveType),
            apkSetV3 = apkBuilder.getMethod("setV3SigningEnabled", Boolean::class.javaPrimitiveType),
            apkSetMinSdk = apkBuilder.getMethod("setMinSdkVersion", Int::class.javaPrimitiveType),
            apkBuild = apkBuilder.getMethod("build"),
            apkSign = apkSigner.getMethod("sign")
        )
        cached = ip
        return ip
    }

    /** 把 assets 里的工具链解包到 filesDir/buildproject/libs（assets 不能直接执行）。 */
    fun ensureAssets(ctx: Context): File {
        val out = File(ctx.filesDir, "buildproject/libs")
        out.mkdirs()
        val names = listOf(
            "ecj.jar", "d8.jar", "apksigner.jar", "android.jar", "debug.keystore", "base.apk",
            // 已 dex 化的工具 jar：进程内 DexClassLoader 加载，避免 Android 14+ 禁止独立 dalvikvm。
            "ecj_dex.jar", "d8_dex.jar", "apksigner_dex.jar"
        )
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

    /** 把目录下所有 .class（含子目录）打成 jar，供 d8 作为 program 输入。 */
    private fun jarDirectory(srcDir: File, outJar: File) {
        outJar.parentFile?.mkdirs()
        outJar.delete()
        ZipOutputStream(FileOutputStream(outJar)).use { zos ->
            srcDir.walkTopDown().filter { it.isFile }.forEach { f ->
                val name = f.relativeTo(srcDir).path.replace('\\', '/')
                zos.putNextEntry(ZipEntry(name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
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
        val ecjDex = File(libs, "ecj_dex.jar")
        val d8Dex = File(libs, "d8_dex.jar")
        val sigDex = File(libs, "apksigner_dex.jar")
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
            ToolStatus("ecj_dex.jar（进程内 ecj）", ecjDex.exists(), ecjDex.absolutePath,
                if (ecjDex.exists()) "已就绪：DexClassLoader 进程内加载" else "缺失：端侧编译不可用"),
            ToolStatus("d8_dex.jar（进程内 d8）", d8Dex.exists(), d8Dex.absolutePath,
                if (d8Dex.exists()) "已就绪：DexClassLoader 进程内加载" else "缺失：生成 DEX 不可用"),
            ToolStatus("apksigner_dex.jar（进程内签名）", sigDex.exists(), sigDex.absolutePath,
                if (sigDex.exists()) "已就绪：apksig API 进程内签名" else "缺失：签名不可用"),
            ToolStatus("端侧运行时（App 进程内 ART）", true, "app-process",
                "可用：工具链直接在 App 进程内执行（不再依赖独立 dalvikvm 子进程，Android 16 亦可用）"),
            ToolStatus("aapt2（资源打包）", false, "未打包",
                "未打包：本引擎用 base.apk 模板注入 DEX 再签名，不依赖 aapt2，APK 组装仍可用")
        )
    }

    /**
     * 是否能组装完整 APK。本引擎的 assembleApk() 是把 classes.dex 注入预置的 base.apk 模板
     * （已含 AndroidManifest.xml + resources.arsc）再进程内签名，**不经过 aapt2**、**不依赖独立运行时**。
     * 真正需要：base.apk + apksigner_dex.jar（进程内签名）+ debug.keystore。
     */
    fun canAssembleApk(ctx: Context): Boolean {
        ensureAssets(ctx)
        val libs = File(ctx.filesDir, "buildproject/libs")
        return File(libs, "base.apk").exists()
            && File(libs, "apksigner_dex.jar").exists()
            && File(libs, "debug.keystore").exists()
    }

    /**
     * 编译整个 src 目录下所有 .java 文件为 classes.dex（真实管线：ecj → class，d8 → dex）。
     * 进程内执行，不需要设备提供独立 JVM 运行时。
     */
    fun compileProject(ctx: Context, srcDir: File, outDir: File): BuildResult {
        val libs = ensureAssets(ctx)
        srcDir.mkdirs()
        // 递归清空上一轮产物（含 ecj 按包名生成的子目录），避免残留旧 class 干扰本轮
        outDir.deleteRecursively()
        outDir.mkdirs()

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
        val dexDir = File(outDir.parentFile ?: outDir, "dexout").apply { mkdirs() }
        val dexFile = File(dexDir, "classes.dex")
        // 清理上一轮可能残留的损坏 DEX，避免"前几次失败留下坏中间文件"导致本轮也失败
        dexFile.delete()

        val toolchain = try {
            getToolchain(ctx)
        } catch (e: Throwable) {
            return BuildResult(false, log.append("\n== 工具链加载失败 ==\n${e.javaClass.name}: ${e.message}\n").toString(), null)
        }

        // 1) ecj：Java → class（source/target 降到 8：旧版 ecj 在 ART 下不支持 11；-proc:none 关闭注解处理，减少噪音）
        val ecjArgs = mutableListOf(
            "-proc:none",
            "-d", outDir.absolutePath,
            "-cp", aj.absolutePath,
            "-source", "8", "-target", "8"
        )
        ecjArgs.addAll(sources)
        val (ecjOk, ecjOut) = try {
            toolchain.compileEcj(ecjArgs.toTypedArray())
        } catch (e: Throwable) {
            return BuildResult(false, log.append("\n== ecj 进程内崩溃 ==\n${e.javaClass.name}: ${e.message}\n").toString(), null)
        }
        log.append("\n== ecj 编译 Java → class ==\n").append(ecjOut)
        if (!ecjOk) return BuildResult(false, log.toString(), null)

        // 2) d8：class → dex
        // 关键修复（PC 干跑发现）：本版 R8 的 parse 不支持「目录」作为 program 输入、
        // 也不支持「文件」作为 --output（必须是目录或 jar/zip）。因此先把 ecj 产物打成 jar，
        // 再让 d8 输出到目录，最终 DEX 落在 dexDir/classes.dex。
        val outJar = File(outDir.parentFile ?: outDir, "out.jar")
        try {
            jarDirectory(outDir, outJar)
        } catch (e: Throwable) {
            return BuildResult(false, log.append("\n打包 class 为 jar 失败：${e.message}\n").toString(), null)
        }
        val d8Args = arrayOf(
            "--output", dexDir.absolutePath,
            "--lib", aj.absolutePath,
            outJar.absolutePath
        )
        try {
            toolchain.runD8(d8Args)
        } catch (e: Throwable) {
            return BuildResult(false, log.append("\n== d8 进程内崩溃 ==\n${e.javaClass.name}: ${e.message}\n[DEX 未生成]").toString(), null)
        }
        if (!dexFile.exists()) {
            return BuildResult(false, log.toString() + "\n[DEX 未生成]", null)
        }

        log.append("\n✔ 编译成功：classes.dex（${dexFile.length()} 字节）位于 ${dexFile.absolutePath}")
        return BuildResult(true, log.toString(), dexFile.absolutePath, null)
    }

    /**
     * 把用户编译出的 classes.dex 注入 base.apk 模板并进程内签名，产出可直接安装的 APK。
     * 不需要 aapt2：base.apk 已含 AndroidManifest.xml + resources.arsc + 宿主 classes.dex
     * （com.example.buildapp.MainActivity）。这里把用户 dex 作为 classes2.dex 追加进去，
     * Android 多 dex 机制会自动加载宿主与用户两份 dex，宿主 Activity 反射调用用户代码。
     */
    fun assembleApk(ctx: Context, dexPath: String, outApk: File): BuildResult {
        val libs = ensureAssets(ctx)
        val baseApk = File(libs, "base.apk")
        val keystore = File(libs, "debug.keystore")
        if (!baseApk.exists() || !keystore.exists()) {
            return BuildResult(false, "工具链不完整（base.apk / debug.keystore 缺失），无法构建 APK。", dexPath, null)
        }
        val dexFile = File(dexPath)
        if (!dexFile.exists()) {
            return BuildResult(false, "classes.dex 不存在，请先编译工程。", dexPath, null)
        }

        val log = StringBuilder()
        log.append("== 注入用户 classes.dex（classes2.dex）到 base.apk ==\n")
        val unsigned = File(outApk.parentFile ?: ctx.filesDir, "app-unsigned.apk")
        try {
            unsigned.parentFile?.mkdirs()
            // base.apk 已含宿主 classes.dex（com.example.buildapp.MainActivity）+ AndroidManifest + resources.arsc。
            // 这里完整保留 base.apk 的所有条目（含宿主 classes.dex），再把用户编译出的 dex 作为
            // classes2.dex 追加进去。Android 的 PathClassLoader 会自动加载 base APK 里的
            // classes.dex / classes2.dex / …，宿主 Activity 因此能反射调用用户代码。
            ZipInputStream(BufferedInputStream(FileInputStream(baseApk))).use { zis ->
                ZipOutputStream(FileOutputStream(unsigned)).use { zos ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        zos.putNextEntry(ZipEntry(entry.name))
                        zis.copyTo(zos)
                        zos.closeEntry()
                        entry = zis.nextEntry
                    }
                    zos.putNextEntry(ZipEntry("classes2.dex"))
                    FileInputStream(dexFile).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            log.append("已生成未签名 APK：${unsigned.absolutePath}（${unsigned.length()} 字节，含宿主 classes.dex + 用户 classes2.dex）\n")
        } catch (e: Throwable) {
            return BuildResult(false, log.toString() + "\n注入失败：${e.message}", dexPath, null)
        }

        log.append("\n== apksigner 进程内签名 ==\n")
        val toolchain = try {
            getToolchain(ctx)
        } catch (e: Throwable) {
            return BuildResult(false, log.append("工具链加载失败：${e.javaClass.name}: ${e.message}\n[APK 签名失败]").toString(), dexPath, null)
        }
        try {
            toolchain.signApk(unsigned, outApk, keystore, "android", "android", "androiddebugkey")
        } catch (e: Throwable) {
            return BuildResult(false, log.append("签名失败：${e.javaClass.name}: ${e.message}\n[APK 签名失败]").toString(), dexPath, null)
        }
        if (!outApk.exists()) {
            return BuildResult(false, log.toString() + "\n[APK 签名失败]", dexPath, null)
        }
        unsigned.delete()
        log.append("\n✔ APK 构建成功：${outApk.absolutePath}（${outApk.length()} 字节）")
        return BuildResult(true, log.toString(), dexPath, outApk.absolutePath)
    }

    /**
     * 把一段 Java 源码编译为 classes.dex（兼容旧版单文件调用；内部也走 compileProject）。
     * 进程内执行，不需要设备提供独立 JVM 运行时。
     */
    fun compileToDex(ctx: Context, source: String, className: String): BuildResult {
        val libs = ensureAssets(ctx)
        val ecj = File(libs, "ecj.jar")
        val d8 = File(libs, "d8.jar")
        val aj = File(libs, "android.jar")
        if (!ecj.exists() || !d8.exists() || !aj.exists()) {
            return BuildResult(false, "工具链不完整（ecj/d8/android.jar 缺失），无法编译。", null)
        }

        val project = File(ctx.filesDir, "buildproject")
        val srcDir = File(project, "src").apply { mkdirs() }
        val outDir = File(project, "out").apply { mkdirs() }
        outDir.deleteRecursively()
        outDir.mkdirs()

        val srcFile = File(srcDir, "$className.java")
        try {
            srcFile.writeText(source)
        } catch (e: Throwable) {
            return BuildResult(false, "写入源码失败：${e.message}", null)
        }

        val dexDir = File(project, "dexout").apply { mkdirs() }
        val dexFile = File(dexDir, "classes.dex")
        // 清理上一轮残留的损坏 DEX
        dexFile.delete()

        val log = StringBuilder()
        val toolchain = try {
            getToolchain(ctx)
        } catch (e: Throwable) {
            return BuildResult(false, log.append("\n== 工具链加载失败 ==\n${e.javaClass.name}: ${e.message}\n").toString(), null)
        }

        // 1) ecj：Java → class（source/target 降到 8，兼容 ART 下的旧版 ecj）
        val ecjArgs = arrayOf(
            "-proc:none",
            "-d", outDir.absolutePath,
            "-cp", aj.absolutePath,
            "-source", "8", "-target", "8",
            srcFile.absolutePath
        )
        val (ecjOk, ecjOut) = try {
            toolchain.compileEcj(ecjArgs)
        } catch (e: Throwable) {
            return BuildResult(false, log.append("== ecj 进程内崩溃 ==\n${e.javaClass.name}: ${e.message}\n").toString(), null)
        }
        log.append("== ecj 编译 Java → class ==\n").append(ecjOut)
        if (!ecjOk) return BuildResult(false, log.toString(), null)

        // 2) d8：class → dex
        // 关键修复（PC 干跑发现）：本版 R8 的 parse 不支持「目录」作为 program 输入、
        // 也不支持「文件」作为 --output（必须是目录或 jar/zip）。因此先把 ecj 产物打成 jar，
        // 再让 d8 输出到目录，最终 DEX 落在 dexDir/classes.dex。
        val outJar = File(outDir.parentFile ?: outDir, "out.jar")
        try {
            jarDirectory(outDir, outJar)
        } catch (e: Throwable) {
            return BuildResult(false, log.append("\n打包 class 为 jar 失败：${e.message}\n").toString(), null)
        }
        val d8Args = arrayOf(
            "--output", dexDir.absolutePath,
            "--lib", aj.absolutePath,
            outJar.absolutePath
        )
        try {
            toolchain.runD8(d8Args)
        } catch (e: Throwable) {
            return BuildResult(false, log.append("\n== d8 进程内崩溃 ==\n${e.javaClass.name}: ${e.message}\n[DEX 未生成]").toString(), null)
        }
        if (!dexFile.exists()) {
            return BuildResult(false, log.toString() + "\n[DEX 未生成]", null)
        }

        log.append("\n✔ 编译成功：classes.dex（${dexFile.length()} 字节）位于 ${dexFile.absolutePath}")
        return BuildResult(true, log.toString(), dexFile.absolutePath, null)
    }
}
