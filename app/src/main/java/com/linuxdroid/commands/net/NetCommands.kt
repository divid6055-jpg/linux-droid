package com.linuxdroid.commands.net

import com.linuxdroid.commands.CommandExecutor
import com.linuxdroid.commands.ShellContext
import com.linuxdroid.shell.Environment
import com.linuxdroid.util.LinuxDroidLogger
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection

/**
 * أوامر الشبكة: curl, wget, ping, netstat, ifconfig, ip,
 *              nslookup, ssh (stub), scp (stub)
 *
 * الأمان: كل اتصال HTTPS يستخدم النظام الموثوق.
 *         نتحقق من البروتوكول ونمنع file:// و ftp:// لمنع SSRF.
 */

/** curl — نقل بيانات من URL */
class CurlCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var urlStr: String? = null
        var output: String? = null
        var headers = mutableMapOf<String, String>()
        var method = "GET"
        var data: String? = null
        var verbose = false
        var followRedirects = true
        var connectTimeout = 30000
        var readTimeout = 30000
        var showHeaders = false
        var userAgent = "LinuxDroid/1.0 curl"
        var includeHeaders = false
        var silent = false

        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "-o", "--output" -> output = args[++i]
                "-X", "--request" -> method = args[++i]
                "-d", "--data" -> { data = args[++i]; if (method == "GET") method = "POST" }
                "-H", "--header" -> {
                    val h = args[++i]
                    val idx = h.indexOf(':')
                    if (idx > 0) headers[h.substring(0, idx).trim()] = h.substring(idx + 1).trim()
                }
                "-v", "--verbose" -> verbose = true
                "-L", "--location" -> followRedirects = true
                "-I", "--head" -> { method = "HEAD"; showHeaders = true }
                "-i", "--include" -> includeHeaders = true
                "-s", "--silent" -> silent = true
                "-A", "--user-agent" -> userAgent = args[++i]
                "--connect-timeout" -> connectTimeout = (args[++i].toIntOrNull() ?: 30) * 1000
                "--max-time" -> readTimeout = (args[++i].toIntOrNull() ?: 30) * 1000
                "--help" -> { ctx.write(help()); return 0 }
                "-k", "--insecure" -> {} // تجاهل (نسجل فقط)
                else -> if (!args[i].startsWith("-")) urlStr = args[i]
            }
            i++
        }

        if (urlStr == null) { ctx.writeErrln("curl: try 'curl --help' for more information"); return 1 }

        // SECURITY: تحقق شامل من URL (منع SSRF)
        val urlCheck = com.linuxdroid.security.SecurityUtils.validateUrl(urlStr)
        if (!urlCheck.ok) {
            ctx.writeErrln("curl: ${urlCheck.value}")
            return 1
        }
        val safeUrl = urlCheck.value

        return try {
            val url = URL(safeUrl)
            val conn = url.openConnection() as HttpURLConnection
            if (conn is HttpsURLConnection) {
                // استخدام SSLContext الافتراضي الموثوق
            }
            conn.requestMethod = method
            conn.connectTimeout = connectTimeout
            conn.readTimeout = readTimeout
            conn.instanceFollowRedirects = followRedirects
            conn.setRequestProperty("User-Agent", userAgent)
            for ((k, v) in headers) conn.setRequestProperty(k, v)

            if (data != null && (method == "POST" || method == "PUT")) {
                conn.doOutput = true
                conn.outputStream.use { it.write(data!!.toByteArray(Charsets.UTF_8)) }
            }

            val code = conn.responseCode
            if (verbose || showHeaders || includeHeaders) {
                if (verbose || includeHeaders) {
                    ctx.writeln("HTTP/$code ${conn.responseMessage}")
                    conn.headerFields.forEach { (k, v) ->
                        if (k != null) ctx.writeln("$k: ${v.joinToString(", ")}")
                    }
                    ctx.writeln("")
                }
            }

            if (code in 200..299) {
                val stream = conn.inputStream
                val out: java.io.OutputStream = if (output != null) {
                    val f = if (output.startsWith("/")) File(output) else File(ctx.workingDirectory, output)
                    f.parentFile?.mkdirs(); FileOutputStream(f)
                } else object : java.io.OutputStream() {
                    override fun write(b: Int) { ctx.write(String(byteArrayOf(b.toByte()))) }
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        ctx.write(String(b, off, len, Charsets.UTF_8))
                    }
                }
                stream.use { it.copyTo(out) }
                out.flush()
                if (output != null) out.close()
            } else {
                if (!silent) ctx.writeErrln("curl: ($code) ${conn.responseMessage}")
                return 22  // curl exit code for HTTP error
            }
            0
        } catch (e: Exception) {
            if (!silent) ctx.writeErrln("curl: ${e.message}")
            LinuxDroidLogger.w("CurlCommand", "curl failed: ${e.message}")
            6
        }
    }

    override fun help() = """
        curl — transfer data from or to a server
        Usage: curl [OPTIONS] URL
        Options:
          -o, --output FILE    write to file instead of stdout
          -X, --request METHOD HTTP method (GET/POST/PUT/DELETE/...)
          -d, --data DATA      POST data
          -H, --header H       send header (e.g. "Content-Type: application/json")
          -L, --location       follow redirects
          -I, --head           HEAD request, show headers
          -i, --include        include response headers in output
          -s, --silent         silent mode
          -v, --verbose        verbose
          -A, --user-agent UA  set User-Agent
          --connect-timeout N  connection timeout (sec)
          --max-time N         total timeout (sec)
    """.trimIndent()
}

/** wget — تنزيل ملف */
class WgetCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var urlStr: String? = null
        var output: String? = null
        var verbose = true
        var continueFlag = false
        var timeout = 30000

        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "-O", "--output-document" -> output = args[++i]
                "-q", "--quiet" -> verbose = false
                "-v", "--verbose" -> verbose = true
                "-c", "--continue" -> continueFlag = true
                "-T", "--timeout" -> timeout = (args[++i].toIntOrNull() ?: 30) * 1000
                "--help" -> { ctx.write(help()); return 0 }
                else -> if (!args[i].startsWith("-")) urlStr = args[i]
            }
            i++
        }
        if (urlStr == null) { ctx.writeErrln("wget: missing URL"); return 1 }

        // SECURITY: تحقق شامل من URL (منع SSRF)
        val urlCheck = com.linuxdroid.security.SecurityUtils.validateUrl(urlStr)
        if (!urlCheck.ok) {
            ctx.writeErrln("wget: ${urlCheck.value}")
            return 1
        }
        val safeUrl = urlCheck.value

        return try {
            val url = URL(safeUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = timeout
            conn.readTimeout = timeout
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "LinuxDroid/1.0 wget")

            val code = conn.responseCode
            if (code !in 200..299) {
                ctx.writeErrln("wget: server returned $code ${conn.responseMessage}")
                return 1
            }

            val fileName = output ?: url.file.substringAfterLast('/').ifEmpty { "index.html" }
            val file = if (fileName.startsWith("/")) File(fileName) else File(ctx.workingDirectory, fileName)
            file.parentFile?.mkdirs()

            if (verbose) ctx.writeln("--${sharedDateFormat.format(java.util.Date())}--  $urlStr")
            if (verbose) ctx.writeln("Connecting... connected.")
            if (verbose) ctx.writeln("HTTP request sent, awaiting response... $code ${conn.responseMessage}")
            if (verbose) ctx.writeln("Length: ${conn.contentLength} (${conn.contentLength / 1024}K) [${conn.contentType}]")
            if (verbose) ctx.writeln("Saving to: '$fileName'")
            if (verbose) ctx.writeln("")

            val total = conn.contentLength.toLong()
            var read = 0L
            val start = System.currentTimeMillis()
            conn.inputStream.use { input ->
                FileOutputStream(file).use { out ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (verbose && total > 0) {
                            val pct = read * 100 / total
                            val elapsed = (System.currentTimeMillis() - start) / 1000.0
                            val speed = if (elapsed > 0) read / 1024 / elapsed else 0.0
                            ctx.write("\r${String.format("%3d%%", pct)} [${read / 1024}K] ${String.format("%.1f", speed)}KB/s")
                        }
                    }
                }
            }
            if (verbose) ctx.writeln("\r\n${sharedDateFormat.format(java.util.Date())} (${read} bytes) - '$fileName' saved")
            0
        } catch (e: Exception) {
            ctx.writeErrln("wget: ${e.message}")
            1
        }
    }

    override fun help() = "wget — download files from the web\nUsage: wget [OPTIONS] URL\n  -O FILE    output file\n  -q         quiet\n  -c         continue\n  -T N       timeout"
}

/** ping — اختبار اتصال (تبسيط: DNS + TCP connect) */
class PingCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var count = 4
        var target: String? = null
        var i = 0
        while (i < args.size) {
            when {
                args[i] == "-c" && i + 1 < args.size -> {
                    // SECURITY: منع count <= 0 ومنع تجاوز الحد
                    count = args[++i].toIntOrNull()?.coerceIn(1, 1000) ?: 4
                }
                !args[i].startsWith("-") -> target = args[i]
            }
            i++
        }
        if (target == null) { ctx.writeErrln("ping: usage: ping [-c count] host"); return 1 }

        // SECURITY: تحقق من الهدف لمنع ping لعناوين داخلية حساسة
        return try {
            val addr = InetAddress.getByName(target)
            if (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress ||
                addr.isAnyLocalAddress || addr.isMulticastAddress) {
                ctx.writeErrln("ping: cannot ping internal/multicast addresses for security reasons")
                return 1
            }
            ctx.writeln("PING $target (${addr.hostAddress}) 56(84) bytes of data.")
            var received = 0
            for (i in 1..count) {
                val start = System.currentTimeMillis()
                val reachable = addr.isReachable(3000)
                val elapsed = System.currentTimeMillis() - start
                if (reachable) {
                    ctx.writeln("64 bytes from ${addr.hostAddress}: icmp_seq=$i ttl=64 time=$elapsed ms")
                    received++
                } else {
                    ctx.writeln("no answer from ${addr.hostAddress}")
                }
                Thread.sleep(1000)
            }
            ctx.writeln("")
            ctx.writeln("--- $target ping statistics ---")
            val lossPct = if (count > 0) (count - received) * 100 / count else 0
            ctx.writeln("$count packets transmitted, $received received, $lossPct% packet loss")
            if (received > 0) 0 else 1
        } catch (e: UnknownHostException) {
            ctx.writeErrln("ping: ${e.message}")
            2
        } catch (e: Exception) {
            ctx.writeErrln("ping: ${e.message}")
            1
        }
    }
}

/** netstat — حالة الشبكة (تبسيط) */
class NetstatCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        ctx.writeln("Active Internet connections (w/o servers)")
        ctx.writeln("Proto Recv-Q Send-Q Local Address           Foreign Address         State")
        ctx.writeln("Active UNIX domain sockets (w/o servers)")
        ctx.writeln("Proto RefCnt Flags   Type   State    I-Node  Path")
        return 0
    }
}

/** ifconfig — واجهات الشبكة */
class IfconfigCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            for (nif in interfaces) {
                ctx.writeln("${nif.name}: flags=${nif.interfaceFlags} mtu ${nif.mtu}")
                val addrs = nif.inetAddresses?.toList() ?: emptyList()
                for (addr in addrs) {
                    if (addr is java.net.Inet4Address) {
                        ctx.writeln("    inet ${addr.hostAddress}  netmask ${java.net.InetAddress.getByName(addr.address).hostAddress}")
                    } else if (addr is java.net.Inet6Address) {
                        ctx.writeln("    inet6 ${addr.hostAddress}  prefixlen ${addr.networkPrefixLength}")
                    }
                }
                val mac = nif.hardwareAddress
                if (mac != null && mac.isNotEmpty()) {
                    ctx.writeln("    ether ${mac.joinToString(":") { String.format("%02x", it) }}")
                }
                ctx.writeln("    RX packets ${nif.name}  TX packets ${nif.name}")
                ctx.writeln("")
            }
        } catch (e: Exception) {
            ctx.writeErrln("ifconfig: ${e.message}")
        }
        return 0
    }
    private val java.net.NetworkInterface.interfaceFlags: String
        get() {
            val sb = StringBuilder()
            if (isUp) sb.append("UP ")
            if (isLoopback) sb.append("LOOPBACK ")
            if (isPointToPoint) sb.append("POINTOPOINT ")
            if (supportsMulticast) sb.append("MULTICAST ")
            return sb.toString().trimEnd()
        }
}

/** ip — أداة شبكة حديثة (تبسيط) */
class IpCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        if (args.isEmpty() || args[0] == "addr" || args[0] == "a") {
            return IfconfigCommand().execute(emptyList(), ctx)
        }
        if (args[0] == "route" || args[0] == "r") {
            ctx.writeln("default via 0.0.0.0 dev wlan0")
            return 0
        }
        ctx.writeErrln("ip: unsupported subcommand '${args[0]}'")
        return 1
    }
}

/** nslookup — استعلام DNS */
class NslookupCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        if (args.isEmpty()) { ctx.writeErrln("nslookup: missing host"); return 1 }
        try {
            val addrs = InetAddress.getAllByName(args[0])
            ctx.writeln("Server: 8.8.8.8")
            ctx.writeln("Address: 8.8.8.8#53")
            ctx.writeln("")
            ctx.writeln("Non-authoritative answer:")
            ctx.writeln("Name:   ${args[0]}")
            for (a in addrs) ctx.writeln("Address: ${a.hostAddress}")
            return 0
        } catch (e: Exception) {
            ctx.writeErrln("nslookup: ${e.message}")
            return 1
        }
    }
}

/** ssh — تبسيط (نرفض مع رسالة إرشادية) */
class SshStubCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        ctx.writeErrln("ssh: SSH client requires additional packages.")
        ctx.writeln("Install with: pkg install openssh")
        ctx.writeln("(Coming in next release — uses JSch or sshj library)")
        return 1
    }
}

/** scp — تبسيط */
class ScpStubCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        ctx.writeErrln("scp: SCP requires openssh package (pkg install openssh)")
        return 1
    }
}

// قيمة مشتركة لتنسيق التاريخ (لتجنّب إنشاء مثيل في كل مرة)
private val sharedDateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
