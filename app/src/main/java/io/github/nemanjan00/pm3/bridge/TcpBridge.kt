package io.github.nemanjan00.pm3.bridge

import io.github.nemanjan00.pm3.transport.Transport
import io.github.nemanjan00.pm3.transport.TransportException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Pumps bytes between a localhost TCP socket and a [Transport].
 *
 * This is the whole reason the app can ship a stock Proxmark client: the client
 * already speaks `tcp:host:port` natively, so rather than teaching it about
 * Android USB or BLE we terminate the transport here and let it connect to
 * 127.0.0.1. The same socket serves the app's own console, the flasher, and a
 * `proxmark3` running in Termux.
 *
 * Bound to loopback only -- the Proxmark protocol is unauthenticated, and
 * binding the wildcard address would expose the reader to the local network.
 */
class TcpBridge(
    private val transport: Transport,
    private val port: Int = DEFAULT_PORT,
    private val onEvent: (Event) -> Unit = {},
) {

    sealed interface Event {
        data class Listening(val port: Int) : Event
        data class ClientConnected(val peer: String) : Event
        data object ClientDisconnected : Event
        data class Error(val message: String, val cause: Throwable?) : Event
        data object Stopped : Event
    }

    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null

    val bytesToDevice = AtomicLong(0)
    val bytesFromDevice = AtomicLong(0)

    val isRunning: Boolean get() = running.get()

    /** The port actually bound, which differs from [port] if that was 0. */
    var boundPort: Int = -1
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return

        val s = try {
            // Backlog 1: one client at a time. Two clients sharing one framed
            // binary link would interleave frames and corrupt both sessions.
            ServerSocket(port, 1, InetAddress.getByName(LOOPBACK))
        } catch (e: IOException) {
            running.set(false)
            onEvent(Event.Error("Could not bind $LOOPBACK:$port", e))
            throw e
        }
        server = s
        boundPort = s.localPort
        onEvent(Event.Listening(boundPort))

        acceptThread = thread(name = "pm3-bridge-accept") {
            // A bare thread's uncaught exception reaches Android's default
            // handler, which kills the process. Nothing that goes wrong on a
            // bridge thread justifies taking the app down, so everything is
            // funnelled into an event instead.
            try {
                while (running.get()) {
                    val client = try {
                        s.accept()
                    } catch (e: IOException) {
                        if (running.get()) onEvent(Event.Error("accept() failed", e))
                        break
                    }
                    try {
                        serve(client)
                    } catch (e: Throwable) {
                        onEvent(Event.Error("Bridge session failed", e))
                        runCatching { client.close() }
                    }
                }
            } catch (e: Throwable) {
                onEvent(Event.Error("Bridge accept loop failed", e))
            } finally {
                onEvent(Event.Stopped)
            }
        }
    }

    private fun serve(client: Socket) {
        onEvent(Event.ClientConnected(client.remoteSocketAddress.toString()))
        // Nagle would coalesce small command frames and add latency to every
        // round trip; the Proxmark protocol is request/response and latency
        // sensitive, so send each write immediately.
        runCatching { client.tcpNoDelay = true }

        val clientAlive = AtomicBoolean(true)

        // Device -> socket. Runs on its own thread so neither direction can
        // starve the other on a busy link (e.g. a trace dump).
        val fromDevice = thread(name = "pm3-bridge-rx") {
            val buffer = ByteArray(BUFFER_BYTES)
            try {
                val out = client.getOutputStream()
                while (running.get() && clientAlive.get()) {
                    val n = transport.read(buffer, READ_TIMEOUT_MS)
                    if (n > 0) {
                        out.write(buffer, 0, n)
                        out.flush()
                        bytesFromDevice.addAndGet(n.toLong())
                    } else if (!transport.isConnected()) {
                        break
                    }
                }
            } catch (e: Throwable) {
                // Throwable, not Exception: an Error here would otherwise
                // escape the thread and kill the process.
                if (running.get() && clientAlive.get()) {
                    onEvent(Event.Error("Device read failed", e))
                }
            } finally {
                clientAlive.set(false)
                runCatching { client.close() }
            }
        }

        // Socket -> device, on this thread.
        val buffer = ByteArray(BUFFER_BYTES)
        try {
            val input = client.getInputStream()
            while (running.get() && clientAlive.get()) {
                val n = input.read(buffer)
                if (n < 0) break // client closed
                if (n > 0) {
                    transport.write(buffer.copyOf(n))
                    bytesToDevice.addAndGet(n.toLong())
                }
            }
        } catch (e: TransportException) {
            onEvent(Event.Error("Device write failed", e))
        } catch (e: IOException) {
            // Ordinary client disconnect.
        } finally {
            clientAlive.set(false)
            runCatching { client.close() }
            fromDevice.join(THREAD_JOIN_MS)
            onEvent(Event.ClientDisconnected)
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { server?.close() }
        server = null
        acceptThread?.join(THREAD_JOIN_MS)
        acceptThread = null
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"

        /**
         * Upstream's client falls back to 18888 when a tcp: address carries no
         * port, so using it means `proxmark3 tcp:localhost` just works.
         */
        const val DEFAULT_PORT = 18888

        private const val BUFFER_BYTES = 8192

        /**
         * Short enough that a disappearing device is noticed promptly, long
         * enough not to spin the CPU on an idle link.
         */
        private const val READ_TIMEOUT_MS = 200

        private const val THREAD_JOIN_MS = 2000L
    }
}
