package dev.alice.bridge;

/** User-facing progress derived only from observed evidence, never from a guessed timer. */
public record ProgressInfo(String state, String platform, String version, String target, String confirmed,
                           String message, String downloadState, String downloadError, long downloaded,
                           long downloadTotal, long sent, long total, long updatedAt) {
    public static final ProgressInfo EMPTY = new ProgressInfo("", "", "", "", "", "", "", "", 0, 0, 0, 0, 0);
    public record View(String title, String hint, int step, int percent, String amount, boolean warning, boolean complete) {}
    public boolean versionConfirmed() { return !target.isEmpty() && target.equals(confirmed) && target.equals(version); }
    public boolean transferred() { return total > 0 && sent >= total; }
    public boolean tlsRejected() {
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("unknown_ca") || lower.contains("unknown ca") || lower.contains("certificate unknown");
    }
    public View view(boolean stationKnown, boolean adbVerified, String connectionError) {
        if (adbVerified) return new View("ADB подключён", "Колонка ответила на проверку. Настройка, YSE и переход на домашний Wi-Fi показаны ниже.", 4, -1, "", false, true);
        if (state.equals("tls_error") && !tlsRejected()) return new View("Ошибка защищённого соединения", "Сервер не смог установить соединение с колонкой. Проверку версии продолжим, когда связь восстановится; подробности записаны в журнал.", transferred() ? 2 : 1, -1, "", true, false);
        if (tlsRejected() && transferred()) return new View("Возможно, прошивка не подошла", "После передачи прошивки колонка отклонила сертификат сервера. Возможно, эта сборка не доверяет нашему сертификату и потребуется другая прошивка. Установка и перезагрузка пока не подтверждены; подробности — в журнале.", 2, -1, "", true, false);
        if (tlsRejected()) return new View("Колонка отклонила сертификат", "Защищённое соединение с сервером не установлено. Версию прошивки и ADB пока подтвердить нельзя. Причина записана в журнал.", 1, -1, "", true, false);
        if (!downloadError.isEmpty() || downloadState.equals("error")) return new View("Не удалось подготовить прошивку", "Загрузка образа на сервер завершилась ошибкой. Неполный файл колонке не выдаётся; подробности — в журнале.", 1, -1, "", true, false);
        if (state.equals("ambiguous")) return new View("Не удалось сопоставить статус", "За одним адресом обнаружено несколько сессий. Запросы записываются, но прогресс конкретной колонки пока не определён.", stationKnown ? 1 : 0, -1, "", true, false);
        if (!connectionError.isEmpty()) return new View("Ждём связи с сервером", "Сейчас сервер не отвечает. Повторяем подключение автоматически; предыдущие отметки не означают завершение установки.", stationKnown ? 1 : 0, -1, "", true, false);
        if (!stationKnown) return new View("Ждём подключения Алисы", "Выберите сеть AliceBridge в «Доме с Алисой». Продолжим автоматически, когда появятся запросы колонки.", 0, -1, "", false, false);
        if (versionConfirmed()) return new View("Проверяем ADB", "Целевая версия подтверждена. Подключаемся к колонке и ждём ответ — повторные попытки выполняются автоматически.", 3, -1, "", false, false);
        if (transferred()) return new View("Файл передан. Ждём установку и перезапуск", "После установки прошивки Станция перезагрузится. Завершение установки пока не подтверждено. Не отключайте питание и оставьте хотспот включённым — после загрузки найдём колонку снова, даже если сменится IP.", 2, 100, "", false, false);
        if (total > 0 && sent > 0) return new View("Передаём прошивку Алисе", "Колонка скачивает файл с сервера. После установки она перезагрузится. Не отключайте питание и оставьте точку доступа включённой.", 1, percent(sent, total), amount(sent, total), false, false);
        if (downloadState.equals("loading")) return new View("Сервер готовит прошивку", "Скачиваем и проверяем образ для вашей модели. Колонка получит его после проверки целостности.", 1, downloadTotal > 0 ? percent(downloaded, downloadTotal) : -1, amount(downloaded, downloadTotal), false, false);
        return new View("Ждём запрос обновления", "Колонка появилась в сети. Ожидаем проверку прошивки и ответ сервера; ADB пока проверяем в фоне.", 1, -1, "", false, false);
    }
    private static int percent(long value, long max) { return (int)Math.max(0, Math.min(100, value * 100d / max)); }
    private static String amount(long value, long max) {
        return String.format(java.util.Locale.forLanguageTag("ru"), "%.1f МБ", value / 1048576d) +
            (max > 0 ? String.format(java.util.Locale.forLanguageTag("ru"), " из %.1f МБ", max / 1048576d) : "");
    }
}
