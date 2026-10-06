package com.wallpaperswitcher.engine

import com.wallpaperswitcher.data.OnlineSource
import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineBuiltinsTest {

    @Test
    fun onlyEnabledBuiltinProvidersAreScheduledOrRefreshed() {
        val rows = listOf(
            OnlineSource(id = 1, type = OnlineSource.TYPE_BING, enabled = true),
            OnlineSource(id = 2, type = OnlineSource.TYPE_NETBIAN, enabled = false),
            // 旧版遗留的其它类型即使还是 enabled，也不能再被排期。
            OnlineSource(id = 3, type = OnlineSource.TYPE_MEIRENTU, enabled = true),
            OnlineSource(id = 4, type = OnlineSource.TYPE_URL, enabled = true),
        )
        assertEquals(listOf(1L), OnlineBuiltins.builtinEnabled(rows).map { it.id })
    }
}
