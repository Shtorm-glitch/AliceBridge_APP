package dev.alicebridge.app.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.alicebridge.app.AppSnapshot
import dev.alicebridge.app.ToolStep
import dev.alicebridge.app.ui.components.Action
import dev.alicebridge.app.ui.components.CardBox
import dev.alicebridge.app.ui.components.CopyRow
import dev.alicebridge.app.ui.components.Eyebrow
import dev.alicebridge.app.ui.components.Heading
import dev.alicebridge.app.ui.theme.AbColors
import dev.alicebridge.domain.ConnectionMethod
import dev.alicebridge.domain.PhoneRootStage
import dev.alicebridge.domain.ProbeResult
import dev.alicebridge.domain.SetupPhase
import dev.alicebridge.domain.SetupSession
import dev.alicebridge.domain.ShizukuRecovery
import dev.alicebridge.domain.ToolAccessPolicy

@Composable
fun ColumnScope.HomeScreen(
    state: AppSnapshot,
    activeModelName: String?,
    retailStep: ToolStep?,
    phaseTitle: (SetupSession) -> String,
    phaseDetail: (SetupSession) -> String,
    savedKsuPkg: String,
    passwordEditor: Boolean,
    passwordDraft: String,
    passwordApply: Boolean,
    passwordNote: String,
    onCopy: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onStartSetup: () -> Unit,
    onFindExisting: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenJournal: () -> Unit,
    onOpenManual: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenToolsWifi: () -> Unit,
    onOpenTerminal: () -> Unit,
    onServiceStart: () -> Unit,
    onServiceStop: () -> Unit,
    onRestoreNetwork: () -> Unit,
    onSelectCandidate: (String) -> Unit,
    onRestartHome: () -> Unit,
    onOpenKsuSilent: (String) -> Unit,
    onOpenKsuWithFallback: (String) -> Unit,
    onPasswordEditorChange: (Boolean) -> Unit,
    onPasswordDraftChange: (String) -> Unit,
    onPasswordApplyChange: (Boolean) -> Unit,
    onPasswordNoteChange: (String) -> Unit,
    onSavePassword: (String) -> Unit,
) {
    Spacer(Modifier.height(6.dp))
    if (state.error.isNotBlank()) {
        CardBox {
            Text(
                if (state.session?.method == ConnectionMethod.SHIZUKU) {
                    "Не удалось продолжить с Shizuku"
                } else {
                    "Нужна ваша помощь"
                },
                color = AbColors.Peach,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(state.error, lineHeight = 23.sp)
            if (state.session?.method == ConnectionMethod.DFROOT) {
                if (savedKsuPkg.isNotBlank()) {
                    TextButton(onClick = { onOpenKsuSilent(savedKsuPkg) }) { Text("Открыть KernelSU") }
                }
                TextButton(onClick = onOpenGuide) { Text("DFRoot не подошёл? Попробовать Shizuku") }
            } else if (state.session?.method == ConnectionMethod.SHIZUKU) {
                val chip = (Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL).lowercase()
                val advice = ShizukuRecovery.advice(state.error, chip.contains("mediatek") || chip.contains("mtk"))
                Text(advice.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AbColors.Purple)
                Text(advice.detail, color = AbColors.Muted, lineHeight = 22.sp)
                Text("Другие варианты Shizuku", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Сначала откройте установленный менеджер, перезапустите его службу и проверьте, что AliceBridge разрешён доступ. Затем повторите подготовку здесь.",
                    color = AbColors.Muted,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
                Text(
                    "Если ошибка повторяется, можно попробовать другой вариант Shizuku. У разных сборок отличаются исправления и поведение на оболочках Android: один менеджер может не запускать нужную сетевую службу, а другой — работать на том же телефоне. Ссылки ниже открывают страницы скачивания.",
                    color = AbColors.Muted,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
                Text(
                    "Как сменить менеджер:\n1. Остановите службу в прежнем менеджере.\n2. Установите выбранный вариант и запустите его службу по инструкции менеджера.\n3. Заново разрешите AliceBridge доступ.\n4. Вернитесь сюда и нажмите «Повторить подготовку Shizuku». Одновременно должна работать одна служба.",
                    color = AbColors.Muted,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
                if (!advice.offerForks) {
                    Text(
                        "Сначала проверьте причину выше: смена менеджера не гарантирует исправление DNS или сервера.",
                        color = AbColors.Peach,
                        fontSize = 13.sp,
                    )
                }
                TextButton(onClick = { onOpenUrl("https://github.com/rushiranpise/Shizuku-Next/releases") }) {
                    Text("Shizuku-Next → попробовать другую сборку")
                }
                TextButton(onClick = { onOpenUrl("https://github.com/thedjchi/Shizuku/releases") }) {
                    Text("thedjchi / Shizuku → ещё один вариант")
                }
                TextButton(onClick = { onOpenUrl("https://github.com/HmnDev-Tech/shevery/releases") }) {
                    Text("HyperOS → попробовать Shevery")
                }
                Action("Повторить подготовку Shizuku", enabled = !state.busy, onClick = onRestoreNetwork)
                TextButton(onClick = onOpenJournal) { Text("Открыть журнал ошибки") }
                TextButton(onClick = onOpenGuide) { Text("Инструкция и другие менеджеры") }
            }
        }
    }
    if (state.session == null) {
        Eyebrow("НАСТРОЙКА С ANDROID")
        Heading("Ваша Алиса.\nВаши возможности.")
        Text(
            "Выберите точную модель. Покажем сброс настроек, подготовим телефон и будем следить за запросами колонки.",
            color = AbColors.Muted,
            lineHeight = 24.sp,
        )
        Action("Начать настройку", onClick = onStartSetup)
    } else if (!state.running) {
        Eyebrow("СОХРАНЁННЫЙ СЕАНС")
        Heading("Продолжим настройку")
        CardBox {
            Text(activeModelName ?: state.session.modelId, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Сначала проверим последний адрес ADB. Если колонка уже доступна, сразу продолжим с её состояния. Иначе восстановим прежнюю точку — без повторного сброса.",
                color = AbColors.Muted,
                lineHeight = 23.sp,
            )
            if (state.session.currentVersion.isNotBlank()) {
                Text("Последняя версия: ${state.session.currentVersion}", color = AbColors.Muted, fontSize = 13.sp)
            }
        }
        Action("Продолжить настройку", onClick = onServiceStart)
        TextButton(onClick = onStartSetup) { Text("Начать заново") }
    }
    if (!state.running) {
        OutlinedButton(
            onClick = onFindExisting,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Подключить настроенную колонку") }
        OutlinedButton(
            onClick = onOpenManual,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Ручная настройка прошивки") }
        Text(
            "Колонка уже настроена и ADB включён? Подключите телефон к той же Wi-Fi сети — найдём её без сброса и точки доступа.",
            color = AbColors.Muted,
            fontSize = 13.sp,
        )
    }
    if (state.session != null) {
        val stationAddress = state.session.stationIp.ifBlank { state.session.lastAdbAddress }
        if (stationAddress.isNotBlank()) {
            CardBox {
                CopyRow(
                    if (state.session.adbVerified) "IP колонки" else "Последний IP колонки",
                    stationAddress,
                    onCopy,
                )
                if (!state.session.adbVerified) {
                    Text(
                        "Проверяем связь. После перезагрузки адрес может измениться.",
                        color = AbColors.Muted,
                        fontSize = 13.sp,
                    )
                }
            }
        } else if (state.candidates.isNotEmpty()) {
            CardBox {
                state.candidates.forEach { ip ->
                    CopyRow("Найдено устройство · проверяем Алису", ip, onCopy)
                }
            }
        }
    }
    if (state.running) {
        val session = state.session!!
        Eyebrow(activeModelName?.uppercase() ?: "НАСТРОЙКА")
        val trafficWaiting = session.phase == SetupPhase.WAITING_STATION &&
            state.backendStatus in setOf("network_activity", "client_traffic")
        Heading(
            retailStep?.title
                ?: when {
                    session.adbVerified -> "Алиса на связи"
                    trafficWaiting -> "Запросы поступают"
                    else -> state.headline
                },
        )
        CardBox {
            Text("ЧТО ПРОИСХОДИТ СЕЙЧАС", color = AbColors.Purple, fontSize = 12.sp, letterSpacing = 1.sp)
            if (!state.directMode &&
                (state.busy || state.phoneRoot.stage == PhoneRootStage.WAITING_PERMISSION)
            ) {
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = AbColors.Purple)
                }
                val gate = state.startupGate
                if (session.method == ConnectionMethod.DFROOT) {
                    val rootProbes = if (state.phoneRoot.existing) {
                        listOf(
                            "Готовый root телефона" to state.phoneRoot.active,
                            "Права AliceBridge · UID 0" to state.phoneRoot.permission,
                        )
                    } else {
                        listOf(
                            "KernelSU установлен и выбран" to state.phoneRoot.manager,
                            "Root телефона активен" to state.phoneRoot.active,
                            "Права AliceBridge · UID 0" to state.phoneRoot.permission,
                        )
                    }
                    rootProbes.forEach { (name, result) ->
                        ProbeRow(name, result)
                    }
                }
                listOf(
                    "Точка доступа" to gate.hotspot,
                    "DNS · UDP" to gate.dnsUdp,
                    "DNS · TCP" to gate.dnsTcp,
                    "Доступность сервера" to gate.serverHttp,
                    "Статус через DNS" to gate.feedback,
                ).forEach { (name, result) -> ProbeRow(name, result) }
            }
            val waitingPermission = state.phoneRoot.stage == PhoneRootStage.WAITING_PERMISSION
            Text(
                if (state.busy || waitingPermission) {
                    state.headline
                } else {
                    retailStep?.title ?: if (trafficWaiting) "Сеанс активен" else phaseTitle(session)
                },
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (state.busy || waitingPermission) {
                    state.detail
                } else {
                    retailStep?.detail
                        ?: if (trafficWaiting) {
                            "Запросы из сети телефона доходят до сервера. Продолжаем искать колонку по ADB; повторная проверка обновлений для этого не нужна."
                        } else {
                            phaseDetail(session)
                        }
                },
                color = AbColors.Muted,
                lineHeight = 23.sp,
            )
            if (session.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { (session.transferredBytes.toFloat() / session.totalBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = AbColors.Purple,
                )
                Text(
                    "Передача файла: ${session.transferredBytes * 100 / session.totalBytes}% · установка подтверждается отдельно",
                    color = AbColors.Muted,
                    fontSize = 13.sp,
                )
            }
            if (session.currentVersion.isNotBlank()) {
                Text("Полученная версия: ${session.currentVersion}", color = AbColors.Muted, fontSize = 13.sp)
            }
            if (session.targetVersion.isNotBlank()) {
                Text("Целевая версия: ${session.targetVersion}", color = AbColors.Muted, fontSize = 13.sp)
            }
            if (state.backend.isNotBlank()) {
                Text(
                    state.backend,
                    color = if (state.backendStatus in listOf("ambiguous", "stale", "unreachable", "tls_error")) {
                        AbColors.Peach
                    } else {
                        AbColors.Muted
                    },
                    fontSize = 13.sp,
                )
            }
        }
        if (state.phoneRoot.stage == PhoneRootStage.WAITING_PERMISSION) {
            CardBox {
                Text("Разрешите AliceBridge в KernelSU", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Суперпользователи → AliceBridge → разрешить. После возврата проверим права автоматически. Точка до подтверждения UID 0 не запускается.",
                    color = AbColors.Muted,
                    lineHeight = 23.sp,
                )
                Action("Открыть выбранный KernelSU") { onOpenKsuWithFallback(savedKsuPkg) }
                TextButton(onClick = onServiceStart) { Text("Права выданы · проверить ещё раз") }
            }
        }
        if (state.candidates.size > 1) {
            CardBox {
                Text("Нашли несколько устройств ADB", fontWeight = FontWeight.Bold)
                Text(
                    "Выберите нужную колонку. Автоматически переключаться между ними не будем.",
                    color = AbColors.Muted,
                )
                state.candidates.forEach { ip ->
                    TextButton(onClick = { onSelectCandidate(ip) }) { Text(ip) }
                }
            }
        }
        if (session.adbVerified) {
            CardBox {
                Text("ПАНЕЛЬ КОЛОНКИ", color = AbColors.Purple, fontSize = 12.sp)
                Text(
                    if (session.adbRoot) "Root подтверждён · uid=0" else "ADB подтверждён · root пока не подтверждён",
                    color = AbColors.Green,
                )
                state.stationInfo.filterKeys { it in listOf("VERSION", "KERNEL", "DISK") }.forEach { (key, value) ->
                    if (value.isNotBlank()) {
                        Text("$key\n$value", color = AbColors.Muted, fontSize = 13.sp)
                    }
                }
                state.tools.steps.forEach { step ->
                    Text(
                        "${step.title} · ${step.status}",
                        color = when (step.status) {
                            "Ошибка" -> AbColors.Peach
                            "Готово" -> AbColors.Green
                            else -> AbColors.Muted
                        },
                        fontSize = 13.sp,
                    )
                }
                if (state.tools.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(state.tools.operation, color = AbColors.Purple)
                }
                if (state.tools.error.isNotBlank()) Text(state.tools.error, color = AbColors.Peach)
                val access = ToolAccessPolicy.of(
                    true,
                    session.adbRoot,
                    state.tools.profileReady,
                    session.modelId,
                    session.modelId in listOf("lite2", "mini3") &&
                        state.stationInfo["VERSION"].orEmpty().contains("factory", true),
                )
                Action("Домашний Wi-Fi", enabled = access.wifi && !state.tools.busy, onClick = onOpenToolsWifi)
                if (access.wifiDisabledReason.isNotBlank()) {
                    Text(access.wifiDisabledReason, color = AbColors.Peach, fontSize = 13.sp, lineHeight = 20.sp)
                }
                if (access.mutationDisabledReason.isNotBlank()) {
                    Text(access.mutationDisabledReason, color = AbColors.Peach, fontSize = 13.sp, lineHeight = 20.sp)
                }
                if (state.tools.busy) {
                    Text("Дождитесь завершения текущей операции на колонке.", color = AbColors.Muted, fontSize = 13.sp)
                }
                Action("Терминал", enabled = access.terminal && !state.tools.busy, onClick = onOpenTerminal)
                Action("Управление колонкой", onClick = onOpenTools)
            }
        }
        if (state.directMode && !state.busy && state.candidates.size <= 1 && !session.adbVerified) {
            CardBox {
                Text("Последний адрес пока не отвечает", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Продолжаем проверять ADB. Если колонка ждёт прежнюю сеть AliceBridge, восстановите точку.",
                    color = AbColors.Muted,
                    lineHeight = 23.sp,
                )
                Action("Восстановить точку доступа", onClick = onRestoreNetwork)
            }
        }
        if (state.hotspotReady && !session.adbVerified) {
            CardBox {
                Text("СЕТЬ ДЛЯ АЛИСЫ", color = AbColors.Muted, fontSize = 12.sp)
                CopyRow("Название", session.ssid, onCopy)
                CopyRow("Пароль", session.password, onCopy)
                Text(
                    "2,4 ГГц · ${if (session.legacyWpa) "WPA/WPA2" else "WPA2"}",
                    color = AbColors.Muted,
                    fontSize = 12.sp,
                )
                TextButton(onClick = {
                    onPasswordDraftChange(session.password)
                    onPasswordEditorChange(true)
                }) { Text("Изменить пароль точки") }
                if (passwordNote.isNotBlank()) {
                    Text(passwordNote, color = AbColors.Peach, fontSize = 13.sp, lineHeight = 20.sp)
                }
            }
            if (passwordEditor) {
                val valid = passwordDraft.length in 8..63 && passwordDraft.all { it.code in 32..126 }
                AlertDialog(
                    onDismissRequest = { onPasswordEditorChange(false) },
                    title = { Text("Пароль точки доступа") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = passwordDraft,
                                onValueChange = onPasswordDraftChange,
                                singleLine = true,
                                label = { Text("Новый пароль") },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "8–63 печатных символа. Пароль не попадает в журнал.",
                                color = AbColors.Muted,
                                fontSize = 13.sp,
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            enabled = valid,
                            onClick = {
                                onSavePassword(passwordDraft)
                                onPasswordEditorChange(false)
                                onPasswordNoteChange(
                                    if (state.hotspotReady) {
                                        "Пароль сохранён. Сейчас точка ещё работает со старым паролем."
                                    } else {
                                        "Пароль сохранён и применится при запуске точки."
                                    },
                                )
                                if (state.hotspotReady) onPasswordApplyChange(true)
                            },
                        ) { Text("Сохранить") }
                    },
                    dismissButton = {
                        TextButton(onClick = { onPasswordEditorChange(false) }) { Text("Отмена") }
                    },
                )
            }
            if (passwordApply) {
                AlertDialog(
                    onDismissRequest = { onPasswordApplyChange(false) },
                    title = { Text("Применить пароль сейчас?") },
                    text = {
                        Text(
                            "Точка будет перезапущена с новым паролем. Колонка отключится; возможно, её потребуется снова выбрать в «Доме с Алисой». Если применить позже, старый пароль продолжит работать до перезапуска точки.",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            onPasswordApplyChange(false)
                            onPasswordNoteChange("")
                            onRestoreNetwork()
                        }) { Text("Перезапустить точку") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            onPasswordApplyChange(false)
                            onPasswordNoteChange("Новый пароль применится при следующем запуске точки.")
                        }) { Text("Позже") }
                    },
                )
            }
            Action(
                text = if (state.homeLaunching) "Перезапускаем «Дом с Алисой»…" else "Открыть «Дом с Алисой»",
                enabled = !state.homeLaunching,
                onClick = onRestartHome,
            )
            Text(
                "Перед открытием принудительно перезапустим «Дом с Алисой», чтобы избежать зависшего подключения Bluetooth.",
                color = AbColors.Muted,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
        }
        TextButton(onClick = onServiceStop) {
            Text("Остановить сеанс и точку", color = AbColors.Muted)
        }
    }
}

@Composable
private fun ProbeRow(name: String, result: ProbeResult) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, color = AbColors.Muted, fontSize = 13.sp)
        Text(
            if (result == ProbeResult.PASSED) "Готово" else "Ожидаем",
            color = if (result == ProbeResult.PASSED) AbColors.Green else AbColors.Muted,
            fontSize = 13.sp,
        )
    }
}
