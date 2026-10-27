package `in`.nispax.nixmc.engine

enum class SessionUiState {
    PREPARING,
    VERIFYING_RUNTIME,
    VERIFYING_GAME_FILES,
    VERIFYING_BRIDGE,
    STARTING,
    RUNNING,
    STOPPED,
    FAILED
}
