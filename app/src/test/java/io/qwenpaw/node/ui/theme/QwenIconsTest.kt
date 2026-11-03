package io.qwenpaw.node.ui.theme

import androidx.compose.ui.graphics.vector.ImageVector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 界面图标的完整性。
 *
 * 图标由 lucide 的 SVG 一次性转换而来，转换有三个容易出错的地方，都靠这里守住：
 *
 * 1. **属性名撞 Kotlin 关键字。** `package` 直接当属性名编译不过，已改名 `packageIcon`。
 * 2. **路径为空。** 只含 `circle / rect / line` 的图标若在转换时漏掉某条分支，
 *    会得到一个什么都不画的空矢量，界面上表现为图标位置是空的。
 * 3. **视口与名字不一致。** 全部必须是 24×24，且属性名与内部名字一一对应，
 *    否则以后按名字排查时找不到对应关系。
 *
 * `ImageVector` 把路径藏在内部实现里，JVM 单测拿不到；这里退一步守住可观测的几项，
 * 真正的「画出来对不对」由模拟器走查确认。
 */
class QwenIconsTest {

    /** 全量清单：新增图标时同步补一行，避免加了图标却没被检查到。 */
    private val all: Map<String, ImageVector> = mapOf(
        "settings" to QwenIcons.settings,
        "server" to QwenIcons.server,
        "user" to QwenIcons.user,
        "palette" to QwenIcons.palette,
        "messageSquare" to QwenIcons.messageSquare,
        "mic" to QwenIcons.mic,
        "bell" to QwenIcons.bell,
        "receiptText" to QwenIcons.receiptText,
        "info" to QwenIcons.info,
        "chevronRight" to QwenIcons.chevronRight,
        "chevronDown" to QwenIcons.chevronDown,
        "chevronUp" to QwenIcons.chevronUp,
        "plus" to QwenIcons.plus,
        "check" to QwenIcons.check,
        "close" to QwenIcons.close,
        "arrowLeft" to QwenIcons.arrowLeft,
        "arrowUp" to QwenIcons.arrowUp,
        "search" to QwenIcons.search,
        "delete" to QwenIcons.delete,
        "archive" to QwenIcons.archive,
        "pin" to QwenIcons.pin,
        "pencil" to QwenIcons.pencil,
        "copy" to QwenIcons.copy,
        "fileText" to QwenIcons.fileText,
        "fileCode" to QwenIcons.fileCode,
        "alignLeft" to QwenIcons.alignLeft,
        "share" to QwenIcons.share,
        "refresh" to QwenIcons.refresh,
        "folder" to QwenIcons.folder,
        "bot" to QwenIcons.bot,
        "brain" to QwenIcons.brain,
        "shieldCheck" to QwenIcons.shieldCheck,
        "tag" to QwenIcons.tag,
        "star" to QwenIcons.star,
        "moreVertical" to QwenIcons.moreVertical,
        "send" to QwenIcons.send,
        "paperclip" to QwenIcons.paperclip,
        "globe" to QwenIcons.globe,
        "clock" to QwenIcons.clock,
        "code" to QwenIcons.code,
        "lightbulb" to QwenIcons.lightbulb,
        "packageIcon" to QwenIcons.packageIcon,
        "languages" to QwenIcons.languages,
        "wrench" to QwenIcons.wrench,
        "clipboardList" to QwenIcons.clipboardList,
        "alertTriangle" to QwenIcons.alertTriangle,
        "messageCircle" to QwenIcons.messageCircle,
        "briefcase" to QwenIcons.briefcase,
        "sparkles" to QwenIcons.sparkles,
        "cornerDownLeft" to QwenIcons.cornerDownLeft,
        "circleHelp" to QwenIcons.circleHelp,
        "zap" to QwenIcons.zap,
        "flaskConical" to QwenIcons.flaskConical,
        "testTube" to QwenIcons.testTube,
        "microscope" to QwenIcons.microscope,
        "bookOpen" to QwenIcons.bookOpen,
        "listBullet" to QwenIcons.listBullet,
        "barChart" to QwenIcons.barChart,
        "library" to QwenIcons.library,
        "filePen" to QwenIcons.filePen,
        "filePlus" to QwenIcons.filePlus,
        "scanSearch" to QwenIcons.scanSearch,
        "mailCheck" to QwenIcons.mailCheck,
        "layers" to QwenIcons.layers,
        "blocks" to QwenIcons.blocks,
        "gauge" to QwenIcons.gauge,
        "sendPlane" to QwenIcons.sendPlane,
        "listChecks" to QwenIcons.listChecks,
        "messagesSquare" to QwenIcons.messagesSquare,
        "users" to QwenIcons.users,
        "panelsTopLeft" to QwenIcons.panelsTopLeft,
        "workflow" to QwenIcons.workflow,
        "image" to QwenIcons.image,
        "monitor" to QwenIcons.monitor,
        "folderSearch" to QwenIcons.folderSearch,
        "terminal" to QwenIcons.terminal,
        "checkCheck" to QwenIcons.checkCheck,
        "volume" to QwenIcons.volume,
    )

    /** 全部按 24×24 视口构建，同一行里混用才不会有大小差异。 */
    @Test
    fun `every icon uses a 24 by 24 viewport`() {
        all.forEach { (key, icon) ->
            assertEquals("$key 的视口宽度", 24f, icon.viewportWidth, 0f)
            assertEquals("$key 的视口高度", 24f, icon.viewportHeight, 0f)
            assertEquals("$key 的默认宽度", 24f, icon.defaultWidth.value, 0f)
            assertEquals("$key 的默认高度", 24f, icon.defaultHeight.value, 0f)
        }
    }

    /** 内部名字要与属性名一致，按名字排查时才对得上。 */
    @Test
    fun `icon name matches its property`() {
        all.forEach { (key, icon) ->
            assertEquals(key, icon.name)
        }
    }

    /** 两个图标取到同一个对象说明映射写错了（复制粘贴时最容易发生）。 */
    @Test
    fun `icons are distinct`() {
        val duplicates = all.entries
            .groupBy({ it.value.name }, { it.key })
            .filterValues { it.size > 1 }
        assertTrue("以下图标指向同一个对象：$duplicates", duplicates.isEmpty())
    }
}