package org.flossware.hotspot.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.flossware.hotspot.R
import org.flossware.hotspot.model.HotspotState

@Composable
fun SetupInstructions(
    state: HotspotState,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column {
            ListItem(
                headlineContent = {
                    Text(
                        stringResource(R.string.setup_instructions),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                trailingContent = {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess
                        else Icons.Default.ExpandMore,
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable { expanded = !expanded },
            )

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        stringResource(R.string.step_1_connect, state.networkName),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(
                        stringResource(R.string.step_2_password, state.passphrase),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(
                        stringResource(R.string.step_3_client),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(
                        stringResource(R.string.step_4_connect),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(
                        stringResource(R.string.step_5_manual, state.socksAddress),
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }
            }
        }
    }
}
