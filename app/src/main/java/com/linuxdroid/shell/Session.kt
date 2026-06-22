package com.linuxdroid.shell

import android.content.Context
import com.linuxdroid.app.LinuxDroidApp
import com.linuxdroid.commands.CommandRegistry
import com.linuxdroid.util.LinuxDroidLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * جلسة طرفية واحدة — تمثل مثيل shell نشط.
 *
 * المكونات:
 * 1. [ShellInterpreter] — يحلل الأوامر وينفذها
 * 2. قناة إدخال (من المستخدم → shell)
 * 3. قناة إخراج (من shell → المستخدم)
 * 4. خيط تنفيذ منفصل ( coroutine context) لمنع حظر الواجهة
 *
 * تدفق البيانات:
 *   المستخدم → write() → inputPipe → ShellInterpreter.read()
 *   ShellInterpreter.write() → outputPipe → onOutput callback → TerminalView
 *
 * الخيوط: نستخدم Dispatchers.Default للتنفيذ IO + خيط منفصل للقراءة.
 */
class Session(
    val id: String,
    val context: Context,
    initialCols: Int,
    initialRows: Int
) {
    var onOutput: ((String) -> Unit)? = null
    var onExit: (() -> Unit)? = null

    private val outputPipe = PipedOutputStream()
    private val inputPipe = PipedInputStream(8192)
    private val scope = CoroutineScope(SupervisorJob() + newSingleThreadContext("session-$id"))
    private var shellJob: Job? = null
    private val running = AtomicBoolean(false)
    private var paused = AtomicBoolean(false)

    var columns: Int = initialCols
        private set
    var rows: Int = initialRows
        private set

    lateinit var shell: ShellInterpreter
        private set

    fun start() {
        if (running.getAndSet(true)) return
        LinuxDroidLogger.i(TAG, "Starting session $id (cols=$columns, rows=$rows)")

        shellJob = scope.launch {
            try {
                // ربط المدخلات: ShellInterpreter يقرأ من inputPipe
                // ربط المخرجات: ShellInterpreter يكتب إلى outputPipe → نحوّلها إلى onOutput

                // سلسلة إخراج: نستخدم خيط لقراءة outputPipe وتحويلها إلى callback
                val outputReader = PipedInputStream(outputPipe, 8192)

                launch {
                    val buffer = ByteArray(8192)
                    while (running.get()) {
                        val n = outputReader.read(buffer)
                        if (n > 0) {
                            val data = String(buffer, 0, n, Charsets.UTF_8)
                            onOutput?.invoke(data)
                        } else if (n == -1) break
                    }
                }

                // إنشاء المفسّر
                shell = ShellInterpreter(
                    inputStream = inputPipe,
                    outputStream = outputPipe,
                    columns = columns,
                    rows = rows,
                    context = context,
                    sessionId = id
                )

                shell.start()

                // انتظر انتهاء الـ shell
                while (running.get() && shell.isRunning()) {
                    Thread.sleep(100)
                }
            } catch (e: Exception) {
                LinuxDroidLogger.e(TAG, "Session $id crashed", e)
                onOutput?.invoke("\r\n\u001B[31mSession error: ${e.message}\u001B[0m\r\n")
            } finally {
                running.set(false)
                onExit?.invoke()
                LinuxDroidLogger.i(TAG, "Session $id ended")
            }
        }
    }

    /** كتابة إدخال إلى الجلسة (يستقبلها الـ shell) */
    fun write(data: String) {
        if (!running.get() || paused.get()) return
        try {
            val bytes = data.toByteArray(Charsets.UTF_8)
            // منع الحظر: نكتب في خيط منفصل
            scope.launch {
                try {
                    inputPipe.write(bytes)
                    inputPipe.flush()
                } catch (e: Exception) {
                    LinuxDroidLogger.w(TAG, "Failed to write to session: ${e.message}")
                }
            }
        } catch (e: Exception) {
            LinuxDroidLogger.w(TAG, "write() failed: ${e.message}")
        }
    }

    /** إرسال مخرجات مباشرة (مثل رسالة الترحيب) */
    fun emitOutput(data: String) {
        onOutput?.invoke(data)
    }

    fun resize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        columns = cols
        rows = rows
        if (::shell.isInitialized) {
            shell.resize(cols, rows)
        }
    }

    fun pause() {
        paused.set(true)
    }

    fun resume() {
        paused.set(false)
    }

    fun stop() {
        running.set(false)
        try { ::shell.get().let { if (it.isRunning()) it.stop() } } catch (_: UninitializedPropertyAccessException) {}
        try { inputPipe.close() } catch (_: Exception) {}
        try { outputPipe.close() } catch (_: Exception) {}
        scope.cancel()
    }

    fun isRunning(): Boolean = running.get()

    companion object { private const val TAG = "Session" }
}

/**
 * مدير الجلسات — يحتفظ بجلسات متعددة (تبسيط: جلسة واحدة نشطة حالياً)
 *
 * SECURITY/THREAD-SAFETY: نستخدم ConcurrentHashMap و AtomicInteger
 * لمنع التضارب عند إنشاء/إغلاق الجلسات من خيوط متعددة.
 */
class SessionManager private constructor(private val context: Context) {

    private val sessions = java.util.concurrent.ConcurrentHashMap<String, Session>()
    private val sessionCounter = java.util.concurrent.atomic.AtomicInteger(0)

    fun createSession(cols: Int, rows: Int): Session {
        val id = "session-${sessionCounter.incrementAndGet()}"
        val session = Session(id, context, cols, rows)
        // putIfAbsent لمنع التصادم النظري
        sessions.putIfAbsent(id, session)
        return session
    }

    fun getSession(id: String): Session? = sessions[id]
    fun getActiveSessions(): List<Session> = sessions.values.filter { it.isRunning() }

    fun closeSession(id: String) {
        sessions.remove(id)?.stop()
    }

    fun closeAll() {
        sessions.values.forEach { it.stop() }
        sessions.clear()
    }

    companion object {
        @Volatile private var instance: SessionManager? = null
        fun getInstance(context: Context): SessionManager =
            instance ?: synchronized(this) {
                instance ?: SessionManager(context.applicationContext).also { instance = it }
            }
    }
}
