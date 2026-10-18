package app.radiusrf.features.wifi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.radiusrf.core.ui.zd.ZdChip
import app.radiusrf.features.wifi.domain.WifiNicSource
import app.radiusrf.ui.theme.ZdColors
import app.radiusrf.ui.theme.ZdType

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WifiNicPicker(
    source: WifiNicSource,
    onSelect: (WifiNicSource) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp
) {
    Column(
        modifier.padding(horizontal = horizontalPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Wi-Fi radio", style = ZdType.Label, color = ZdColors.Text)
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WifiNicSource.entries.forEach { option ->
                ZdChip(option.label, selected = source == option, onClick = { onSelect(option) })
            }
        }
        Text(
            "Phone is the default. 8821AU covers the T2U Plus. 8812AU, 8814AU, PAU0A, and 7612U listen for beacons when that adapter is plugged in.",
            style = ZdType.Caption,
            color = ZdColors.Text3
        )
    }
}
