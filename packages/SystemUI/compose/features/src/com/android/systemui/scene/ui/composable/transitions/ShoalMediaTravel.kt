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
import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.animation.scene.transformation.InterpolatedPropertyTransformation
import com.android.compose.animation.scene.transformation.PropertyTransformation
import com.android.compose.animation.scene.transformation.PropertyTransformationScope
import com.android.compose.animation.scene.transformation.Transformation

/**
 * IchthysOS "Shoal": moves an element that is in one content only to where [counterpart] is in the
 * other content of the transition, for two elements that show the same thing in different shapes
 * (the media card of the shade and the docked media strip of quick settings). Give each of the two
 * this transformation with the other as its counterpart and they travel as one, their bottom edges
 * together, so a fade between them reads as the one turning into the other on the way.
 *
 * Without a counterpart in the other content (nothing is playing, or the other content does not
 * dock the player) the element stays where it is.
 */
internal class TravelToCounterpart private constructor(private val counterpart: ElementKey) :
    InterpolatedPropertyTransformation<Offset> {
    override val property = PropertyTransformation.Property.Offset

    override fun PropertyTransformationScope.transform(
        content: ContentKey,
        element: ElementKey,
        transition: TransitionState.Transition,
        idleValue: Offset,
    ): Offset {
        val otherContent =
            if (content == transition.fromContent) transition.toContent else transition.fromContent
        val counterpartOffset = counterpart.targetOffset(otherContent) ?: return idleValue
        val counterpartSize = counterpart.targetSize(otherContent) ?: return idleValue
        val size = element.targetSize(content) ?: return idleValue
        return Offset(
            x = counterpartOffset.x + (counterpartSize.width - size.width) / 2f,
            y = counterpartOffset.y + counterpartSize.height - size.height,
        )
    }

    class Factory(private val counterpart: ElementKey) : Transformation.Factory {
        override fun create(): Transformation = TravelToCounterpart(counterpart)
    }
}
