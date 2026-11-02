package dev.alicebridge.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.alicebridge.app.KernelSuManager
import dev.alicebridge.app.StationModel
import dev.alicebridge.app.ui.components.Action
import dev.alicebridge.app.ui.components.CardBox
import dev.alicebridge.app.ui.components.Eyebrow
import dev.alicebridge.app.ui.components.Heading
import dev.alicebridge.app.ui.components.MethodCard
import dev.alicebridge.app.ui.components.ShizukuSteps
import dev.alicebridge.app.ui.theme.AbColors
import dev.alicebridge.domain.ConnectionMethod

@Composable
fun ColumnScope.PrepareScreen(
    model: StationModel,
    method: ConnectionMethod?,
    legacy: Boolean,
    running: Boolean,
    phoneRootExisting: Boolean,
    phoneRootReady: Boolean,
    managers: List<KernelSuManager>,
    managersLoading: Boolean,
    chosenKsu: String,
    shizukuInstalled: Boolean,
    shizukuRunning: Boolean,
    shizukuAllowed: Boolean,
    shizukuReady: Boolean,
    onMethodChange: (ConnectionMethod) -> Unit,
    onLegacyChange: (Boolean) -> Unit,
    onChooseKsu: (String) -> Unit,
    onRefreshManagers: () -> Unit,
    onRequestShizuku: () -> Unit,
    onOpenShizukuManager: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenGuide: () -> Unit,
    onStart: () -> Unit,
) {
    Eyebrow("ТЕЛЕФОН И ТОЧКА ДОСТУПА")
    Heading("Выберите способ")
    MethodCard(
        if (phoneRootExisting) "Готовый root" else "DFRoot",
        if (phoneRootExisting) {
            "Права суперпользователя уже подтверждены. DFRoot не запускается."
        } else {
            "Встроенное получение временного root телефона."
        },
        method == ConnectionMethod.DFROOT,
    ) { onMethodChange(ConnectionMethod.DFROOT) }
    if (method == ConnectionMethod.DFROOT) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = legacy, onCheckedChange = onLegacyChange)
            Text("Поддержка WPA-1\nДля Станции первого поколения", fontSize = 14.sp)
        }
        if (phoneRootExisting && phoneRootReady) {
            CardBox {
                Text("Root уже готов", color = AbColors.Green, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    "AliceBridge получил UID 0 через ваш менеджер root. Устанавливать KernelSU и запускать DFRoot не нужно — можно сразу подготовить точку доступа.",
                    color = AbColors.Muted,
                    lineHeight = 22.sp,
                )
            }
        } else {
            CardBox {
                Text("1. Установите и выберите KernelSU", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = { onOpenUrl("https://github.com/diabl0w/KernelSU/releases") }) {
                    Text("Samsung → скачать")
                }
                TextButton(onClick = { onOpenUrl("https://github.com/tiann/KernelSU/releases") }) {
                    Text("Другие смартфоны → скачать")
                }
                if (managersLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = AbColors.Purple)
                }
                if (!managersLoading && managers.isEmpty()) {
                    Text(
                        "KernelSU не установлен. Скачайте APK по ссылке, установите и вернитесь сюда. Без установленного менеджера запуск закрыт.",
                        color = AbColors.Peach,
                    )
                }
                managers.forEach { manager ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onChooseKsu(manager.packageName) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = chosenKsu == manager.packageName,
                            onClick = { onChooseKsu(manager.packageName) },
                        )
                        Text(manager.label)
                    }
                }
                TextButton(onClick = onRefreshManagers) { Text("Проверить установленный KernelSU") }
                Text(
                    "DFRoot уже встроен. После выбора менеджера приложение само запустит его и проверит полученные права.",
                    color = AbColors.Muted,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                )
                Text(
                    "После DFRoot: KernelSU → Суперпользователи → разрешить AliceBridge. Для завершения работы временного root перезагрузите телефон после настройки колонки.",
                    color = AbColors.Muted,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                )
            }
        }
    }
    MethodCard(
        "Shizuku",
        "Альтернативный способ без root телефона. Требуется Android 13+ для нашего сетевого режима.",
        method == ConnectionMethod.SHIZUKU,
    ) { onMethodChange(ConnectionMethod.SHIZUKU) }
    if (method == ConnectionMethod.SHIZUKU) {
        ShizukuSteps(
            installed = shizukuInstalled || shizukuRunning,
            running = shizukuRunning,
            allowed = shizukuAllowed,
            request = onRequestShizuku,
            openManager = onOpenShizukuManager,
            openUrl = onOpenUrl,
        )
        TextButton(onClick = onOpenGuide) { Text("Особенности телефона и помощь с запуском") }
    }
    if (running) Text("Сначала остановите текущий сеанс на главной.", color = AbColors.Peach)
    val managerReady = !managersLoading && managers.any { it.packageName == chosenKsu }
    val canStart = !running &&
        model.platform.isNotBlank() &&
        method != null &&
        (
            method != ConnectionMethod.DFROOT ||
                managerReady ||
                (phoneRootReady && phoneRootExisting)
            ) &&
        (method != ConnectionMethod.SHIZUKU || shizukuReady)
    Action(
        text = when {
            method == null -> "Сначала выберите способ"
            method == ConnectionMethod.DFROOT &&
                phoneRootExisting &&
                phoneRootReady -> "Подготовить точку доступа"
            method == ConnectionMethod.DFROOT &&
                !managerReady -> "Сначала установите и выберите KernelSU"
            method == ConnectionMethod.DFROOT -> "2. Получить root и продолжить"
            !shizukuRunning -> "Сначала запустите службу Shizuku"
            !shizukuAllowed -> "Сначала разрешите доступ AliceBridge"
            else -> "Подготовить сеть через Shizuku"
        },
        enabled = canStart,
        onClick = onStart,
    )
    if (method != null) {
        Text(
            "Сначала подтвердим права выбранного способа, затем поднимем точку и проверим DNS. До завершения проверок подключать колонку не нужно.",
            color = AbColors.Muted,
            fontSize = 13.sp,
        )
    }
}
