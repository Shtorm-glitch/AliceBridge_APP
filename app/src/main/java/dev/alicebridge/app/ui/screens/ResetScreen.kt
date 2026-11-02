package dev.alicebridge.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.alicebridge.app.StationModel
import dev.alicebridge.app.ui.components.Action
import dev.alicebridge.app.ui.components.CardBox
import dev.alicebridge.app.ui.components.Eyebrow
import dev.alicebridge.app.ui.components.Heading
import dev.alicebridge.app.ui.theme.AbColors

@Composable
fun ColumnScope.ResetScreen(model: StationModel, onOpenUrl: (String) -> Unit, onContinue: () -> Unit) {
    Eyebrow(model.name.uppercase())
    Heading("Сбросим настройки")
    Text(
        "Начните со сброса колонки. Если вы восстанавливаете прерванный сеанс, вернитесь на главную и нажмите «Продолжить».",
        color = AbColors.Muted,
        lineHeight = 23.sp,
    )
    CardBox {
        model.steps.forEachIndexed { i, text ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("${i + 1}", color = AbColors.Purple, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(text, Modifier.weight(1f), lineHeight = 24.sp)
            }
        }
    }
    if (model.id in setOf("lite2", "mini3")) {
        CardBox {
            Text("Factory → retail CA", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AbColors.Purple)
            Text(
                if (model.id == "mini3") {
                    "Без отката прошивки: проверим старый factory-образ, добавим AAA в CA обычной системы, сохраним маркеры ADB и Wi-Fi. Если recovery обновлён до .50, этот способ не подойдёт."
                } else {
                    "Без отката прошивки: из factory заменим только CA-бандл обычной системы. Затем колонка перезагрузится, а приложение проверит root ADB."
                },
                color = AbColors.Muted,
                lineHeight = 22.sp,
            )
        }
    }
    Text(
        "Пока не подключайте колонку к домашней сети. После подготовки телефона добавим её в «Доме с Алисой» на этом же смартфоне.",
        color = AbColors.Muted,
        lineHeight = 23.sp,
    )
    TextButton(onClick = { onOpenUrl(model.source) }) { Text("Инструкция производителя") }
    if (model.id == "max") {
        Text(
            "Макс: метод root рассчитан на первую модель без Zigbee YNDX-0008 и определённую прошивку. Доступность дополнительных установщиков зависит от модели; неподдерживаемые действия не запускаются.",
            color = AbColors.Peach,
        )
    }
    Action("Колонка сброшена · дальше", onClick = onContinue)
}
