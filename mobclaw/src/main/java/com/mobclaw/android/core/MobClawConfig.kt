package com.mobclaw.android.core

/**
 * Configuration for MobClaw agent.
 */
data class MobClawConfig(
    /** Maximum tool call iterations per task before stopping. */
    val maxIterations: Int = 120,

    /** Default temperature for LLM calls. */
    val temperature: Double = 0.7,

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

    /** Maximum number of actions allowed per single observation turn. */
    val maxActionsPerTurn: Int = 1,
)
