package com.mobiledivecontrol.platform.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * Result of a single serialized GATT operation.
 *
 * [failure] is non-null only for problems the stack never reported through a callback —
 * the operation was refused outright, timed out, or the link went away underneath it.
 * A completed-but-unhappy operation carries the GATT [status] instead, which the caller
 * needs in order to distinguish e.g. an authentication demand from a plain failure.
 */
internal class GattOutcome(
    val status: Int,
    val value: ByteArray?,
    val failure: String?,
) {
    val isSuccess: Boolean
        get() = failure == null && status == BluetoothGatt.GATT_SUCCESS

    /** Short description suitable for a diagnostics line. */
    fun describe(): String = failure ?: if (isSuccess) "ok" else "status $status"
}

/**
 * Serializes GATT operations onto a single in-flight slot.
 *
 * Android's `BluetoothGatt` holds exactly one outstanding read/write/descriptor-write per
 * connection and silently discards any operation issued before the previous callback
 * arrives — no exception, no error callback, the request simply never happens. Subscribing
 * to six characteristics in a loop therefore subscribes to one. Every operation has to pass
 * through here.
 *
 * The queue never blocks forever: an operation that receives no callback within
 * [operationTimeoutMs] is failed and the queue advances. A stalled housing must degrade the
 * link, not freeze the app that is meant to warn the diver about it.
 *
 * @param scope single-threaded scope owned by the transport; all queue bookkeeping is
 *   confined to it, while completions may arrive on any binder thread.
 */
internal class GattQueue(
    scope: CoroutineScope,
    private val operationTimeoutMs: Long = DEFAULT_OPERATION_TIMEOUT_MS,
    private val onDiagnostic: (String) -> Unit = {},
) {

    /** Which GATT callback completes an operation. */
    enum class Kind { Read, Write, DescriptorWrite }

    private class Operation(
        val kind: Kind,
        val uuid: String,
        val label: String,
        val start: () -> Boolean,
        val result: CompletableDeferred<GattOutcome> = CompletableDeferred(),
    )

    private val pending = Channel<Operation>(Channel.UNLIMITED)
    private val inFlight = AtomicReference<Operation?>(null)

    @Volatile
    private var closed = false

    init {
        scope.launch {
            for (operation in pending) {
                execute(operation)
            }
        }
    }

    /** Reads a characteristic, resolving when `onCharacteristicRead` fires. */
    suspend fun readCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ): GattOutcome = submit(
        kind = Kind.Read,
        uuid = characteristic.uuid.toString(),
        label = "read ${characteristic.uuid}",
    ) {
        gatt.readCharacteristic(characteristic)
    }

    /** Writes a characteristic, resolving when `onCharacteristicWrite` fires. */
    suspend fun writeCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int,
    ): GattOutcome = submit(
        kind = Kind.Write,
        uuid = characteristic.uuid.toString(),
        label = "write ${characteristic.uuid}",
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.writeType = writeType
                characteristic.value = value
                gatt.writeCharacteristic(characteristic)
            }
        }
    }

    /**
     * Writes a descriptor, resolving when `onDescriptorWrite` fires. Used for the CCCD
     * write that actually turns notifications on over the air.
     */
    suspend fun writeDescriptor(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): GattOutcome = submit(
        kind = Kind.DescriptorWrite,
        uuid = descriptor.characteristic?.uuid?.toString().orEmpty(),
        label = "descriptor write ${descriptor.uuid}",
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = value
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    /**
     * Completes the in-flight operation from a GATT callback. Safe to call from any binder
     * thread; unmatched completions are logged and dropped rather than advancing the queue.
     */
    fun complete(kind: Kind, uuid: String, status: Int, value: ByteArray?) {
        val operation = inFlight.get()
        if (operation == null) {
            onDiagnostic("GATT $kind callback for $uuid with no operation in flight")
            return
        }
        if (operation.kind != kind) {
            onDiagnostic("GATT $kind callback for $uuid while ${operation.label} was in flight")
            return
        }
        if (operation.uuid.isNotEmpty() && !operation.uuid.equals(uuid, ignoreCase = true)) {
            onDiagnostic("GATT $kind callback UUID $uuid does not match ${operation.label}")
        }
        operation.result.complete(GattOutcome(status, value, null))
    }

    /**
     * Fails everything queued and in flight. Called on disconnect so no caller is left
     * waiting on a link that no longer exists.
     */
    fun cancelAll(reason: String) {
        inFlight.getAndSet(null)?.result?.complete(GattOutcome(0, null, reason))
        while (true) {
            val operation = pending.tryReceive().getOrNull() ?: break
            operation.result.complete(GattOutcome(0, null, reason))
        }
    }

    /** Permanently retires the queue. Subsequent submissions fail immediately. */
    fun close(reason: String = "transport closed") {
        closed = true
        cancelAll(reason)
        pending.close()
    }

    private suspend fun submit(
        kind: Kind,
        uuid: String,
        label: String,
        start: () -> Boolean,
    ): GattOutcome {
        if (closed) return GattOutcome(0, null, "queue closed")

        val operation = Operation(kind, uuid, label, start)
        if (pending.trySend(operation).isFailure) {
            return GattOutcome(0, null, "queue closed")
        }

        // Second-line guard: if the worker itself is gone, the caller still returns.
        return withTimeoutOrNull(operationTimeoutMs * STALL_GUARD_FACTOR) { operation.result.await() }
            ?: GattOutcome(0, null, "queue stalled")
    }

    private suspend fun execute(operation: Operation) {
        if (closed) {
            operation.result.complete(GattOutcome(0, null, "queue closed"))
            return
        }

        inFlight.set(operation)
        val started = try {
            operation.start()
        } catch (error: SecurityException) {
            onDiagnostic("GATT ${operation.label} denied: ${error.message}")
            false
        }

        if (!started) {
            inFlight.compareAndSet(operation, null)
            operation.result.complete(GattOutcome(0, null, "${operation.label} refused by the stack"))
            return
        }

        val outcome = withTimeoutOrNull(operationTimeoutMs) { operation.result.await() }
        if (outcome == null) {
            onDiagnostic("GATT ${operation.label} timed out after $operationTimeoutMs ms")
            operation.result.complete(GattOutcome(0, null, "timeout"))
        }
        inFlight.compareAndSet(operation, null)
    }

    companion object {
        /** CLAUDE.md §18.4 allows 3s for a characteristic read/write; 5s covers a slow CCCD write too. */
        const val DEFAULT_OPERATION_TIMEOUT_MS = 5_000L

        private const val STALL_GUARD_FACTOR = 2
    }
}
