package com.logisticsmanagement.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.logisticsmanagement.app.model.ParcelStatus
import com.logisticsmanagement.app.model.Shipment
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarScreen(shipments: List<Shipment>, modifier: Modifier, onOpen: (String) -> Unit) {
    var dateValue by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var showDatePicker by remember { mutableStateOf(false) }
    val selectedDate = LocalDate.parse(dateValue)
    val weekStart = selectedDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val active = shipments.filterNot { it.archived }
    val expected = active.filter { it.estimatedDeliveryAt?.let(::localDateOf) == selectedDate }
    val updated = active.filter { localDateOf(it.updatedAt) == selectedDate }
    val undated = active.filter { it.estimatedDeliveryAt == null && it.status != ParcelStatus.SIGNED }
    Scaffold(modifier = modifier, topBar = { TopAppBar(title = { Text("日历") }, actions = { TextButton(onClick = { dateValue = LocalDate.now().toString() }, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("返回今天") } }) }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).testTag("calendarList"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = { dateValue = selectedDate.minusWeeks(1).toString() }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "上一周") }
                    TextButton(onClick = { showDatePicker = true }, modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("calendarDateSelector")) { Text("${selectedDate.year}年${selectedDate.monthValue}月") }
                    IconButton(onClick = { dateValue = selectedDate.plusWeeks(1).toString() }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "下一周") }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items((0L..6L).map(weekStart::plusDays)) { day ->
                        val count = active.count { it.estimatedDeliveryAt?.let(::localDateOf) == day }
                        FilterChip(selected = day == selectedDate, onClick = { dateValue = day.toString() }, label = {
                            Column { Text("${listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[day.dayOfWeek.value - 1]} ${day.dayOfMonth}"); if (count > 0) Text("${count}件") }
                        }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp))
                    }
                }
            }
            item { SectionTitle("${selectedDate.monthValue}月${selectedDate.dayOfMonth}日预计送达 · ${expected.size} 件") }
            if (expected.isEmpty()) item { Text("平台尚未提供当天预计送达的包裹。", style = MaterialTheme.typography.bodyLarge) }
            items(expected, key = { "expected_${it.id}" }) { shipment -> CalendarShipmentRow(shipment, "预计送达", onOpen) }
            item { SectionTitle("当天物流更新 · ${updated.size} 件") }
            if (updated.isEmpty()) item { Text("当天没有已记录的物流更新。", style = MaterialTheme.typography.bodyLarge) }
            items(updated, key = { "updated_${it.id}" }) { shipment -> CalendarShipmentRow(shipment, "物流更新 ${formatTimestamp(shipment.updatedAt)}", onOpen) }
            if (undated.isNotEmpty()) {
                item { SectionTitle("预计送达日期未提供 · ${undated.size} 件") }
                items(undated, key = { "undated_${it.id}" }) { shipment -> CalendarShipmentRow(shipment, "平台暂未提供预计送达日期", onOpen) }
            }
        }
    }
    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = selectedDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(onDismissRequest = { showDatePicker = false }, confirmButton = {
            TextButton(onClick = { dateValue = Instant.ofEpochMilli(checkNotNull(state.selectedDateMillis)).atZone(ZoneOffset.UTC).toLocalDate().toString(); showDatePicker = false }, enabled = state.selectedDateMillis != null, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("选择日期") }
        }, dismissButton = { TextButton(onClick = { showDatePicker = false }, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("取消选择") } }) { DatePicker(state) }
    }
}

@Composable
private fun CalendarShipmentRow(shipment: Shipment, label: String, onOpen: (String) -> Unit) {
    Card(onClick = { onOpen(shipment.id) }, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(shipment.title, style = MaterialTheme.typography.titleMedium)
            Text(shipment.status.displayName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun localDateOf(epochMillis: Long) = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
