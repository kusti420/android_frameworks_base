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

package com.android.systemui.scene.ui.composable.transitions

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.animation.scene.transformation.InterpolatedPropertyTransformation
import com.android.compose.animation.scene.transformation.PropertyTransformation
import com.android.compose.animation.scene.transformation.PropertyTransformationScope
import com.android.compose.animation.scene.transformation.Transformation
import kotlin.math.abs

/**
 * IchthysOS: a horizontal anchor move larger than this is a pager page apart, not travel: half the
 * narrowest phone width, while QS <-> QQS anchors sit a few dp apart at most.
 */
private val PageShift = 160.dp

/**
 * IchthysOS: the stock anchoredTranslate (an element moves by as much as [anchor] does between the
 * two contents), except that a horizontal move of more than [PageShift] is left out. Such a move
 * is not the anchor travelling but the anchor sitting on another page of a horizontal pager: with
 * the quick settings notes page showing, the tiles page (and the grid anchor in it) is composed one
 * page width to the right, and anchoredTranslate slid the tile content and the fading tiles of the
 * collapsed row a whole page sideways during the collapse to the shade. Moves within a page (the
 * stock QS <-> QQS case) are kept as they were.
 */
internal class PagedAnchoredTranslate private constructor(private val anchor: ElementKey) :
    InterpolatedPropertyTransformation<Offset> {
    override val property = PropertyTransformation.Property.Offset

    override fun PropertyTransformationScope.transform(
        content: ContentKey,
        element: ElementKey,
        transition: TransitionState.Transition,
        idleValue: Offset,
    ): Offset {
        val anchorFrom = anchor.targetOffset(transition.fromContent) ?: return idleValue
        val anchorTo = anchor.targetOffset(transition.toContent) ?: return idleValue
        val delta = anchorTo - anchorFrom
        val dx = if (abs(delta.x) > PageShift.toPx()) 0f else delta.x
        return if (content == transition.toContent) {
            Offset(idleValue.x - dx, idleValue.y - delta.y)
        } else {
            Offset(idleValue.x + dx, idleValue.y + delta.y)
        }
    }

    class Factory(private val anchor: ElementKey) : Transformation.Factory {
        override fun create(): Transformation = PagedAnchoredTranslate(anchor)
    }
}
