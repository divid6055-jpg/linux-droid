package com.linuxdroid.commands

import com.linuxdroid.commands.files.*
import com.linuxdroid.commands.text.*
import com.linuxdroid.commands.system.*
import com.linuxdroid.commands.net.*
import com.linuxdroid.util.LinuxDroidLogger

/**
 * سجل الأوامر المدمجة.
 *
 * المسؤولية: تسجيل كل أوامر LinuxDroid وتنفيذها بالاسم.
 *
 * الأوامر تنقسم إلى فئات:
 *  - files: ls, cd, pwd, cat, cp, mv, rm, mkdir, rmdir, touch, ln, find, stat, file
 *  - text:  grep, sed, awk, head, tail, wc, sort, uniq, cut, tr, tee, diff, paste
 *  - system: ps, kill, top, whoami, uname, date, uptime, env, which, sleep, history
 *  - net:   curl, wget, ping, netstat, ifconfig
 *  - pkg:   pkg, apt-like package manager
 *
 * كل أمر يطبّق [CommandExecutor] ويسجّل نفسه عند التحميل.
 */
object CommandRegistry {

    private val commands = mutableMapOf<String, CommandExecutor>()

    init {
        registerAll()
    }

    private fun registerAll() {
        // Files
        register("ls", LsCommand())
        register("ll", LsCommand().apply { setLongFormat(true) })
        register("dir", LsCommand())
        register("cat", CatCommand())
        register("cp", CpCommand())
        register("mv", MvCommand())
        register("rm", RmCommand())
        register("mkdir", MkdirCommand())
        register("rmdir", RmdirCommand())
        register("touch", TouchCommand())
        register("ln", LnCommand())
        register("find", FindCommand())
        register("stat", StatCommand())
        register("file", FileCommand())
        register("tree", TreeCommand())
        register("du", DuCommand())
        register("df", DfCommand())
        register("chmod", ChmodCommand())
        register("chown", ChownCommand())

        // Text processing
        register("echo", EchoCommand())
        register("grep", GrepCommand())
        register("egrep", GrepCommand().apply { extendedRegex = true })
        register("fgrep", GrepCommand().apply { fixedString = true })
        register("rgrep", GrepCommand().apply { recursive = true })
        register("sed", SedCommand())
        register("awk", AwkCommand())
        register("head", HeadCommand())
        register("tail", TailCommand())
        register("wc", WcCommand())
        register("sort", SortCommand())
        register("uniq", UniqCommand())
        register("cut", CutCommand())
        register("tr", TrCommand())
        register("tee", TeeCommand())
        register("diff", DiffCommand())
        register("paste", PasteCommand())
        register("rev", RevCommand())
        register("nl", NlCommand())
        register("tac", TacCommand())
        register("basename", BasenameCommand())
        register("dirname", DirnameCommand())
        register("realpath", RealpathCommand())
        register("xxd", XxdCommand())
        register("od", OdCommand())
        register("column", ColumnCommand())
        register("fold", FoldCommand())
        register("expand", ExpandCommand())
        register("unexpand", UnexpandCommand())

        // System
        register("ps", PsCommand())
        register("kill", KillCommand())
        register("top", TopCommand())
        register("whoami", WhoamiCommand())
        register("uname", UnameCommand())
        register("date", DateCommand())
        register("uptime", UptimeCommand())
        register("env", EnvCommand())
        register("printenv", EnvCommand())
        register("which", WhichCommand())
        register("sleep", SleepCommand())
        register("test", TestCommand())
        register("[", TestCommand())
        register("id", IdCommand())
        register("hostname", HostnameCommand())
        register("tty", TtyCommand())
        register("clear", ClearCommand())
        register("reset", ResetCommand())
        register("man", ManCommand())
        register("info", ManCommand())
        register("seq", SeqCommand())
        register("yes", YesCommand())
        register("cal", CalCommand())
        register("free", FreeCommand())
        register("mount", MountCommand())
        register("dmesg", DmesgCommand())

        // Network
        register("curl", CurlCommand())
        register("wget", WgetCommand())
        register("ping", PingCommand())
        register("netstat", NetstatCommand())
        register("ifconfig", IfconfigCommand())
        register("ip", IpCommand())
        register("nslookup", NslookupCommand())
        register("ssh", SshStubCommand())
        register("scp", ScpStubCommand())

        // Package manager
        register("pkg", PkgCommand())
        register("apt", PkgCommand().apply { mode = PkgCommand.APT_MODE })

        // Editor
        register("nano", NanoCommand())
        register("vi", ViCommand())
        register("vim", ViCommand())

        // Extended commands (printf, gzip, zip, base64, hashes, etc.)
        register("printf", com.linuxdroid.commands.system.PrintfCommand())
        register("gzip", com.linuxdroid.commands.system.GzipCommand())
        register("gunzip", com.linuxdroid.commands.system.GunzipCommand())
        register("zcat", com.linuxdroid.commands.system.GzipCommand().apply {})
        register("zip", com.linuxdroid.commands.system.ZipCommand())
        register("unzip", com.linuxdroid.commands.system.UnzipCommand())
        register("tar", com.linuxdroid.commands.system.TarCommand())
        register("base64", com.linuxdroid.commands.system.Base64Command())
        register("md5sum", com.linuxdroid.commands.system.Md5SumCommand())
        register("sha256sum", com.linuxdroid.commands.system.Sha256SumCommand())
        register("sha1sum", com.linuxdroid.commands.system.Sha256SumCommand())  // تبسيط
        register("time", com.linuxdroid.commands.system.TimeCommand())
        register("xargs", com.linuxdroid.commands.system.XargsCommand())
        register("timeout", com.linuxdroid.commands.system.TimeoutCommand())
        register("watch", com.linuxdroid.commands.system.WatchCommand())

        LinuxDroidLogger.i(TAG, "Registered ${commands.size} commands")
    }

    fun register(name: String, executor: CommandExecutor) {
        commands[name] = executor
    }

    fun has(name: String): Boolean = commands.containsKey(name)

    fun execute(name: String, args: List<String>, ctx: ShellContext): Int {
        val cmd = commands[name] ?: return 127
        return try {
            cmd.execute(args, ctx)
        } catch (e: Exception) {
            ctx.writeErrln("$name: ${e.message}")
            1
        }
    }

    fun listCommands(): List<String> = commands.keys.toList()

    fun getCommand(name: String): CommandExecutor? = commands[name]

    private const val TAG = "CommandRegistry"
}

/** واجهة كل أمر */
interface CommandExecutor {
    /** ينفّذ الأمر مع args، يعيد كود الخروج */
    fun execute(args: List<String>, ctx: ShellContext): Int

    /** نص المساعدة المختصرة */
    fun help(): String = ""
}
