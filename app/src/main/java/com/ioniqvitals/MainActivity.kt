package com.ioniqvitals

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ioniqvitals.billing.BillingManager
import com.ioniqvitals.car.IoniqVitalsCarAppService
import com.ioniqvitals.car.IoniqVitalsMediaBrowserService
import com.ioniqvitals.car.SurfaceConfig
import com.ioniqvitals.obd.ConnectionState
import com.ioniqvitals.obd.Ioniq5Pids
import com.ioniqvitals.obd.ObdDataRepository
import com.ioniqvitals.obd.ObdPreferences
import com.ioniqvitals.obd.ObdReaderService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var adapterSpinner: Spinner
    private lateinit var adapterHintText: TextView
    private lateinit var carImageContainer: View
    private lateinit var frontCarImageView: ImageView
    private lateinit var rearCarImageView: ImageView
    private lateinit var toggleDebugButton: Button
    private lateinit var copyDebugButton: Button
    private lateinit var connectButton: Button
    private lateinit var supportButton: Button
    private lateinit var billingManager: BillingManager
    private var selectedDeviceAddress: String? = null
    private var adapterAddresses: List<String> = emptyList()
    private var showDebugInfo: Boolean = false
    private var lastSnapshot: com.ioniqvitals.obd.ObdSnapshot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Keep the display on while this screen is visible (live OBD dashboard / debug view).
        // Scoped to the activity window: auto-released when the app leaves the foreground.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        statusText = findViewById(R.id.statusText)
        adapterSpinner = findViewById(R.id.adapterSpinner)
        adapterHintText = findViewById(R.id.adapterHintText)
        carImageContainer = findViewById(R.id.carImageContainer)
        frontCarImageView = findViewById(R.id.frontCarImageView)
        rearCarImageView = findViewById(R.id.rearCarImageView)
        toggleDebugButton = findViewById(R.id.toggleDebugButton)
        copyDebugButton = findViewById(R.id.copyDebugButton)

        toggleDebugButton.setOnClickListener {
            showDebugInfo = !showDebugInfo
            toggleDebugButton.text = if (showDebugInfo) "Hide Debug Info" else "Show Debug Info"
            copyDebugButton.visibility = if (showDebugInfo) View.VISIBLE else View.GONE
            
            // Toggle car image visibility based on debug mode
            lastSnapshot?.let { 
                updateStatusText(it)
                updateUiVisibility(it)
            }
        }

        copyDebugButton.setOnClickListener {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("OBD Debug Info", statusText.text)
            clipboard.setPrimaryClip(clip)
            android.widget.Toast.makeText(this, "Debug info copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.refreshAdaptersButton).setOnClickListener {
            requestBluetoothPermissions { refreshAdapterList() }
        }
        connectButton = findViewById(R.id.connectButton)
        connectButton.setOnClickListener {
            if (isConnectedOrConnecting(lastSnapshot?.connectionState)) {
                startService(
                    Intent(this, ObdReaderService::class.java).apply {
                        action = ObdReaderService.ACTION_DISCONNECT
                    },
                )
            } else {
                requestBluetoothPermissions { connectSelectedAdapter() }
            }
        }

        supportButton = findViewById(R.id.supportButton)
        supportButton.setOnClickListener { showTipChooser() }
        billingManager = BillingManager(
            activity = this,
            onMessage = { message -> android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show() },
            onProductsReady = {},
        )
        billingManager.start()

        // Enforce the compile-time surface choice (SurfaceConfig) and self-heal any stale
        // component-enabled overrides left by the previous runtime toggle.
        applySurfaceComponents()

        adapterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedDeviceAddress = adapterAddresses.getOrNull(position)?.takeIf { it.isNotBlank() }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        lifecycleScope.launch {
            ObdDataRepository.dataFlow.collectLatest { data ->
                lastSnapshot = data
                updateStatusText(data)
                updateUiVisibility(data)
            }
        }

        requestBluetoothPermissions { refreshAdapterList() }
    }

    override fun onDestroy() {
        billingManager.release()
        super.onDestroy()
    }

    /**
     * Shows the tip-tier chooser. Each row is the product's Play Console name plus its localized,
     * currency-correct price straight from Play. If no products have loaded (e.g. sideloaded build,
     * or Play Console products not yet active), tells the user tips are unavailable.
     */
    private fun showTipChooser() {
        val products = billingManager.availableProducts()
        if (products.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.tip_unavailable, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val labels = products.map { product ->
            val price = product.oneTimePurchaseOfferDetails?.formattedPrice ?: ""
            "${product.name}  $price".trim()
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.tip_chooser_title)
            .setItems(labels) { _, which -> billingManager.launchTip(products[which].productId) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun isConnectedOrConnecting(state: ConnectionState?): Boolean =
        state == ConnectionState.CONNECTED ||
            state == ConnectionState.READING ||
            state == ConnectionState.CONNECTING

    private fun updateUiVisibility(data: com.ioniqvitals.obd.ObdSnapshot) {
        connectButton.text = getString(
            if (isConnectedOrConnecting(data.connectionState)) R.string.disconnect_obd else R.string.connect_obd,
        )

        if (data.connectionState == ConnectionState.CONNECTED || data.connectionState == ConnectionState.READING) {
            toggleDebugButton.visibility = View.VISIBLE
            if (showDebugInfo) copyDebugButton.visibility = View.VISIBLE else copyDebugButton.visibility = View.GONE

            // Hide car images when debug info is showing
            if (showDebugInfo) {
                carImageContainer.visibility = View.GONE
            } else {
                carImageContainer.visibility = View.VISIBLE
                
                // Front view (Headlights)
                if (data.headlightsOn == true) {
                    frontCarImageView.setImageResource(R.drawable.ioniq_5_front_lights_on_comic_book_style)
                } else {
                    frontCarImageView.setImageResource(R.drawable.ioniq_5_front_lights_off_comic_book_style)
                }

                // Rear view (Brake lights)
                if (data.brakeLightsOn == true) {
                    rearCarImageView.setImageResource(R.drawable.ioniq_5_rear_lights_on_comic_book_style)
                } else {
                    rearCarImageView.setImageResource(R.drawable.ioniq_5_rear_lights_off_comic_book_style)
                }
            }
        } else {
            toggleDebugButton.visibility = View.GONE
            copyDebugButton.visibility = View.GONE
            carImageContainer.visibility = View.GONE
        }
    }

    private fun updateStatusText(data: com.ioniqvitals.obd.ObdSnapshot) {
        statusText.text = buildString {
            appendLine("Connection: ${data.connectionState}")
            data.adapterName?.let { appendLine("Adapter: $it") }

            if (showDebugInfo) {
                appendLine("Loop Time: ${data.loopDurationMs}ms")
                appendLine("SOC: ${data.socDisplayPercent?.let { "%.1f%%".format(it) } ?: "--"} (Idx: ${data.socIndex}, Stitched: ${data.stitchedSoc})")
                appendLine("SOH: ${data.sohPercent?.let { "%.1f%%".format(it) } ?: "--"} (Idx: ${data.sohIndexHigh}, Stitched: ${data.stitchedSoh})")
                appendLine("12V SOC: ${data.auxSocPercent?.let { "%.0f%%".format(it) } ?: "--"} (Idx: ${data.auxSocIndex}, Stitched: ${data.stitchedAux})")
                appendLine("Coolant: ${formatTemp(data.coolantTempCelsius)} (Idx: ${data.coolantIndex}, Stitched: ${data.stitchedCoolant})")
                appendLine("Headlights: ${formatOnOff(data.headlightsOn)} (Stitched: ${data.stitchedHeadlights})")
                appendLine("Brake lights: ${formatOnOff(data.brakeLightsOn)} (Idx: ${Ioniq5Pids.BRAKE_LIGHT_STATE_BYTE}, Stitched: ${data.stitchedBrakes})")
                data.decelDebug?.let { d ->
                    appendLine("\n--- Decel/Regen ---")
                    appendLine("Speed: ${d.speedKmH} km/h (prev ${d.prevSpeedKmH})  dt: ${"%.2f".format(d.deltaTimeS)}s  accel: ${"%.2f".format(d.accelMs2)} m/s²")
                    appendLine("Current: ${"%.1f".format(d.currentAmps)} A  Voltage: ${"%.1f".format(d.voltageVolts)} V  Power: ${"%.2f".format(d.powerKw)} kW")
                    appendLine("Hysteresis: ${if (d.hysteresisOn) "ON" else "off"}  Output: ${if (d.output) "ON" else "off"}  recomputed: ${d.recomputed}${d.note?.let { " ($it)" } ?: ""}")
                }
                appendLine("\n--- Full Polling Log ---")
                appendLine(data.debugLog ?: "No log available")
            } else {
                appendLine("SOC: ${data.socDisplayPercent?.let { "%.1f%%".format(it) } ?: "--"}")
                appendLine("SOH: ${data.sohPercent?.let { "%.1f%%".format(it) } ?: "--"}")
                appendLine("12V SOC: ${data.auxSocPercent?.let { "%.0f%%".format(it) } ?: "--"}")
                appendLine("Coolant: ${formatTemp(data.coolantTempCelsius)}")
                appendLine("Headlights: ${formatOnOff(data.headlightsOn)}")
                appendLine("Brake lights: ${formatOnOff(data.brakeLightsOn)}")
            }
            data.lastError?.let { appendLine("Error: $it") }
        }.trimEnd()
    }

    private fun refreshAdapterList() {
        val adapter = getBluetoothAdapter()
        if (adapter == null || !adapter.isEnabled) {
            adapterHintText.text = getString(R.string.bluetooth_disabled_hint)
            adapterSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, emptyList<String>())
            return
        }

        val bonded = adapter.bondedDevices
            .sortedBy { it.name?.lowercase().orEmpty() }
            .map { device ->
                val label = device.name?.takeIf { it.isNotBlank() } ?: "Unknown adapter"
                "$label (${device.address})|${device.address}"
            }
            .toMutableList()

        // Add Mock Adapter
        bonded.add(0, "${getString(R.string.mock_obd_adapter)}|${ObdReaderService.MOCK_DEVICE_ADDRESS}")

        if (bonded.isEmpty()) {
            adapterHintText.text = getString(R.string.no_paired_adapters_hint)
            adapterAddresses = listOf("")
            adapterSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.adapter_spinner_placeholder)),
            )
            selectedDeviceAddress = null
            return
        }

        adapterHintText.text = getString(R.string.select_adapter_hint)
        val labels = listOf(getString(R.string.adapter_spinner_placeholder)) + bonded.map { it.substringBefore("|") }
        adapterAddresses = listOf("") + bonded.map { it.substringAfter("|") }

        adapterSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )

        val savedAddress = ObdPreferences.getSelectedDeviceAddress(this)
        val selectedIndex = adapterAddresses.indexOfFirst { it.equals(savedAddress, ignoreCase = true) }.coerceAtLeast(1)
        adapterSpinner.setSelection(selectedIndex)
        selectedDeviceAddress = adapterAddresses.getOrNull(selectedIndex)?.takeIf { it.isNotBlank() }
    }

    private fun connectSelectedAdapter() {
        val address = selectedDeviceAddress
        if (address.isNullOrBlank()) {
            adapterHintText.text = getString(R.string.select_adapter_hint)
            return
        }

        ObdPreferences.setSelectedDeviceAddress(this, address)
        startService(
            Intent(this, ObdReaderService::class.java).apply {
                action = ObdReaderService.ACTION_CONNECT
                putExtra(ObdReaderService.EXTRA_DEVICE_ADDRESS, address)
            },
        )
    }

    private fun getBluetoothAdapter(): BluetoothAdapter? {
        val manager = getSystemService(BluetoothManager::class.java)
        return manager?.adapter
    }

    private fun formatTemp(celsius: Float?): String {
        if (celsius == null) return "--"
        val fahrenheit = celsius * 9f / 5f + 32f
        return "%.0f°C / %.0f°F".format(celsius, fahrenheit)
    }

    private fun formatOnOff(value: Boolean?): String =
        when (value) {
            true -> getString(R.string.state_on)
            false -> getString(R.string.state_off)
            null -> "--"
        }

    /**
     * Enforces the compile-time [SurfaceConfig.USE_TEMPLATE_SURFACE] choice and exposes exactly
     * one surface to Android Auto. Template mode (POI Car App) enables the Car App service +
     * activity and disables the media browser; media mode does the reverse. Self-heals any stale
     * component-enabled overrides. Takes effect after Android Auto reconnects.
     */
    private fun applySurfaceComponents() {
        val template = SurfaceConfig.USE_TEMPLATE_SURFACE
        fun setEnabled(component: ComponentName, enabled: Boolean) {
            packageManager.setComponentEnabledSetting(
                component,
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
        // The media browser is always enabled — it's the only component that lists a sideloaded
        // app on this DHU. The Car App service + activity are enabled only in template mode (for an
        // allowlisted / AAOS host).
        setEnabled(ComponentName(this, IoniqVitalsMediaBrowserService::class.java), true)
        setEnabled(ComponentName(this, IoniqVitalsCarAppService::class.java), template)
        setEnabled(ComponentName(this, "androidx.car.app.activity.CarAppActivity"), template)
    }

    private fun requestBluetoothPermissions(onGranted: () -> Unit) {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            onGranted()
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_BT)
            pendingAction = onGranted
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BT && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            pendingAction?.invoke()
            pendingAction = null
        }
    }

    companion object {
        private const val REQUEST_BT = 1001
        private var pendingAction: (() -> Unit)? = null
    }
}
