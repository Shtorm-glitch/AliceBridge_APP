package dev.alicebridge.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.alicebridge.app.ui.theme.AbColors
import dev.alicebridge.app.ui.theme.AliceBridgeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

private data class Template(val id: String, val label: String, val platform: String)

class ManualActivity : ComponentActivity() {
    private val repository get() = (application as BridgeApplication).repository
    private val prefs get() = getSharedPreferences("v2-config", MODE_PRIVATE)
    private fun token(): String {
        val saved = prefs.getString("feedback_token", "").orEmpty()
        if (saved.matches(Regex("[a-f0-9]{32}"))) return saved
        val value = (1..32).map { "0123456789abcdef"[SecureRandom().nextInt(16)] }.joinToString("")
        prefs.edit().putString("feedback_token", value).apply()
        return value
    }
    private fun resolver(): String = repository.state.value.session?.resolver?.takeIf { it.isNotBlank() }
        ?: prefs.getString("resolver", "78.24.223.83").orEmpty().ifBlank { "78.24.223.83" }
    private fun templates(): List<Template> {
        val json = JSONObject(assets.open("reset-catalog.json").bufferedReader().use { it.readText() })
        val models = json.getJSONArray("models")
        val list =
            mutableListOf(
                Template(
                    "base",
                    "Базовый шаблон — без выбора станции",
                    repository.state.value.session?.platform.orEmpty(),
                ),
            )
        for (i in 0 until models.length()) {
            val model = models.getJSONObject(i)
            list +=
                Template(
                    model.getString("id"),
                    model.getString("name"),
                    if (model.isNull("platform")) "" else model.getString("platform"),
                )
        }
        return list
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AliceBridgeTheme { Screen() }
        }
    }

    @Composable private fun Screen() {
        val scope = rememberCoroutineScope()
        val templates = remember { templates() }
        var template by remember { mutableStateOf(templates.first()) }
        var menu by remember { mutableStateOf(false) }
        var file by remember { mutableStateOf<File?>(null) }
        var inspection by remember { mutableStateOf<FirmwareInspection?>(null) }
        var version by remember { mutableStateOf("") }
        var unsignedAccepted by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var progress by remember { mutableStateOf(0f) }
        var message by remember { mutableStateOf("") }
        var manual by remember { mutableStateOf<JSONObject?>(null) }
        var library by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
        val client = remember(resolver()) { ManualFirmware(resolver(), token()) }

        fun apply(json: JSONObject) {
            manual = json.optJSONObject("manual")
            val items = json.optJSONArray("library") ?: JSONArray()
            library = (0 until items.length()).map { items.getJSONObject(it) }
        }
        suspend fun refresh() {
            try {
                val json = withContext(Dispatchers.IO) { client.refresh() }
                apply(json)
            } catch (
                error: Exception,
            ) {
                message = "Сервер ручного режима: ${error.message}"
            }
        }
        LaunchedEffect(Unit) { refresh() }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                busy = true
                message = "Читаем и проверяем файл…"
                inspection = null
                unsignedAccepted = false
                try {
                    val target = File(cacheDir, "manual-firmware.zip")
                    withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(uri)?.use { input ->
                            target.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
                        } ?: error("Не удалось открыть выбранный файл")
                    }
                    file = target
                    val result = withContext(Dispatchers.IO) { FirmwareInspect.inspect(target) }
                    inspection = result
                    version =
                        target.name.removeSuffix(".zip").replace(Regex("[^A-Za-z0-9._-]"), "-").trim('-').ifBlank {
                            "manual-${System.currentTimeMillis() / 1000}"
                        }
                    message = result.message
                } catch (error: Exception) {
                    message = "Файл не принят: ${error.message}"
                } finally {
                    busy = false
                }
            }
        }

        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(
                rememberScrollState(),
            ).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    finish()
                }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = AbColors.Purple) }
                Text("Ручная настройка", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "Backend отдаст выбранный файл по обычной яндексовской ссылке для этой станции. Автоматический выбор прошивки по дате отключается только для вашего IP; остальные запросы не меняются.",
                color = AbColors.Muted,
                lineHeight = 22.sp,
            )
            Text(
                "Ручной режим действует до кнопки «Выключить», завершения сеанса настройки или 12 часов. Затем backend снова выбирает прошивку автоматически; загруженные файлы остаются в библиотеке.",
                color = AbColors.Muted,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
            if (manual != null) {
                Card {
                    Text("РУЧНОЙ РЕЖИМ ВКЛЮЧЁН", color = AbColors.Green, fontSize = 12.sp)
                    Text(
                        "${manual!!.optString(
                            "filename",
                        )} · ${manual!!.optString("platform")} · ${manual!!.optString("version")}",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Шаблон: ${manual!!.optString("template")} · ${manual!!.optLong("size") / 1024 / 1024} МБ",
                        color = AbColors.Muted,
                        fontSize = 13.sp,
                    )
                    if (manual!!.optString(
                            "signature_client",
                        ).isNotBlank()
                    ) {
                        Text(
                            "Подпись: ${manual!!.optString("signature_client")}",
                            color = AbColors.Muted,
                            fontSize = 13.sp,
                        )
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            busy = true
                            try {
                                apply(withContext(Dispatchers.IO) { client.clear() })
                                message =
                                    "Ручной режим выключен"
                                prefs.edit().putBoolean("manual_mode", false).apply()
                            } catch (e: Exception) {
                                message =
                                    e.message.orEmpty()
                            } finally {
                                busy = false
                            }
                        }
                    }, enabled = !busy) { Text("Выключить ручной режим") }
                }
            }
            Card {
                Text("1. Шаблон запросов", fontWeight = FontWeight.Bold)
                Box {
                    OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("${template.label} · ${template.platform.ifBlank { "нет платформы" }}", maxLines = 2)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        templates.forEach { item ->
                            DropdownMenuItem(text = {
                                Text("${item.label} · ${item.platform.ifBlank { "—" }}")
                            }, onClick = {
                                template =
                                    item
                                menu = false
                            })
                        }
                    }
                }
                if (template.id == "base" &&
                    template.platform.isBlank()
                ) {
                    Text(
                        "Базовый шаблон без станции требует активного сеанса, чтобы взять его платформу. Иначе выберите конкретную модель.",
                        color = AbColors.Peach,
                        fontSize = 13.sp,
                    )
                }
            }
            Card {
                Text("2. ZIP прошивки", fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = {
                    picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (file == null) "Выбрать .zip на телефоне" else "Выбрать другой файл")
                }
                inspection?.let { item ->
                    Text(
                        item.message,
                        color = when {
                            !item.signed -> AbColors.Peach
                            item.signatureValid -> AbColors.Green
                            else -> AbColors.Peach
                        },
                        lineHeight = 21.sp,
                    )
                    Text("${item.format} · ${item.size / 1024 / 1024} МБ", color = AbColors.Muted, fontSize = 13.sp)
                    Text("SHA-256: ${item.sha256}", color = AbColors.Muted, fontSize = 12.sp)
                    Text("CRC32: ${item.crc32}", color = AbColors.Muted, fontSize = 12.sp)
                    if (item.signer.isNotBlank()) {
                        Text(
                            "Подписант: ${item.signer}",
                            color = AbColors.Muted,
                            fontSize = 12.sp,
                        )
                    }
                    if (!item.signed || !item.signatureValid) {
                        if (item.signed &&
                            !item.signatureValid
                        ) {
                            Text("Файл с недействительной подписью загружать нельзя.", color = AbColors.Peach)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(unsignedAccepted, { unsignedAccepted = it })
                                Text(
                                    "Файл без встроенной подписи — продолжить на свой риск",
                                    color = AbColors.Peach,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                    OutlinedTextField(version, {
                        version = it
                    }, label = { Text("Версия в манифесте") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
            val canUpload = !busy &&
                file != null &&
                inspection != null &&
                template.platform.isNotBlank() &&
                version.isNotBlank() &&
                (inspection!!.signed && inspection!!.signatureValid || (!inspection!!.signed && unsignedAccepted))
            Button(onClick = {
                scope.launch {
                    busy = true
                    progress = 0f
                    message = "Загружаем прошивку на backend…"
                    try {
                        val result = withContext(Dispatchers.IO) {
                            client.upload(
                                file!!,
                                template.platform,
                                template.id,
                                version,
                                inspection!!.let {
                                    if (it.signed &&
                                        it.signatureValid
                                    ) {
                                        "valid:${it.signer}"
                                    } else {
                                        "none"
                                    }
                                },
                            ) { sent ->
                                progress = sent.toFloat() / file!!.length().coerceAtLeast(1)
                            }
                        }
                        apply(result)
                        message = "Файл загружен и включён в ручном режиме"
                        prefs.edit().putBoolean("manual_mode", true).apply()
                    } catch (error: Exception) {
                        message = "Не удалось: ${error.message}"
                    } finally {
                        busy = false
                    }
                }
            }, enabled = canUpload, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Text("Загрузить и включить ручной режим")
            }
            if (busy &&
                progress > 0f
            ) {
                LinearProgressIndicator(progress = {
                    progress
                }, modifier = Modifier.fillMaxWidth(), color = AbColors.Purple)
            }
            if (message.isNotBlank()) Text(message, color = AbColors.Muted, lineHeight = 21.sp)
            if (library.isNotEmpty()) {
                Text("ЗАГРУЖЕННЫЕ ПРОШИВКИ", color = AbColors.Purple, fontSize = 12.sp, letterSpacing = 1.sp)
                library.forEach { item ->
                    Card {
                        Text(item.optString("filename"), fontWeight = FontWeight.SemiBold)
                        Text(
                            "${item.optString(
                                "platform",
                            )} · ${item.optString("version")} · ${item.optString("template")}",
                            color = AbColors.Muted,
                            fontSize = 13.sp,
                        )
                        Text(
                            "${item.optLong("size") / 1024 / 1024} МБ · SHA-256 ${item.optString("sha256").take(12)}…",
                            color = AbColors.Muted,
                            fontSize = 12.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    busy = true
                                    try {
                                        apply(withContext(Dispatchers.IO) { client.select(item.optString("sha256")) })
                                        message =
                                            "Выбрана прошивка ${item.optString("filename")}"
                                        prefs.edit().putBoolean("manual_mode", true).apply()
                                    } catch (e: Exception) {
                                        message =
                                            e.message.orEmpty()
                                    } finally {
                                        busy = false
                                    }
                                }
                            }, enabled = !busy) { Text("Выбрать") }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    busy = true
                                    try {
                                        apply(withContext(Dispatchers.IO) { client.remove(item.optString("sha256")) })
                                        message =
                                            "Удалено из библиотеки"
                                    } catch (
                                        e: Exception,
                                    ) {
                                        message = e.message.orEmpty()
                                    } finally {
                                        busy =
                                            false
                                    }
                                }
                            }, enabled = !busy) { Text("Удалить") }
                        }
                    }
                }
            }
        }
    }

    @Composable private fun Card(content: @Composable ColumnScope.() -> Unit) =
        Surface(color = AbColors.Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
}
