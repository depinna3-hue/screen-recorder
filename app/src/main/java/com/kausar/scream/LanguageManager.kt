package com.kausar.scream

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

object LanguageManager {

    data class Lang(val code: String, val name: String)

    val LANGUAGES = listOf(
        Lang("in", "Bahasa Indonesia"),
        Lang("en", "English"),
        Lang("ar", "العربية"),
        Lang("zh", "中文"),
        Lang("es", "Español"),
        Lang("fr", "Français"),
        Lang("de", "Deutsch"),
        Lang("ja", "日本語"),
        Lang("ko", "한국어"),
        Lang("pt", "Português"),
        Lang("ru", "Русский"),
        Lang("hi", "हिन्दी"),
        Lang("th", "ไทย"),
        Lang("vi", "Tiếng Việt"),
        Lang("tr", "Türkçe"),
        Lang("ms", "Bahasa Melayu"),
        Lang("nl", "Nederlands"),
        Lang("it", "Italiano"),
        Lang("pl", "Polski"),
        Lang("uk", "Українська"),
        Lang("sv", "Svenska"),
        Lang("fi", "Suomi"),
        Lang("no", "Norsk"),
        Lang("da", "Dansk"),
        Lang("cs", "Čeština"),
        Lang("ro", "Română"),
        Lang("hu", "Magyar"),
        Lang("el", "Ελληνικά"),
        Lang("he", "עברית"),
        Lang("fa", "فارسی"),
        Lang("ur", "اردو"),
        Lang("bn", "বাংলা"),
        Lang("ta", "தமிழ்"),
        Lang("te", "తెలుగు"),
        Lang("mr", "मराठी"),
        Lang("gu", "ગુજરાતી"),
        Lang("kn", "ಕನ್ನಡ"),
        Lang("ml", "മലയാളം"),
        Lang("si", "සිංහල"),
        Lang("ne", "नेपाली"),
        Lang("my", "မြန်မာ"),
        Lang("km", "ខ្មែរ"),
        Lang("lo", "ລາວ"),
        Lang("fil", "Filipino"),
        Lang("sw", "Kiswahili"),
        Lang("af", "Afrikaans"),
        Lang("zu", "isiZulu"),
        Lang("am", "አማርኛ"),
        Lang("ha", "Hausa"),
        Lang("yo", "Yorùbá"),
        Lang("ig", "Igbo")
    )

    private const val PREF = "kausar_lang"
    private const val KEY = "lang_code"

    fun getSavedCode(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY, null)
        if (!saved.isNullOrEmpty()) return saved
        // Ikuti bahasa perangkat jika belum dipilih
        // (Android baru memakai kode "id"/"nb"/"iw"/"tl", sedangkan daftar ini memakai "in"/"no"/"he"/"fil")
        val sys = when (val l = java.util.Locale.getDefault().language) {
            "id" -> "in"
            "nb", "nn" -> "no"
            "iw" -> "he"
            "tl" -> "fil"
            else -> l
        }
        return if (LANGUAGES.any { it.code == sys }) sys else "en"
    }

    fun saveCode(ctx: Context, code: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, code).apply()
    }

    fun applyLocale(ctx: Context, code: String = getSavedCode(ctx)): Context {
        val locale = Locale(code)
        Locale.setDefault(locale)
        val config = Configuration(ctx.resources.configuration)
        if (Build.VERSION.SDK_INT >= 17) {
            config.setLocale(locale)
            return ctx.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
            @Suppress("DEPRECATION")
            ctx.resources.updateConfiguration(config, ctx.resources.displayMetrics)
            return ctx
        }
    }

    /** Tidak boleh melempar: dipakai di service/notifikasi saat merekam. */
    fun wrap(ctx: Context): Context = try { applyLocale(ctx) } catch (_: Exception) { ctx }
}
