package dev.alicebridge.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.alicebridge.app.R
import dev.alicebridge.app.ui.theme.AbColors

@Composable
fun Heading(text: String) {
    Text(text, fontSize = 33.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun Eyebrow(text: String) {
    Text(text, color = AbColors.Purple, fontSize = 12.sp, letterSpacing = 1.4.sp)
}

@Composable
fun SectionLabel(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = AbColors.Purple, fontSize = 11.sp, letterSpacing = 1.3.sp)
        if (detail.isNotBlank()) Text(detail, color = AbColors.Muted, fontSize = 14.sp)
    }
}

@Composable
fun CardBox(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(25.dp),
        color = AbColors.Panel,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

@Composable
fun Action(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick,
        enabled = enabled,
        shape = RoundedCornerShape(21.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 62.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun FilledAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(18.dp, 15.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label)
    }
}

@Composable
fun MethodCard(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) AbColors.Purple else AbColors.OutlineSoft,
        ),
        colors = CardDefaults.outlinedCardColors(containerColor = AbColors.Panel),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RadioButton(selected, onClick)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(description, color = AbColors.Muted, fontSize = 14.sp, lineHeight = 22.sp)
            }
        }
    }
}

@Composable
fun CopyRow(label: String, value: String, onCopy: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = AbColors.Muted, fontSize = 13.sp)
            Text(value, fontSize = 20.sp)
        }
        IconButton(onClick = { onCopy(value) }) {
            Icon(
                painterResource(R.drawable.ic_copy),
                "Скопировать $label",
                tint = AbColors.Purple,
            )
        }
    }
}

@Composable
fun ToolBadge(title: String, large: Boolean = false) {
    Box(
        Modifier
            .size(if (large) 76.dp else 46.dp)
            .background(
                AbColors.Purple.copy(alpha = .12f),
                RoundedCornerShape(if (large) 24.dp else 14.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title.take(2).uppercase(),
            color = AbColors.Purple,
            fontWeight = FontWeight.Bold,
            fontSize = if (large) 26.sp else 15.sp,
        )
    }
}
