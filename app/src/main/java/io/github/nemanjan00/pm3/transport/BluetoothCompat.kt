package io.github.nemanjan00.pm3.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context

/**
 * The adapter, via BluetoothManager.
 *
 * BluetoothAdapter.getDefaultAdapter() has been deprecated since API 31 and
 * returns null on a device with no adapter, which the system service handles
 * more predictably.
 */
internal fun adapterOf(context: Context): BluetoothAdapter? =
    context.getSystemService(BluetoothManager::class.java)?.adapter
