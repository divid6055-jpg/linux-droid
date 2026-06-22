# Changelog

جميع التغييرات الجوهرية في هذا المشروع موثّقة هنا.

التنسيق مستوحى من [Keep a Changelog](https://keepachangelog.com/ar/1.0.0/)
والإصدارات تتبع [Semantic Versioning](https://semver.org/lang/ar/).

## [1.1.0] — 2026-06-22

### Added
- ✨ **إكمال Tab تفاعلي** مدمج في ShellInterpreter (Tab key)
  - إكمال الأوامر، المسارات، ومتغيرات البيئة
  - عرض خيارات متعددة في أعمدة عند توفرها
  - أطول بادئة مشتركة تلقائياً
- ✨ **تنقّل في التاريخ بالأسهم** (↑/↓) — مدمج في الـ shell
- ✨ **Ctrl+C** لإلغاء السطر الحالي مع علامة `^C`
- ✨ **Ctrl+D** للإنهاء (EOF) عند سطر فارغ
- ✨ **Backspace** محسّن مع إعادة رسم السطر
- ✨ **شريط مفاتيح إضافي** مدمج في الواجهة (20 مفتاحاً)
  - CTRL, ALT, SHIFT (sticky عبر long-press)
  - ESC, TAB, الأسهم, HOME, END, PGUP, PGDN
  - رموز شائعة: -, /, |, >, <, $, ~, *, ?
- ✨ **15+ أمر Linux جديد**: `printf`, `gzip`, `gunzip`, `zcat`, `zip`, `unzip`, `tar`, `base64`, `md5sum`, `sha256sum`, `sha1sum`, `time`, `xargs`, `timeout`, `watch`
- ✨ **`ShellContext.resolveSafe`** و **`resolveSafeForWrite`** — واجهة مركزية للتحقق من المسارات
- ✨ **منع Zip Slip** في `unzip` (path traversal في entries)
- ✨ **40+ اختبار وحدوي جديد** (المجموع: 96 اختبار)
  - ExtendedCommandsTest (11 اختبار لـ printf)
  - TabCompleterTest (13 اختبار للإكمال)
  - HistoryManagerTest (16 اختبار للتاريخ)

### Security
- 🔒 جميع الأوامر الجديدة تستخدم `resolveSafe` للتحقق من المسارات
- 🔒 `unzip` يتحقق من canonical path لكل entry لمنع Zip Slip
- 🔒 `gzip`/`gunzip`/`zip`/`base64`/`md5sum`/`sha256sum` لا تكتب خارج صندوق الرمل

### Changed
- 🔄 `activity_main.xml` يستخدم `ExtraKeysBar` بدلاً من أزرار منفصلة
- 🔄 `MainActivity` يربط مستمعي الأزرار بالجلسة النشطة
- 🔄 `readLogicalLine` في `ShellInterpreter` تدعم ANSI escape sequences (الأسهم، Home/End، إلخ)

## [1.0.0] — 2026-06-22

### Added
- ✨ **محاكي طرفية كامل** مع دعم شامل لتسلسلات ANSI escape (CSI, OSC, SGR)
- ✨ **مفسّر Shell شبيه بـ Bash** مع الأنابيب، التوجيه، المتغيرات، والبدائل
- ✨ **100+ أمر مدمج** عبر 5 فئات:
  - الملفات: `ls`, `cat`, `cp`, `mv`, `rm`, `mkdir`, `touch`, `find`, `tree`, `du`, `df`, `chmod`, ...
  - معالجة النصوص: `echo`, `grep`, `sed`, `awk`, `head`, `tail`, `wc`, `sort`, `uniq`, `cut`, `tr`, `tee`, `diff`, ...
  - النظام: `ps`, `kill`, `top`, `whoami`, `uname`, `date`, `env`, `which`, `sleep`, `free`, ...
  - الشبكة: `curl`, `wget`, `ping`, `netstat`, `ifconfig`, `nslookup`
  - الحزم: `pkg` (install/remove/list/search/update/upgrade/show/files)
- ✨ **محرر nano** مبسّط داخل الطرفية
- ✨ **مدير تاريخ** مع التنقل بالأسهم والبحث العكسي (Ctrl+R)
- ✨ **إكمال Tab** للأوامر والمسارات ومتغيرات البيئة
- ✨ **توسيع glob** (`*`, `?`, `[abc]`) للملفات
- ✨ **شريط مفاتيح إضافي** (CTRL, ALT, ESC, TAB, الأسهم, رموز شائعة)
- ✨ **4 أنظمة ألوان**: Dark, Light, Solarized Dark, Dracula
- ✨ **إعدادات قابلة للتخصيص**: حجم الخط، المؤشر، الشاشة، الاهتزاز
- ✨ **خدمة أمامية** للحفاظ على الجلسات في الخلفية
- ✨ **نظام تسجيل دوار** (512KB × 3 ملفات)
- ✨ **اختبارات وحدية** (56 اختبار) للأمان، الطرفية، البيئة
- ✨ **توثيق شامل**: README, SECURITY, CHANGELOG

### Security
- 🔒 منع SSRF في curl/wget/ping (حظر loopback, private, metadata IPs)
- 🔒 فلترة الأسرار في الـ logs (passwords, tokens, API keys → [REDACTED])
- 🔒 إزالة `MANAGE_EXTERNAL_STORAGE` و `WRITE_EXTERNAL_STORAGE`
- 🔒 تعطيل `allowBackup` لمنع استخراج بيانات التطبيق
- 🔒 صندوق رمل للمسارات (path traversal prevention)
- 🔒 تحقق SHA-256 للحزم + التحقق من المسارات داخل صندوق الرمل
- 🔒 Thread safety في SessionManager (ConcurrentHashMap + AtomicInteger)
- 🔒 DoS prevention في AnsiEscapeParser (حدود أحجام الـ buffers)
- 🔒 إصلاح division-by-zero في `ping -c 0`
- 🔒 History manager يفلتر الأوامر الحساسة

### Technical
- 🏗️ **Kotlin 1.9.22** + Android SDK 34 (minSdk 24, ~95% من الأجهزة)
- 🏗️ Gradle Kotlin DSL + Gradle Wrapper 8.5
- 🏗️ MVVM pattern + single-activity architecture
- 🏗️ Coroutines للـ I/O غير المتزامن
- 🏗️ Material Design 3
- 🏗️ No external dependencies beyond AndroidX

## [Unreleased]

### Planned
- 📋 محرر Vi كامل
- 📋 دعم SSH (عبر sshj library)
- 📋 دعم proot لتشغيل ثنائيات Linux الأصلية
- 📋 واجهة رسومية لاختيار الأوامر
- 📋 دعم ملفات .tar.gz فعلية في pkg install
- 📋 دعم unicode أفضل في TerminalBuffer
- 📋 شاشة بداية (splash screen)
- 📋 دعم RTL كامل في الواجهة
