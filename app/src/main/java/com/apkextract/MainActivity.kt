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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MainActivity : ComponentActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            MaterialTheme {
                var selectedFile by remember { mutableStateOf<Uri?>(null) }
                var fileName by remember { mutableStateOf("") }
                var progress by remember { mutableStateOf(0f) }
                var status by remember { mutableStateOf("") }
                var isProcessing by remember { mutableStateOf(false) }
                var outputPath by remember { mutableStateOf("") }
                
                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent()
                ) { uri ->
                    uri?.let {
                        selectedFile = it
                        fileName = it.lastPathSegment ?: "app.apk"
                    }
                }
                
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        
                        Text(
                            "APK Extractor",
                            style = MaterialTheme.typography.headlineLarge
                        )
                        
                        Spacer(Modifier.height(32.dp))
                        
                        if (fileName.isNotEmpty()) {
                            Text(fileName)
                            Spacer(Modifier.height(16.dp))
                        }
                        
                        Button(
                            onClick = { picker.launch("application/vnd.android.package-archive") },
                            enabled = !isProcessing,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Select APK")
                        }
                        
                        Spacer(Modifier.height(16.dp))
                        
                        if (selectedFile != null) {
                            Button(
                                onClick = {
                                    lifecycleScope.launch {
                                        isProcessing = true
                                        try {
                                            extractApk(
                                                selectedFile!!,
                                                onProgress = { p, s ->
                                                    progress = p
                                                    status = s
                                                },
                                                onComplete = { path ->
                                                    outputPath = path
                                                    isProcessing = false
                                                }
                                            )
                                        } catch (e: Exception) {
                                            status = "Error: ${e.message}"
                                            isProcessing = false
                                        }
                                    }
                                },
                                enabled = !isProcessing,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Extract")
                            }
                        }
                        
                        Spacer(Modifier.height(24.dp))
                        
                        if (isProcessing) {
                            LinearProgressIndicator(
                                progress = progress,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(status)
                            Text("${(progress * 100).toInt()}%")
                        }
                        
                        if (outputPath.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "Saved: $outputPath",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        
                        if (!hasStoragePermission()) {
                            Spacer(Modifier.height(24.dp))
                            Button(
                                onClick = { requestStoragePermission() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("Grant Storage Permission")
                            }
                        }
                    }
                }
            }
        }
    }
    
    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }
    
    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }
    
    private suspend fun extractApk(
        uri: Uri,
        onProgress: (Float, String) -> Unit,
        onComplete: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        
        onProgress(0.1f, "Reading APK...")
        
        val cacheDir = File(cacheDir, "temp")
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
        
        val apkFile = File(cacheDir, "temp.apk")
        contentResolver.openInputStream(uri)?.use { input ->
            apkFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        
        onProgress(0.3f, "Extracting...")
        
        val extractDir = File(cacheDir, "extracted")
        extractDir.mkdirs()
        
        ZipInputStream(apkFile.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val file = File(extractDir, entry.name)
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { output ->
                        zip.copyTo(output)
                    }
                }
                entry = zip.nextEntry
            }
        }
        
        onProgress(0.7f, "Creating ZIP...")
        
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val outputFile = File(downloadsDir, "extracted_${System.currentTimeMillis()}.zip")
        
        ZipOutputStream(outputFile.outputStream()).use { zipOut ->
            extractDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val path = file.relativeTo(extractDir).path
                    zipOut.putNextEntry(ZipEntry(path))
                    file.inputStream().use { it.copyTo(zipOut) }
                    zipOut.closeEntry()
                }
            }
        }
        
        onProgress(1f, "Done!")
        
        cacheDir.deleteRecursively()
        
        withContext(Dispatchers.Main) {
            onComplete(outputFile.absolutePath)
        }
    }
}
