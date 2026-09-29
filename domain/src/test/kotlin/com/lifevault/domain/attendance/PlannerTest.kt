package com.lifevault.domain.attendance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlannerTest {

    @Test
    fun `can skip matches floor of p over r minus t`() {
        assertEquals(4, Planner.canSkip(30, 36, 75)) // 30/40 = 75%
        assertEquals(0, Planner.canSkip(3, 5, 75))
        assertEquals(2, Planner.canSkip(9, 10, 75)) // 9/12 = 75%
        assertEquals(8, Planner.canSkip(9, 10, 50)) // 9/18 = 50%
    }

    @Test
    fun `skip count keeps you at or above threshold`() {
        for (t in 0..60) for (p in 0..t) for (r in listOf(50, 60, 65, 75, 80, 85, 90)) {
            val skip = Planner.canSkip(p, t, r)
            if (t + skip > 0 && p * 100 >= r * t) {
                assertTrueMsg(p * 100 >= r * (t + skip), "p=$p t=$t r=$r skip=$skip should stay >= r")
            }
            // One more skip would drop below the threshold.
            assertTrueMsg(p * 100 < r * (t + skip + 1), "p=$p t=$t r=$r skip=$skip is not maximal")
        }
    }

    @Test
    fun `must attend is the minimal recovery`() {
        for (t in 0..60) for (p in 0..t) for (r in listOf(50, 60, 65, 75, 80, 85, 90, 99)) {
            val n = Planner.mustAttend(p, t, r)!!
            assertTrueMsg((p + n) * 100 >= r * (t + n), "p=$p t=$t r=$r n=$n should reach r")
            if (n > 0) assertTrueMsg((p + n - 1) * 100 < r * (t + n - 1), "p=$p t=$t r=$r n=$n is not minimal")
        }
    }

    @Test
    fun `at 75 percent must attend equals 3t minus 4p`() {
        for (t in 0..40) for (p in 0..t) {
            assertEquals(maxOf(0, 3 * t - 4 * p), Planner.mustAttend(p, t, 75))
        }
    }

    @Test
    fun `exact threshold means skip zero and attend zero`() {
        assertEquals(0, Planner.canSkip(3, 4, 75))
        assertEquals(0, Planner.mustAttend(3, 4, 75))
        assertEquals(0, Planner.canSkip(15, 20, 75))
        assertEquals(0, Planner.mustAttend(15, 20, 75))
    }

    @Test
    fun `zero sessions`() {
        assertEquals(0, Planner.canSkip(0, 0, 75))
        assertEquals(0, Planner.mustAttend(0, 0, 75))
    }

    @Test
    fun `all absent`() {
        assertEquals(0, Planner.canSkip(0, 4, 75))
        assertEquals(12, Planner.mustAttend(0, 4, 75))
    }

    @Test
    fun `hundred percent threshold`() {
        assertEquals(0, Planner.canSkip(5, 5, 100))
        assertEquals(0, Planner.mustAttend(5, 5, 100))
        assertNull(Planner.mustAttend(4, 5, 100))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `present above conducted is rejected`() {
        Planner.canSkip(5, 4, 75)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero threshold is rejected`() {
        Planner.mustAttend(1, 1, 0)
    }

    @Test
    fun `planner uses marked sessions and reports pending separately`() {
        val res = Planner.forTally(Tally(present = 6, absent = 4, pending = 3), 75)
        assertEquals(6, res.mustAttend)
        assertEquals(0, res.canSkip)
        assertEquals(3, res.unmarked)
    }

    private fun assertTrueMsg(cond: Boolean, msg: String) { if (!cond) throw AssertionError(msg) }
}
