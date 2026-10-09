package com.synapse.localai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCompletionGateTest {

    @Test
    fun agentTaskCannotCompleteWithoutAcceptanceCriteria() {
        val task = AgentTask(
            description = "Agent task",
            command = "Perform the requested work",
            taskType = "AGENT_TASK"
        )

        assertFalse(canMarkTaskCompleted(task))
    }

    @Test
    fun agentTaskRequiresAllCriteriaToBeVerified() {
        val task = AgentTask(
            description = "Agent task",
            command = "Perform the requested work",
            taskType = "AGENT_TASK",
            acceptanceCriteria = listOf("The result is correct")
        )

        assertFalse(canMarkTaskCompleted(task))
        assertTrue(canMarkTaskCompleted(task.copy(criteriaVerified = true)))
    }

    @Test
    fun commandTasksWithoutAcceptanceCriteriaRemainCompletable() {
        val task = AgentTask(
            description = "Scheduled command",
            command = "echo ready"
        )

        assertTrue(canMarkTaskCompleted(task))
    }

    @Test
    fun terminalFrameWithoutExitStatusIsNotTreatedAsSuccessful() {
        val frame = StreamDemultiplexer.DemuxBlock.TerminalFrame(
            command = "unknown",
            output = "No exit status supplied"
        )

        assertFalse(frame.exitCode == 0)
    }
}
