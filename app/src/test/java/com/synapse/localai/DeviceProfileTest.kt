package com.synapse.localai

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceProfileTest {

    @Test
    fun inferenceThreadsRespectConfiguredLimitAndPerformanceCores() {
        val hardware = HardwareProfile(
            totalRamMb = 8_192,
            totalCores = 8,
            performanceCores = 4
        )

        assertEquals(2, DeviceProfile.inferenceThreads(2, 4_000, hardware))
        assertEquals(4, DeviceProfile.inferenceThreads(8, 4_000, hardware))
    }

    @Test
    fun lowMemoryUsesConservativeThreadCounts() {
        val hardware = HardwareProfile(
            totalRamMb = 4_096,
            totalCores = 8,
            performanceCores = 4
        )

        assertEquals(1, DeviceProfile.inferenceThreads(8, 700, hardware))
        assertEquals(2, DeviceProfile.inferenceThreads(8, 1_000, hardware))
    }

    @Test
    fun singleCoreHardwareDoesNotRequestMoreThanOneThread() {
        val hardware = HardwareProfile(
            totalRamMb = 2_048,
            totalCores = 1,
            performanceCores = 1
        )

        assertEquals(1, DeviceProfile.inferenceThreads(4, 2_000, hardware))
    }

    @Test
    fun qwenBatchThreadsUseAvailableCoresButRemainBounded() {
        assertEquals(
            8,
            DeviceProfile.qwenBatchThreads(
                HardwareProfile(totalRamMb = 4_096, totalCores = 8, performanceCores = 4)
            )
        )
        assertEquals(
            1,
            DeviceProfile.qwenBatchThreads(
                HardwareProfile(totalRamMb = 2_048, totalCores = 1, performanceCores = 1)
            )
        )
        assertEquals(4, DeviceProfile.qwenBatchThreads(null))
    }

    @Test
    fun lowMemoryContextStillFitsPromptsLargerThanOneThousandTokens() {
        assertEquals(2048, DeviceProfile.inferenceContextLength(4096, 1_000))
    }

    @Test
    fun configuredContextLengthIsClampedToSupportedMemorySafeRange() {
        assertEquals(3072, DeviceProfile.inferenceContextLength(3072, 2_000))
        assertEquals(4096, DeviceProfile.inferenceContextLength(8192, 2_000))
    }

    @Test
    fun qwenGpuLayerBudgetAllowsConservativePartialOffloadWithLessThanOneGigabyteFree() {
        assertEquals(5, DeviceProfile.qwenGpuLayerBudget(2_560, 873, 36))
    }

    @Test
    fun qwenGpuLayerBudgetReservesMemoryAndNeverOffloadsEveryLayer() {
        assertEquals(0, DeviceProfile.qwenGpuLayerBudget(2_560, 500, 36))
        assertEquals(8, DeviceProfile.qwenGpuLayerBudget(2_560, 4_096, 36))
        assertEquals(0, DeviceProfile.qwenGpuLayerBudget(2_560, 4_096, null))
    }

    @Test
    fun agentContextLengthDefaultsToFourThousandNinetySix() {
        assertEquals(4096, AgentConfig().contextLength)
    }
}
