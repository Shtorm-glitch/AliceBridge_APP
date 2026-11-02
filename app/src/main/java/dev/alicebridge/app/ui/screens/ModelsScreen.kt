package dev.alicebridge.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.alicebridge.app.StationModel
import dev.alicebridge.app.ui.components.Eyebrow
import dev.alicebridge.app.ui.theme.AbColors

@Composable
fun ColumnScope.ModelsScreen(catalog: List<StationModel>, isFindFlow: Boolean, onSelect: (StationModel) -> Unit) {
    Eyebrow("СНАЧАЛА ВЫБЕРИТЕ МОДЕЛЬ")
    Text(
        if (isFindFlow) {
            "Выберите модель настроенной колонки. Проверим доступные адреса ADB в текущей Wi-Fi сети; сбрасывать Алису не нужно."
        } else {
            "Поколения используют разные прошивки и способы настройки. Модель можно посмотреть на наклейке снизу или в «Доме с Алисой»."
        },
        color = AbColors.Muted,
        lineHeight = 23.sp,
    )
    catalog.forEach { model ->
        OutlinedCard(
            onClick = { onSelect(model) },
            colors = CardDefaults.outlinedCardColors(containerColor = AbColors.Panel),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(model.name, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    model.platform.ifBlank { "Профиль требует дополнительного подтверждения" },
                    color = AbColors.Muted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
