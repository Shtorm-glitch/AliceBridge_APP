package dev.alicebridge.app.ui.screens

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.alicebridge.app.PhoneGuide
import dev.alicebridge.app.ui.components.Action
import dev.alicebridge.app.ui.components.CardBox
import dev.alicebridge.app.ui.components.Heading
import dev.alicebridge.app.ui.components.ShizukuSteps
import dev.alicebridge.app.ui.theme.AbColors

@Composable
fun ColumnScope.GuideScreen(
    shizukuInstalled: Boolean,
    shizukuRunning: Boolean,
    shizukuAllowed: Boolean,
    shizukuReady: Boolean,
    busy: Boolean,
    phoneLabel: String,
    installedManagers: List<Pair<String, String>>,
    onRequestShizuku: () -> Unit,
    onOpenShizukuManager: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenPackage: (String) -> Unit,
    onUseShizuku: () -> Unit,
) {
    Heading("Подключим Shizuku")
    Text(
        "Выполните шаги по порядку. Статусы службы и разрешения обновляются автоматически.",
        color = AbColors.Muted,
        lineHeight = 23.sp,
    )
    ShizukuSteps(
        installed = shizukuInstalled || shizukuRunning,
        running = shizukuRunning,
        allowed = shizukuAllowed,
        request = onRequestShizuku,
        openManager = onOpenShizukuManager,
        openUrl = onOpenUrl,
    )
    CardBox {
        Text("Для вашего телефона", fontWeight = FontWeight.Bold, fontSize = 19.sp)
        Text(phoneLabel, color = AbColors.Purple, fontSize = 13.sp)
        PhoneGuide.tips().forEach { tip ->
            Text(tip, color = AbColors.Muted, lineHeight = 22.sp, fontSize = 14.sp)
        }
        TextButton(onClick = { onOpenUrl("https://shizuku.rikka.app/guide/setup/") }) {
            Text("Официальная инструкция по оболочкам")
        }
        installedManagers.forEach { (pkg, name) ->
            TextButton(onClick = { onOpenPackage(pkg) }) { Text("Открыть $name") }
        }
    }
    CardBox {
        Text("Менеджер запущен, а служба не работает?", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(
            "На MediaTek у Shizuku 13.6.0 известен сбой UserService. У thedjchi есть исправление, Next продолжает эту ветку. Форк может помочь с запуском службы, но не гарантирует исправление DNS.",
            color = AbColors.Muted,
            lineHeight = 23.sp,
        )
        TextButton(onClick = { onOpenUrl("https://github.com/thedjchi/Shizuku/releases") }) {
            Text("thedjchi → релизы")
        }
        TextButton(onClick = { onOpenUrl("https://github.com/rushiranpise/Shizuku-Next/releases") }) {
            Text("Shizuku-Next → релизы")
        }
        Text(
            "При смене менеджера могут отличаться пакет и подпись APK. Следуйте его инструкции установки; AliceBridge не удаляет менеджеры автоматически.",
            color = AbColors.Muted,
            fontSize = 13.sp,
        )
    }
    Action(
        text = if (shizukuReady) "Использовать Shizuku" else "Сначала запустите службу и выдайте доступ",
        enabled = shizukuReady && !busy,
        onClick = onUseShizuku,
    )
}
