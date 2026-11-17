package com.smartclass.manager.core

/**
 * ملف الإعدادات المركزية:
 * عدّل أي قيمة افتراضية هنا دون لمس بقية ملفات التطبيق.
 */
object AppConfig {
    const val PREFS_NAME = "smart_class_prefs"
    const val KEY_DB_DATA = "db_main_json"
    const val KEY_SETTINGS = "app_settings_json"
    const val KEY_CRASH_LOG = "last_crash_report"

    // الثوابت الافتراضية للقرعة والتقييم
    const val DEFAULT_COUNTDOWN_SECONDS = 3
    const val DEFAULT_ANSWER_TIMER_SECONDS = 30
    const val DEFAULT_MAX_SCORE = 20.0
    const val DEFAULT_BONUS_POINT = 1.0
    const val UNDO_TIMEOUT_MS = 5000L
    const val MAX_BREADCRUMBS = 15

    // بطاقات السلوك السريع الجاهزة
    val DEFAULT_BEHAVIOR_TAGS = listOf(
        "انتباه ممتاز",
        "مبادرة وإبداع",
        "مشاركة فعالة",
        "نسيان الأدوات",
        "تشتت أو شغب"
    )
}

/**
 * إعدادات المرونة (تشغيل / إيقاف كل ميزة بشكل مستقل)
 */
data class AppSettings(
    var allowRepeatInCycle: Boolean = false,       // السماح بتكرار التلميذ في نفس الدورة
    var countdownSeconds: Int = AppConfig.DEFAULT_COUNTDOWN_SECONDS,
    var soundEnabled: Boolean = true,              // المؤثرات الصوتية
    var ttsEnabled: Boolean = true,                // نطق اسم التلميذ صوتياً
    var ttsSpeechRate: Float = 0.95f,              // سرعة النطق
    var answerTimerEnabled: Boolean = false,       // مؤقت الإجابة التنازلي
    var answerTimerSeconds: Int = AppConfig.DEFAULT_ANSWER_TIMER_SECONDS,
    var maxScore: Double = AppConfig.DEFAULT_MAX_SCORE,
    var bonusPointValue: Double = AppConfig.DEFAULT_BONUS_POINT,
    var showBehaviorTags: Boolean = true,          // إظهار بطاقات السلوك السريع
    var showSubjectField: Boolean = false,         // التقييم حسب المادة
    var safeDisplayMode: Boolean = false,          // إخفاء النقاط عند العرض أمام التلاميذ
    var keepScreenOn: Boolean = true,              // منع قفل الشاشة أثناء الحصة
    var themeMode: String = "dark"                 // dark | light | classroom
)
