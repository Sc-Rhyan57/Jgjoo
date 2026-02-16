package com.apkextract

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// ─── Data ───────────────────────────────────────────────────────────────────

enum class FileCategory(
    val label: String,
    val emoji: String,
    val description: String,
    val color: Color
) {
    MANIFEST("Manifest", "📋", "AndroidManifest.xml", Color(0xFF64B5F6)),
    CODE("Código DEX", "⚙️", "Bytecode Dalvik compilado", Color(0xFFFFB74D)),
    RESOURCES("Recursos", "🎨", "res/ + resources.arsc", Color(0xFFA5D6A7)),
    ASSETS("Assets", "📁", "assets/ — arquivos brutos", Color(0xFFCE93D8)),
    NATIVE("Libs Nativas", "🔧", "lib/ — arquivos .so (C/C++)", Color(0xFFEF9A9A)),
    CERTIFICATES("Assinaturas", "🔐", "META-INF/ — certificados", Color(0xFFFFF176)),
    OTHER("Outros", "📄", "Arquivos não categorizados", Color(0xFF90A4AE))
}

data class ApkEntry(
    val path: String,
    val name: String,
    val size: Long,
    val compressedSize: Long,
    val category: FileCategory,
    val extension: String
)

data class ApkAnalysis(
    val entries: List<ApkEntry>,
    val totalSize: Long,
    val apkName: String
) {
    val byCategory: Map<FileCategory, List<ApkEntry>>
        get() = entries.groupBy { it.category }

    fun countOf(cat: FileCategory) = byCategory[cat]?.size ?: 0
    fun sizeOf(cat: FileCategory) = byCategory[cat]?.sumOf { it.size } ?: 0L
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

fun Long.toReadableSize(): String = when {
    this < 1024 -> "${this} B"
    this < 1024 * 1024 -> "${"%.1f".format(this / 1024.0)} KB"
    else -> "${"%.2f".format(this / (1024.0 * 1024.0))} MB"
}

fun categorize(path: String): FileCategory {
    val lower = path.lowercase()
    return when {
        path == "AndroidManifest.xml" -> FileCategory.MANIFEST
        lower.endsWith(".dex") -> FileCategory.CODE
        lower.startsWith("assets/") -> FileCategory.ASSETS
        lower.startsWith("lib/") && lower.endsWith(".so") -> FileCategory.NATIVE
        lower.startsWith("lib/") -> FileCategory.NATIVE
        lower.startsWith("meta-inf/") -> FileCategory.CERTIFICATES
        lower.startsWith("res/") || path == "resources.arsc" -> FileCategory.RESOURCES
        else -> FileCategory.OTHER
    }
}

fun extensionOf(path: String) = path.substringAfterLast('.', "").lowercase()

fun archOfLib(path: String): String {
    // lib/arm64-v8a/libfoo.so → arm64-v8a
    val parts = path.split("/")
    return if (parts.size >= 2) parts[1] else "unknown"
}

// ─── Activity ────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ApkExtractApp()
            }
        }
    }

    @Composable
    fun ApkExtractApp() {
        var selectedUri by remember { mutableStateOf<Uri?>(null) }
        var apkFileName by remember { mutableStateOf("") }
        var isAnalyzing by remember { mutableStateOf(false) }
        var isExtracting by remember { mutableStateOf(false) }
        var progress by remember { mutableStateOf(0f) }
        var statusMsg by remember { mutableStateOf("") }
        var analysis by remember { mutableStateOf<ApkAnalysis?>(null) }
        var outputPath by remember { mutableStateOf("") }
        var selectedCategory by remember { mutableStateOf<FileCategory?>(null) }

        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri ->
            uri?.let {
                selectedUri = it
                apkFileName = it.lastPathSegment?.substringAfterLast('/') ?: "app.apk"
                analysis = null
                outputPath = ""
                selectedCategory = null
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF0D0F14)
        ) {
            if (analysis == null) {
                // ── Home / Select screen ──────────────────────────────────
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(48.dp))

                    Text(
                        "APK",
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF00E5FF),
                        letterSpacing = (-2).sp
                    )
                    Text(
                        "EXTRACTOR PRO",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF546E7A),
                        letterSpacing = 6.sp
                    )

                    Spacer(Modifier.height(48.dp))

                    // Drop card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { if (!isAnalyzing) picker.launch("application/vnd.android.package-archive") },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF131720)),
                        border = BorderStroke(
                            1.dp,
                            if (selectedUri != null) Color(0xFF00E5FF) else Color(0xFF1E2530)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(if (selectedUri == null) "📦" else "✅", fontSize = 48.sp)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                if (selectedUri == null) "Selecionar APK" else apkFileName,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedUri == null) Color(0xFF546E7A) else Color(0xFF00E5FF),
                                fontSize = 16.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            if (selectedUri == null) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Toque para selecionar um arquivo .apk",
                                    fontSize = 12.sp,
                                    color = Color(0xFF37474F)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    // Progress
                    AnimatedVisibility(isAnalyzing) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            LinearProgressIndicator(
                                progress = progress,
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(0xFF00E5FF),
                                trackColor = Color(0xFF1E2530)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(statusMsg, fontSize = 12.sp, color = Color(0xFF546E7A))
                        }
                    }

                    AnimatedVisibility(selectedUri != null && !isAnalyzing) {
                        Button(
                            onClick = {
                                lifecycleScope.launch {
                                    isAnalyzing = true
                                    try {
                                        val result = analyzeApk(selectedUri!!) { p, s ->
                                            progress = p; statusMsg = s
                                        }
                                        analysis = result
                                    } catch (e: Exception) {
                                        statusMsg = "Erro: ${e.message}"
                                    } finally {
                                        isAnalyzing = false
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                "🔍  ANALISAR APK",
                                color = Color(0xFF0D0F14),
                                fontWeight = FontWeight.Black,
                                fontSize = 15.sp,
                                letterSpacing = 2.sp
                            )
                        }
                    }

                    if (!hasStoragePermission()) {
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = { requestStoragePermission() },
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, Color(0xFFFF5252)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("⚠️ Conceder Permissão de Armazenamento", color = Color(0xFFFF5252))
                        }
                    }
                }

            } else {
                // ── Analysis result screen ────────────────────────────────
                val a = analysis!!
                if (selectedCategory == null) {
                    AnalysisDashboard(
                        analysis = a,
                        isExtracting = isExtracting,
                        outputPath = outputPath,
                        onCategoryClick = { selectedCategory = it },
                        onExtract = {
                            lifecycleScope.launch {
                                isExtracting = true
                                try {
                                    val path = extractApk(selectedUri!!) { p, s ->
                                        progress = p; statusMsg = s
                                    }
                                    outputPath = path
                                } catch (e: Exception) {
                                    statusMsg = "Erro: ${e.message}"
                                } finally {
                                    isExtracting = false
                                }
                            }
                        },
                        onBack = {
                            analysis = null
                            selectedUri = null
                            apkFileName = ""
                        }
                    )
                } else {
                    CategoryDetailScreen(
                        analysis = a,
                        category = selectedCategory!!,
                        onBack = { selectedCategory = null }
                    )
                }
            }
        }
    }

    // ── Dashboard ────────────────────────────────────────────────────────────

    @Composable
    fun AnalysisDashboard(
        analysis: ApkAnalysis,
        isExtracting: Boolean,
        outputPath: String,
        onCategoryClick: (FileCategory) -> Unit,
        onExtract: () -> Unit,
        onBack: () -> Unit
    ) {
        Column(Modifier.fillMaxSize()) {
            // Header
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF131720))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Text("←", fontSize = 20.sp, color = Color(0xFF00E5FF))
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        analysis.apkName,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFECEFF1),
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${analysis.entries.size} arquivos · ${analysis.totalSize.toReadableSize()}",
                        fontSize = 12.sp,
                        color = Color(0xFF546E7A)
                    )
                }
            }

            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Total bar
                item {
                    TotalBar(analysis)
                }

                // Category cards
                item {
                    Text(
                        "CATEGORIAS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF546E7A),
                        letterSpacing = 3.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                items(FileCategory.values()) { cat ->
                    val count = analysis.countOf(cat)
                    if (count > 0) {
                        CategoryCard(
                            category = cat,
                            count = count,
                            size = analysis.sizeOf(cat),
                            totalSize = analysis.totalSize,
                            onClick = { onCategoryClick(cat) }
                        )
                    }
                }

                // Arch breakdown for native libs
                val nativeEntries = analysis.byCategory[FileCategory.NATIVE]
                if (!nativeEntries.isNullOrEmpty()) {
                    item {
                        ArchBreakdown(nativeEntries)
                    }
                }

                // Extract button
                item {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onExtract,
                        enabled = !isExtracting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isExtracting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color(0xFF0D0F14),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("Extraindo...", color = Color(0xFF0D0F14), fontWeight = FontWeight.Bold)
                        } else {
                            Text(
                                "📤  EXTRAIR TODOS OS ARQUIVOS",
                                color = Color(0xFF0D0F14),
                                fontWeight = FontWeight.Black,
                                fontSize = 14.sp,
                                letterSpacing = 1.sp
                            )
                        }
                    }

                    if (outputPath.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1F0F)),
                            border = BorderStroke(1.dp, Color(0xFF2E7D32)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("✅", fontSize = 20.sp)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Extração concluída!", fontWeight = FontWeight.Bold, color = Color(0xFF81C784))
                                    Text(outputPath, fontSize = 11.sp, color = Color(0xFF546E7A), maxLines = 2)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun TotalBar(analysis: ApkAnalysis) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131720)),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0xFF1E2530))
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatChip("Arquivos", analysis.entries.size.toString())
                    StatChip("Tamanho total", analysis.totalSize.toReadableSize())
                    StatChip("Categorias",
                        analysis.byCategory.keys.size.toString())
                }
                Spacer(Modifier.height(16.dp))
                // Proportional bar
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                ) {
                    FileCategory.values().forEach { cat ->
                        val sz = analysis.sizeOf(cat)
                        if (sz > 0) {
                            val ratio = sz.toFloat() / analysis.totalSize
                            Box(
                                Modifier
                                    .weight(ratio)
                                    .fillMaxHeight()
                                    .background(cat.color)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FileCategory.values().forEach { cat ->
                        if (analysis.countOf(cat) > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(cat.color)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(cat.label, fontSize = 9.sp, color = Color(0xFF546E7A))
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun StatChip(label: String, value: String) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontWeight = FontWeight.Black, color = Color(0xFF00E5FF), fontSize = 18.sp)
            Text(label, fontSize = 10.sp, color = Color(0xFF546E7A))
        }
    }

    @Composable
    fun CategoryCard(
        category: FileCategory,
        count: Int,
        size: Long,
        totalSize: Long,
        onClick: () -> Unit
    ) {
        val ratio = if (totalSize > 0) size.toFloat() / totalSize else 0f

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() },
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131720)),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Color(0xFF1E2530))
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(category.emoji, fontSize = 24.sp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            category.label,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFECEFF1),
                            fontSize = 15.sp
                        )
                        Text(
                            category.description,
                            fontSize = 11.sp,
                            color = Color(0xFF546E7A)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "$count arquivo${if (count != 1) "s" else ""}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = category.color
                        )
                        Text(
                            size.toReadableSize(),
                            fontSize = 11.sp,
                            color = Color(0xFF546E7A)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = ratio,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = category.color,
                    trackColor = Color(0xFF1E2530)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${"%.1f".format(ratio * 100)}% do APK · toque para ver arquivos →",
                    fontSize = 10.sp,
                    color = Color(0xFF37474F)
                )
            }
        }
    }

    @Composable
    fun ArchBreakdown(entries: List<ApkEntry>) {
        val byArch = entries.groupBy { archOfLib(it.path) }
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131720)),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Color(0xFF1E2530))
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    "ARQUITETURAS NATIVAS",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF546E7A),
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.height(12.dp))
                byArch.forEach { (arch, files) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "🔩 $arch",
                            fontSize = 13.sp,
                            color = Color(0xFFEF9A9A),
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "${files.size} libs · ${files.sumOf { it.size }.toReadableSize()}",
                            fontSize = 12.sp,
                            color = Color(0xFF546E7A)
                        )
                    }
                }
            }
        }
    }

    // ── Category Detail ──────────────────────────────────────────────────────

    @Composable
    fun CategoryDetailScreen(
        analysis: ApkAnalysis,
        category: FileCategory,
        onBack: () -> Unit
    ) {
        val entries = analysis.byCategory[category] ?: emptyList()
        val grouped = entries.groupBy { it.path.substringBeforeLast('/', "") }

        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF131720))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Text("←", fontSize = 20.sp, color = Color(0xFF00E5FF))
                }
                Text(category.emoji, fontSize = 22.sp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(category.label, fontWeight = FontWeight.Bold, color = Color(0xFFECEFF1))
                    Text("${entries.size} arquivos · ${entries.sumOf { it.size }.toReadableSize()}", fontSize = 12.sp, color = Color(0xFF546E7A))
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                grouped.forEach { (folder, files) ->
                    if (folder.isNotEmpty()) {
                        item {
                            Text(
                                "📂 $folder/",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF546E7A),
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                letterSpacing = 1.sp
                            )
                        }
                    }
                    items(files) { entry ->
                        FileEntryRow(entry, category)
                    }
                }
            }
        }
    }

    @Composable
    fun FileEntryRow(entry: ApkEntry, category: FileCategory) {
        val icon = when (entry.extension) {
            "dex" -> "⚙️"
            "so" -> "🔩"
            "xml" -> "📝"
            "png", "jpg", "jpeg", "webp", "gif" -> "🖼️"
            "json" -> "🗂️"
            "html", "htm" -> "🌐"
            "ttf", "otf", "woff" -> "🔤"
            "arsc" -> "🗃️"
            "sf", "rsa", "dsa", "mf" -> "🔑"
            "js" -> "📜"
            else -> "📄"
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D0F14)),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(0.5.dp, Color(0xFF1A1E28))
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(icon, fontSize = 18.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.name,
                        fontSize = 13.sp,
                        color = Color(0xFFB0BEC5),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = FontFamily.Monospace
                    )
                    if (entry.extension.isNotEmpty()) {
                        Text(
                            ".${entry.extension.uppercase()}",
                            fontSize = 10.sp,
                            color = category.color.copy(alpha = 0.7f)
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        entry.size.toReadableSize(),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF546E7A)
                    )
                    val ratio = if (entry.size > 0)
                        ((1 - entry.compressedSize.toDouble() / entry.size) * 100).toInt() else 0
                    if (ratio > 0) {
                        Text(
                            "-$ratio% zip",
                            fontSize = 9.sp,
                            color = Color(0xFF2E7D32)
                        )
                    }
                }
            }
        }
    }

    // ─── Logic ───────────────────────────────────────────────────────────────

    private suspend fun analyzeApk(
        uri: Uri,
        onProgress: (Float, String) -> Unit
    ): ApkAnalysis = withContext(Dispatchers.IO) {

        onProgress(0.05f, "Copiando APK...")

        val cacheDir = File(cacheDir, "apk_temp").also {
            it.deleteRecursively(); it.mkdirs()
        }
        val apkFile = File(cacheDir, "input.apk")
        contentResolver.openInputStream(uri)?.use { it.copyTo(apkFile.outputStream()) }

        val apkName = uri.lastPathSegment?.substringAfterLast('/') ?: "app.apk"
        val entries = mutableListOf<ApkEntry>()
        var totalSize = 0L

        onProgress(0.2f, "Lendo entradas do ZIP...")

        java.util.zip.ZipFile(apkFile).use { zip ->
            val all = zip.entries().toList()
            all.forEachIndexed { idx, ze ->
                if (!ze.isDirectory) {
                    val path = ze.name
                    val name = path.substringAfterLast('/')
                    val size = if (ze.size >= 0) ze.size else 0L
                    val compSize = if (ze.compressedSize >= 0) ze.compressedSize else 0L
                    totalSize += size
                    entries.add(
                        ApkEntry(
                            path = path,
                            name = name,
                            size = size,
                            compressedSize = compSize,
                            category = categorize(path),
                            extension = extensionOf(name)
                        )
                    )
                    onProgress(0.2f + 0.7f * (idx.toFloat() / all.size), "Analisando: $name")
                }
            }
        }

        onProgress(1f, "Análise concluída!")
        cacheDir.deleteRecursively()

        ApkAnalysis(entries = entries.sortedWith(compareBy({ it.category.ordinal }, { it.path })),
                    totalSize = totalSize,
                    apkName = apkName)
    }

    private suspend fun extractApk(
        uri: Uri,
        onProgress: (Float, String) -> Unit
    ): String = withContext(Dispatchers.IO) {

        onProgress(0.05f, "Preparando extração...")

        val cacheDir = File(cacheDir, "extract_temp").also {
            it.deleteRecursively(); it.mkdirs()
        }
        val apkFile = File(cacheDir, "input.apk")
        contentResolver.openInputStream(uri)?.use { it.copyTo(apkFile.outputStream()) }

        onProgress(0.2f, "Extraindo arquivos...")

        val extractDir = File(cacheDir, "extracted")
        extractDir.mkdirs()

        ZipInputStream(apkFile.inputStream()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val file = File(extractDir, entry.name)
                if (entry.isDirectory) file.mkdirs()
                else {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { zip.copyTo(it) }
                }
                entry = zip.nextEntry
            }
        }

        onProgress(0.75f, "Compactando resultado...")

        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val outFile = File(downloads, "apk_extracted_${System.currentTimeMillis()}.zip")

        ZipOutputStream(outFile.outputStream()).use { zipOut ->
            extractDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val rel = file.relativeTo(extractDir).path
                    zipOut.putNextEntry(ZipEntry(rel))
                    file.inputStream().use { it.copyTo(zipOut) }
                    zipOut.closeEntry()
                }
            }
        }

        onProgress(1f, "Extração concluída!")
        cacheDir.deleteRecursively()

        withContext(Dispatchers.Main) { outFile.absolutePath }
        outFile.absolutePath
    }

    private fun hasStoragePermission() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Environment.isExternalStorageManager()
        else true

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }
}
