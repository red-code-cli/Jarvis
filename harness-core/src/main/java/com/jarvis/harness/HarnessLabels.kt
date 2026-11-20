package com.jarvis.harness

import android.content.Context
import com.jarvis.harness.model.AgentKind
import com.jarvis.harness.model.DevStack
import com.jarvis.harness.model.ProviderKind

/**
 * Non-Compose display labels for engine enums. Upstream kept these in a Compose
 * module; the engine only needs the plain [Context] variants, so only those were
 * brought over.
 */

fun AgentKind.localizedTitle(context: Context): String = AppStrings.get(
    context,
    when (this) {
        AgentKind.CLAUDE_CODE -> R.string.agent_title_claude
        AgentKind.DEEPSEEK_HARNESS -> R.string.agent_title_deepseek
        AgentKind.ANTIGRAVITY -> R.string.agent_title_antigravity
    },
)

fun DevStack.localizedLabel(context: Context): String = AppStrings.get(
    context,
    when (this) {
        DevStack.WEB -> R.string.md_stack_web_label
        DevStack.PYTHON -> R.string.md_stack_python_label
        DevStack.ANDROID -> R.string.md_stack_android_label
        DevStack.CPP -> R.string.md_stack_cpp_label
        DevStack.PHP -> R.string.md_stack_php_label
    },
)

fun ProviderKind.localizedTitle(context: Context): String = AppStrings.get(
    context,
    when (this) {
        ProviderKind.CLAUDE -> R.string.provider_title_claude
        ProviderKind.ANTHROPIC -> R.string.provider_title_anthropic
        ProviderKind.LLM_ROUTER -> R.string.provider_title_openrouter
        ProviderKind.DEEPSEEK -> R.string.provider_title_deepseek
        ProviderKind.KIMI -> R.string.provider_title_kimi
        ProviderKind.OPENCODE_ZEN -> R.string.provider_title_opencode
        ProviderKind.NVIDIA_NIM -> R.string.provider_title_nvidia
        ProviderKind.CUSTOM -> R.string.provider_title_custom
    },
)
