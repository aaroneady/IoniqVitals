package com.ioniqvitals.obd

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.github.eltonvs.obd.command.ObdRawResponse
import com.ioniqvitals.MainActivity
import com.ioniqvitals.R
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

@SuppressLint("MissingPermission") // Ensure you have permissions handled in your UI/Manifest
class ObdReaderService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readJob: Job? = null


    // BLE Specific variables
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null

    // Coroutine synchronization for BLE commands
    private val commandMutex = Mutex()
    private val responseChannel = Channel<String>(Channel.UNLIMITED)
    private val responseBuffer = java.lang.StringBuilder()
    private var isGattConnected = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Register the notification channel before any startForeground() call; without it
        // the foreground-service notification silently fails to post on API 26+.
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                connectAndRead(address)
            }
            ACTION_DISCONNECT -> disconnect()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        disconnect()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun connectAndRead(requestedAddress: String?) {
        if (readJob?.isActive == true) return

        val address = requestedAddress ?: ObdPreferences.getSelectedDeviceAddress(this)
        if (address == MOCK_DEVICE_ADDRESS) {
            startMockRead()
            return
        }

        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.obd_notification_text)))
        ObdDataRepository.tryEmit(ObdSnapshot(connectionState = ConnectionState.CONNECTING))

        val device = resolveDevice(requestedAddress)
        if (device == null) {
            ObdDataRepository.tryEmit(ObdSnapshot(connectionState = ConnectionState.DISCONNECTED, lastError = "Device not found"))
            stopSelf()
            return
        }

        ObdPreferences.setSelectedDeviceAddress(this@ObdReaderService, device.address)
        val adapterLabel = device.name ?: device.address

        // 1. Initiate BLE Connection
        bluetoothGatt = device.connectGatt(this, false, gattCallback)

        // 2. Start the Polling Loop
        readJob = serviceScope.launch {
            try {
                // Wait for GATT connection and service discovery to finish
                withTimeout(15_000L.milliseconds) {
                    while (!isGattConnected || writeCharacteristic == null) {
                        delay(100.milliseconds)
                    }
                }

                ObdDataRepository.emit(ObdSnapshot(connectionState = ConnectionState.CONNECTED, adapterName = adapterLabel))

                // Initial adapter setup commands. Only ATZ (full reset) needs settle time.
                sendObdCommand("ATZ", 300) // Reset
                sendObdCommand("ATE0") // Echo off
                sendObdCommand("ATL0") // Linefeed off
                sendObdCommand("ATH0") // Turn off ECU headers
                sendObdCommand("ATSP6") // Set protocol to ISO 15765-4 CAN (typical for modern EVs)

                // Slow-changing values (SOC/SOH/12V) are sampled once every SLOW_POLL_DIVISOR
                // loops and cached, so the brake/headlight cadence stays fast. A brake tap
                // lasts ~1s, so missing it is the failure mode we are optimizing against.
                var socP: Float? = null
                var sohP: Float? = null
                var auxP: Float? = null
                var lastBms0105Raw = ""
                var lastIccuRaw = ""
                var loopCount = 0L

                while (isActive) {
                    val startTime = System.currentTimeMillis()
                    val logBuilder = StringBuilder()
                    val readSlow = (loopCount % SLOW_POLL_DIVISOR == 0L)

                    // --- 7E4 (BMS): coolant + decel inputs every loop; SOC/SOH occasionally ---
                    setHeader(Ioniq5Pids.BMS_CAN_HEADER, logBuilder)
                    val bms0101 = sendPid("220101", logBuilder)
                    if (readSlow) {
                        val bms0105 = sendPid("220105", logBuilder)
                        Ioniq5Pids.parseSocPercent(bms0105)?.let { socP = it }
                        Ioniq5Pids.parseSohPercent(bms0105)?.let { sohP = it }
                        lastBms0105Raw = bms0105.value
                    }

                    // --- 7E5 (ICCU 12V aux): slow ---
                    if (readSlow) {
                        setHeader(Ioniq5Pids.ICCU_CAN_HEADER, logBuilder)
                        val iccuE011 = sendPid("22E011", logBuilder)
                        Ioniq5Pids.parseAuxSocPercent(iccuE011)?.let { auxP = it }
                        lastIccuRaw = iccuE011.value
                    }

                    // --- 770 (IGMP exterior lights): headlights + brake lamp, every loop ---
                    setHeader(Ioniq5Pids.IGMP_CAN_HEADER, logBuilder)
                    val igmpBc09 = sendPid("22BC09", logBuilder)
                    val igmpBc10 = sendPid("22BC10", logBuilder)
                    val igmpBc06 = sendPid("22BC06", logBuilder)
                    val headlightsOn = Ioniq5Pids.parseHeadlightState(igmpBc09) == true ||
                        Ioniq5Pids.parseHeadlightState(igmpBc10) == true

                    // Brake lamp — the verified signal: BC06 byte 4 (0x00 off, 0x2D pedal,
                    // 0x2C regen; any non-zero = lamp commanded on). Covers both pedal and regen,
                    // so it's the car's own brake-light state. brakeProbeStr surfaces the raw byte
                    // for on-road confirmation.
                    val brakeLampOn = Ioniq5Pids.parseBrakeLampOn(igmpBc06)

                    // --- 7A0 (BCM brake pedal): retained as a fallback signal ---
                    setHeader(Ioniq5Pids.BCM_CAN_HEADER, logBuilder)
                    val bcmB008 = sendPid("22B008", logBuilder)
                    val brakePedalState = Ioniq5Pids.parseBrakePedalState(bcmB008)

                    // Populate the decel/regen debug readout (real pack power etc.) for info only.
                    // Its result is intentionally NOT used for brake lights — it's a fake stand-in
                    // without a real speed source (010D is NODATA on E-GMP). getVehicleSpeed() is
                    // kept dormant for if/when a real speed source is found.
                    Ioniq5Pids.parseDecelerationState(bms0101, 0)

                    val duration = System.currentTimeMillis() - startTime

                    ObdDataRepository.emit(
                        ObdSnapshot(
                            connectionState = ConnectionState.CONNECTED,
                            adapterName = adapterLabel,
                            socDisplayPercent = socP,
                            sohPercent = sohP,
                            auxSocPercent = auxP,
                            coolantTempCelsius = Ioniq5Pids.parseCoolantTempCelsius(bms0101),
                            headlightsOn = headlightsOn,
                            // Verified brake-lamp signal (BC06) drives this. Fall back to the
                            // physical pedal (B008) only if BC06 returns no data. The power-based
                            // decelerationState is intentionally NOT used — it's a fake stand-in
                            // (no real speed source) and would only be reinstated if speed is found.
                            brakeLightsOn = brakeLampOn ?: (brakePedalState == true),

                            // Debug Info
                            socRaw = lastBms0105Raw,
                            socIndex = Ioniq5Pids.SOC_PERCENT_BYTE,
                            sohRaw = lastBms0105Raw,
                            sohIndexHigh = Ioniq5Pids.SOH_PERCENT_HIGH_BYTE,
                            auxSocRaw = lastIccuRaw,
                            auxSocIndex = Ioniq5Pids.AUX_SOC_PERCENT_BYTE,
                            coolantRaw = bms0101.value,
                            coolantIndex = Ioniq5Pids.COOLANT_TEMP_CELSIUS_BYTE,
                            headlightsRaw = igmpBc09.value,
                            brakeLightsRaw = bcmB008.value,

                            // Enhanced Diagnostics
                            debugLog = logBuilder.toString(),
                            loopDurationMs = duration,
                            stitchedSoc = Ioniq5Pids.getStitchedPayload(lastBms0105Raw, Ioniq5Pids.CONFIRMATION_HEADER_0105),
                            stitchedSoh = Ioniq5Pids.getStitchedPayload(lastBms0105Raw, Ioniq5Pids.CONFIRMATION_HEADER_0105),
                            stitchedAux = Ioniq5Pids.getStitchedPayload(lastIccuRaw, Ioniq5Pids.CONFIRMATION_HEADER_E011),
                            stitchedCoolant = Ioniq5Pids.getStitchedPayload(bms0101.value, Ioniq5Pids.CONFIRMATION_HEADER_0101),
                            stitchedHeadlights = Ioniq5Pids.getStitchedPayload(igmpBc09.value, Ioniq5Pids.CONFIRMATION_HEADER_BC09),
                            stitchedBrakes = Ioniq5Pids.getStitchedPayload(bcmB008.value, Ioniq5Pids.CONFIRMATION_HEADER_B008),

                            decelDebug = Ioniq5Pids.lastDecelDebug,
                        ),
                    )

                    loopCount++
                    delay(POLL_INTERVAL_MS.milliseconds)
                }
            } catch (error: TimeoutCancellationException) {
                Log.e(TAG, "BLE Connection Timeout", error)
                handleError("Connection Timeout")
            } catch (error: Exception) {
                Log.e(TAG, "OBD read loop failed", error)
                handleError(error.message)
            }
        }
    }

    // --- BLE GATT CALLBACK STATE MACHINE ---
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server. Requesting larger MTU...")
                gatt.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from GATT server.")
                isGattConnected = false
                handleError("Adapter Disconnected")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "MTU successfully negotiated to $mtu. Discovering services...")
                gatt.discoverServices()
            } else {
                Log.w(TAG, "MTU change failed (status $status). Proceeding to discover services anyway...")
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(OBDLINK_SERVICE_UUID)
                if (service != null) {
                    writeCharacteristic = service.getCharacteristic(OBDLINK_TX_UUID)
                    readCharacteristic = service.getCharacteristic(OBDLINK_RX_UUID)

                    gatt.setCharacteristicNotification(readCharacteristic, true)

                    val descriptor = readCharacteristic?.getDescriptor(CCCD_UUID)
                    if (descriptor != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(descriptor)
                        }
                    }

                    isGattConnected = true
                } else {
                    Log.e(TAG, "OBDLink Service not found on this device!")
                    handleError("Incompatible Adapter")
                }
            }
        }

        // Old Android 12 and below callback
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            processBleData(characteristic.uuid, characteristic.value)
        }

        // New Android 13+ callback
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            processBleData(characteristic.uuid, value)
        }

        // Common processor for incoming data
        private fun processBleData(uuid: UUID, value: ByteArray) {
            if (uuid == OBDLINK_RX_UUID) {
                val chunk = String(value)
                responseBuffer.append(chunk)

                if (responseBuffer.contains(">")) {
                    val completeResponse = responseBuffer.toString().replace(">", "").trim()
                    responseBuffer.clear()
                    responseChannel.trySend(completeResponse)
                }
            }
        }
    }

    // --- ASYNC QUERY ARCHITECTURE ---
    // Post-command delays are intentionally omitted: sendObdCommand() already blocks
    // until the ELM327 '>' prompt is received, so fixed sleeps only added dead latency.

    /** Sets the active CAN request header (ATSH). Call once per header group. */
    private suspend fun setHeader(header: String, log: StringBuilder? = null) {
        val shResult = sendObdCommand("ATSH$header")
        log?.append("  > ATSH$header -> $shResult\n")
    }

    /** Sends a PID under the currently-set header and returns the cleaned response. */
    private suspend fun sendPid(command: String, log: StringBuilder? = null): ObdRawResponse {
        val rawString = sendObdCommand(command)
        log?.append("  > $command -> $rawString\n")

        val cleanHex = cleanElm327Response(rawString)
        log?.append("  > Cleaned: $cleanHex\n")

        return ObdRawResponse(value = cleanHex, elapsedTime = 0L)
    }

    /** Convenience: set header then send a PID in one call (used for one-off reads). */
    private suspend fun query(header: String, command: String, log: StringBuilder? = null): ObdRawResponse {
        setHeader(header, log)
        return sendPid(command, log)
    }

    private fun cleanElm327Response(raw: String): String {
        // Just normalize and remove spaces. The parser in Ioniq5Pids will handle the segments.
        return raw.replace(" ", "").uppercase()
    }
    private suspend fun sendObdCommand(cmd: String, postDelayMs: Long = 0): String {
        return commandMutex.withLock {
            while (responseChannel.tryReceive().isSuccess) { }

            val payload = "$cmd\r".toByteArray()
            val characteristic = writeCharacteristic

            if (characteristic != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    bluetoothGatt?.writeCharacteristic(
                        characteristic,
                        payload,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    )
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = payload
                    @Suppress("DEPRECATION")
                    bluetoothGatt?.writeCharacteristic(characteristic)
                }
            }

            val result = withTimeoutOrNull(2000L.milliseconds) {
                responseChannel.receive()
            }

            if (postDelayMs > 0) delay(postDelayMs.milliseconds)

            result?.replace("\\s".toRegex(), "") ?: ""
        }
    }

    private fun handleError(message: String?) {
        ObdDataRepository.tryEmit(ObdSnapshot(connectionState = ConnectionState.DISCONNECTED, lastError = message))
        disconnect()
    }

    private fun disconnect() {
        readJob?.cancel()
        readJob = null
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing GATT", e)
        } finally {
            bluetoothGatt = null
            isGattConnected = false
        }
        // Publish a DISCONNECTED snapshot so the UI updates (the cancelled read loop can exit
        // without emitting one, leaving the connect/disconnect toggle stuck on "Disconnect").
        ObdDataRepository.tryEmit(ObdSnapshot(connectionState = ConnectionState.DISCONNECTED))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startMockRead() {
        startForeground(NOTIFICATION_ID, buildNotification("Running in Mock Mode (Raw Hex)"))
        ObdPreferences.setSelectedDeviceAddress(this, MOCK_DEVICE_ADDRESS)

        readJob = serviceScope.launch {
            val adapterLabel = "OBD Simulator"
            var mockSocPercent = 75.0f
            var mockSohPercent = 95.5f
            var mockTempCelsius = 25.0f
            var mockAuxSoc = 88
            var counter = 0

            // Headlights and brake lights each toggle on their own random 0-1000 ms interval
            // (instead of every poll) so mock mode doesn't strobe the indicators. Each flip
            // schedules the next toggle. nextToggle == 0 forces an immediate first flip.
            var mockHeadlightsOn = false
            var nextHeadlightToggleMs = 0L
            var mockBrakeOn = false
            var nextBrakeToggleMs = 0L

            // Each numeric readout (12V / Temp / SOH / SOC) randomly drops to "no data" for a
            // short window so the head-unit tile's "--" placeholder can be exercised. Available
            // most of the time (5-9 s up) with brief 1.5-3 s outages; each field toggles on its
            // own schedule. Keyed by the ObdSnapshot field name.
            val mockAvailable = mutableMapOf(
                "aux" to true, "temp" to true, "soh" to true, "soc" to true,
            )
            val nextAvailToggleMs = mutableMapOf(
                "aux" to 0L, "temp" to 0L, "soh" to 0L, "soc" to 0L,
            )

            // Helper to build a realistic raw HEX string for the parsers
            // Note: Byte 0 is '62', Byte 1/2 are the PID. Data starts at Byte 3.
            fun buildFakeHex(pidHeader: String, totalBytes: Int, byteMap: Map<Int, Int>): ObdRawResponse {
                // 1. Create a byte array for the payload and initialize with zero
                val payloadArray = IntArray(totalBytes) { 0 }

                // 2. Populate the custom byte values passed from your test configuration
                for ((index, value) in byteMap) {
                    if (index in 0 until totalBytes) {
                        payloadArray[index] = value and 0xFF // Ensure value fits in a single byte
                    }
                }

                // 3. Construct the flat hex string starting with the positive confirmation code (62)
                val flatPayloadBuilder = StringBuilder()
                flatPayloadBuilder.append("62").append(pidHeader.replace(" ", "").uppercase())

                for (byteVal in payloadArray) {
                    flatPayloadBuilder.append(String.format("%02X", byteVal))
                }

                val cleanPayload = flatPayloadBuilder.toString()
                val frameBuilder = StringBuilder()

                // Reproduce the OBDLink CX wire format faithfully so the mock exercises
                // the real reassembleIsoTp() path:
                //   <3-nibble total length><frame#0>:<frame0 data><frame#1>:<frame1 data>...
                // i.e. the length prefix encodes the true byte count, and the index of the
                // NEXT frame is appended to the END of the current frame's segment.
                val totalMsgBytes = cleanPayload.length / 2
                val canHeader = String.format("%03X", totalMsgBytes) + "0"

                // 4. Package into ISO-TP Multi-Frames
                val firstFrameSize = 12 // 6 bytes / 12 hex characters
                val firstFrameData = cleanPayload.take(firstFrameSize)
                frameBuilder.append("$canHeader:$firstFrameData")

                var remainingPayload = cleanPayload.drop(firstFrameSize)
                var frameIndex = 1

                while (remainingPayload.length > 14) {
                    val currentChunk = remainingPayload.take(14)
                    // Index of THIS frame closes the previous segment, then its data follows.
                    frameBuilder.append("$frameIndex:$currentChunk")

                    remainingPayload = remainingPayload.drop(14)
                    frameIndex++
                }

                if (remainingPayload.isNotEmpty()) {
                    val finalFrameBuilder = StringBuilder()
                    finalFrameBuilder.append(remainingPayload)

                    while (finalFrameBuilder.length < 14) {
                        finalFrameBuilder.append("AA")
                    }
                    // Close the previous segment with the final frame's index nibble, like the device.
                    val finalIdx = (frameIndex and 0xF).toString(16).uppercase()
                    frameBuilder.append("$finalIdx:${finalFrameBuilder.toString()}")
                }

                return ObdRawResponse(value = frameBuilder.toString(), elapsedTime = 0L)
            }



            //var lightsState = Ioniq5Pids.LightsState()

            while (isActive) {
                // Advance the random-interval toggles. Each signal flips at most once per
                // poll and only after its scheduled time, giving a 0-1000 ms on/off cadence.
                val nowMs = System.currentTimeMillis()
                if (nowMs >= nextHeadlightToggleMs) {
                    mockHeadlightsOn = !mockHeadlightsOn
                    nextHeadlightToggleMs = nowMs + (500..1500).random()
                }
                if (nowMs >= nextBrakeToggleMs) {
                    mockBrakeOn = !mockBrakeOn
                    nextBrakeToggleMs = nowMs + (500..1500).random()
                }

                // Advance each numeric readout's availability: when up, stay up 5-9 s; when
                // down ("no data"), stay down 1.5-3 s, then recover.
                for (key in mockAvailable.keys) {
                    if (nowMs >= (nextAvailToggleMs[key] ?: 0L)) {
                        val nowUp = !(mockAvailable[key] ?: true)
                        mockAvailable[key] = nowUp
                        nextAvailToggleMs[key] = nowMs + if (nowUp) (5000..9000).random() else (1500..3000).random()
                    }
                }

                // 1. Simulate BMS 0105 (Main Battery SOC & SOH)
                // SOH is bytes 28 & 29 (val / 10.0) -> 1000 = 100.0% -> 0x03 0xE8
                // SOC is byte 34 (val / 2.0) -> 75.0 * 2 = 150 -> 0x96
                // Calculate SOH bytes
                val sohRaw = (mockSohPercent * 10).toInt()
                val sohHighByte = (sohRaw shr 8) and 0xFF
                val sohLowByte = sohRaw and 0xFF

                val bms0105 = buildFakeHex("0105", 42, mapOf(
                    Ioniq5Pids.SOH_PERCENT_HIGH_BYTE to sohHighByte,
                    Ioniq5Pids.SOH_PERCENT_LOW_BYTE to sohLowByte,
                    Ioniq5Pids.SOC_PERCENT_BYTE to (mockSocPercent * 2).toInt()
                ))

                // 2. Simulate BMS 0101 (Coolant Temp + regen power).
                // Inject regen current (-50.0 A) only while the brake toggle is "on" so the
                // power-based decel path flips in lockstep with the brake instead of pinning
                // on (a constant -30 kW would otherwise hold the decel hysteresis on forever
                // and mask the toggle). When off, 0 A keeps power above REGEN_OFF and disarms.
                val ampsHigh = if (mockBrakeOn) 0xFE else 0x00
                val ampsLow = if (mockBrakeOn) 0x0C else 0x00
                val bms0101 = buildFakeHex("0101", 59, mapOf(
                    Ioniq5Pids.COOLANT_TEMP_CELSIUS_BYTE to (mockTempCelsius + 40).toInt(),
                    Ioniq5Pids.AMPS_BYTE to ampsHigh, (Ioniq5Pids.AMPS_BYTE + 1) to ampsLow,  // -50.0 A signed when braking
                    Ioniq5Pids.VOLTAGE_BYTE to 0x17, (Ioniq5Pids.VOLTAGE_BYTE + 1) to 0x70    // 600.0 V
                ))

                // Sawtooth speed feeds the decel/regen debug readout only (info, not used for
                // brake lights now that BC06 is the source).
                val mockSpeedKmH = 60 - (counter % 60)
                Ioniq5Pids.parseDecelerationState(bms0101, mockSpeedKmH)

                // 3. Simulate ICCU E011 (12V Aux Battery)
                val iccuE011 = buildFakeHex("E011", 49, mapOf(
                    Ioniq5Pids.AUX_SOC_PERCENT_BYTE to mockAuxSoc
                ))

                // 4. Simulate BCM B008 (Brake Lights)
                // Bit 4 (0x10) of byte 6 is the brake-lamp flag the real parser checks
                // (parseBrakePedalState -> `state and 0x10`). Keep the mock bit identical
                // so mock mode actually exercises the production parse path.
                val brakesOn = mockBrakeOn
                val toValue = if (brakesOn) 0x10 else 0x00
                val bcmB008 = buildFakeHex("B008", 8, mapOf(
                    Ioniq5Pids.BRAKE_LIGHT_STATE_BYTE to toValue
                ))

                // 5. Simulate BCM BC09 (low beam headlights)
                val mockLowBeam = mockHeadlightsOn
                val toValue2 = if (mockLowBeam) 0x40 else 0x00
                val bcmBC09 = buildFakeHex("BC09", 8, mapOf(
                    Ioniq5Pids.HEADLIGHT_BC09_STATE_BYTE to toValue2
                ))

                // Pass the fake hex responses into your ACTUAL parsers
                //lightsState = Ioniq5Pids.parseLightsBc09(bcmBC09, lightsState)
                //lightsState = Ioniq5Pids.parseLightsBc10(buildFakeHex("BC10", 15, emptyMap()), lightsState)
                //val lightsOnBc09:Boolean? = Ioniq5Pids.parseHeadlightState(bcmBC09)

                // Drive the brake flag through the production parser so mock mode
                // verifies the real B008 byte/bit decode, not just the test boolean.
                val mockBrakeParsed = Ioniq5Pids.parseBrakePedalState(bcmB008) == true

                // Mock the BC06 brake lamp from the brake toggle (0x2D pedal-style when on),
                // mirroring the real car's primary brake source.
                val bcmBC06 = buildFakeHex("BC06", 8, mapOf(
                    Ioniq5Pids.BRAKE_LAMP_BC06_BYTE to if (mockBrakeOn) 0x2D else 0x00
                ))
                val mockBrakeLampOn = Ioniq5Pids.parseBrakeLampOn(bcmBC06)

                ObdDataRepository.emit(
                    ObdSnapshot(
                        connectionState = ConnectionState.CONNECTED,
                        adapterName = adapterLabel,
                        socDisplayPercent = Ioniq5Pids.parseSocPercent(bms0105).takeIf { mockAvailable["soc"] == true },
                        sohPercent = Ioniq5Pids.parseSohPercent(bms0105).takeIf { mockAvailable["soh"] == true },
                        auxSocPercent = Ioniq5Pids.parseAuxSocPercent(iccuE011).takeIf { mockAvailable["aux"] == true },
                        coolantTempCelsius = Ioniq5Pids.parseCoolantTempCelsius(bms0101).takeIf { mockAvailable["temp"] == true },
                        headlightsOn = mockLowBeam,
                        brakeLightsOn = mockBrakeLampOn ?: mockBrakeParsed,

                        // Debug Info
                        socRaw = bms0105.value,
                        socIndex = Ioniq5Pids.SOC_PERCENT_BYTE,
                        sohRaw = bms0105.value,
                        sohIndexHigh = Ioniq5Pids.SOH_PERCENT_HIGH_BYTE,
                        auxSocRaw = iccuE011.value,
                        auxSocIndex = Ioniq5Pids.AUX_SOC_PERCENT_BYTE,
                        coolantRaw = bms0101.value,
                        coolantIndex = Ioniq5Pids.COOLANT_TEMP_CELSIUS_BYTE,
                        headlightsRaw = bcmBC09.value,
                        brakeLightsRaw = bcmB008.value,
                        decelDebug = Ioniq5Pids.lastDecelDebug,
                    ),
                )

                // Fluctuate the data slightly for realism
                //mockSocPercent = (mockSocPercent - 0.1f).coerceAtLeast(0f)
                //mockSohPercent = (mockSohPercent - 0.05f).coerceAtLeast(0f)
                //mockTempCelsius = (mockTempCelsius + 0.2f).coerceAtMost(100f)
                counter++

                delay(POLL_INTERVAL_MS.milliseconds)
            }
        }
    }

    private fun resolveDevice(requestedAddress: String?): BluetoothDevice? {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager?.adapter ?: return null
        return if (requestedAddress != null) {
            adapter.getRemoteDevice(requestedAddress)
        } else {
            null
        }
    }

    private fun buildNotification(content: String): Notification {
        val launchIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.obd_notification_title))
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(launchIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.obd_notification_title),
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    /**
     * One line of the brake-lamp probe readout for a given request [header]. On a positive
     * 62195E response, shows the on/off interpretation (byte 0 != 0) plus the first payload
     * bytes for confirming the byte offset; otherwise shows the raw response (e.g. a 7Fxxxx
     * negative response like 7F2231) so failures are visible on screen, not hidden as "no data".
     */
    suspend fun getVehicleSpeed(log: StringBuilder? = null): Int? {
        // 1. Send the standard request for PID 01 0D.
        // 11-bit functional powertrain header is "7E0" (3 nibbles); "07E0" would be
        // interpreted as a 29-bit header and typically yields no response.
        val response = query("7E0", "010D", log)

        // 2. Parse the response
        // A successful response will look like "410D[SPEED_HEX]"
        val payload = response.value

        // Look for the "410D" pattern in the cleaned hex string
        val headerIndex = payload.indexOf("410D")
        if (headerIndex == -1) return null

        // The speed byte is immediately after "410D"
        val hexByteStart = headerIndex + 4
        if (hexByteStart + 2 > payload.length) return null

        return try {
            payload.substring(hexByteStart, hexByteStart + 2).toInt(16)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val ACTION_CONNECT = "com.ioniqvitals.action.CONNECT_OBD"
        const val ACTION_DISCONNECT = "com.ioniqvitals.action.DISCONNECT_OBD"
        const val EXTRA_DEVICE_ADDRESS = "device_address"

        private const val TAG = "ObdReaderService"
        private const val CHANNEL_ID = "obd_reader"
        private const val NOTIFICATION_ID = 42

        // Inter-loop pause. Kept small so transient signals (brake/headlights) are sampled
        // frequently; the BLE round-trips themselves dominate the actual loop time.
        private const val POLL_INTERVAL_MS = 50L

        // Read SOC/SOH/12V once every N loops (they change slowly); brake/headlight/coolant
        // are read every loop. Keeps the brake-detection cadence sub-second.
        private const val SLOW_POLL_DIVISOR = 8L


        const val MOCK_DEVICE_ADDRESS = "00:00:00:00:00:00"

        // --- OBDLINK CX PROPRIETARY BLE UUIDs ---
        private val OBDLINK_SERVICE_UUID: UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
        private val OBDLINK_RX_UUID: UUID = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB")
        private val OBDLINK_TX_UUID: UUID = UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }
}