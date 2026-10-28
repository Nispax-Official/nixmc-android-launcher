package `in`.nispax.nixmc.engine

data class RendererSelection(
    val renderer: BridgeRenderer?,
    val reason: String,
    val blockers: List<String>
) {
    val isReady: Boolean get() = renderer != null && blockers.isEmpty()
}

/**
 * Chooses only renderer IDs declared by a checksum-verified bridge manifest.
 * It never invents a renderer based on the device model or GPU name.
 */
object RendererSelectionPolicy {
    private val preferredOrder = listOf("gl4es", "opengles2", "opengles3_ltw", "mobileglues")

    fun select(
        renderers: List<BridgeRenderer>,
        requestedId: String? = null
    ): RendererSelection {
        val ids = renderers.map { it.id }
        if (ids.any { it.isBlank() } || ids.distinct().size != ids.size) {
            return RendererSelection(null, "Invalid renderer catalogue", listOf("Renderer catalogue contains blank or duplicate IDs."))
        }
        if (renderers.isEmpty()) {
            return RendererSelection(null, "No renderer installed", listOf("The verified engine bridge provides no renderer backend."))
        }
        val requested = requestedId?.takeIf { it.isNotBlank() }
        if (requested != null) {
            val match = renderers.singleOrNull { it.id == requested }
            if (match == null) {
                return RendererSelection(
                    null,
                    "Requested renderer is unavailable",
                    listOf("Renderer '$requested' is not provided by the installed verified bridge.")
                )
            }
            return RendererSelection(match, "Using the requested installed renderer", emptyList())
        }
        val preferred = preferredOrder.firstNotNullOfOrNull { candidate ->
            renderers.firstOrNull { it.id == candidate }
        }
        val selected = preferred ?: renderers.first()
        return RendererSelection(selected, "Selected an installed compatible renderer", emptyList())
    }
}
