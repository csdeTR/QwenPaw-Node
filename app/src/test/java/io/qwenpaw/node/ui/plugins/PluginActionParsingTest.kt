package io.qwenpaw.node.ui.plugins

import io.qwenpaw.node.data.model.ApiErrorDetailDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件 409 归类与依赖升级状态的纯逻辑单测。
 *
 * 这些断言防的是「同一状态码读错形状」：409 在安装时可能是依赖升级、
 * 在配置写入时可能是 legacy 钩子、在启停时可能是任务还在跑，三者对用户的
 * 处置完全不同。判错一个方向，用户就会去点「覆盖安装」或以为插件已经装好。
 */
class PluginActionParsingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun raw(text: String) = json.parseToJsonElement(text.trimIndent()).jsonObject

    private fun detailOf(text: String): ApiErrorDetailDto =
        json.decodeFromString(ApiErrorDetailDto.serializer(), text.trimIndent())

    // ---------- 409 归类 ----------

    @Test
    fun `dependency upgrade conflict exposes the operation id`() {
        val conflict = pluginConflictOf(
            detailOf(
                """{"code":"dependency_upgrade_required",
                   "message":"dependencies must be upgraded",
                   "operation":{"operation_id":"op-1"}}""",
            ),
        )
        assertTrue(conflict is PluginConflict.DependencyUpgrade)
        assertEquals("op-1", (conflict as PluginConflict.DependencyUpgrade).operationId)
    }

    @Test
    fun `operation object alone is still a dependency upgrade`() {
        // 服务端未定型 code，命中 operation 对象就足以进入升级页；这里同时确认
        // 它压过同一 detail 里的 legacy 钩子与任务标志。
        val conflict = pluginConflictOf(
            detailOf(
                """{"code":"some_other_conflict","message":"conflict",
                   "legacy_hooks":["onUninstall"],"quiescent":false,
                   "operation":{"id":"op-2"}}""",
            ),
        )
        assertEquals(PluginConflict.DependencyUpgrade("op-2"), conflict)
    }

    @Test
    fun `operation id falls back across known keys and skips blanks`() {
        assertEquals("a", dependencyOperationIdOf(raw("""{"operation_id":"a"}""")))
        assertEquals("b", dependencyOperationIdOf(raw("""{"operationId":"b"}""")))
        assertEquals("c", dependencyOperationIdOf(raw("""{"upgrade_id":"c"}""")))
        assertEquals("d", dependencyOperationIdOf(raw("""{"id":"d","operation_id":"e"}""")))
        assertEquals(
            "e",
            dependencyOperationIdOf(raw("""{"id":"  ","operation_id":"e"}""")),
        )
        assertNull(dependencyOperationIdOf(raw("""{"id":{"nested":1}}""")))
        assertNull(dependencyOperationIdOf(raw("""{"id":null}""")))
        assertNull(dependencyOperationIdOf(null))
    }

    @Test
    fun `legacy hooks conflict keeps the hook list`() {
        val withHooks = pluginConflictOf(
            detailOf("""{"code":"legacy_hooks_conflict","legacy_hooks":["onUninstall","onDisable"]}"""),
        )
        assertEquals(PluginConflict.LegacyHooks(listOf("onUninstall", "onDisable")), withHooks)

        val codeOnly = pluginConflictOf(detailOf("""{"code":"legacy_hooks_conflict"}"""))
        assertEquals(PluginConflict.LegacyHooks(emptyList()), codeOnly)
    }

    @Test
    fun `running tasks conflict is detected by flag or code`() {
        assertEquals(
            PluginConflict.RunningTasks("2 tasks running"),
            pluginConflictOf(detailOf("""{"quiescent":false,"message":"2 tasks running"}""")),
        )
        assertEquals(
            PluginConflict.RunningTasks("confirm"),
            pluginConflictOf(detailOf("""{"requires_confirmation":true,"message":"confirm"}""")),
        )
        assertEquals(
            PluginConflict.RunningTasks("busy"),
            pluginConflictOf(detailOf("""{"code":"plugin_tasks_running","message":"busy"}""")),
        )
    }

    @Test
    fun `unknown conflict keeps the server message`() {
        assertEquals(
            PluginConflict.Other("same name exists"),
            pluginConflictOf(detailOf("""{"code":"duplicate_id","message":"same name exists"}""")),
        )
        assertEquals(PluginConflict.Other(""), pluginConflictOf(null))
    }

    @Test
    fun `detail json with missing keys decodes to defaults`() {
        // 服务端在不同端点给的键不同，缺键不能让解析抛异常；
        // 解析成功但读不懂时回退到原文，不臆造结论。
        val minimal = detailOf("""{"code":"legacy_hooks_conflict"}""")
        assertEquals("", minimal.message)
        assertTrue(minimal.quiescent)
        assertEquals(PluginConflict.LegacyHooks(emptyList()), pluginConflictOf(minimal))
        assertEquals(PluginConflict.Other(""), pluginConflictOf(detailOf("{}")))
    }

    // ---------- 阶段与轮询 ----------

    @Test
    fun `stage mapping covers server synonyms`() {
        listOf("preparing", "pending", "planning", "resolving").forEach {
            assertEquals(it, DependencyStage.PREPARING, stageOf(it))
        }
        listOf("needs_confirmation", "awaiting_confirmation", "awaiting_approval").forEach {
            assertEquals(it, DependencyStage.NEEDS_CONFIRMATION, stageOf(it))
        }
        listOf("applying", "installing", "upgrading", "running", "in_progress").forEach {
            assertEquals(it, DependencyStage.APPLYING, stageOf(it))
        }
        listOf("waiting_restart", "needs_restart", "restart_required").forEach {
            assertEquals(it, DependencyStage.WAITING_RESTART, stageOf(it))
        }
        listOf("repair_required", "repairs_required").forEach {
            assertEquals(it, DependencyStage.REPAIR_REQUIRED, stageOf(it))
        }
        listOf("succeeded", "success", "completed", "done", "applied").forEach {
            assertEquals(it, DependencyStage.SUCCEEDED, stageOf(it))
        }
        listOf("failed", "error").forEach {
            assertEquals(it, DependencyStage.FAILED, stageOf(it))
        }
        listOf("cancelled", "canceled").forEach {
            assertEquals(it, DependencyStage.CANCELLED, stageOf(it))
        }
        assertEquals(DependencyStage.APPLYING, stageOf("  APPLYING  "))
        assertEquals(DependencyStage.UNKNOWN, stageOf("mystery_state"))
        assertEquals(DependencyStage.UNKNOWN, stageOf(""))
    }

    @Test
    fun `terminal stages stop polling and waiting restart pauses it`() {
        DependencyUpgradeState("op", stage = DependencyStage.SUCCEEDED).let {
            assertTrue(it.terminal)
            assertFalse(it.shouldPoll)
        }
        DependencyUpgradeState("op", stage = DependencyStage.FAILED).let {
            assertTrue(it.terminal)
            assertFalse(it.shouldPoll)
        }
        DependencyUpgradeState("op", stage = DependencyStage.CANCELLED).let {
            assertTrue(it.terminal)
            assertFalse(it.shouldPoll)
        }
        DependencyUpgradeState("op", stage = DependencyStage.WAITING_RESTART).let {
            assertFalse(it.terminal)
            assertFalse(it.shouldPoll)
        }
        listOf(
            DependencyStage.PREPARING,
            DependencyStage.NEEDS_CONFIRMATION,
            DependencyStage.APPLYING,
            DependencyStage.REPAIR_REQUIRED,
            DependencyStage.UNKNOWN,
        ).forEach { stage ->
            val state = DependencyUpgradeState("op", stage = stage)
            assertFalse(stage.name, state.terminal)
            assertTrue(stage.name, state.shouldPoll)
        }
    }

    @Test
    fun `unknown stage keeps the raw text and keeps polling`() {
        val state = dependencyStateOf("op-1", raw("""{"status":"mystery_state"}"""))
        assertEquals("op-1", state.operationId)
        assertEquals(DependencyStage.UNKNOWN, state.stage)
        assertEquals("mystery_state", state.statusText)
        assertTrue(state.shouldPoll)
    }

    @Test
    fun `waiting restart is inferred from needs restart`() {
        val state = dependencyStateOf("op-1", raw("""{"needs_restart":true}"""))
        assertEquals(DependencyStage.WAITING_RESTART, state.stage)
        assertEquals("", state.statusText)
        assertFalse(state.shouldPoll)
    }

    @Test
    fun `needs confirmation folds a running stage but not a terminal one`() {
        val running = dependencyStateOf(
            "op-1",
            raw("""{"status":"applying","needs_confirmation":true}"""),
        )
        assertEquals(DependencyStage.NEEDS_CONFIRMATION, running.stage)
        assertTrue(running.needsConfirmation)
        assertTrue(running.shouldPoll)

        val alias = dependencyStateOf(
            "op-1",
            raw("""{"status":"applying","requires_confirmation":true}"""),
        )
        assertEquals(DependencyStage.NEEDS_CONFIRMATION, alias.stage)

        val terminal = dependencyStateOf(
            "op-1",
            raw("""{"status":"succeeded","needs_confirmation":true}"""),
        )
        assertEquals(DependencyStage.SUCCEEDED, terminal.stage)
        assertTrue(terminal.terminal)
    }

    @Test
    fun `needs confirmation does not pull waiting restart back`() {
        val state = dependencyStateOf(
            "op-1",
            raw("""{"status":"waiting_restart","needs_confirmation":true}"""),
        )
        assertEquals(DependencyStage.WAITING_RESTART, state.stage)
        assertFalse(state.shouldPoll)
    }

    @Test
    fun `state key message error and commands are read defensively`() {
        val state = dependencyStateOf(
            "op-1",
            raw(
                """{"state":"waiting_restart","message":"restart the service",
                   "error":"install incomplete","can_cancel":true,
                   "maintenance_commands":["systemctl restart qwenpaw","  ",
                     {"command":"pip install -U qwenpaw"},{"cmd":""}]}""",
            ),
        )
        assertEquals(DependencyStage.WAITING_RESTART, state.stage)
        assertEquals("waiting_restart", state.statusText)
        assertEquals("restart the service", state.message)
        assertEquals("install incomplete", state.error)
        assertTrue(state.canCancel)
        assertEquals(
            listOf("systemctl restart qwenpaw", "pip install -U qwenpaw"),
            state.maintenanceCommands,
        )
    }

    @Test
    fun `state with unknown shapes falls back to empty defaults`() {
        val empty = dependencyStateOf("op-1", raw("{}"))
        assertEquals(DependencyStage.UNKNOWN, empty.stage)
        assertEquals("", empty.statusText)
        assertEquals("", empty.message)
        assertEquals("", empty.error)
        assertFalse(empty.needsConfirmation)
        assertFalse(empty.canCancel)
        assertTrue(empty.maintenanceCommands.isEmpty())

        val wrongShapes = dependencyStateOf(
            "op-1",
            raw("""{"status":{},"needs_restart":"yes","maintenance_commands":"nope"}"""),
        )
        assertEquals(DependencyStage.UNKNOWN, wrongShapes.stage)
        assertTrue(wrongShapes.maintenanceCommands.isEmpty())
    }

    // ---------- 配置文本 ----------

    @Test
    fun `config draft must be a json object`() {
        assertTrue(isConfigDraftValid("""{"a":1}"""))
        assertTrue(isConfigDraftValid("""  {"nested":{"b":[1,2]}}  """))
        assertFalse(isConfigDraftValid("""[1,2]"""))
        assertFalse(isConfigDraftValid("\"just a string\""))
        assertFalse(isConfigDraftValid("1"))
        assertFalse(isConfigDraftValid("{broken"))
        assertFalse(isConfigDraftValid(""))
        assertFalse(isConfigDraftValid("   "))
    }

    @Test
    fun `config object of rejects non object payloads`() {
        val parsed = configObjectOf(""" {"a":{"b":2}} """)
        assertEquals("2", parsed!!["a"]!!.jsonObject["b"]!!.jsonPrimitive.content)
        assertNull(configObjectOf("[1,2]"))
        assertNull(configObjectOf("\"text\""))
        assertNull(configObjectOf("{broken"))
        assertNull(configObjectOf(""))
    }

    @Test
    fun `config unchanged flag and error list are read defensively`() {
        assertTrue(isConfigUnchanged(detailOf("""{"unchanged":true}""")))
        assertFalse(isConfigUnchanged(detailOf("""{"unchanged":false}""")))
        assertFalse(isConfigUnchanged(detailOf("{}")))
        assertFalse(isConfigUnchanged(null))

        assertEquals(
            listOf("first error", "second error"),
            configErrorsOf(detailOf("""{"errors":[" first error ","","first error","second error"]}""")),
        )
        assertTrue(configErrorsOf(detailOf("{}")).isEmpty())
        assertTrue(configErrorsOf(null).isEmpty())
    }
}
