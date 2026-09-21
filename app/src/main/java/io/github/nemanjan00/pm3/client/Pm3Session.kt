package io.github.nemanjan00.pm3.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.Writer

/**
 * A running Proxmark client, driven over the [pm3_rpc.lua] JSON-line protocol.
 *
 * The client is a REPL with no machine-readable mode, so commands are sent to
 * an in-client Lua shim that frames each one: output lines come through as
 * plain text, and a `{"type":"command_end"}` object marks completion. That
 * framing is what makes [execute] able to return a command's output rather
 * than guessing where it ended.
 *
 * The design follows nemanjan00/node-proxmark3's daemon: one child process,
 * one command in flight, the rest queued.
 */
class Pm3Session(
    private val runtime: Pm3Runtime,
    private val scope: CoroutineScope,
) {

    /** Every output line the client produces, for the console view. */
    private val _output = MutableSharedFlow<String>(
        replay = OUTPUT_REPLAY,
        extraBufferCapacity = OUTPUT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val output: SharedFlow<String> = _output

    private var process: Process? = null
    private var stdin: Writer? = null

    /** Serialises commands: the client handles exactly one at a time. */
    private val commandLock = Mutex()

    /** Completion of the command currently in flight. */
    @Volatile private var pending: CompletableDeferred<CommandResult>? = null

    /** Output lines accumulated for the command in flight. */
    private val collected = StringBuilder()

    private val started = CompletableDeferred<Unit>()

    val isAlive: Boolean get() = process?.isAlive == true

    data class CommandResult(val output: String, val ok: Boolean, val error: String?)

    /**
     * Starts the client against [address], e.g. "tcp:127.0.0.1:18888".
     *
     * Suspends until the shim reports itself started, which is also the point
     * at which the client has finished talking to the device -- so a failure to
     * reach the Proxmark surfaces here rather than on the first command.
     */
    @Throws(IOException::class)
    suspend fun start(address: String) {
        check(process == null) { "Session already started" }

        val builder = ProcessBuilder(
            runtime.binary.absolutePath,
            address,
            // Keep reading stdin after the -c script, so the shim owns the REPL.
            "-i",
            "-c", "script run $RPC_SCRIPT",
        ).apply {
            directory(runtime.home)
            environment().putAll(runtime.environment())
            // The client writes warnings to stderr that belong in the console
            // next to the output that provoked them.
            redirectErrorStream(true)
        }

        runtime.home.mkdirs()
        val p = builder.start()
        process = p
        stdin = p.outputStream.bufferedWriter()

        scope.launch(Dispatchers.IO) { pump(p.inputStream.bufferedReader()) }
        scope.launch(Dispatchers.IO) {
            val code = runCatching { p.waitFor() }.getOrNull()
            // Fail any in-flight command rather than leaving a caller hanging.
            pending?.complete(
                CommandResult(collected.toString(), ok = false, error = "client exited ($code)")
            )
            _output.emit("[!] proxmark3 client exited (code $code)")
        }

        withTimeout(START_TIMEOUT_MS) { started.await() }
    }

    private suspend fun pump(reader: BufferedReader) {
        reader.useLines { lines ->
            for (line in lines) {
                val control = parseControl(line)
                if (control == null) {
                    _output.emit(line)
                    // Only accumulate while a command is in flight; unsolicited
                    // output (device notifications) is display-only.
                    if (pending != null) collected.appendLine(line)
                    continue
                }
                when (control.optString("type")) {
                    "started" -> started.complete(Unit)
                    "command_end" -> {
                        val result = CommandResult(
                            output = collected.toString().trimEnd(),
                            ok = control.optBoolean("ok", true),
                            error = control.optString("error").ifEmpty { null },
                        )
                        collected.setLength(0)
                        pending?.complete(result)
                        pending = null
                    }
                    "error" -> _output.emit("[!] rpc: ${control.optString("error")}")
                }
            }
        }
    }

    /**
     * A control object, or null if the line is ordinary command output.
     *
     * Command output can legitimately look like JSON (`hf mf` dumps, `data
     * load`), so a line only counts as control if it parses *and* carries one
     * of the shim's own type tags.
     */
    private fun parseControl(line: String): JSONObject? {
        if (!line.startsWith("{")) return null
        val obj = runCatching { JSONObject(line) }.getOrNull() ?: return null
        return if (obj.optString("type") in CONTROL_TYPES) obj else null
    }

    /** Runs [command] and returns everything it printed. */
    suspend fun execute(command: String, timeoutMs: Long = COMMAND_TIMEOUT_MS): CommandResult =
        commandLock.withLock {
            val writer = stdin ?: throw IllegalStateException("Session not started")
            val deferred = CompletableDeferred<CommandResult>()
            collected.setLength(0)
            pending = deferred

            val request = JSONObject()
                .put("type", "command")
                .put("command", command)

            _output.emit("$PROMPT $command")
            try {
                writer.write(request.toString())
                writer.write("\n")
                writer.flush()
            } catch (e: IOException) {
                pending = null
                throw IOException("Client is not accepting commands", e)
            }

            try {
                withTimeout(timeoutMs) { deferred.await() }
            } finally {
                pending = null
            }
        }

    fun stop() {
        runCatching {
            stdin?.write(JSONObject().put("type", "exit").toString() + "\n")
            stdin?.flush()
        }
        runCatching { stdin?.close() }
        // Give the client a moment to unwind its device session cleanly; it
        // resets the Proxmark on the way out.
        runCatching { process?.waitFor(EXIT_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
        runCatching { process?.destroy() }
        process = null
        stdin = null
    }

    companion object {
        private const val RPC_SCRIPT = "pm3_rpc"
        /**
         * Neutral on purpose.
         *
         * This is the app's own echo of what it sent, not the client's
         * prompt. It used to read "[usb] pm3 -->", which is a claim about the
         * transport and the hardware -- and a wrong one on, say, a PM5 over
         * BLE, where the client's real prompt is "[fpc|tcp|script] pm5 -->".
         * Better to say nothing about the link than to say something false.
         */
        private const val PROMPT = "pm3 -->"

        private val CONTROL_TYPES = setOf("started", "command_end", "error")

        private const val OUTPUT_REPLAY = 500
        private const val OUTPUT_BUFFER = 2000

        private const val START_TIMEOUT_MS = 30_000L

        /**
         * Generous: some commands legitimately run for minutes (hardnested,
         * dictionary attacks, `lf search` on a slow tag).
         */
        private const val COMMAND_TIMEOUT_MS = 10 * 60_000L

        private const val EXIT_GRACE_MS = 2000L
    }
}

