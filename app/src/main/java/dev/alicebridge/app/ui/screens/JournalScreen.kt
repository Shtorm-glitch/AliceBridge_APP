package dev.alicebridge.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.alicebridge.app.JournalLine
import dev.alicebridge.app.ui.components.Action
import dev.alicebridge.app.ui.components.Heading
import dev.alicebridge.app.ui.theme.AbColors

@Composable
fun ColumnScope.JournalScreen(
    lines: List<JournalLine>,
    verbose: Boolean,
    onVerboseChange: (Boolean) -> Unit,
    onShare: () -> Unit,
) {
    Heading("Диагностика без шума")
    Text(
        "На экране — изменения состояния, DNS-ответы и ошибки. Повторяющиеся сообщения ADB скрыты. Экспорт включает технический журнал с фильтрацией секретов.",
        color = AbColors.Muted,
        lineHeight = 23.sp,
    )
    Action("Поделиться диагностикой", onClick = onShare)
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Verbose · подробности запросов")
            Text(
                "DNS-пакеты и доступные ответы бэкенда. Всегда включены в экспорт.",
                color = AbColors.Muted,
                fontSize = 12.sp,
            )
        }
        Switch(checked = verbose, onCheckedChange = onVerboseChange)
    }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        lines.filter { verbose || it.kind != "VERBOSE" }
            .takeLast(150)
            .asReversed()
            .forEach { line ->
                Surface(
                    color = AbColors.Panel,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                "${line.time} · ${line.kind}",
                                color = AbColors.Purple,
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                            )
                        }
                        Text(line.text, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
            }
    }
    if (lines.isEmpty()) Text("Событий пока нет", color = AbColors.Muted)
}
