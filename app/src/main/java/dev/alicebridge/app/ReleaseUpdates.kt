package dev.alicebridge.app

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/** The update prompt covers the screen; downloading and installation require a user action. */
internal class ReleaseUpdates(private val activity: Activity, private val repository: SessionRepository) {
    private val handler = Handler(Looper.getMainLooper())
    private var checking = false
    private var dialog: Dialog? = null
    private var disposed = false
    fun check(manual: Boolean = false) {
        if (disposed || checking || dialog?.isShowing == true) return
        checking = true
        if (manual) toast("Проверяем обновления…")
        Thread({
            try {
                val release = AppUpdates.check()
                val current = AppUpdates.installedCode(activity)
                handler.post {
                    checking = false
                    if (!disposed && !activity.isFinishing && !activity.isDestroyed) {
                        if (release.code() >
                            current
                        ) {
                            show(release)
                        } else if (manual) {
                            toast("Установлена последняя версия AliceBridge")
                        }
                    }
                }
            } catch (error: Exception) {
                handler.post {
                    checking = false
                    if (!disposed &&
                        manual
                    ) {
                        toast("Не удалось проверить обновления: ${error.message}")
                    }
                }
            }
        }, "alicebridge-update-check").start()
    }
    private fun toast(text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    }
    private fun show(release: UpdateInfo.Release) {
        val popup = Dialog(activity, android.R.style.Theme_Material_NoActionBar)
        dialog = popup
        val layout = ScreenUi.column(activity)
        layout.setBackgroundColor(ScreenUi.BG)
        layout.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(
                bars.left + ScreenUi.dp(activity, 24),
                bars.top + ScreenUi.dp(activity, 24),
                bars.right + ScreenUi.dp(activity, 24),
                bars.bottom + ScreenUi.dp(activity, 20),
            )
            insets
        }
        layout.addView(ScreenUi.text(activity, "НОВАЯ ВЕРСИЯ", 12, ScreenUi.ACCENT))
        layout.addView(
            ScreenUi.text(activity, "AliceBridge ${release.version()}", 28, ScreenUi.TEXT),
            ScreenUi.space(activity, 16),
        )
        layout.addView(
            ScreenUi.text(
                activity,
                "${"%.1f".format(release.size() / 1048576.0)} МБ · ваши настройки сохранятся",
                14,
                ScreenUi.MUTED,
            ),
            ScreenUi.space(activity, 8),
        )
        val changes = ScreenUi.column(activity)
        changes.addView(ScreenUi.text(activity, "Что нового", 20, ScreenUi.TEXT), ScreenUi.space(activity, 24))
        release.changes().forEach {
            changes.addView(ScreenUi.text(activity, "• $it", 14, ScreenUi.MUTED), ScreenUi.space(activity, 12))
        }
        val scroll = ScrollView(activity)
        scroll.addView(changes)
        layout.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        layout.addView(
            ScreenUi.button(activity, "Обновить", {
                val state = repository.state.value
                if (state.busy || state.tools.busy || (state.running && state.session?.adbVerified != true)) {
                    toast("Завершите текущую настройку колонки перед обновлением приложения")
                } else {
                    val json = JSONObject().put(
                        "versionCode",
                        release.code(),
                    ).put("versionName", release.version()).put("url", release.url())
                        .put(
                            "sha256",
                            release.sha256(),
                        ).put("size", release.size()).put("changes", JSONArray(release.changes())).toString()
                    popup.dismiss()
                    activity.startActivity(Intent(activity, UpdateActivity::class.java).putExtra("release", json))
                }
            }, true),
            ScreenUi.space(activity, 20),
        )
        layout.addView(
            ScreenUi.button(activity, "Пропустить обновление", {
                popup.dismiss()
            }, false),
            ScreenUi.space(activity, 10),
        )
        popup.setOnDismissListener { if (dialog === popup)dialog = null }
        popup.setContentView(layout)
        popup.show()
        popup.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDecorFitsSystemWindows(false)
            statusBarColor = ScreenUi.BG
            navigationBarColor = ScreenUi.BG
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        layout.requestApplyInsets()
    }
    fun close() {
        disposed = true
        dialog?.dismiss()
        handler.removeCallbacksAndMessages(null)
    }
}
