package dev.arrbrants.controller

import dev.arrbrants.controller.llm.DeepSeekBalance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BalanceTest {

    @Test
    fun detectsOfficialHostOnly() {
        assertTrue(DeepSeekBalance.isOfficial("https://api.deepseek.com"))
        assertTrue(DeepSeekBalance.isOfficial("https://api.deepseek.com/v1"))
        assertFalse(DeepSeekBalance.isOfficial("https://proxy.example.com/v1"))
        assertFalse(DeepSeekBalance.isOfficial("https://api.deepseek.com.evil.test"))
        assertFalse(DeepSeekBalance.isOfficial(""))
    }

    @Test
    fun parsesCnyBalance() {
        val balance = DeepSeekBalance.parse(
            """
            {"is_available":true,"balance_infos":[
              {"currency":"CNY","total_balance":"110.00",
               "granted_balance":"0.00","topped_up_balance":"110.00"}
            ]}
            """.trimIndent()
        )
        assertEquals("CNY", balance?.currency)
        assertEquals("110.00", balance?.total)
        assertEquals("¥110.00", balance?.display)
        assertEquals(true, balance?.available)
    }

    @Test
    fun parsesUsdBalance() {
        val balance = DeepSeekBalance.parse(
            """{"is_available":true,"balance_infos":[{"currency":"USD","total_balance":"4.20"}]}"""
        )
        assertEquals("$4.20", balance?.display)
    }

    @Test
    fun malformedOrEmptyReturnsNull() {
        assertNull(DeepSeekBalance.parse("not json"))
        assertNull(DeepSeekBalance.parse("""{"balance_infos":[]}"""))
        assertNull(DeepSeekBalance.parse("""{"balance_infos":[{"currency":"CNY"}]}"""))
    }
}
