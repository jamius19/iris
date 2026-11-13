/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Flat press feedback: a [color] overlay over the content that fades in on press (60 ms) and out on release
 * (150 ms, after the fade-in finished, so a quick tap still shows). Clipped by the caller's `clip(shape)`.
 */
class PressIndication(private val color: Color) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        PressIndicationNode(interactionSource, color)

    override fun equals(other: Any?): Boolean = other is PressIndication && other.color == color

    override fun hashCode(): Int = color.hashCode()
}

private class PressIndicationNode(
    private val interactionSource: InteractionSource,
    private val color: Color,
) : Modifier.Node(), DrawModifierNode {

    private val alpha = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            var press: Job? = null
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> press = launch { alpha.animateTo(1f, tween(PRESS_IN_MS)) }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        val pressed = press
                        launch {
                            pressed?.join()
                            alpha.animateTo(0f, tween(PRESS_OUT_MS))
                        }
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val value = alpha.value
        if (value > 0f) drawRect(color, alpha = value)
    }
}

private const val PRESS_IN_MS = 60
private const val PRESS_OUT_MS = 150
