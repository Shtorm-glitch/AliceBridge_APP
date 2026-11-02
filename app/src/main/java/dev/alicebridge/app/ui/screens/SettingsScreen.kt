package dev.alicebridge.app.ui.screens

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import dev.alicebridge.app.ui.components.Action
import dev.alicebridge.app.ui.components.Heading
import dev.alicebridge.app.ui.theme.AbColors

@Composable
fun ColumnScope.SettingsScreen(
    initialResolver: String,
    versionName: String,
    onSaveResolver: (String) -> Unit,
    onCheckUpdates: () -> Unit,
) {
    Heading("Сервер и сеть")
    var resolver by remember { mutableStateOf(initialResolver) }
    OutlinedTextField(
        value = resolver,
        onValueChange = { resolver = it.trim() },
        label = { Text("IPv4 DNS-сервера") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "По умолчанию используется публичный сервер AliceBridge. Можно указать свой DNS-сервер с совместимым backend. Изменение применяется к новому сеансу; сохранённый сеанс использует прежний сервер.",
        color = AbColors.Muted,
        lineHeight = 23.sp,
    )
    Action("Сохранить") { onSaveResolver(resolver) }
    Action("Проверить обновления", onClick = onCheckUpdates)
    Text(
        "Версия $versionName\nНастройка и управление Яндекс.Станцией с Android.",
        color = AbColors.Muted,
        fontSize = 13.sp,
    )
}
