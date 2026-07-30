package dev.deftmartian.runway

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeNavigationLayoutTest {
    @Test
    fun `uses bottom navigation below the compact width breakpoint`() {
        assertEquals(NativeNavigationLayout.BottomBar, nativeNavigationLayoutForWidth(599.9f))
    }

    @Test
    fun `uses a rail at and above the expanded width breakpoint`() {
        assertEquals(NativeNavigationLayout.Rail, nativeNavigationLayoutForWidth(600f))
        assertEquals(NativeNavigationLayout.Rail, nativeNavigationLayoutForWidth(960f))
    }
}
