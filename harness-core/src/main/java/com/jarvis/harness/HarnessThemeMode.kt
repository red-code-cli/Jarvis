package com.jarvis.harness

/**
 * Theme preference persisted by the orchestrator's preferences store.
 *
 * Upstream reused its Compose theme enum here. The module owns no UI, so it keeps a
 * plain enum and lets the host application map it to its own theme.
 */
enum class HarnessThemeMode {
    DARK,
    LIGHT,
}
