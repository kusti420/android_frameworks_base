/*
 * Copyright (C) 2026 IchthysOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.tiles.impl.backstage

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.compose.ui.graphics.painter.rememberDrawablePainter
import com.android.systemui.qs.tiles.dialog.colordisplay.rememberIchthysDialogTokens
import com.android.systemui.res.R

private const val BACKSTAGE_DETAILS_VIEW_TEST_TAG = "backstage_details_view"
private val AppIconSize = 36.dp

/**
 * IchthysOS: the Backstage popup's content, shared by the dual-shade details view (TileDetails.kt)
 * and the single-shade dialog ([BackstageDialogDelegate]). Two sections: the apps in Backstage,
 * each with Remove, and the recently used apps, each with Send; Send is off with the full message
 * shown once Backstage holds its 3 apps. Styled like the Cast / Modes details contents: Material
 * colour roles and the tile_details paddings, no hard-coded colours.
 */
@Composable
fun BackstageDetailsContent(viewModel: BackstageDetailsViewModel) {
    val stateFlow = remember(viewModel) { viewModel.state() }
    val state by stateFlow.collectAsState(initial = null)
    val current = state ?: return

    Column(
        modifier =
            Modifier.fillMaxWidth()
                .padding(
                    start = dimensionResource(R.dimen.tile_details_horizontal_padding),
                    end = dimensionResource(R.dimen.tile_details_horizontal_padding),
                    bottom = dimensionResource(R.dimen.tile_details_bottom_padding),
                )
                .testTag(BACKSTAGE_DETAILS_VIEW_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SectionTitle(stringResource(R.string.ichthys_backstage_details_in_backstage))
        if (current.inBackstage.isEmpty()) {
            Note(stringResource(R.string.ichthys_backstage_details_empty))
        }
        current.inBackstage.forEach { app ->
            AppRow(app) {
                OutlinedButton(onClick = { viewModel.remove(app.pkg) }) {
                    Text(stringResource(R.string.qs_customize_remove))
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        SectionTitle(stringResource(R.string.ichthys_backstage_details_recent))
        if (current.full) {
            Note(stringResource(R.string.ichthys_backstage_tile_full))
        }
        current.recent.forEach { app ->
            AppRow(app) {
                Button(onClick = { viewModel.send(app.pkg) }, enabled = !current.full) {
                    Text(stringResource(R.string.accessibility_send_smart_reply))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Icon, label and the row's control (Send / Remove). */
@Composable
private fun AppRow(app: BackstageDetailsViewModel.App, control: @Composable () -> Unit) {
    val tokens = rememberIchthysDialogTokens()
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(tokens.radiusSmall))
                .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (app.icon != null) {
            Image(
                painter = rememberDrawablePainter(app.icon),
                contentDescription = null,
                modifier = Modifier.size(AppIconSize),
            )
        } else {
            Spacer(Modifier.size(AppIconSize))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = app.label.toString(),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(12.dp))
        control()
    }
}
