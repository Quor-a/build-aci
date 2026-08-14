package com.ai.assistance.quro.build

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: ProjectViewModel = viewModel()
            BuildApp(vm)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildApp(vm: ProjectViewModel = viewModel()) {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showNewDialog by remember { mutableStateOf<NewDialogState?>(null) }
    var showRenameDialog by remember { mutableStateOf<ProjectFile?>(null) }
    var fileToDelete by remember { mutableStateOf<ProjectFile?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) vm.exportDex(context, uri)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet {
                FileTreeDrawer(
                    files = vm.files,
                    selected = vm.selected,
                    onSelect = { vm.select(it) },
                    onCreateFile = { parent -> showNewDialog = NewDialogState(parent, true) },
                    onCreateFolder = { parent -> showNewDialog = NewDialogState(parent, false) },
                    onRename = { showRenameDialog = it },
                    onDelete = { fileToDelete = it }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Column {
                        Text("Zorv 构建台", fontSize = 18.sp)
                        Text("多文件 Java 工程 → classes.dex", fontSize = 12.sp, lineHeight = 14.sp)
                    } },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "文件树")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { vm.buildProject() },
                            enabled = !vm.isBuilding
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "编译工程")
                        }
                        IconButton(
                            onClick = { if (vm.dexPath != null) exportLauncher.launch("classes.dex") },
                            enabled = vm.dexPath != null
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "导出 DEX")
                        }
                    }
                )
            },
            floatingActionButton = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallFloatingActionButton(
                        onClick = { showNewDialog = NewDialogState("", true) }
                    ) { Icon(Icons.Default.Add, contentDescription = "新建文件") }
                    SmallFloatingActionButton(
                        onClick = { showNewDialog = NewDialogState("", false) }
                    ) { Icon(Icons.Default.CreateNewFolder, contentDescription = "新建目录") }
                }
            }
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp)
            ) {
                // 编辑器
                val fileName = vm.selected?.name ?: "未选择文件"
                Text(
                    text = fileName,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                OutlinedTextField(
                    value = vm.editorText,
                    onValueChange = { vm.onEditorTextChanged(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    placeholder = { Text("在左侧文件树选择或新建文件开始编写代码…") }
                )
                Spacer(Modifier.height(8.dp))
                // 日志
                Text("编译日志", fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 220.dp)
                ) {
                    Text(
                        vm.log,
                        Modifier
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    showNewDialog?.let { state ->
        NewItemDialog(
            isFile = state.isFile,
            parentRel = state.parentRel,
            onConfirm = { name ->
                if (state.isFile) vm.createFile(state.parentRel, name)
                else vm.createFolder(state.parentRel, name)
                showNewDialog = null
            },
            onDismiss = { showNewDialog = null }
        )
    }

    showRenameDialog?.let { file ->
        RenameDialog(
            currentName = file.name,
            onConfirm = { newName ->
                vm.rename(file, newName)
                showRenameDialog = null
            },
            onDismiss = { showRenameDialog = null }
        )
    }

    fileToDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text("确认删除") },
            text = { Text("确定删除「${file.name}" + (if (file.isDirectory) "」及其全部内容？" else "」？")) },
            confirmButton = {
                TextButton(onClick = { vm.delete(file); fileToDelete = null }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) { Text("取消") }
            }
        )
    }
}

private data class NewDialogState(val parentRel: String, val isFile: Boolean)

@Composable
private fun FileTreeDrawer(
    files: List<ProjectFile>,
    selected: ProjectFile?,
    onSelect: (ProjectFile) -> Unit,
    onCreateFile: (String) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRename: (ProjectFile) -> Unit,
    onDelete: (ProjectFile) -> Unit
) {
    Column(Modifier.fillMaxHeight().widthIn(min = 240.dp, max = 320.dp)) {
        Text(
            "工程文件",
            fontSize = 18.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            modifier = Modifier.padding(16.dp)
        )
        HorizontalDivider()
        Box(Modifier.weight(1f)) {
            if (files.isEmpty()) {
                Text(
                    "暂无文件，点击 + 新建",
                    Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val scroll = rememberScrollState()
                Column(Modifier.verticalScroll(scroll)) {
                    files.forEach { file ->
                        FileTreeItem(
                            file = file,
                            selected = selected?.relativePath == file.relativePath,
                            onClick = { if (!file.isDirectory) onSelect(file) },
                            onLongClick = {
                                if (file.isDirectory) {
                                    onCreateFile(file.relativePath)
                                } else {
                                    onRename(file)
                                }
                            },
                            onAddFile = { onCreateFile(file.relativePath) },
                            onAddFolder = { onCreateFolder(file.relativePath) },
                            onRename = { onRename(file) },
                            onDelete = { onDelete(file) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileTreeItem(
    file: ProjectFile,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAddFile: () -> Unit,
    onAddFolder: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Surface(
        color = bg,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (file.depth * 16).dp)
            .heightIn(min = 40.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            if (file.isDirectory) {
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                        contentDescription = null
                    )
                }
            } else {
                Spacer(Modifier.width(24.dp))
            }
            Icon(
                imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                file.name,
                fontSize = 13.sp,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 10.dp)
                    .noRippleClickable { if (file.isDirectory) expanded = !expanded else onClick() }
            )
            if (file.isDirectory) {
                Row {
                    IconButton(onClick = onAddFile, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Add, contentDescription = "新建文件", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onAddFolder, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "新建目录", modifier = Modifier.size(18.dp))
                    }
                }
            } else {
                IconButton(onClick = onRename, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "重命名", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier = this.then(
    clickable(
        indication = null,
        interactionSource = remember { MutableInteractionSource() }
    ) { onClick() }
)

@Composable
private fun NewItemDialog(
    isFile: Boolean,
    parentRel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    val title = if (isFile) "新建文件" else "新建目录"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (parentRel.isNotEmpty()) {
                    Text("位置：$parentRel", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(if (isFile) "文件名（如 Main.java）" else "目录名") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name) }, enabled = name.isNotBlank()) {
                Text("确定")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun RenameDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("新名称") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name) }, enabled = name.isNotBlank()) {
                Text("确定")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
