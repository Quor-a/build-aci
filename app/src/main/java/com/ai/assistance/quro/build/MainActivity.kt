package com.ai.assistance.quro.build

import ai.aidl.aci.core.AidlAciRequest
import ai.aidl.aci.core.IAidlAciService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BuildAci 主界面（Jetpack Compose）。既可作为 Zorv AI 的 ACI 受控端（端侧 APK 构建），也可独立使用。
 * 四个 Tab：
 *  - 构建：生成默认工程 / 一键构建（aapt2→ecj→d8→apksigner）/ 进度与日志 / 安装产物 APK。
 *  - 代码：文件树 + 语法高亮编辑器，写/改源码后「保存并构建」—— 把“开发”补进来，让本 App 真正成为「开发构建软件」。
 *  - 工具链：显示端侧工具链齐备情况与放置说明。
 *  - 操控台：自绑定 ACI Service（同进程 AIDL），可视化能力列表与手动调 call()。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 内容延伸到系统栏，由 Compose 处理安全区，避免工具条压到摄像头/刘海。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        BuildEngine.init(applicationContext)
        setContent { BuildApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    val busy = remember { mutableStateOf(false) }
    val jobId = remember { mutableStateOf("") }
    val toast = remember { mutableStateOf("") }

    fun startBuild() {
        if (busy.value) return
        busy.value = true
        scope.launch(Dispatchers.IO) {
            val id = BuildEngine.build(defaultProjectRoot(context))
            jobId.value = id
            withContext(Dispatchers.Main) { toast.value = "已启动构建 $id" }
        }
    }

        MaterialTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                ) {
                    TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("构建") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("代码") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("工具链") })
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        0 -> BuildScreen(context, busy, jobId, toast, ::startBuild)
                        1 -> CodeScreen(context, busy, jobId, toast, ::startBuild) { tab = 0 }
                        2 -> ToolsScreen(context)
                    }
                }
            }
        }
    }
}

// ───────────────────────── 构建 Tab ─────────────────────────

private fun defaultProjectRoot(context: Context): File =
    File(context.filesDir, "buildproject")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildScreen(
    context: Context,
    busy: MutableState<Boolean>,
    jobId: MutableState<String>,
    toast: MutableState<String>,
    startBuild: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val root = remember { defaultProjectRoot(context) }
    val state = remember { mutableStateOf(BuildEngine.getState("") ?: emptyState()) }
    val logs = remember { mutableStateOf("（尚未构建）") }
    val listState = rememberLazyListState()

    fun refreshStatic() {
        if (!busy.value) {
            state.value = emptyState()
            logs.value = if (root.isDirectory) "工程目录：${root.absolutePath}\n文件数：${BuildEngine.listProject(root).size}" else "工程尚未生成，请点「生成默认工程」"
        }
    }

    LaunchedEffect(Unit) { refreshStatic() }

    LaunchedEffect(busy.value, jobId.value) {
        if (busy.value && jobId.value.isNotBlank()) {
            while (busy.value) {
                val st = BuildEngine.getState(jobId.value)
                if (st != null) {
                    state.value = st
                    logs.value = BuildEngine.getLogs(jobId.value, 300)
                    if (st.finished) { busy.value = false }
                }
                delay(400)
            }
        }
    }
    LaunchedEffect(logs.value.length) {
        if (logs.value.isNotEmpty()) listState.scrollToItem(logs.value.lineSequence().count().coerceAtLeast(1) - 1)
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("端侧 APK 构建器", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("工程目录：${root.absolutePath}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch(Dispatchers.IO) {
                            val ok = BuildEngine.scaffoldProject(root, true)
                            withContext(Dispatchers.Main) {
                                toast.value = if (ok) "已生成默认工程" else "生成工程失败"
                                refreshStatic()
                            }
                        }
                    }, modifier = Modifier.weight(1f)) { Text("生成默认工程") }
                    Button(onClick = startBuild, modifier = Modifier.weight(1f), enabled = !busy.value) { Text(if (busy.value) "构建中…" else "构建 APK") }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        val s = state.value
        LinearProgressIndicator(
            progress = { (s.progress.coerceIn(0, 100)) / 100f },
            modifier = Modifier.fillMaxWidth().height(8.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text("步骤：${s.step} · ${s.progress}%${if (s.finished) " · ${if (s.success) "成功" else "失败"}" else ""}",
            fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        if (s.steps.isNotEmpty()) {
            s.steps.forEach { st ->
                Text("${if (st.ok) "✓" else "✗"} ${st.name} · ${st.message}", fontSize = 12.sp,
                    color = if (st.ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("构建日志", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(8.dp), state = listState) {
            items(logs.value.lineSequence().toList()) { line ->
                Text(line, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (s.apkPath != null) {
            val apkFile = File(s.apkPath!!)
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("产物 APK", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    SelectionContainer { Text(apkFile.absolutePath, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                    Text("${apkFile.length()} 字节", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = {
                        try {
                            val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
                            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                                setDataAndType(uri, "application/vnd.android.package-archive")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (e: Throwable) { toast.value = "安装失败：${e.message}" }
                    }) { Text("安装 APK") }
                }
            }
        }
        if (toast.value.isNotBlank()) Text(toast.value, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
    }
}

private fun emptyState(): BuildEngine.State =
    BuildEngine.State("", false, "待命", 0, null, emptyList(), emptyList(), false, false)

// ───────────────────────── 代码 Tab（写代码） ─────────────────────────

/** 轻量语法高亮（Java/Kotlin/XML 通用）：注释 / 字符串 / 数字 / 关键字。仅改显示，不改文本长度，光标安全。 */
class CodeHighlightTransformation : VisualTransformation {
    companion object {
        private val KEYWORDS = setOf(
            "public","private","protected","static","final","abstract","class","interface","enum",
            "extends","implements","void","int","long","double","float","boolean","byte","char","short",
            "String","Object","new","return","if","else","for","while","do","switch","case","default",
            "break","continue","try","catch","finally","throw","throws","import","package","super","this",
            "null","true","false","annotation","@Override","@SuppressWarnings","fun","val","var","object",
            "override","typeof","struct","const","let"
        )
        private val PATTERN = Regex(
            """(//[^\n]*)|(/\*.*?\*/)|("(?:[^"\\]|\\.)*")|('(?:[^'\\]|\\.)*')|(\b\d[\d_]*(?:\.\d+)?\b)|(\b(?:""" +
            KEYWORDS.joinToString("|") + """)\b)""",
            RegexOption.DOT_MATCHES_ALL
        )
    }
    override fun filter(text: AnnotatedString): TransformedText {
        val out = AnnotatedString.Builder(text.text)
        PATTERN.findAll(text.text).forEach { m ->
            val style = when {
                m.groups[1] != null || m.groups[2] != null -> SpanStyle(color = Color(0xFF9E9E9E))
                m.groups[3] != null || m.groups[4] != null -> SpanStyle(color = Color(0xFF2E7D32))
                m.groups[5] != null -> SpanStyle(color = Color(0xFFEF6C00))
                else -> SpanStyle(color = Color(0xFF7B1FA2), fontWeight = FontWeight.Bold)
            }
            out.addStyle(style, m.range.first, m.range.last + 1)
        }
        return TransformedText(out.toAnnotatedString(), OffsetMapping.Identity)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeScreen(
    context: Context,
    busy: MutableState<Boolean>,
    jobId: MutableState<String>,
    toast: MutableState<String>,
    startBuild: () -> Unit,
    goBuild: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val root = remember { defaultProjectRoot(context) }
    val files = remember { mutableStateOf<List<String>>(emptyList()) }
    val selected = remember { mutableStateOf("") }
    val content = remember { mutableStateOf("") }
    val dirty = remember { mutableStateOf(false) }
    val showNew = remember { mutableStateOf(false) }
    val newPath = remember { mutableStateOf("") }

    fun refreshFiles() { files.value = BuildEngine.listProject(root) }

    fun openFile(rel: String) {
        val f = File(root, rel)
        if (f.isFile) {
            content.value = f.readText(Charsets.UTF_8)
            selected.value = rel
            dirty.value = false
        }
    }

    LaunchedEffect(Unit) {
        if (!root.isDirectory) BuildEngine.scaffoldProject(root, false)
        refreshFiles()
    }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        // 工具条
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("代码编辑器", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            TextButton(onClick = { newPath.value = ""; showNew.value = true }) { Text("新建") }
            Button(onClick = {
                val rel = selected.value
                if (rel.isBlank()) { toast.value = "先选择一个文件"; return@Button }
                scope.launch(Dispatchers.IO) {
                    val ok = BuildEngine.writeSource(root, rel, content.value)
                    withContext(Dispatchers.Main) { dirty.value = !ok; toast.value = if (ok) "已保存 $rel" else "保存失败" }
                }
            }) { Text(if (dirty.value) "保存*" else "保存") }
            Button(onClick = {
                scope.launch(Dispatchers.IO) {
                    if (selected.value.isNotBlank()) BuildEngine.writeSource(root, selected.value, content.value)
                    withContext(Dispatchers.Main) {
                        toast.value = "已保存，转构建…"
                        startBuild()
                        goBuild()
                    }
                }
            }, enabled = !busy.value) { Text("保存并构建") }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxSize().weight(1f)) {
            // 文件树
            LazyColumn(
                Modifier.width(150.dp).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(4.dp)
            ) {
                items(files.value) { rel ->
                    val isSel = rel == selected.value
                    Text(
                        rel,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().clickable { openFile(rel) }.padding(6.dp)
                    )
                }
                if (files.value.isEmpty()) item { Text("（空工程）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(6.dp)) }
            }
            Spacer(Modifier.width(6.dp))
            // 编辑器
            if (selected.value.isBlank()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("← 选择左侧文件开始编辑，或点「新建」", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                OutlinedTextField(
                    value = content.value,
                    onValueChange = { content.value = it; dirty.value = true },
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    visualTransformation = CodeHighlightTransformation(),
                    singleLine = false,
                    label = { Text(selected.value) }
                )
            }
        }
        if (toast.value.isNotBlank()) Text(toast.value, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
    }

    // 新建文件对话框
    if (showNew.value) {
        AlertDialog(
            onDismissRequest = { showNew.value = false },
            confirmButton = {
                TextButton(onClick = {
                    val rel = newPath.value.trim()
                    showNew.value = false
                    if (rel.isBlank()) { toast.value = "路径不能为空"; return@TextButton }
                    val initial = if (rel.endsWith(".java")) "public class NewFile {}\n" else if (rel.endsWith(".kt")) "fun main() {}\n" else if (rel.endsWith(".xml")) "<!-- new file -->\n" else ""
                    scope.launch(Dispatchers.IO) {
                        val ok = BuildEngine.writeSource(root, rel, initial)
                        withContext(Dispatchers.Main) {
                            if (ok) { refreshFiles(); openFile(rel) } else toast.value = "创建失败：$rel"
                        }
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showNew.value = false }) { Text("取消") } },
            title = { Text("新建源/资源文件") },
            text = {
                OutlinedTextField(
                    value = newPath.value,
                    onValueChange = { newPath.value = it },
                    label = { Text("相对工程根的路径") },
                    placeholder = { Text("如 src/com/example/buildapp/Second.java") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        )
    }
}

// ───────────────────────── 工具链 Tab ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(context: Context) {
    val tools = remember { mutableStateOf<List<BuildEngine.ToolInfo>>(emptyList()) }
    LaunchedEffect(Unit) {
        tools.value = BuildEngine.detectTools()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("端侧工具链状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                val ready = tools.value.isNotEmpty() && tools.value.all { it.present }
                Text("● 总体：${if (ready) "齐备，可构建" else "缺失若干项（见下）"}",
                    fontSize = 13.sp, color = if (ready) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error)
                Text("● 内置签名密钥：debug.keystore（androiddebugkey / android）已打包", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        tools.value.forEach { t ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${if (t.present) "✓" else "✗"} ", fontSize = 16.sp,
                            color = if (t.present) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold)
                        Text(t.label, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    Text("类型：${t.kind}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (t.present && t.path != null) {
                        SelectionContainer { Text(t.path!!, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else {
                        Text("缺失说明：${t.note}", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("如何补齐工具链", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "把下列文件按对应位置放入即可「真出包」：\n" +
                    "• aapt2（原生，须匹配设备 ABI）：assets/libs/<abi>/aapt2 或 filesDir/toolchain/<abi>/aapt2\n" +
                    "• ecj.jar / d8.jar / apksigner.jar（纯 Java）：assets/libs/common/ 或 filesDir/toolchain/common/\n" +
                    "• android.jar（对应 SDK platform 框架桩）：同上 common/ 目录\n" +
                    "• framework-res.apk（可选，缺则依赖设备 /system/framework/framework-res.apk）\n" +
                    "纯 Java 工具经 /system/bin/dalvikvm 运行；aapt2 为原生二进制直接 exec。\n" +
                    "Kotlin 工程需另行提供 kotlinc，本版默认支持 Java 单 Activity 模板。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ───────────────────────── 操控台 Tab ─────────────────────────

