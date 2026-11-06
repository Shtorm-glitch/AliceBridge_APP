package dev.alicebridge.app.ui.screens

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.alicebridge.app.StationModel
import dev.alicebridge.app.ui.components.Action
import dev.alicebridge.app.ui.components.CardBox
import dev.alicebridge.app.ui.components.Eyebrow
import dev.alicebridge.app.ui.components.Heading
import dev.alicebridge.app.ui.theme.AbColors

@Composable
fun ColumnScope.FindScreen(
    model: StationModel,
    manualAddress: String,
    onManualAddressChange: (String) -> Unit,
    onFind: () -> Unit,
    onFindManual: () -> Unit,
) {
    Eyebrow(model.name.uppercase())
    Heading("Найдём вашу Алису")
    Text(
        "Телефон и колонка должны быть подключены к одной Wi-Fi сети. На колонке уже должен быть включён ADB. Сброс и точка доступа не нужны.",
        color = AbColors.Muted,
        lineHeight = 23.sp,
    )
    Action("Найти в сети", onClick = onFind)
    CardBox {
        Text("Подключиться вручную", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Если знаете IP колонки, укажите его здесь. Проверим ADB на порту 5555 и соответствие выбранной модели.",
            color = AbColors.Muted,
            fontSize = 13.sp,
        )
        OutlinedTextField(
            value = manualAddress,
            onValueChange = onManualAddressChange,
            singleLine = true,
            label = { Text("IP-адрес Алисы") },
            placeholder = { Text("192.168.1.201") },
            modifier = Modifier.fillMaxWidth(),
        )
        Action("Подключиться по IP", enabled = manualAddress.isNotBlank(), onClick = onFindManual)
    }
}
