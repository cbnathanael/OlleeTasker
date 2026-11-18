package com.naate.olleetasker

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

open class GattException(message: String) : Exception(message)
class GattTimeoutException(message: String) : GattException(message)

/**
 * The persistent BLE link to the watch. Lives for the whole process; [LinkService] keeps the process
 * alive while the link is wanted.
 *
 * GATT transactions (connect sequence, read, write) are serialized by [opLock], and run blocking on
 * the caller's thread. Callbacks from a closed [BluetoothGatt] are ignored, and a timed-out operation
 * drops the connection, so a late callback can never complete a newer operation.
 */
@SuppressLint("MissingPermission")
object OlleeLink {
    enum class State(val label: String, val description: String) {
        STOPPED("stopped", "Not running"),
        PAUSED("paused", "Paused while the Ollee app is in use"),
        WAITING("waiting", "Waiting for the watch"),
        CONNECTING("connecting", "Connecting…"),
        CONNECTED("connected", "Connected"),
    }

    fun interface Listener {
        fun onStateChanged(state: State)
    }

    const val PAUSED_MESSAGE = "The watch link is paused (the Ollee app is in use). Run the Resume action first."
    const val NOT_CONNECTED_MESSAGE = "The watch isn't connected and auto-reconnect is off. Run the Connect action first."
    private const val TAG = "OlleeLink"
    private const val CONNECT_TIMEOUT_MS = 20_000L
    private const val OP_TIMEOUT_MS = 10_000L
    private const val LOCK_TIMEOUT_MS = 40_000L
    /**
     * Notifications are 20-byte chunks; a full chunk may be followed by more of the same message. Long enough to
     * span a low-power connection's quiet period (~125 ms interval × 3 with slave latency 2).
     */
    private const val MESSAGE_IDLE_MS = 500L
    private val RETRY_DELAYS_MS = longArrayOf(1_000, 10_000, 30_000)

    /** Worst case for an action that has to wait for the link and connect first. */
    const val MAX_ACTION_MS = LOCK_TIMEOUT_MS + CONNECT_TIMEOUT_MS + 3 * OP_TIMEOUT_MS

    private lateinit var appContext: Context
    private lateinit var prefs: LinkPrefs

    private val reconnectExecutor = Executors.newSingleThreadScheduledExecutor { Thread(it, "ollee-reconnect") }
    private val messageExecutor = Executors.newSingleThreadScheduledExecutor { Thread(it, "ollee-messages") }
    private val opLock = ReentrantLock()
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val pending = AtomicReference<Pending?>()
    private val failures = AtomicInteger()

    @Volatile private var lastConnectError: String? = null

    @Volatile var state = State.STOPPED
        private set
    @Volatile var address: String? = null
        private set

    // Guarded by opLock.
    private var gatt: BluetoothGatt? = null
    @Volatile private var callback: Callback? = null
    @Volatile private var rx: BluetoothGattCharacteristic? = null
    @Volatile private var tx: BluetoothGattCharacteristic? = null

    private var retryTask: Future<*>? = null // guarded by `this`

    // Only touched on messageExecutor.
    private val messageBuffer = ByteArrayOutputStream()
    private var flushTask: ScheduledFuture<*>? = null

    @Synchronized
    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        prefs = LinkPrefs(appContext)
        address = prefs.address
    }

    fun addListener(listener: Listener) = listeners.add(listener)
    fun removeListener(listener: Listener) = listeners.remove(listener)

    // ---- Link control ----

    /** Starts (or switches) the persistent link to [newAddress]. Doesn't wait for the connection. */
    fun start(context: Context, newAddress: String) {
        init(context)
        if (prefs.paused) throw GattException(PAUSED_MESSAGE)
        if (!Perms.canConnect(appContext)) throw GattException(NO_PERMISSION)
        prefs.address = newAddress
        prefs.enabled = true
        ensureService()
        if (newAddress != address) {
            interruptPending("Switching to another watch")
            withLock {
                closeGattLocked()
                address = newAddress
                failures.set(0)
                setState(State.WAITING, "switched")
            }
        } else if (state == State.STOPPED || state == State.PAUSED) {
            setState(State.WAITING, null)
        }
        if (state != State.CONNECTED) kick()
    }

    /** Called by [LinkService] when it (re)starts, to pick up where the saved settings left off. */
    fun restore(context: Context) {
        init(context)
        reconnectExecutor.execute {
            if (!prefs.enabled || address == null) return@execute
            if (!prefs.autoReconnect && state != State.CONNECTED && !prefs.paused) {
                // The process was restarted while the link was up; with auto-reconnect off, that counts as a drop.
                stop(appContext)
            } else if (prefs.paused) {
                setState(State.PAUSED, "paused")
            } else {
                if (state == State.STOPPED || state == State.PAUSED) setState(State.WAITING, null)
                kick()
            }
        }
    }

    /** Disconnects and stops the link until the next Connect. */
    fun stop(context: Context) {
        init(context)
        prefs.enabled = false
        cancelRetry()
        interruptPending("Link stopped")
        withLock {
            closeGattLocked()
            setState(State.STOPPED, "stopped")
        }
        appContext.stopService(Intent(appContext, LinkService::class.java))
    }

    /** Releases the watch (e.g. while the Ollee app is open). Persists until [resume]. */
    fun pause(context: Context) {
        init(context)
        prefs.paused = true
        cancelRetry()
        interruptPending("Link paused")
        withLock {
            closeGattLocked()
            setState(if (prefs.enabled) State.PAUSED else State.STOPPED, "paused")
        }
    }

    fun resume(context: Context) {
        init(context)
        prefs.paused = false
        if (!prefs.enabled) return
        address = prefs.address ?: return
        ensureService()
        if (state == State.PAUSED || state == State.STOPPED) setState(State.WAITING, null)
        failures.set(0)
        kick()
    }

    /**
     * Turns auto-reconnect on or off. Turning it off while the link is waiting to reconnect stops the link.
     * Blocking; call from a background thread.
     */
    fun setAutoReconnect(context: Context, on: Boolean) {
        init(context)
        prefs.autoReconnect = on
        if (on) return
        cancelRetry()
        val stopped = withLock {
            if (!prefs.enabled || prefs.paused || state == State.CONNECTED) return@withLock false
            stopAfterFailureLocked()
            true
        }
        if (stopped) appContext.stopService(Intent(appContext, LinkService::class.java))
    }

    /**
     * Turns the low-power connection request on or off, applying it to the live connection if there is one.
     * Blocking; call from a background thread.
     */
    fun setLowPower(context: Context, on: Boolean) {
        init(context)
        prefs.lowPower = on
        withLock {
            val g = gatt
            if (state == State.CONNECTED && g != null) requestPriorityLocked(g)
        }
    }

    fun onBluetoothOn() {
        if (!::appContext.isInitialized || !wanted()) return
        failures.set(0)
        kick()
    }

    /** Starts [LinkService] if it isn't running. Throws if Android doesn't allow it from the background. */
    fun ensureService() {
        if (LinkService.running) return
        try {
            appContext.startForegroundService(Intent(appContext, LinkService::class.java))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (Android 12+) is an IllegalStateException.
            throw GattException(
                "Android didn't allow the plugin to start its connection from the background. " +
                    "Open the Ollee Watch plugin app and allow it to ignore battery optimization.",
            )
        } catch (e: SecurityException) {
            throw GattException("Couldn't start the connection service: ${e.message}")
        }
    }

    // ---- Operations (blocking; call from a background thread) ----

    /** Connects (or switches) to [address] and waits for the connection. */
    fun connect(context: Context, address: String) = withReadyLink(context, address, connect = true) { }

    /**
     * Writes [chunks] to the watch. Connects first if needed, unless auto-reconnect is off, in which case the link
     * must already be connected to [address].
     */
    fun send(context: Context, address: String, chunks: List<ByteArray>) = withReadyLink(context, address, connect = false) { g ->
        val c = rx ?: throw GattException(NO_FORGE)
        chunks.forEach { writeLocked(g, c, it) }
    }

    private fun <T> withReadyLink(
        context: Context,
        address: String,
        connect: Boolean,
        block: (BluetoothGatt) -> T,
    ): T {
        init(context)
        if (connect || prefs.autoReconnect) {
            start(context, address)
        } else {
            if (prefs.paused) throw GattException(PAUSED_MESSAGE)
            if (state != State.CONNECTED) throw GattException(NOT_CONNECTED_MESSAGE)
            val current = this.address
            if (!address.equals(current, ignoreCase = true)) {
                throw GattException("Connected to $current, not $address. Run the Connect action to switch watches.")
            }
        }
        return withLock {
            if (prefs.paused) throw GattException(PAUSED_MESSAGE)
            if (state != State.CONNECTED) {
                // Auto-reconnect is off and the link dropped (or the connect attempt failed) in the meantime.
                if (!prefs.enabled || (!connect && !prefs.autoReconnect)) {
                    throw GattException(
                        if (connect) "Couldn't connect to the watch: ${lastConnectError ?: "connection failed"}" else NOT_CONNECTED_MESSAGE,
                    )
                }
                connectLocked()
            }
            val g = gatt ?: throw GattException("Not connected")
            try {
                block(g)
            } catch (e: GattTimeoutException) {
                // The stack is in an unknown state; start over with a fresh connection.
                linkFailedLocked()
                throw e
            }
        }
    }

    // ---- Connection management (opLock held) ----

    private fun connectLocked() {
        val addr: String
        val adapter: BluetoothAdapter
        try {
            addr = address ?: throw GattException("No watch chosen")
            adapter = adapter() ?: throw GattException("Bluetooth is not available on this device")
            if (!adapter.isEnabled) throw GattException("Bluetooth is turned off")
        } catch (e: GattException) {
            lastConnectError = e.message
            // With auto-reconnect on, the link waits (Bluetooth coming back on kicks it); with it off, stop now.
            if (!prefs.autoReconnect) linkFailedLocked()
            throw e
        }
        try {
            closeGattLocked()
            setState(State.CONNECTING, null)
            val cb = Callback()
            callback = cb
            runOp(Op.CONNECT, CONNECT_TIMEOUT_MS, "connection") {
                val g = adapter.getRemoteDevice(addr).connectGatt(appContext, false, cb, BluetoothDevice.TRANSPORT_LE)
                gatt = g
                g != null
            }
            setupLocked(gatt ?: throw GattException("Not connected"))
            failures.set(0)
            setState(State.CONNECTED, null)
        } catch (e: Exception) {
            lastConnectError = e.message
            linkFailedLocked()
            throw e
        }
    }

    /**
     * Discovers services, turns on notifications from the watch's TX characteristic, then requests the
     * connection priority.
     */
    private fun setupLocked(g: BluetoothGatt) {
        runOp(Op.DISCOVER, OP_TIMEOUT_MS, "service discovery") { g.discoverServices() }
        val service = g.getService(Ble.FORGE_SERVICE)
        rx = service?.let { findInService(it, Ble.FORGE_RX) }
        tx = service?.let { findInService(it, Ble.FORGE_TX) }
        val notifyChar = tx
        if (notifyChar == null) {
            Log.w(TAG, "Forge service/TX characteristic not found; messages from the watch won't be received")
        } else {
            enableNotificationsLocked(g, notifyChar)
        }
        requestPriorityLocked(g)
    }

    /**
     * Asks for a low-power or balanced connection. Balanced is requested explicitly because some phones keep the
     * last request. The watch may refuse or pick its own parameters, and a failed request never drops the link.
     */
    private fun requestPriorityLocked(g: BluetoothGatt) {
        val lowPower = prefs.lowPower
        val priority = if (lowPower) BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER else BluetoothGatt.CONNECTION_PRIORITY_BALANCED
        try {
            val ok = g.requestConnectionPriority(priority)
            Log.i(TAG, "Requested ${if (lowPower) "low-power" else "balanced"} connection priority: ${if (ok) "accepted" else "rejected"}")
        } catch (e: Exception) {
            Log.w(TAG, "Connection priority request failed", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun enableNotificationsLocked(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        if (!g.setCharacteristicNotification(c, true)) throw GattException("Couldn't enable notifications from the watch")
        val cccd = c.getDescriptor(Ble.CCCD) ?: throw GattException("Watch TX characteristic has no notification descriptor")
        val value = if ((c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        runOp(Op.DESCRIPTOR, OP_TIMEOUT_MS, "enabling notifications") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                cccd.value = value
                g.writeDescriptor(cccd)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun writeLocked(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
        val canWrite = (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
        val canWriteNoResponse = (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        // Prefer acknowledged writes when the characteristic supports them.
        val useResponse = canWrite || !canWriteNoResponse
        if (useResponse && !canWrite || !useResponse && !canWriteNoResponse) {
            val mode = if (useResponse) "with response" else "without response"
            throw GattException("Characteristic ${c.uuid} does not support write $mode")
        }
        val type = if (useResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        runOp(Op.WRITE, OP_TIMEOUT_MS, "write") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                var result = g.writeCharacteristic(c, value, type)
                var tries = 0
                while (result == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY && tries++ < 10) {
                    Thread.sleep(20)
                    result = g.writeCharacteristic(c, value, type)
                }
                result == BluetoothStatusCodes.SUCCESS
            } else {
                c.writeType = type
                c.value = value
                g.writeCharacteristic(c)
            }
        }
    }

    private fun closeGattLocked() {
        callback = null
        rx = null
        tx = null
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
    }

    // ---- Background reconnection ----

    private fun wanted(): Boolean =
        prefs.enabled && !prefs.paused && address != null && Perms.canConnect(appContext)

    private fun idleState(): State = when {
        !prefs.enabled -> State.STOPPED
        prefs.paused -> State.PAUSED
        else -> State.WAITING
    }

    /**
     * Handles a lost connection or a failed connect attempt (opLock held). With auto-reconnect on, schedules a retry;
     * with it off, stops the link (like Disconnect) so nothing reconnects until the user runs Connect.
     */
    private fun linkFailedLocked() {
        closeGattLocked()
        if (prefs.autoReconnect || !wanted()) {
            setState(idleState(), "lost")
            scheduleReconnect()
            return
        }
        stopAfterFailureLocked()
        appContext.stopService(Intent(appContext, LinkService::class.java))
    }

    /** Stops the link without reconnecting (opLock held). The caller stops [LinkService]. */
    private fun stopAfterFailureLocked() {
        prefs.enabled = false
        cancelRetry()
        interruptPending(NOT_CONNECTED_MESSAGE)
        closeGattLocked()
        setState(State.STOPPED, "lost")
    }

    /** Tries a few direct connections with backoff, then falls back to a low-power autoConnect. */
    @Synchronized
    private fun scheduleReconnect() {
        if (!wanted() || !prefs.autoReconnect || retryTask?.isDone == false) return
        val attempt = failures.getAndIncrement()
        retryTask = if (attempt < RETRY_DELAYS_MS.size) {
            reconnectExecutor.schedule(::backgroundConnect, RETRY_DELAYS_MS[attempt], TimeUnit.MILLISECONDS)
        } else {
            reconnectExecutor.submit(::startAutoConnect)
        }
    }

    @Synchronized
    private fun kick() {
        if (!wanted()) return
        retryTask?.cancel(false)
        retryTask = reconnectExecutor.submit(::backgroundConnect)
    }

    @Synchronized
    private fun cancelRetry() {
        retryTask?.cancel(false)
        retryTask = null
    }

    @Synchronized
    private fun clearRetryTask() {
        retryTask = null
    }

    private fun backgroundConnect() {
        clearRetryTask()
        try {
            withLock { if (wanted() && state != State.CONNECTED) connectLocked() }
        } catch (e: Exception) {
            Log.i(TAG, "Reconnect attempt failed: ${e.message}")
        }
    }

    private fun startAutoConnect() {
        clearRetryTask()
        try {
            withLock {
                if (!wanted() || state == State.CONNECTED) return@withLock
                val adapter = adapter()?.takeIf { it.isEnabled } ?: return@withLock
                closeGattLocked()
                val cb = Callback()
                callback = cb
                gatt = adapter.getRemoteDevice(address).connectGatt(appContext, true, cb, BluetoothDevice.TRANSPORT_LE)
                setState(State.WAITING, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "autoConnect failed", e)
        }
    }

    private fun onAutoConnected(cb: Callback) {
        opLock.lock()
        try {
            if (callback !== cb || state == State.CONNECTED) return
            if (!wanted()) {
                closeGattLocked()
                return
            }
            setState(State.CONNECTING, null)
            setupLocked(gatt ?: return)
            failures.set(0)
            setState(State.CONNECTED, null)
        } catch (e: Exception) {
            Log.w(TAG, "Setup after autoConnect failed", e)
            linkFailedLocked()
        } finally {
            opLock.unlock()
        }
    }

    private fun onLinkLost(cb: Callback) {
        opLock.lock()
        try {
            if (callback !== cb) return
            linkFailedLocked()
        } finally {
            opLock.unlock()
        }
    }

    private fun setState(new: State, reason: String?) {
        val old = state
        if (old == new) return
        state = new
        Log.i(TAG, "State $old -> $new")
        listeners.forEach { it.onStateChanged(new) }
        val addr = address ?: return
        if (new == State.CONNECTED) {
            TaskerEvents.fire(appContext, WatchEvent(EventType.CONNECTED, addr))
        } else if (old == State.CONNECTED) {
            TaskerEvents.fire(appContext, WatchEvent(EventType.DISCONNECTED, addr, reason = reason ?: "lost"))
        }
    }

    // ---- Incoming messages ----

    private fun onNotification(value: ByteArray) = messageExecutor.execute {
        messageBuffer.write(value)
        flushTask?.cancel(false)
        flushTask = if (value.size < Ble.MAX_WRITE_BYTES) {
            flushMessage()
            null
        } else {
            messageExecutor.schedule(::flushMessage, MESSAGE_IDLE_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun flushMessage() {
        if (messageBuffer.size() == 0) return
        val bytes = messageBuffer.toByteArray()
        messageBuffer.reset()
        Log.d(TAG, "Message from watch: ${Ble.bytesToHex(bytes)}")
        address?.let { TaskerEvents.fire(appContext, WatchEvent(EventType.MESSAGE, it, bytes)) }
    }

    // ---- Pending GATT operation ----

    private enum class Op { CONNECT, DISCOVER, DESCRIPTOR, WRITE }

    private class Pending(val op: Op, val future: CompletableFuture<ByteArray> = CompletableFuture())

    private fun runOp(op: Op, timeoutMs: Long, what: String, start: () -> Boolean): ByteArray {
        val p = Pending(op)
        pending.set(p)
        try {
            if (!start()) throw GattException("Couldn't start $what")
            return p.future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw GattTimeoutException("Timed out waiting for $what")
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } finally {
            pending.compareAndSet(p, null)
        }
    }

    private fun completeOp(op: Op, value: ByteArray = ByteArray(0)): Boolean {
        val p = pending.get() ?: return false
        return p.op == op && p.future.complete(value)
    }

    private fun failOp(op: Op, message: String) {
        val p = pending.get() ?: return
        if (p.op == op) p.future.completeExceptionally(GattException(message))
    }

    private fun interruptPending(message: String) {
        pending.get()?.future?.completeExceptionally(GattException(message))
    }

    // ---- Helpers ----

    private fun <T> withLock(block: () -> T): T {
        if (!opLock.tryLock(LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw GattException("The Bluetooth link is busy; try again")
        }
        try {
            return block()
        } finally {
            opLock.unlock()
        }
    }

    private fun adapter(): BluetoothAdapter? = appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private fun findInService(s: BluetoothGattService, uuid: UUID): BluetoothGattCharacteristic? =
        s.getCharacteristic(uuid) ?: s.characteristics.firstOrNull { Ble.samePrefix(it.uuid, uuid) }

    private const val NO_PERMISSION =
        "Bluetooth permission not granted. Open the Ollee Watch plugin app to grant it."
    private const val NO_FORGE =
        "The watch doesn't expose the Forge BLE service (C6FB0001-…). Check that it's an Ollee watch."

    // ---- GATT callback (binder thread). Each connection gets its own instance. ----

    private class Callback : BluetoothGattCallback() {
        private val isCurrent get() = callback === this

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!isCurrent) return
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (!completeOp(Op.CONNECT)) reconnectExecutor.execute { onAutoConnected(this) }
            } else if (newState == BluetoothProfile.STATE_CONNECTED || newState == BluetoothProfile.STATE_DISCONNECTED) {
                val message = if (newState == BluetoothProfile.STATE_CONNECTED) {
                    "Connection failed (GATT status $status)"
                } else {
                    "Watch disconnected (GATT status $status)"
                }
                interruptPending(message)
                reconnectExecutor.execute { onLinkLost(this) }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!isCurrent) return
            if (status == BluetoothGatt.GATT_SUCCESS) completeOp(Op.DISCOVER)
            else failOp(Op.DISCOVER, "Service discovery failed (status $status)")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (!isCurrent) return
            if (status == BluetoothGatt.GATT_SUCCESS) completeOp(Op.DESCRIPTOR)
            else failOp(Op.DESCRIPTOR, "Enabling notifications failed (status $status)")
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (!isCurrent) return
            if (status == BluetoothGatt.GATT_SUCCESS) completeOp(Op.WRITE)
            else failOp(Op.WRITE, "Write failed (status $status)")
        }

        // Android 13+
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = notified(characteristic, value.copyOf())

        // Android 12 and lower
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            notified(characteristic, characteristic.value?.copyOf() ?: return)
        }

        private fun notified(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val txChar = tx ?: return
            if (isCurrent && characteristic.uuid == txChar.uuid) onNotification(value)
        }
    }
}
