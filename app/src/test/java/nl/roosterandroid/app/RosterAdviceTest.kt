package nl.roosterandroid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RosterAdviceTest {

    private fun row(
        hourDifference: Double = 0.0,
        targetHours: Double = 160.0,
        atwErrors: Int = 0,
        atwWarnings: Int = 0,
        weekendOverload: Double = 0.0
    ): RosterPriorityRow =
        RosterPriorityRow(
            quality =
                RosterQualityRow(
                    employeeId =
                        "manager-1",
                    employeeName =
                        "Daniel",
                    role =
                        EmployeeRole.MANAGER,
                    plannedHours =
                        targetHours +
                            hourDifference,
                    targetHours =
                        targetHours,
                    hourDifference =
                        hourDifference,
                    shifts =
                        20,
                    weekendShifts =
                        2,
                    manualShifts =
                        0,
                    atwErrors =
                        atwErrors,
                    issues =
                        emptyList()
                ),
            atwWarnings =
                atwWarnings,
            weekendsWorked =
                2,
            teamAverageWeekends =
                2.0,
            weekendOverload =
                weekendOverload,
            priorityScore =
                0,
            level =
                RosterPriorityLevel.OK,
            reasons =
                emptyList()
        )

    @Test
    fun atwErrorIsFirstAdvice() {
        val advice =
            row(
                hourDifference =
                    -20.0,
                atwErrors =
                    1
            ).recommendedActions()

        assertEquals(
            RosterAdviceType.FIX_ATW,
            advice.first().type
        )
    }

    @Test
    fun overloadedCloseShiftsProduceAdvice() {
        val advice =
            row().copy(
                closeShifts = 5,
                closeOverload = 2.0
            ).recommendedActions()

        assertEquals(
            RosterAdviceType.REDISTRIBUTE_CLOSE,
            advice.single().type
        )
    }

    @Test
    fun underContractSuggestsExtraHours() {
        val advice =
            row(
                hourDifference =
                    -24.0
            ).primaryAdvice()

        assertEquals(
            RosterAdviceType.ADD_HOURS,
            advice?.type
        )

        assertTrue(
            advice?.detail
                ?.contains("24 uur") ==
                true
        )
    }

    @Test
    fun overContractSuggestsReducingHours() {
        val advice =
            row(
                hourDifference =
                    16.0
            ).primaryAdvice()

        assertEquals(
            RosterAdviceType.REDUCE_HOURS,
            advice?.type
        )
    }

    @Test
    fun weekendOverloadSuggestsRedistribution() {
        val advice =
            row(
                targetHours =
                    0.0,
                weekendOverload =
                    1.5
            ).primaryAdvice()

        assertEquals(
            RosterAdviceType.REDISTRIBUTE_WEEKENDS,
            advice?.type
        )
    }

    @Test
    fun healthyRosterNeedsNoAdvice() {
        val advice =
            row().primaryAdvice()

        assertNull(advice)
    }

    @Test
    fun multipleProblemsProduceMultipleActions() {
        val advice =
            row(
                hourDifference =
                    -20.0,
                atwErrors =
                    1,
                atwWarnings =
                    2,
                weekendOverload =
                    1.0
            ).recommendedActions()

        assertEquals(
            4,
            advice.size
        )

        assertEquals(
            RosterAdviceType.FIX_ATW,
            advice[0].type
        )
    }
}
