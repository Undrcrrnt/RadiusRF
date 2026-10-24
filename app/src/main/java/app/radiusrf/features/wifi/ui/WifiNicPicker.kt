package app.radiusrf.features.wifi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.radiusrf.core.ui.zd.ZdIcons
import app.radiusrf.core.ui.zd.ZdListCard
import app.radiusrf.core.ui.zd.ZdListRow
import app.radiusrf.features.wifi.domain.WifiNicSource
import app.radiusrf.ui.theme.ZdColors
import app.radiusrf.ui.theme.ZdType

@Composable
fun WifiNicPicker(
    source: WifiNicSource,
    onSelect: (WifiNicSource) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp,
    showHeading: Boolean = true
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier.padding(horizontal = horizontalPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (showHeading) Text("Wi-Fi radio", style = ZdType.Label, color = ZdColors.Text)
        ZdListCard(listOf(source)) {
            ZdListRow(
                title = source.adapter,
                titleMono = false,
                subtitle = source.chipset,
                trailing = {
                    Icon(
                        ZdIcons.ChevronDown,
                        contentDescription = if (open) "Hide radios" else "Choose a radio",
                        tint = ZdColors.Text3,
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(if (open) 180f else 0f)
                    )
                },
                onClick = { open = !open }
            )
        }
        if (open) {
            ZdListCard(WifiNicSource.entries) { option ->
                ZdListRow(
                    title = option.adapter,
                    titleMono = false,
                    subtitle = option.chipset,
                    trailing = {
                        if (source == option) {
                            Icon(
                                ZdIcons.Check,
                                contentDescription = "Selected",
                                tint = ZdColors.Accent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    onClick = {
                        onSelect(option)
                        open = false
                    }
                )
            }
            Text(
                "USB adapters listen for beacons while that stick is plugged in.",
                style = ZdType.Caption,
                color = ZdColors.Text3
            )
        }
    }
}
