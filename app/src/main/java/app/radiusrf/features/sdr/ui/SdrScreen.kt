package app.radiusrf.features.sdr.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import app.radiusrf.core.ui.zd.ZdIcons
import app.radiusrf.core.ui.zd.ZdStatePanel
import app.radiusrf.features.sdr.domain.SdrDetector
import app.radiusrf.features.sdr.viewmodel.SdrViewModel

@Composable
fun SdrScreen(
    viewModel: SdrViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    if (!state.hasUsbHost) {
        ZdStatePanel(
            kicker = "Not on this phone",
            icon = ZdIcons.Usb,
            title = "Your phone can’t host USB devices",
            body = "SDR dongles plug in through USB-OTG, which needs USB host support. This phone doesn’t report it.",
            chipsLabel = "Recognised dongles",
            chips = SdrDetector.FAMILIES
        )
        return
    }

    SdrWorkbench(state, viewModel, Modifier.fillMaxSize())
}
