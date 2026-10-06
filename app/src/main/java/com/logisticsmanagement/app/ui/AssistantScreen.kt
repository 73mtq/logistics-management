package com.logisticsmanagement.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.logisticsmanagement.app.AppContainer
import com.logisticsmanagement.app.data.AppSettings
import com.logisticsmanagement.app.model.Shipment
import com.logisticsmanagement.app.network.AiAnswer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistantScreen(container: AppContainer, settings: AppSettings, shipments: List<Shipment>, busy: Map<String, Boolean>, runAction: RunAction, modifier: Modifier, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val reports by remember(container) { container.repository.observeReports() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var question by rememberSaveable { mutableStateOf("") }
    var answer by remember { mutableStateOf<AiAnswer?>(null) }
    val aiBusy = busy["askAi"] == true || busy["generateReport"] == true
    val ask: (String) -> Unit = { text ->
        answer = null
        runAction("askAi") { answer = container.aiService.ask(text) }
    }
    Scaffold(modifier = modifier, topBar = { TopAppBar(title = { Text("助手") }) }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!settings.aiEnabled) {
                item {
                    EmptyState("AI 服务尚未启用", "配置 API 后，可以询问包裹状态并生成物流日报。") {
                        ActionButton("配置 API", false, onClick = onSettings)
                    }
                }
            } else {
                item { InfoBanner("问题会发送到已配置的 API 服务，请勿填写登录凭据、完整手机号或详细地址。") }
                item { FormField("询问包裹状态", question, { question = it }, singleLine = false) }
                item { ActionButton("发送问题", busy["askAi"] == true, question.isNotBlank() && !aiBusy) { ask(question.trim()) } }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("今天有哪些包裹待取件？", "有哪些异常物流？").forEach { prompt ->
                            TextButton(onClick = { question = prompt; ask(prompt) }, enabled = !aiBusy, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text(prompt) }
                        }
                    }
                }
                item {
                    ActionButton("生成物流日报", busy["generateReport"] == true, !aiBusy) {
                        answer = null
                        runAction("generateReport") { answer = container.aiService.generateDailyReport() }
                    }
                }
                answer?.let { current ->
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("生成时间 ${formatTimestamp(current.createdAt)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                SelectionContainer { Text(current.text, style = MaterialTheme.typography.bodyLarge) }
                                current.shipmentIds.mapNotNull { id -> shipments.firstOrNull { it.id == id } }.forEach { shipment ->
                                    TextButton(onClick = { onOpen(shipment.id) }, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) { Text("查看包裹：${shipment.title}") }
                                }
                            }
                        }
                    }
                }
            }
            if (reports.isNotEmpty()) {
                item { SectionTitle("问答与日报记录") }
                items(reports.filter { it.createdAt != answer?.createdAt }, key = { it.id }) { report ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${if (report.kind == "daily") "物流日报" else "包裹问答"} · ${formatTimestamp(report.createdAt)}", style = MaterialTheme.typography.titleMedium)
                            SelectionContainer { Text(report.text, style = MaterialTheme.typography.bodyLarge) }
                        }
                    }
                }
            }
        }
    }
}
