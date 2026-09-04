package com.d4viddf.hyperbridge.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.d4viddf.hyperbridge.R
import com.d4viddf.hyperbridge.data.db.AppDatabase
import com.d4viddf.hyperbridge.models.NotificationType
import com.d4viddf.hyperbridge.util.TestNotificationHelper
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestIslandScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val saved by db.savedNotificationDao().getRecentFlow().collectAsState(initial = emptyList())
    var showClearDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Test Pill — Semua Kategori") },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    FilledTonalIconButton(onClick = {
                        TestNotificationHelper.cancelAllTests(context)
                    }) { Icon(Icons.Outlined.Delete, "Cancel all tests") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.BugReport, null, tint = MaterialTheme.colorScheme.primary)
                            Text("Test vs Real — Log terpisah", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Semua tombol TEST posting notif dengan extra hyperbridge_test=true. Logcat: HyperBridgeTest (TEST) vs HyperBridgeDebug (REAL). Notif REAL otomatis disimpan (50 terakhir) untuk replay/diagnosa.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Lihat log: adb logcat -s HyperBridgeTest:V HyperBridgeDebug:V",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            item {
                Text("Kategori Pill", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            // Buttons for each NotificationType
            items(NotificationType.entries.toList()) { type ->
                val desc = when (type) {
                    NotificationType.STANDARD -> "Standard • pesanan diproses"
                    NotificationType.MESSAGE -> "Message • chat driver MOMOYO"
                    NotificationType.PROGRESS -> "Progress • sync 45%"
                    NotificationType.DOWNLOAD -> "Download • 62% (channel download)"
                    NotificationType.MEDIA -> "Media • Poweramp style"
                    NotificationType.NAVIGATION -> "Navigation • 500m belok kanan"
                    NotificationType.CALL -> "Call • incoming driver"
                    NotificationType.TIMER -> "Timer • 5 min chronometer"
                    NotificationType.DELIVERY -> "Delivery • ShopeeFood clone persis + logo (SHOPEE_LIVE_ACTIVITY_ID + liveId + customView)"
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(type.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                TestNotificationHelper.postTest(context, type)
                                // Also log to file via helper if needed
                            }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Outlined.Visibility, null, modifier = Modifier.padding(end = 4.dp))
                                Text("Post TEST")
                            }
                            OutlinedButton(onClick = {
                                TestNotificationHelper.cancelTest(context, type)
                            }) { Text("Cancel") }
                        }
                    }
                }
            }

            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Notifikasi Tersimpan (REAL, 50 terakhir)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { showClearDialog = true }) { Text("Clear") }
                }
                Text(
                    "Otomatis simpan setiap notif REAL yang masuk (bukan TEST). Bisa bedain log REAL vs TEST via isTest flag. Tap Save untuk export log.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }

            if (saved.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Belum ada notif REAL tersimpan", style = MaterialTheme.typography.bodyMedium)
                            Text("Order Shopee / kirim chat / download file untuk mengisi.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                items(saved) { item ->
                    val isTest = item.isTest
                    val time = SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault()).format(Date(item.postTime))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    text = if (isTest) "TEST • ${item.detectedType}" else "REAL • ${item.detectedType}",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = if (isTest) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("${item.packageName} • ${item.channelId ?: "-"}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp))
                            Text("Title: ${item.title}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            if (item.text.isNotEmpty()) Text("Text: ${item.text}", style = MaterialTheme.typography.bodySmall)
                            if (!item.bigText.isNullOrEmpty()) Text("Big: ${item.bigText}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Tpl: ${item.template ?: "-"}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = {
                                    // Replay: re-post as test with same data
                                    TestNotificationHelper.postTest(context, try { NotificationType.valueOf(item.detectedType) } catch (_: Exception) { NotificationType.STANDARD })
                                }) { Text("Replay as TEST") }
                                if (!isTest) {
                                    OutlinedButton(onClick = {
                                        // Save to log file
                                        scope.launch {
                                            android.util.Log.w("HyperBridgeTest", "SAVED REAL replay pkg=${item.packageName} type=${item.detectedType} title=${item.title}")
                                        }
                                    }) { Icon(Icons.Outlined.Save, null); Text("Log") }
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Hapus semua simpanan?") },
            text = { Text("50 notifikasi REAL tersimpan akan dihapus.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { db.savedNotificationDao().clearAll() }
                    showClearDialog = false
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("Batal") } }
        )
    }
}
