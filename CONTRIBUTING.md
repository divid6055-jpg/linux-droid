# 🤝 دليل المساهمة في LinuxDroid

شكراً لاهتمامك بالمساهمة في LinuxDroid! هذا الدليل يشرح كيف تساهم بفعالية.

## 📋 جدول المحتويات

- [قبل البدء](#قبل-البدء)
- [إعداد بيئة التطوير](#إعداد-بيئة-التطوير)
- [كيف تساهم](#كيف-تساهم)
- [معايير الكود](#معايير-الكود)
- [إضافة أمر جديد](#إضافة-أمر-جديد)
- [الاختبارات](#الاختبارات)
- [تقارير الأخطاء](#تقارير-الأخطاء)

## قبل البدء

قبل البدء، تأكّد من:
1. قراءة [README.md](README.md) لفهم المشروع
2. قراءة [SECURITY.md](SECURITY.md) للسياسات الأمنية
3. فهم بنية المشروع (انظر قسم "البنية المعمارية" في README)

## إعداد بيئة التطوير

### المتطلبات
- Android Studio Hedgehog (2023.1.1) أو أحدث
- JDK 17
- Android SDK 34

### الخطوات
```bash
# 1. Fork المستودع على GitHub
# 2. استنساخ fork الخاص بك
git clone https://github.com/YOUR_USERNAME/linux-droid.git
cd linux-droid

# 3. إضافة upstream
git remote add upstream https://github.com/divid6055-jpg/linux-droid.git

# 4. مزامنة قبل البدء
git fetch upstream
git checkout main
git merge upstream/main

# 5. فتح في Android Studio
# File → Open → اختر مجلد linux-droid
```

## كيف تساهم

### 1. الإبلاغ عن خطأ
- استخدم [GitHub Issues](https://github.com/divid6055-jpg/linux-droid/issues)
- ابحث أولاً إن كان الخطأ مُبلّغاً مسبقاً
- استخدم القالب المناسب
- أرفق:
  - نسخة LinuxDroid
  - إصدار أندرويد
  - نوع الجهاز
  - خطوات الاستنساخ
  - السجلات (من `dmesg` أو logcat)

### 2. اقتراح ميزة
- افتح issue بعنوان `[FEAT] وصف الميزة`
- اشرح الهدف والاستخدام المتوقع
- انتظر الموافقة قبل البدء بالكود

### 3. تنفيذ إصلاح/ميزة
1. أنشئ فرعاً: `git checkout -b feature/amazing-feature`
2. اكتب الكود باتباع [معايير الكود](#معايير-الكود)
3. أضف اختبارات
4. تأكّد من نجاح البناء: `./gradlew assembleDebug`
5. تأكّد من نجاح الاختبارات: `./gradlew test`
6. Commit: `git commit -m "feat: add amazing feature"`
7. Push: `git push origin feature/amazing-feature`
8. افتح Pull Request

## معايير الكود

### Kotlin
- اتبع [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- استخدم 4 مسافات للإزاحة (لا tabs)
- طول السطر الأقصى: 120 حرفاً
- وثّق كل دالة عمومية بـ KDoc

### التنسيق
```kotlin
/**
 * وصف مختصر للدالة.
 *
 * شرح مفصّل إن لزم.
 *
 * @param param1 وصف المعطى الأول
 * @return وصف القيمة المعادة
 */
fun myFunction(param1: String): Int {
    // التنفيذ
}
```

### الأمان
- لا تستخدم `Runtime.exec()` أو `ProcessBuilder`
- تحقق من المسارات عبر `SecurityUtils.resolveSafe`
- تحقق من URLs عبر `SecurityUtils.validateUrl`
- لا تسجّل بيانات حساسة (استخدم `SecurityUtils.sanitizeLogMessage`)
- أضف اختبارات أمنية للميزات الجديدة

## إضافة أمر جديد

لإضافة أمر Linux جديد:

### 1. أنشئ ملف الأمر
في المجلد المناسب:
- `app/src/main/java/com/linuxdroid/commands/files/` لأوامر الملفات
- `app/src/main/java/com/linuxdroid/commands/text/` لأوامر النصوص
- `app/src/main/java/com/linuxdroid/commands/system/` لأوامر النظام
- `app/src/main/java/com/linuxdroid/commands/net/` لأوامر الشبكة

```kotlin
package com.linuxdroid.commands.files

import com.linuxdroid.commands.CommandExecutor
import com.linuxdroid.commands.ShellContext
import java.io.File

/**
 * mycmd — وصف مختصر للأمر.
 *
 * الخيارات:
 *   -a  وصف الخيار
 *   -b  وصف الخيار
 *
 * الاستخدام:
 *   mycmd [-a] [-b] FILE...
 */
class MyCmdCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var flagA = false
        var flagB = false
        val targets = mutableListOf<String>()

        for (arg in args) {
            when {
                arg == "--help" -> { ctx.write(help()); return 0 }
                arg.startsWith("-") && arg.length > 1 -> {
                    for (c in arg.drop(1)) when (c) {
                        'a' -> flagA = true
                        'b' -> flagB = true
                        else -> { ctx.writeErrln("mycmd: invalid option -- '$c'"); return 1 }
                    }
                }
                else -> targets.add(arg)
            }
        }

        // SECURITY: تحقق من المسارات
        for (t in targets) {
            val f = com.linuxdroid.security.SecurityUtils.resolveSafe(t, ctx.workingDirectory)
                ?: run { ctx.writeErrln("mycmd: cannot access '$t'"); return 1 }
            // التنفيذ...
        }
        return 0
    }

    override fun help() = """
        mycmd — short description
        Usage: mycmd [-a] [-b] FILE...
        Options:
          -a  description
          -b  description
    """.trimIndent()
}
```

### 2. سجّل الأمر في CommandRegistry
في `app/src/main/java/com/linuxdroid/commands/CommandRegistry.kt`:
```kotlin
register("mycmd", MyCmdCommand())
```

### 3. أضف اختبارات
في `app/src/test/java/com/linuxdroid/MyCmdCommandTest.kt`:
```kotlin
class MyCmdCommandTest {
    @Test
    fun `basic execution`() {
        // ...
    }
}
```

### 4. حدّث التوثيق
- أضف الأمر إلى README.md في جدول الأوامر
- أضفه إلى CHANGELOG.md تحت `[Unreleased]`

## الاختبارات

### تشغيل الاختبارات
```bash
# كل الاختبارات
./gradlew test

# اختبار محدد
./gradlew test --tests "com.linuxdroid.SecurityUtilsTest"

# مع تقرير تغطية
./gradlew test jacocoTestReport
```

### معايير الاختبارات
- اكتب اختباراً لكل دالة عمومية
- اكتب اختبارات أمنية للثغرات المحتملة
- استخدم أسماء وصفية بالـ backticks: `` `validateUrl blocks localhost` ``
- استخدم `assertEquals`, `assertTrue`, `assertFalse` بوضوح

## تقارير الأخطاء

### قالب تقرير الخطأ
```markdown
**وصف الخطأ**
وصف واضح ومختصر.

**خطوات الاستنساخ**
1. اذهب إلى '...'
2. اكتب '....'
3. شاهد الخطأ

**السلوك المتوقع**
وصف ما كنت تتوقّعه.

**السلوك الفعلي**
وصف ما حدث.

**لقطات الشاشة**
إن أمكن.

**البيئة**
- نسخة LinuxDroid: [مثل 1.0.0]
- إصدار أندرويد: [مثل 14]
- نوع الجهاز: [مثل Pixel 7]
- هل الجهاز مُروّت؟ [نعم/لا]

**السجلات**
```
الصق هنا مخرجات `dmesg` أو logcat
```
```

## رخصة المساهمة

بمساهمتك، فإنك توافق على أن مساهمتك ستُرخّص تحت [MIT License](LICENSE).

## شكراً

شكراً لكل من يساهم في جعل LinuxDroid أفضل! 🐧
