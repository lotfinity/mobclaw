package com.mobclaw.android.core

/**
 * Configuration for MobClaw agent.
 */
data class MobClawConfig(
    /** Maximum tool call iterations per task before stopping. */
    val maxIterations: Int = 60,

    /** Low sampling temperature keeps GUI actions deterministic. */
    val temperature: Double = 0.2,

    /** Model name for the LLM provider. */
    val model: String? = null,

    /** Delay (ms) between actions to allow UI to settle. */
    val actionDelayMs: Long = 300,

    /** Whether to auto-read the screen after each action. */
    val autoScreenRead: Boolean = true,

    /** Stability wait time (ms) after action before reading screen. */
    val stabilityWaitMs: Long = 500,

    /** Maximum consecutive no-change iterations before declaring stuck. */
    val maxStuckCount: Int = 3,

    /** Maximum retries for the same failed action. */
    val maxActionRetries: Int = 2,

    /** Whether to perform strict post-completion verification. */
    val verifyOnFinish: Boolean = true,

    /** Whether to exclude MobClaw's own windows from screen reads. */
    val excludeSelfFromScreen: Boolean = true,

    /** Delay (ms) before post-completion verification snapshot. */
    val verificationDelayMs: Long = 1000,

    /** Exactly one action is executed for each visual observation. */
    val maxActionsPerTurn: Int = 1,
)