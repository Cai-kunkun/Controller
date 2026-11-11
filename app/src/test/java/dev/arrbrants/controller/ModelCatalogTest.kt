package dev.arrbrants.controller

import dev.arrbrants.controller.llm.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelCatalogTest {

    @Test
    fun parsesOpenAiStyleModelList() {
        val models = ModelCatalog.parse(
            """{"object":"list","data":[{"id":"deepseek-chat"},{"id":"deepseek-flash"},""" +
                """{"id":"deepseek-chat"}]}"""
        )
        assertEquals(listOf("deepseek-chat", "deepseek-flash"), models)
    }

    @Test
    fun malformedOrEmptyListsReturnEmpty() {
        assertTrue(ModelCatalog.parse("not json").isEmpty())
        assertTrue(ModelCatalog.parse("""{"data":[]}""").isEmpty())
        assertTrue(ModelCatalog.parse("""{"data":[{"no_id":true}]}""").isEmpty())
    }
}
