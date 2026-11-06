package dev.alicebridge.app.ui.components

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.alicebridge.app.ui.theme.AbColors

@Composable
fun InstructionStep(
    number: Int,
    title: String,
    complete: Boolean,
    active: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = AbColors.Panel,
        border = BorderStroke(
            1.dp,
            if (active && !complete) AbColors.Purple.copy(alpha = 0.65f) else AbColors.OutlineSoft,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = (if (complete) AbColors.Green else AbColors.Purple).copy(alpha = 0.16f),
                    modifier = Modifier.size(45.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (complete) {
                            Icon(Icons.Default.Check, "Шаг выполнен", tint = AbColors.Green)
                        } else {
                            Text(
                                number.toString().padStart(2, '0'),
                                color = AbColors.Purple,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        when {
                            complete -> "ГОТОВО"
                            active -> "СДЕЛАЙТЕ СЕЙЧАС"
                            else -> "СЛЕДУЮЩИЙ ШАГ"
                        },
                        color = when {
                            complete -> AbColors.Green
                            active -> AbColors.Purple
                            else -> AbColors.Muted
                        },
                        fontSize = 10.sp,
                        letterSpacing = 1.sp,
                    )
                    Text(title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, lineHeight = 25.sp)
                }
            }
            content()
        }
    }
}

@Composable
fun ColumnScope.ShizukuSteps(
    installed: Boolean,
    running: Boolean,
    allowed: Boolean,
    request: () -> Unit,
    openManager: () -> Unit,
    openUrl: (String) -> Unit,
) {
    if (Build.VERSION.SDK_INT < 33) {
        CardBox {
            Text("Нужен Android 13 или новее", color = AbColors.Peach, fontWeight = FontWeight.Bold)
            Text("Для Android 12L используйте способ DFRoot.", color = AbColors.Muted)
        }
    }
    InstructionStep(1, "Установите менеджер", installed, !installed) {
        Text(
            "Выберите вариант для своего телефона. Если менеджер уже установлен, скачивать заново не нужно.",
            color = AbColors.Muted,
            lineHeight = 22.sp,
        )
        OutlinedButton(
            onClick = { openUrl("https://github.com/HmnDev-Tech/shevery/releases") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("HyperOS → попробовать Shevery")
        }
        OutlinedButton(
            onClick = { openUrl("https://github.com/RikkaApps/Shizuku/releases") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("Другие смартфоны → скачать Shizuku")
        }
    }
    InstructionStep(2, "Запустите службу", running, installed && !running) {
        Text(
            "На телефоне: Настройки → Для разработчиков → Беспроводная отладка. На время сопряжения подключитесь к Wi-Fi.",
            color = AbColors.Muted,
            lineHeight = 23.sp,
        )
        Text(
            "В менеджере: выберите запуск через беспроводную отладку, выполните сопряжение с кодом, затем нажмите «Запустить».",
            color = AbColors.Muted,
            lineHeight = 23.sp,
        )
        Action(if (running) "Открыть работающий менеджер" else "Открыть менеджер") { openManager() }
        Text(
            if (running) {
                "Служба отвечает AliceBridge"
            } else {
                "Пока служба не отвечает. После запуска статус изменится сам."
            },
            color = if (running) AbColors.Green else AbColors.Peach,
            fontSize = 13.sp,
        )
    }
    InstructionStep(3, "Выдайте доступ AliceBridge", allowed, running && !allowed) {
        Text(
            "Нажмите кнопку и разрешите доступ в запросе менеджера. Если запроса нет: менеджер → Приложения → AliceBridge.",
            color = AbColors.Muted,
            lineHeight = 23.sp,
        )
        Action(
            if (allowed) "Доступ подтверждён" else "Разрешить доступ AliceBridge",
            enabled = running && !allowed,
            onClick = request,
        )
        if (allowed) Text("Разрешение проверено — можно готовить сеть", color = AbColors.Green, fontSize = 13.sp)
        if (installed && !running) {
            Text(
                "Новый Shevery не определяется? Проверьте Settings → Application → Compatibility Stubs → Shizuku Stub. Дополнительные модули и Device Owner для AliceBridge не нужны.",
                color = AbColors.Muted,
                fontSize = 12.sp,
                lineHeight = 19.sp,
            )
        }
    }
    InstructionStep(4, "Подготовьте сеть", false, allowed) {
        Text(
            "После допуска AliceBridge кнопка продолжения станет доступна. Приложение поднимет точку и проверит DNS по UDP/TCP и связь с сервером.",
            color = AbColors.Muted,
            lineHeight = 23.sp,
        )
        Text(
            "С публичным сервером лучше тестировать на мобильном интернете без другого VPN. Для локального сервера телефон должен видеть Mac по сети.",
            color = AbColors.Muted,
            lineHeight = 23.sp,
        )
        Text(
            "Колонку подключаем только после сообщения «Точка готова».",
            color = AbColors.Purple,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
