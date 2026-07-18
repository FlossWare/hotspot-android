package org.flossware.hotspot.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.flossware.hotspot.R
import org.flossware.hotspot.model.HotspotState

@Composable
fun SetupInstructions(
    state: HotspotState,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    CollapsibleSection(
        title = stringResource(R.string.setup_instructions),
        modifier = modifier,
        initiallyExpanded = initiallyExpanded,
    ) {
        val stepModifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)

        Text(
            stringResource(R.string.step_1_connect, state.networkName),
            modifier = stepModifier,
        )
        Text(
            stringResource(R.string.step_2_password, state.passphrase),
            modifier = stepModifier,
        )
        Text(
            stringResource(R.string.step_3_client),
            modifier = stepModifier,
        )
        Text(
            stringResource(R.string.step_4_connect),
            modifier = stepModifier,
        )
        Text(
            stringResource(R.string.step_5_manual, state.socksAddress),
            modifier = stepModifier.padding(bottom = 12.dp),
        )
    }
}
