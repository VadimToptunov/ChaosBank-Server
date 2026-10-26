package com.vadimtoptunov.chaosbank.chaos

/**
 * SplitMix64, value for value the apps' `SeededRng`: the same seed draws the same
 * "random" coin flips on the server as in the embedded mock.
 */
class SeededRng(seed: Long) {
    private var state: ULong = seed.toULong() + 0x9E3779B97F4A7C15uL

    fun next(): ULong {
        state += 0x9E3779B97F4A7C15uL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        return z xor (z shr 31)
    }

    /** Uniform double in [0, 1). */
    fun nextDouble(): Double = (next() shr 11).toLong().toDouble() / (1L shl 53).toDouble()
}
