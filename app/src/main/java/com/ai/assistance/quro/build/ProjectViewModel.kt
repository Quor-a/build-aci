package com.ai.assistance.quro.build

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 端侧工程视图模型：管理 buildproject/src 下的多文件 Java 工程。
 *
 * 功能：
 * · 自动初始化默认工程（com/example/hello/Main.java）
 * · 文件树加载、新建/重命名/删除文件与目录
 * · 当前文件编辑与保存
 * · 调用 BuildEngine 编译整个工程为 classes.dex
 * · 导出 DEX 到系统下载/分享
 */
class ProjectViewModel(application: Application) : AndroidViewModel(application) {

    private val projectRoot: File = File(application.filesDir, "buildproject")
    private val srcDir: File = File(projectRoot, "src")
    private val outDir: File = File(projectRoot, "out")

    /** 文件树，按目录优先、名字排序。 */
    val files = mutableStateListOf<ProjectFile>()

    /** 当前选中的文件（非目录）。 */
    var selected by mutableStateOf<ProjectFile?>(null)
        private set

    /** 当前编辑器内容。 */
    var editorText by mutableStateOf("")
        private set

    /** 编译日志。 */
    var log by mutableStateOf("点击「编译工程」把 src 下所有 .java 编成 classes.dex。")
        private set

    /** 是否正在编译。 */
    var isBuilding by mutableStateOf(false)
        private set

    /** 上一次编译成功的 classes.dex 路径。 */
    var dexPath by mutableStateOf<String?>(null)
        private set

    /** 上一次构建成功的 APK 路径。 */
    var apkPath by mutableStateOf<String?>(null)
        private set

    /** 工具链自检结果。 */
    var toolStatus by mutableStateOf<List<BuildEngine.ToolStatus>>(emptyList())
        private set

    init {
        ensureDefaultProject()
        loadFiles()
        refreshToolStatus()
    }

    /** 刷新工具链状态。 */
    fun refreshToolStatus() {
        toolStatus = BuildEngine.detectTools(getApplication())
    }

    /** 构建完整 APK（classes.dex 注入 base.apk 并签名）。 */
    fun buildApk() {
        val dex = dexPath ?: run {
            log += "\n请先编译工程生成 classes.dex，再构建 APK。"
            return
        }
        isBuilding = true
        log = "构建 APK 中…\n"
        viewModelScope.launch {
            val out = File(projectRoot, "app-${timestamp()}.apk")
            val r = withContext(Dispatchers.IO) {
                BuildEngine.assembleApk(getApplication(), dex, out)
            }
            isBuilding = false
            apkPath = r.apkPath
            log = r.log
        }
    }

    /** 导出 APK 到系统分享/下载。 */
    fun exportApk(context: Context, uri: android.net.Uri) {
        val path = apkPath ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    File(path).inputStream().use { it.copyTo(os) }
                }
                withContext(Dispatchers.Main) { log += "\n已导出 APK 到：$uri" }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { log += "\n导出失败：${e.message}" }
            }
        }
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())

    /** 保证默认工程存在：一个带 package 的 Main.java。 */
    private fun ensureDefaultProject() {
        srcDir.mkdirs()
        val pkgDir = File(srcDir, "com/example/hello")
        pkgDir.mkdirs()
        val main = File(pkgDir, "Main.java")
        if (!main.exists()) {
            main.writeText(
                """package com.example.hello;

public class Main {
    public static String run() {
        StringBuilder sb = new StringBuilder();
        sb.append("Hello from on-device compiled DEX!").append("\n");
        for (int i = 1; i <= 5; i++) {
            sb.append("line ").append(i).append("\n");
        }
        return sb.toString();
    }
}
""".trimIndent()
            )
        }
    }

    /** 重新扫描 src 目录，生成展平的文件树列表（目录也作为节点插入）。 */
    fun loadFiles() {
        files.clear()
        if (!srcDir.exists()) srcDir.mkdirs()
        val list = scanDir(srcDir, "", 0)
        files.addAll(list)
        if (selected == null || files.none { it.relativePath == selected?.relativePath && !it.isDirectory }) {
            selectFirstJavaFile()
        }
    }

    private fun scanDir(dir: File, relPrefix: String, depth: Int): List<ProjectFile> {
        val result = mutableListOf<ProjectFile>()
        val children = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: return result
        for (child in children) {
            val rel = if (relPrefix.isEmpty()) child.name else "$relPrefix/${child.name}"
            result.add(ProjectFile(rel, child.name, child.isDirectory, depth))
            if (child.isDirectory) {
                result.addAll(scanDir(child, rel, depth + 1))
            }
        }
        return result
    }

    private fun selectFirstJavaFile() {
        val first = files.firstOrNull { it.isJavaFile }
        if (first != null) select(first)
    }

    /** 选中文件并加载内容到编辑器。 */
    fun select(file: ProjectFile) {
        if (file.isDirectory) return
        // 先保存当前内容到旧文件
        saveCurrent()
        selected = file
        val f = File(srcDir, file.relativePath)
        editorText = if (f.exists()) f.readText() else ""
    }

    /** 编辑器内容变化。 */
    fun onEditorTextChanged(text: String) {
        editorText = text
    }

    /** 把编辑器内容写回磁盘。 */
    fun saveCurrent() {
        val sel = selected ?: return
        val f = File(srcDir, sel.relativePath)
        try {
            f.writeText(editorText)
        } catch (e: Throwable) {
            log += "\n保存 ${sel.name} 失败：${e.message}"
        }
    }

    /** 在指定相对目录下新建文件。 */
    fun createFile(parentRel: String, fileName: String): Boolean {
        val parent = if (parentRel.isEmpty()) srcDir else File(srcDir, parentRel)
        if (!parent.exists() || !parent.isDirectory) return false
        val f = File(parent, fileName.trim())
        return try {
            if (!f.exists()) {
                f.parentFile?.mkdirs()
                f.createNewFile()
                if (fileName.endsWith(".java", ignoreCase = true)) {
                    val pkg = packageFromPath(File(srcDir, parentRel))
                    val cls = fileName.removeSuffix(".java").removeSuffix(".JAVA")
                    f.writeText(
                        if (pkg.isNotBlank()) "package $pkg;\n\npublic class $cls {\n}\n"
                        else "public class $cls {\n}\n"
                    )
                }
            }
            loadFiles()
            val rel = if (parentRel.isEmpty()) fileName.trim() else "$parentRel/${fileName.trim()}"
            files.find { it.relativePath == rel && !it.isDirectory }?.let { select(it) }
            true
        } catch (e: Throwable) {
            log += "\n新建文件失败：${e.message}"
            false
        }
    }

    /** 在指定相对目录下新建目录。 */
    fun createFolder(parentRel: String, folderName: String): Boolean {
        val parent = if (parentRel.isEmpty()) srcDir else File(srcDir, parentRel)
        val f = File(parent, folderName.trim())
        return try {
            f.mkdirs()
            loadFiles()
            true
        } catch (e: Throwable) {
            log += "\n新建目录失败：${e.message}"
            false
        }
    }

    /** 重命名文件或目录。 */
    fun rename(file: ProjectFile, newName: String): Boolean {
        if (newName.isBlank() || newName.contains('/') || newName.contains('\\')) return false
        val f = File(srcDir, file.relativePath)
        val parent = f.parentFile ?: return false
        val dest = File(parent, newName.trim())
        return try {
            if (dest.exists()) return false
            f.renameTo(dest)
            loadFiles()
            true
        } catch (e: Throwable) {
            log += "\n重命名失败：${e.message}"
            false
        }
    }

    /** 删除文件或空目录。 */
    fun delete(file: ProjectFile): Boolean {
        val f = File(srcDir, file.relativePath)
        return try {
            if (file.isDirectory) {
                f.deleteRecursively()
            } else {
                f.delete()
            }
            if (selected?.relativePath == file.relativePath) {
                selected = null
                editorText = ""
            }
            loadFiles()
            true
        } catch (e: Throwable) {
            log += "\n删除失败：${e.message}"
            false
        }
    }

    /** 编译整个工程。 */
    fun buildProject() {
        saveCurrent()
        isBuilding = true
        dexPath = null
        log = "编译中…\n"
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                BuildEngine.compileProject(getApplication(), srcDir, outDir)
            }
            isBuilding = false
            dexPath = r.dexPath
            log = r.log
        }
    }

    /** 导出 DEX。 */
    fun exportDex(context: Context, uri: android.net.Uri) {
        val path = dexPath ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    File(path).inputStream().use { it.copyTo(os) }
                }
                withContext(Dispatchers.Main) {
                    log += "\n已导出 DEX 到：$uri"
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    log += "\n导出失败：${e.message}"
                }
            }
        }
    }

    /** 根据相对目录路径反推 package（如 com/example/hello → com.example.hello）。 */
    private fun packageFromPath(dirUnderSrc: File): String {
        val rel = dirUnderSrc.relativeToOrNull(srcDir)?.path?.replace('/', '.')?.replace('\\', '.') ?: ""
        return rel.trim('.')
    }
}
