package com.textmorph.app.ui.navigation

enum class AppDestination(val title: String) {
    PROMPTS("Custom Prompts"),
    CUSTOM_COMMANDS("Custom Commands"),
    AUTOMATIONS("Voice & Automations"),
    WAKE_WORDS("Wake-Word Detection"),
    PERMISSIONS_GUIDE("Setup & Permissions"),
    MODEL_HUB("Model Hub"),
    TOOLS("Agent Tools"),
    CHAT("AI Playground"),
    SETTINGS("Settings"),
    ABOUT("About TextMorph"),
    DEV_LOGS("Developer Diagnostics"),
    VERSION_HISTORY("Version History"),
    RAW_LLM_DUMP("Raw LLM Dump"),
    BENCHMARK("Agent Capability Benchmark")
}
