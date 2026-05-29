package dev.sankofa.sdk

import android.graphics.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import dev.sankofa.sdk.replay.ComposeMaskRegistry

/**
 * Masks this Composable from Sankofa session replays — the privacy equivalent
 * of `view.sankofaMask = true` for the classic View system.
 *
 * Compose draws every node into a single `AndroidComposeView`, so the SDK's
 * View-tree walker can't see individual `TextField`s and `maskAllInputs` can't
 * reach them. Apply this modifier to any composable that shows sensitive
 * content (text fields, card numbers, PII) and it will be drawn as a solid
 * black rectangle in replays:
 *
 * ```kotlin
 * OutlinedTextField(
 *     value = card,
 *     onValueChange = { card = it },
 *     modifier = Modifier.sankofaMask(),
 * )
 * ```
 *
 * The node reports its window bounds to the SDK on every layout pass and clears
 * them automatically when it leaves composition — no manual cleanup needed.
 *
 * Implemented as a plain [ModifierNodeElement] (no `@Composable`), so the SDK
 * depends on `androidx.compose.ui` only as `compileOnly`: it adds nothing to
 * non-Compose hosts.
 */
fun Modifier.sankofaMask(): Modifier = this then SankofaMaskElement

private object SankofaMaskElement : ModifierNodeElement<SankofaMaskNode>() {
    override fun create(): SankofaMaskNode = SankofaMaskNode()

    // Stateless — nothing to reconcile on recomposition.
    override fun update(node: SankofaMaskNode) {}

    override fun hashCode(): Int = SANKOFA_MASK_ELEMENT_HASH
    override fun equals(other: Any?): Boolean = other === this
}

private const val SANKOFA_MASK_ELEMENT_HASH = 0x5A4B_4D53 // "ZKMS"

private class SankofaMaskNode : Modifier.Node(), GlobalPositionAwareModifierNode {

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val b = coordinates.boundsInWindow()
        ComposeMaskRegistry.update(
            this,
            Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt()),
        )
    }

    override fun onDetach() {
        ComposeMaskRegistry.remove(this)
        super.onDetach()
    }
}
