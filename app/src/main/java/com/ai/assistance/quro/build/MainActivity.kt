package com.ai.assistance.quro.build

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BuildApp() }
    }
}

private fun defaultTemplate(): String = """package com.example.hello;

public class Main {
    public static String run() {
        StringBuilder sb = new StringBuilder();
        sb.append("Hello from on-device compiled DEX!\n");
        for (int i = 1; i <= 5; i++) {
            sb.append("line ").append(i).append("\n");
        }
        return sb.toString();
    }
}
"""

@Composable
fun BuildApp() {
    val context = LocalContext.current
    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                BuildScreen(context)
            }
        }
    }
}

@Composable
fun BuildScreen(context: Context) {
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf(defaultTemplate()) }
    var className by remember { mutableStateOf("Main") }
    val tools = remember { BuildEngine.detectTools(context) }
    var log by remember { mutableStateOf(
        "点击「编译为 DEX」在设备端运行 ecj + d8。\n工具链：${tools.count { it.present }} / ${tools.size} 就绪。"
    ) }
    var dexPath by remember { mutableStateOf<String?>(null) }
    var building by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null && dexPath != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    File(dexPath!!).inputStream().use { it.copyTo(os) }
                }
                log += "\n已导出 DEX 到：$uri"
            } catch (e: Throwable) {
                log += "\n导出失败：${e.message}"
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Zorv 构建台", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "端侧 APK 构建控制台 · ecj → d8 真实编译管线",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        // 工具链状态
        Text("工具链自检", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        tools.forEach { t ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (t.present) "✅" else "❌", fontSize = 14.sp)
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.name, fontSize = 13.sp)
                    Text(t.note, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(2.dp))
        }
        Spacer(Modifier.height(8.dp))

        if (!BuildEngine.canAssembleApk()) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            ) {
                Text(
                    "说明：未打包 aapt2，本构建台可真实产出 classes.dex，但无法生成完整 APK（缺 resources.arsc 打包）。DEX 可导出供 PC 侧进一步打包。",
                    Modifier.padding(12.dp), fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        OutlinedTextField(
            value = className,
            onValueChange = { className = it },
            label = { Text("主类名（不含包名）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Text("Java 源码", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = source,
            onValueChange = { source = it },
            modifier = Modifier.fillMaxWidth().height(220.dp),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            label = { Text("Java 源码") }
        )
        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    building = true; dexPath = null; log = "编译中…\n"
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            BuildEngine.compileToDex(context, source, className.ifBlank { "Main" })
                        }
                        withContext(Dispatchers.Main) {
                            building = false
                            dexPath = r.dexPath
                            log = r.log
                        }
                    }
                },
                enabled = !building,
                modifier = Modifier.weight(1f)
            ) { Text(if (building) "编译中…" else "编译为 DEX") }
            OutlinedButton(
                onClick = { if (dexPath != null) exportLauncher.launch("classes.dex") },
                enabled = dexPath != null,
                modifier = Modifier.weight(1f)
            ) { Text("导出 DEX") }
        }

        Spacer(Modifier.height(12.dp))
        Text("编译日志", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                log,
                Modifier.padding(12.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }
    }
}
