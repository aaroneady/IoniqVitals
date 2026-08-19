package com.ioniqvitals.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.ioniqvitals.R
import com.ioniqvitals.obd.ConnectionState
import com.ioniqvitals.obd.ObdDataRepository
import com.ioniqvitals.obd.ObdSnapshot
import com.ioniqvitals.ui.CarIconRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class VehicleStatusScreen(carContext: CarContext) : Screen(carContext) {

    private val iconRenderer = CarIconRenderer(carContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var latestSnapshot = ObdSnapshot()

    init {
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onCreate(owner: LifecycleOwner) {
                    scope.launch {
                        ObdDataRepository.dataFlow.collectLatest { snapshot ->
                            latestSnapshot = snapshot
                            invalidate()
                        }
                    }
                }

                override fun onDestroy(owner: LifecycleOwner) {
                    scope.cancel()
                }
            },
        )
    }

    override fun onGetTemplate(): Template {
        val snapshot = latestSnapshot
        android.util.Log.i(
            "Ioniq5Template",
            "onGetTemplate state=${snapshot.connectionState} soc=${snapshot.socDisplayPercent} " +
                "soh=${snapshot.sohPercent} aux=${snapshot.auxSocPercent} temp=${snapshot.coolantTempCelsius} " +
                "head=${snapshot.headlightsOn} brake=${snapshot.brakeLightsOn}",
        )

        if (snapshot.connectionState == ConnectionState.DISCONNECTED ||
            snapshot.connectionState == ConnectionState.CONNECTING
        ) {
            val paneBuilder = Pane.Builder()
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.waiting_for_obd))
                    .addText(connectionStatusText(snapshot))
                    .build(),
            )
            // Level-1 APIs only (setTitle + setHeaderAction); the newer Header/setHeader needs a
            // higher Car App API level than this DHU host supports.
            return PaneTemplate.Builder(paneBuilder.build())
                .setTitle(carContext.getString(R.string.vehicle_status_title))
                .setHeaderAction(Action.APP_ICON)
                .build()
        }

        val listBuilder = ItemList.Builder()

        // Grid-template-native rendering: each tile is its own GridItem with a plain (transparent)
        // icon plus the host-drawn title/secondary text. This replaces the media panel's approach
        // of compositing all six tiles — with baked-in labels and opaque cards to survive the
        // album-art scrim — into a single bitmap. On the grid template the host supplies the tile
        // chrome and text, so we render the icon graphic only and let GridItem carry the text.
        // Order matches the old layout: Front, Rear, 12V, Temp, SOH, SOC.
        var itemCount = 0
        fun tile(resId: Int, title: String, subtitle: String? = null) {
            val builder = GridItem.Builder()
                .setTitle(title)
                .setImage(iconRenderer.createScaledIcon(resId), GridItem.IMAGE_TYPE_LARGE)
            subtitle?.let { builder.setText(it) }
            listBuilder.addItem(builder.build())
            itemCount++
        }

        val frontRes = if (snapshot.headlightsOn == true) {
            R.drawable.ioniq_5_front_lights_on_comic_book_style
        } else {
            R.drawable.ioniq_5_front_lights_off_comic_book_style
        }
        tile(frontRes, "Headlights", if (snapshot.headlightsOn == true) "On" else "Off")

        val rearRes = if (snapshot.brakeLightsOn == true) {
            R.drawable.ioniq_5_rear_lights_on_comic_book_style
        } else {
            R.drawable.ioniq_5_rear_lights_off_comic_book_style
        }
        tile(rearRes, "Brakes", if (snapshot.brakeLightsOn == true) "On" else "Off")

        snapshot.auxSocPercent?.let { tile(R.drawable.ic_12v_comic_book_style, "${it.toInt()}%", "12V") }
        snapshot.coolantTempCelsius?.let { tile(R.drawable.ic_coolant_comic_book_style, "${it.toInt()}°", "Temp") }
        snapshot.sohPercent?.let { tile(R.drawable.ic_soh_comic_book_style, "%.1f%%".format(it), "SOH") }
        snapshot.socDisplayPercent?.let { tile(R.drawable.ic_soc_comic_book_style, "%.1f%%".format(it), "SOC") }

        android.util.Log.i("Ioniq5Template", "grid built with $itemCount items")

        // Level-1 APIs only (setTitle + setHeaderAction) — see note above re: minCarApiLevel=1.
        return GridTemplate.Builder()
            .setSingleList(listBuilder.build())
            .setTitle(carContext.getString(R.string.vehicle_status_title))
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun connectionStatusText(snapshot: ObdSnapshot): String =
        when (snapshot.connectionState) {
            ConnectionState.CONNECTING -> "Connecting to OBD-II device…"
            ConnectionState.DISCONNECTED -> "Open the phone app, pick your OBD adapter, then tap Connect OBD."
            else -> ""
        }
}
