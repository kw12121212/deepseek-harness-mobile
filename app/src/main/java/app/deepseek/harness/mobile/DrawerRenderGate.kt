package app.deepseek.harness.mobile

/** Replacing a pressed row cancels Android's pending long-press callback. */
internal class DrawerRenderGate {
    private var touching = false
    private var deferred = false

    fun beginTouch() {
        touching = true
    }

    fun requestRender(): Boolean {
        if (touching) deferred = true
        return !touching
    }

    fun endTouch(): Boolean {
        touching = false
        val needsRender = deferred
        deferred = false
        return needsRender
    }
}
