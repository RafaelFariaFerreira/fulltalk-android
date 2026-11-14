package dev.fulltalk.app.debug

import dev.fulltalk.app.bubble.BubbleService

/** Release builds have no debug hooks; see the debug source set for what they are. */
object DebugHooks {
    @Suppress("UNUSED_PARAMETER")
    fun install(service: BubbleService) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun uninstall(service: BubbleService) = Unit
}
