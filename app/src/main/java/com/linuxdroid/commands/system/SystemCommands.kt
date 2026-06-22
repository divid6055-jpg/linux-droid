package com.linuxdroid.commands.system

import com.linuxdroid.commands.CommandExecutor
import com.linuxdroid.commands.ShellContext
import com.linuxdroid.shell.Environment
import com.linuxdroid.shell.SessionManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * أوامر النظام: ps, kill, top, whoami, uname, date, uptime, env,
 *              which, sleep, test, id, hostname, tty, clear, reset,
 *              man, seq, yes, cal, free, mount, dmesg
 */

/** ps — قائمة العمليات (نظهر عمليات الجلسات + تطبيقات Android المدارة) */
class PsCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        ctx.writeln("  PID  PPID USER     VSZ    RSS STAT NAME")
        // عمليات LinuxDroid الداخلية (جلسات)
        val sm = SessionManager.getInstance(ctx.context)
        sm.getActiveSessions().forEachIndexed { i, s ->
            ctx.writeln(String.format("%5d     1 %s    10000  2000 Ss   linuxdroid-sh [${s.id}]",
                1000 + i, ctx.environment["USER"] ?: "user"))
        }
        // عمليات Android الحالية (من ActivityManager)
        try {
            val am = ctx.context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val procs = am.runningAppProcesses ?: emptyList()
            for (p in procs) {
                ctx.writeln(String.format("%5d     1 %s    %6d %5d Ss   %s",
                    p.pid, ctx.environment["USER"] ?: "user",
                    p.totalPss, p.totalPss, p.processName))
            }
        } catch (e: Exception) {
            ctx.writeErrln("ps: ${e.message}")
        }
        return 0
    }
}

/** kill — إنهاء عملية (تبسيط: فقط لجلسات LinuxDroid) */
class KillCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var signal = 15  // SIGTERM
        val pids = mutableListOf<Int>()
        for (arg in args) {
            when {
                arg.startsWith("-") && arg.length > 1 -> {
                    val s = arg.substring(1)
                    signal = when (s) { "9" -> 9; "KILL" -> 9; "TERM" -> 15; "HUP" -> 1; "INT" -> 2; "STOP" -> 19; "CONT" -> 18; else -> s.toIntOrNull() ?: 15 }
                }
                else -> arg.toIntOrNull()?.let { pids.add(it) }
            }
        }
        if (pids.isEmpty()) { ctx.writeErrln("kill: missing pid"); return 1 }
        for (pid in pids) {
            try {
                android.os.Process.killProcess(pid)
                ctx.writeln("kill: process $pid signaled ($signal)")
            } catch (e: Exception) {
                ctx.writeErrln("kill: ($pid) - ${e.message}")
            }
        }
        return 0
    }
}

/** top — عرض ديناميكي للعمليات (مرة واحدة فقط) */
class TopCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        ctx.writeln("top - ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} up ${UptimeCommand().getUptime(ctx)}")
        ctx.writeln("Tasks: ? total, ? running, ? sleeping")
        ctx.writeln("%Cpu(s): ?")
        ctx.writeln("")
        ctx.writeln("  PID USER      PR  NI    VIRT    RES  SHR S[%CPU] %MEM   TIME+ COMMAND")
        try {
            val am = ctx.context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val procs = am.runningAppProcesses ?: emptyList()
            for (p in procs) {
                ctx.writeln(String.format("%5d %s    20   0   100M   10M  5M S  0.0  1.0  0:00.00 %s",
                    p.pid, ctx.environment["USER"], p.processName))
            }
        } catch (e: Exception) {
            ctx.writeErrln("top: ${e.message}")
        }
        return 0
    }
}

/** whoami — اسم المستخدم */
class WhoamiCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        ctx.writeln(ctx.environment["USER"] ?: "user")
        return 0
    }
}

/** uname — معلومات النظام */
class UnameCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var showAll = false; var kernelName = false; var kernelRelease = false
        var kernelVersion = false; var machine = false; var processor = false
        var osName = false
        for (arg in args) {
            when (arg) {
                "-a" -> showAll = true
                "-s" -> kernelName = true
                "-r" -> kernelRelease = true
                "-v" -> kernelVersion = true
                "-m" -> machine = true
                "-p" -> processor = true
                "-o" -> osName = true
            }
        }
        if (args.isEmpty() || kernelName) {
            ctx.writeln("Linux")
            return 0
        }
        val sb = StringBuilder()
        if (showAll || kernelName) sb.append("Linux ")
        if (showAll || kernelRelease) sb.append("${android.os.Build.VERSION.RELEASE} ")
        if (showAll || kernelVersion) sb.append("#${android.os.Build.ID} SMP ${SimpleDateFormat("EEE MMM d HH:mm:ss", Locale.US).format(Date())} ")
        if (showAll || machine) sb.append("${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64"} ")
        if (showAll || processor) sb.append("${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64"} ")
        if (showAll || osName) sb.append("GNU/Linux ")
        ctx.writeln(sb.toString().trim())
        return 0
    }
}

/** date — التاريخ والوقت */
class DateCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        val format = if (args.size > 1 && args[0] == "+") args[1]
                     else "EEE MMM d HH:mm:ss z yyyy"
        val fmt = try { SimpleDateFormat(format, Locale.US) } catch (e: Exception) { SimpleDateFormat("EEE MMM d HH:mm:ss z yyyy", Locale.US) }
        ctx.writeln(fmt.format(Date()))
        return 0
    }
}

/** uptime — وقت التشغيل */
class UptimeCommand : CommandExecutor {
    fun getUptime(ctx: ShellContext): String {
        val am = ctx.context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val uptimeMs = android.os.SystemClock.elapsedRealtime()
        val hours = uptimeMs / 3600000
        val mins = (uptimeMs % 3600000) / 60000
        return "$hours:$mins"
    }
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        ctx.writeln(" ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} up ${getUptime(ctx)}, 1 user, load average: 0.00, 0.00, 0.00")
        return 0
    }
}

/** env — عرض متغيرات البيئة */
class EnvCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        if (args.isNotEmpty() && args[0] == "-i") {
            ctx.environment.clear()
            return 0
        }
        ctx.environment.forEach { (k, v) -> ctx.writeln("$k=$v") }
        return 0
    }
}

/** which — موقع الأمر */
class WhichCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        for (name in args) {
            if (com.linuxdroid.commands.CommandRegistry.has(name)) {
                ctx.writeln("${Environment.binDir.absolutePath}/$name")
            } else {
                ctx.writeErrln("which: no $name in (${ctx.environment["PATH"]})")
            }
        }
        return 0
    }
}

/** sleep — الانتظار لعدد ثوانٍ */
class SleepCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        for (arg in args) {
            val seconds = arg.toDoubleOrNull() ?: continue
            try { Thread.sleep((seconds * 1000).toLong()) } catch (_: InterruptedException) { return 130 }
        }
        return 0
    }
}

/** test / [ — تقييم تعبير (تبسيط) */
class TestCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        val actualArgs = if (args.lastOrNull() == "]") args.dropLast(1) else args
        if (actualArgs.isEmpty()) return 1
        if (actualArgs.size == 2) {
            val op = actualArgs[0]
            val val1 = actualArgs[1]
            return when (op) {
                "-z" -> if (val1.isEmpty()) 0 else 1
                "-n" -> if (val1.isNotEmpty()) 0 else 1
                "-e" -> if (File(if (val1.startsWith("/")) val1 else File(ctx.workingDirectory, val1).absolutePath).exists()) 0 else 1
                "-f" -> { val f = File(if (val1.startsWith("/")) val1 else File(ctx.workingDirectory, val1).absolutePath); if (f.exists() && f.isFile) 0 else 1 }
                "-d" -> { val f = File(if (val1.startsWith("/")) val1 else File(ctx.workingDirectory, val1).absolutePath); if (f.exists() && f.isDirectory) 0 else 1 }
                else -> 1
            }
        }
        if (actualArgs.size == 3) {
            val v1 = actualArgs[0]; val op = actualArgs[1]; val v2 = actualArgs[2]
            return when (op) {
                "=" -> if (v1 == v2) 0 else 1
                "==" -> if (v1 == v2) 0 else 1
                "!=" -> if (v1 != v2) 0 else 1
                "-eq" -> if (v1.toIntOrNull() == v2.toIntOrNull()) 0 else 1
                "-ne" -> if (v1.toIntOrNull() != v2.toIntOrNull()) 0 else 1
                "-lt" -> if ((v1.toIntOrNull() ?: 0) < (v2.toIntOrNull() ?: 0)) 0 else 1
                "-gt" -> if ((v1.toIntOrNull() ?: 0) > (v2.toIntOrNull() ?: 0)) 0 else 1
                "-le" -> if ((v1.toIntOrNull() ?: 0) <= (v2.toIntOrNull() ?: 0)) 0 else 1
                "-ge" -> if ((v1.toIntOrNull() ?: 0) >= (v2.toIntOrNull() ?: 0)) 0 else 1
                else -> 1
            }
        }
        return 1
    }
}

/** id — هوية المستخدم */
class IdCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        val u = ctx.environment["USER"] ?: "user"
        ctx.writeln("uid=1000($u) gid=1000($u) groups=1000($u),10(wheel)")
        return 0
    }
}

/** hostname — اسم المضيف */
class HostnameCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        if (args.isNotEmpty() && !args[0].startsWith("-")) {
            ctx.environment["HOSTNAME"] = args[0]
        }
        ctx.writeln(ctx.environment["HOSTNAME"] ?: "linuxdroid")
        return 0
    }
}

/** tty — معرّف الطرفية */
class TtyCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        ctx.writeln("/dev/pts/${ctx.sessionId.hashCode() and 0xFFFF}")
        return 0
    }
}

/** clear — مسح الشاشة */
class ClearCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        ctx.write("\u001B[2J\u001B[H")
        return 0
    }
}

/** reset — إعادة ضبط الطرفية */
class ResetCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        ctx.write("\u001Bc\u001B[2J\u001B[H")
        return 0
    }
}

/** man — صفحة دليل (تبسيط: عرض help()) */
class ManCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        if (args.isEmpty()) { ctx.writeErrln("man: what manual page do you want?"); return 1 }
        val cmd = com.linuxdroid.commands.CommandRegistry.getCommand(args[0])
        if (cmd == null) { ctx.writeErrln("man: no manual entry for '${args[0]}'"); return 1 }
        ctx.writeln("NAME")
        ctx.writeln("    ${args[0]} — LinuxDroid built-in command")
        ctx.writeln("")
        ctx.writeln("SYNOPSIS")
        ctx.writeln("    ${cmd.help().lines().firstOrNull() ?: args[0]}")
        ctx.writeln("")
        ctx.writeln("DESCRIPTION")
        ctx.writeln("    ${cmd.help()}")
        return 0
    }
}

/** seq — توليد سلسلة أرقام */
class SeqCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        when (args.size) {
            1 -> { val n = args[0].toIntOrNull() ?: return 1; for (i in 1..n) ctx.writeln(i.toString()) }
            2 -> { val s = args[0].toIntOrNull() ?: return 1; val e = args[1].toIntOrNull() ?: return 1; for (i in s..e) ctx.writeln(i.toString()) }
            3 -> { val s = args[0].toIntOrNull() ?: return 1; val step = args[1].toIntOrNull() ?: return 1; val e = args[2].toIntOrNull() ?: return 1; var i = s; while (i <= e) { ctx.writeln(i.toString()); i += step } }
            else -> { ctx.writeErrln("seq: invalid arguments"); return 1 }
        }
        return 0
    }
}

/** yes — طباعة سطر متكرر */
class YesCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        val s = if (args.isEmpty()) "y" else args.joinToString(" ")
        // نطبع كمية محدودة لتجنب الحلقات اللانهائية
        repeat(1000) { ctx.writeln(s) }
        return 0
    }
}

/** cal — تقويم */
class CalCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        val cal = java.util.Calendar.getInstance()
        val month = cal.get(java.util.Calendar.MONTH)
        val year = cal.get(java.util.Calendar.YEAR)
        val monthName = SimpleDateFormat("MMMM", Locale.US).format(cal.time)
        ctx.writeln("    $monthName $year")
        ctx.writeln("Su Mo Tu We Th Fr Sa")
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        val firstDay = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1
        val daysInMonth = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        var day = 1
        for (w in 0..5) {
            val sb = StringBuilder()
            for (d in 0..5) {
                if (w == 0 && d < firstDay) sb.append("   ")
                else if (day <= daysInMonth) { sb.append(String.format("%2d ", day)); day++ }
                else sb.append("   ")
            }
            if (w == 0 && firstDay == 6) { /* Sunday-only first row */ }
            if (day <= daysInMonth || w == 0) ctx.writeln(sb.toString().trimEnd())
            else break
        }
        return 0
    }
}

/** free — الذاكرة الحرة */
class FreeCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        val am = ctx.context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val total = mi.totalMem
        val free = mi.availMem
        val used = total - free
        ctx.writeln("              total        used        free      shared  buff/cache   available")
        ctx.writeln(String.format("Mem:    %10d %10d %10d %10d %10d %10d",
            total/1024, used/1024, free/1024, 0, 0, free/1024))
        ctx.writeln(String.format("Swap:   %10d %10d %10d", 0, 0, 0))
        return 0
    }
}

/** mount — نقاط التحميل (تبسيط) */
class MountCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        ctx.writeln("rootfs on / type rootfs (rw)")
        ctx.writeln("proc on /proc type proc (rw)")
        ctx.writeln("sysfs on /sys type sysfs (rw)")
        ctx.writeln("/dev/fuse on ${Environment.homeDir.absolutePath}/storage type fuse (rw,nosuid,nodev)")
        return 0
    }
}

/** dmesg — رسائل النواة (تبسيط: رسائل LinuxDroid) */
class DmesgCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        val logFile = File(ctx.context.filesDir, "logs/linuxdroid.log")
        if (logFile.exists()) {
            logFile.readLines().takeLast(50).forEach { ctx.writeln(it) }
        } else {
            ctx.writeln("[LinuxDroid] kernel log not available")
        }
        return 0
    }
}
