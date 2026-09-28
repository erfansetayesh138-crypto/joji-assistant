package com.joji.assistant

import android.content.Context
import android.content.Intent
import org.json.JSONObject

class Agent(private val ctx: Context, private val key: String) {

    private val system = """
تو «جوجی» هستی، یک دستیار صوتی فارسی‌زبان که می‌تواند گوشی اندروید کاربر را از طریق سرویس دسترسی‌پذیری کنترل کند.
در هر پاسخ فقط و فقط یک شیء JSON بده. اقدام‌های ممکن:
{"action":"say","text":"..."}  گفتن جواب نهایی و پایان کار (برای گفتگوی عادی هم همین را بزن)
{"action":"look"}  دیدن متن‌های صفحه فعلی گوشی
{"action":"open_app","name":"..."}  باز کردن برنامه (نام فارسی یا انگلیسی؛ اگر پیدا نشد اسم دیگرش را امتحان کن)
{"action":"click","text":"..."}  کلیک روی عنصری که متن یا توضیحش شامل این عبارت است
{"action":"type","text":"..."}  نوشتن متن در فیلد ورودی
{"action":"scroll","dir":"down"}  اسکرول (down یا up)
{"action":"back"}  {"action":"home"}  {"action":"wait"}
{"action":"done","text":"..."}  پایان کار با یک گزارش کوتاه صوتی
قوانین: متن گفتاری کوتاه باشد (۱ تا ۲ جمله)، طبیعی و مودبانه. در هر مرحله فقط یک اقدام. برای انتخاب اقدام از خروجی صفحه استفاده کن ([C]=قابل کلیک، [E]=قابل نوشتن، [S]=قابل اسکرول). قبل از پرداخت، حذف یا هر کار غیرقابل بازگشت با say از کاربر تأیید بگیر و هرگز رمز عبور یا کد تأیید را تایپ نکن. اگر خروجی می‌گوید سرویس دسترسی‌پذیری روشن نیست، همین را به کاربر بگو.
""".trimIndent()

    private fun speak(t: String) {
        if (t.isBlank()) return
        try {
            Speaker.say(key, t)
        } catch (e: Exception) {
            WakeService.status = "خطای صدا: " + e.message
        }
    }

    private fun clean(s: String): String =
        s.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

    private fun openApp(name: String): Boolean {
        val pm = ctx.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(q, 0)
            .firstOrNull { it.loadLabel(pm).toString().contains(name, ignoreCase = true) } ?: return false
        val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val c: Context = JojiAccessibility.instance ?: ctx
        c.startActivity(launch)
        return true
    }

    fun handle(command: String) {
        val history = StringBuilder()
        var showScreen = false
        for (step in 1..12) {
            val acc = JojiAccessibility.instance
            val screen = if (showScreen) {
                acc?.dumpScreen() ?: "(سرویس دسترسی‌پذیری روشن نیست)"
            } else {
                "(نمایش داده نمی‌شود؛ اگر لازم داری از look استفاده کن)"
            }
            val user = "دستور کاربر: $command\nاقدام‌های قبلی:\n$history\nصفحه فعلی:\n$screen"
            val raw = try {
                Gemini.think(key, system, user, true)
            } catch (e: Exception) {
                WakeService.status = "خطا: " + e.message
                speak("مشکلی در ارتباط با مغز پیش آمد.")
                return
            }
            val obj = try {
                JSONObject(clean(raw))
            } catch (e: Exception) {
                speak(raw.take(200))
                return
            }
            val action = obj.optString("action")
            val text = obj.optString("text")
            history.append(step).append(". ").append(obj.toString()).append('\n')
            WakeService.status = "اقدام: $obj"
            when (action) {
                "say", "done" -> {
                    speak(text)
                    return
                }
                "look" -> showScreen = true
                "open_app" -> {
                    showScreen = true
                    if (!openApp(obj.optString("name"))) history.append("(برنامه پیدا نشد)\n")
                    Thread.sleep(2500)
                }
                "click" -> {
                    showScreen = true
                    if (acc?.clickText(text) != true) history.append("(کلیک ناموفق بود)\n")
                    Thread.sleep(1200)
                }
                "type" -> {
                    showScreen = true
                    if (acc?.typeText(text) != true) history.append("(نوشتن ناموفق بود)\n")
                    Thread.sleep(800)
                }
                "scroll" -> {
                    showScreen = true
                    acc?.scroll(obj.optString("dir") != "up")
                    Thread.sleep(800)
                }
                "back" -> {
                    acc?.goBack()
                    Thread.sleep(800)
                }
                "home" -> {
                    acc?.goHome()
                    Thread.sleep(800)
                }
                "wait" -> Thread.sleep(1500)
                else -> {
                    speak("دستور را متوجه نشدم.")
                    return
                }
            }
        }
        speak("نتوانستم کار را کامل انجام بدهم.")
    }
}
