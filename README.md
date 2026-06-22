# LinuxDroid 🐧📱

<p align="center">
  <strong>نظام Linux خفيف لأندرويد — يعمل كتطبيق APK عادي مع دعم شامل لأوامر وميزات Linux</strong>
</p>

<p align="center">
  <a href="#الميزات">الميزات</a> •
  <a href="#التثبيت">التثبيت</a> •
  <a href="#البناء">البناء</a> •
  <a href="#الاستخدام">الاستخدام</a> •
  <a href="#المساهمة">المساهمة</a> •
  <a href="#الترخيص">الترخيص</a>
</p>

---

## 📋 نظرة عامة

**LinuxDroid** هو تطبيق أندرويد يوفر بيئة Linux خفيفة وكاملة تعمل مباشرة على هاتفك دون الحاجة إلى جذر (root) أو تقسيم النظام. التطبيق يجمع بين:

- **محاكي طرفية (Terminal Emulator)** متكامل مع دعم كامل لتسلسلات ANSI
- **مفسّر Shell شبيه بـ Bash** مع الأنابيب، التوجيه، المتغيرات، والبدائل
- **مجموعة كبيرة من الأوامر المدمجة** (ls, cat, grep, sed, awk, curl, wget, ...)
- **مدير حزم** لتنزيل وتثبيت أدوات إضافية
- **واجهة مادية** عصرية مع دعم أنظمة ألوان متعددة

> **ملاحظة:** هذا المشروع مستوحى من [Termux](https://termux.com/) لكنه مبني من الصفر بـ Kotlin خالص ويهدف إلى أن يكون أبسط وأخف وزناً.

---

## ✨ الميزات

### محاكي الطرفية
- دعم كامل لتسلسلات ANSI escape (CSI, OSC, SGR)
- 16 لون + 256 لون (مخفّضة) + سمات (bold, italic, underline, inverse, blink)
- شاشة بديلة (alternate screen) لتطبيقات fullscreen
- مناطق تمرير، tab stops، حفظ/استعادة المؤشر
- إعادة حجم ديناميكية عند تدوير الجهاز
- مؤشرات متعددة (Block, Underline, Bar)

### مفسّر Shell
- أوامر متسلسلة بـ `;`, `&&`, `||`
- أنابيب `cmd1 | cmd2 | cmd3`
- توجيه `>`, `>>`, `<`, `2>`, `&>`
- متغيرات بيئة: `$VAR`, `${VAR}`, `$?`, `$$`
- أوامر مدمجة: `cd`, `pwd`, `export`, `unset`, `alias`, `source`, `history`
- اقتباس مفرد/مزدوج وهروب بـ `\`
- توسيع `~` والبدائل `*`, `?`
- تعليقات `#`

### الأوامر المدمجة (100+ أمر)

| الفئة | الأوامر |
|------|--------|
| **الملفات** | `ls`, `cd`, `pwd`, `cat`, `cp`, `mv`, `rm`, `mkdir`, `rmdir`, `touch`, `ln`, `find`, `stat`, `file`, `tree`, `du`, `df`, `chmod`, `chown` |
| **معالجة النصوص** | `echo`, `grep`, `egrep`, `fgrep`, `sed`, `awk`, `head`, `tail`, `wc`, `sort`, `uniq`, `cut`, `tr`, `tee`, `diff`, `paste`, `rev`, `nl`, `tac`, `xxd`, `od` |
| **النظام** | `ps`, `kill`, `top`, `whoami`, `uname`, `date`, `uptime`, `env`, `which`, `sleep`, `test`, `id`, `hostname`, `clear`, `reset`, `man`, `seq`, `cal`, `free`, `mount`, `dmesg` |
| **الشبكة** | `curl`, `wget`, `ping`, `netstat`, `ifconfig`, `ip`, `nslookup` |
| **الحزم** | `pkg` (`install`, `remove`, `list`, `search`, `update`, `upgrade`, `show`, `files`) |
| **المحررات** | `nano` (مبسّط) |

### الواجهة
- أنظمة ألوان: Dark, Light, Solarized Dark, Dracula
- أحجام خطوط قابلة للتعديل (12-48)
- أشرطة مفاتيح إضافية (CTRL, ALT, ESC, TAB, الأسهم)
- دعم العربية RTL
- إبقاء الشاشة مضاءة (اختياري)
- اهتزاز عند الكتابة (اختياري)

### الأمان
- فحص بيئة التشغيل (root, emulator, debugger)
- منع بروتوكولات خطيرة في curl (file://, ftp://)
- التحقق من SHA-256 للحزم قبل التثبيت
- نظام تسجيل دوار
- لا توجد أذونات أكثر مما يلزم

---

## 📥 التثبيت

### الطريقة 1: تنزيل APK جاهز
1. اذهب إلى [Releases](https://github.com/divid6055-jpg/linux-droid/releases)
2. نزّل أحدث `linux-droid-vX.X.X.apk`
3. فعّل "تثبيت من مصادر غير معروفة" في إعدادات أندرويد
4. افتح الـ APK لتثبيته

### الطريقة 2: البناء من المصدر
```bash
git clone https://github.com/divid6055-jpg/linux-droid.git
cd linux-droid
./gradlew assembleRelease
# APK في app/build/outputs/apk/release/
```

---

## 🔨 البناء

### المتطلبات
- Android Studio Hedgehog (2023.1.1) أو أحدث
- JDK 17
- Android SDK 34 (Android 14)
- Min SDK 24 (Android 7.0) — يدعم 95%+ من الأجهزة

### الخطوات
```bash
# 1. استنساخ المستودع
git clone https://github.com/divid6055-jpg/linux-droid.git
cd linux-droid

# 2. بناء Debug APK
./gradlew assembleDebug

# 3. بناء Release APK (يتطلب توقيع)
./gradlew assembleRelease

# 4. تشغيل الاختبارات
./gradlew test
```

### توقيع Release APK
أنشئ ملف `keystore.properties` في جذر المشروع:
```properties
storeFile=/path/to/your.keystore
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```
ثم أزل التعليق عن سطر `signingConfig` في `app/build.gradle.kts`.

---

## 🎯 الاستخدام

### البدء السريع
1. افتح التطبيق
2. ستظهر رسالة الترحيب ثم prompt
3. اكتب `help` لعرض كل الأوامر
4. جرّب:
   ```bash
   ls -la ~
   echo "مرحبا LinuxDroid"
   pkg list
   pkg update
   curl https://httpbin.org/get
   ```

### اختصارات لوحة المفاتيح
| المفتاح | الوظيفة |
|---------|---------|
| `Ctrl+C` | إيقاف الأمر الحالي |
| `Ctrl+D` | إنهاء الإدخال (EOF) |
| `Ctrl+L` | مسح الشاشة |
| `Tab` | إكمال الأمر/الملف |
| `↑` / `↓` | تصفّح التاريخ |
| `Ctrl+A` | بداية السطر |
| `Ctrl+E` | نهاية السطر |

### أمثلة
```bash
# تنزيل ملف
wget https://example.com/file.txt

# البحث في النص
cat /etc/os-release | grep -i version

# أنابيب متعددة
ls -la | grep "\.sh$" | wc -l

# متغيرات وتوسيع
export NAME="LinuxDroid"
echo "Welcome to $NAME!"

# استخدام awk
ls -la | awk '{print $5, $9}'

# تثبيت حزمة
pkg update
pkg search bash
pkg install bash-utils
```

---

## 🏗️ البنية المعمارية

```
app/src/main/java/com/linuxdroid/
├── app/                  # Application class
├── terminal/             # محاكي الطرفية
│   ├── TerminalBuffer.kt     # ذاكرة الشاشة
│   ├── AnsiEscapeParser.kt   # معالج ANSI
│   └── TerminalView.kt       # عرض Android
├── shell/                # مفسّر Shell
│   ├── Environment.kt        # متغيرات البيئة
│   ├── ShellInterpreter.kt   # المفسّر الرئيسي
│   └── Session.kt            # جلسات الطرفية
├── commands/             # تنفيذ الأوامر
│   ├── CommandRegistry.kt    # سجل الأوامر
│   ├── ShellContext.kt        # سياق التنفيذ
│   ├── files/                 # أوامر الملفات
│   ├── text/                  # أوامر النصوص
│   ├── system/                # أوامر النظام + pkg
│   └── net/                   # أوامر الشبكة
├── process/              # خدمة الخلفية
├── security/             # مدير الأمان
├── ui/                   # الأنشطة (MainActivity, Settings, Help)
└── util/                 # أدوات مساعدة
```

---

## 🛣️ خارطة الطريق

- [x] الإصدار 1.0.0 — الأساس الكامل
- [ ] الإصدار 1.1 — إكمال Tab متقدم
- [ ] الإصدار 1.2 — محرر vi كامل
- [ ] الإصدار 1.3 — دعم SSH (عبر sshj)
- [ ] الإصدار 1.4 — دعم proot لتشغيل ثنائيات Linux الأصلية
- [ ] الإصدار 2.0 — واجهة رسومية لاختيار الأوامر

---

## 🤝 المساهمة

المساهمات مرحّب بها! يرجى:
1. عمل Fork للمستودع
2. إنشاء فرع للميزة: `git checkout -b feature/amazing-feature`
3. Commit: `git commit -m 'Add amazing feature'`
4. Push: `git push origin feature/amazing-feature`
5. فتح Pull Request

### معايير الكود
- اتبع [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- أضف اختبارات للأوامر الجديدة
- وثّق كل دالة عمومية
- استخدم أسماء واضحة ومتسقة

---

## 📜 الترخيص

هذا المشروع مرخّص تحت **MIT License** — راجع ملف [LICENSE](LICENSE).

---

## 🙏 شكر وتقدير

- [Termux](https://termux.com/) — الإلهام والمرجع
- [Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator) — مرجع تقني
- مجتمع Kotlin و Android

---

## 📞 التواصل

- 🐛 [تبليغ عن خطأ](https://github.com/divid6055-jpg/linux-droid/issues)
- 💬 [مناقشات](https://github.com/divid6055-jpg/linux-droid/discussions)
- 📧 البريد: divid6055@gmail.com

---

<p align="center">
  صُنع بشغف لمن يحب Linux على أجهزته المحمولة 🐧
</p>
