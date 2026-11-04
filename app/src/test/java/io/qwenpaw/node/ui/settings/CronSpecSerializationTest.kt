package io.qwenpaw.node.ui.settings

import io.qwenpaw.node.data.model.CRON_TIMEZONE
import io.qwenpaw.node.data.model.CronJobSpecDto
import io.qwenpaw.node.data.model.CronScheduleDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.TimeZone

/**
 * 定时任务请求体的序列化。
 *
 * 两处最容易出错的地方：`PUT` 是整体替换，`meta` / `request` 里的未知键
 * 若被丢掉，编辑一次就会抹掉迁移审阅锁与模型覆盖；`save_result_to_inbox`
 * 为 null 时不能写成 `null`，服务端要的是「不发这个键」。
 */
class CronSpecSerializationTest {

    companion object {
        private var original: TimeZone? = null

        @BeforeClass
        @JvmStatic
        fun pinUtc() {
            original = TimeZone.getDefault()
            // 服务端与 CI runner 都是 UTC，这里显式固定，验证默认时区与设备无关。
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        }

        @AfterClass
        @JvmStatic
        fun restoreZone() {
            original?.let { TimeZone.setDefault(it) }
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Test
    fun `unknown keys survive a request round trip`() {
        val request = JsonObject(
            mapOf(
                "input" to JsonPrimitive("你好"),
                "model" to JsonPrimitive("qwen-max"),
            ),
        )
        val meta = JsonObject(
            mapOf(
                "portability" to JsonObject(mapOf("requires_review" to JsonPrimitive(true))),
                "safety" to JsonPrimitive("disabled_until_explicit_promotion"),
            ),
        )
        val spec = CronJobSpecDto(id = "job-1", name = "测试", request = request, meta = meta)

        val encoded = json.encodeToJsonElement(CronJobSpecDto.serializer(), spec)
            .jsonObject.toString()
        val decoded = json.decodeFromString(CronJobSpecDto.serializer(), encoded)

        assertEquals("qwen-max", (decoded.request?.get("model") as JsonPrimitive).content)
        assertTrue(decoded.meta.containsKey("portability"))
        assertEquals(
            "disabled_until_explicit_promotion",
            (decoded.meta["safety"] as JsonPrimitive).content,
        )
    }

    @Test
    fun `null save result is omitted rather than sent as null`() {
        val spec = CronJobSpecDto(name = "x", saveResultToInbox = null)
        val body = json.encodeToJsonElement(CronJobSpecDto.serializer(), spec).jsonObject
        assertFalse(body.containsKey("save_result_to_inbox"))
    }

    @Test
    fun `timezone defaults to beijing regardless of device zone`() {
        assertEquals("UTC", TimeZone.getDefault().id)
        assertEquals(CRON_TIMEZONE, CronScheduleDto().timezone)
        val body = json.encodeToJsonElement(
            CronJobSpecDto.serializer(),
            CronJobSpecDto(name = "x", schedule = CronScheduleDto(cron = "0 9 * * *")),
        ).jsonObject
        assertEquals(
            CRON_TIMEZONE,
            (body["schedule"]?.jsonObject?.get("timezone") as JsonPrimitive).content,
        )
    }

    @Test
    fun `editing a draft keeps the original id and enabled state`() {
        val original = CronJobSpecDto(id = "job-9", name = "旧名", enabled = false)
        val draft = CronDraft.from(original).copy(name = "新名")

        val spec = draft.toSpec()

        assertEquals("job-9", spec.id)
        // enabled 必须原样带回，否则一次改名就把暂停的任务重新启用。
        assertFalse(spec.enabled)
    }

    @Test
    fun `new draft is enabled by default`() {
        val spec = CronDraft.from(null).toSpec()
        assertEquals(null, spec.id)
        assertTrue(spec.enabled)
    }
}