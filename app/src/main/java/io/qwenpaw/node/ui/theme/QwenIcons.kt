package io.qwenpaw.node.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 界面图标，取自 lucide（版本 0.400.0，ISC 许可）。
 *
 * 用这一套而不是 Material 的理由是视觉对齐：QwenPaw 官方网页的控制台使用
 * `lucide-react`，实测其 `ui-vendor` 打包产物里有 232 处引用。两边用同一套几何图形，
 * 图标形状、线宽与圆角才对得上。
 *
 * 生成方式：从 `lucide-static@0.400.0/icons/<name>.svg` 取原始 SVG，
 * 把 `path / circle / rect / line / polyline / polygon` 转成等价的绝对坐标 path 数据，
 * 再按统一的 24×24 视口、2px 线宽、圆头圆角重建。转换脚本是一次性的，不入仓库。
 *
 * **转换正确性靠渲染对比验证。** 把原始 SVG 与转换后的 path 并排渲染成位图，
 * 逐图标计算墨迹的 IoU，全部要求 ≥ 0.98。这一步不能省：`circle` 的起点与
 * 圆角矩形四段的顺序都极易写错，写错后图标仍然能编译、能显示，只是形状是坏的。
 *
 * 着色交给调用方的 `Icon(tint = …)`：这里一律用 [Color.Black] 作描边色，
 * `Icon` 的 ColorFilter 会整体替换掉它，深色模式下不会出现一列黑色小图。
 */
object QwenIcons {

    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    /** lucide `settings`。 */
    val settings: ImageVector by lazy {
        icon("settings", "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z", "M9 12A3 3 0 1 0 15 12A3 3 0 1 0 9 12Z")
    }

    /** lucide `server`。 */
    val server: ImageVector by lazy {
        icon("server", "M4 2H20A2 2 0 0 1 22 4V8A2 2 0 0 1 20 10H4A2 2 0 0 1 2 8V4A2 2 0 0 1 4 2Z", "M4 14H20A2 2 0 0 1 22 16V20A2 2 0 0 1 20 22H4A2 2 0 0 1 2 20V16A2 2 0 0 1 4 14Z", "M6 6L6.01 6", "M6 18L6.01 18")
    }

    /** lucide `user`。 */
    val user: ImageVector by lazy {
        icon("user", "M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2", "M8 7A4 4 0 1 0 16 7A4 4 0 1 0 8 7Z")
    }

    /** lucide `palette`。 */
    val palette: ImageVector by lazy {
        icon("palette", "M13 6.5A0.5 0.5 0 1 0 14 6.5A0.5 0.5 0 1 0 13 6.5Z", "M17 10.5A0.5 0.5 0 1 0 18 10.5A0.5 0.5 0 1 0 17 10.5Z", "M8 7.5A0.5 0.5 0 1 0 9 7.5A0.5 0.5 0 1 0 8 7.5Z", "M6 12.5A0.5 0.5 0 1 0 7 12.5A0.5 0.5 0 1 0 6 12.5Z", "M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10c.926 0 1.648-.746 1.648-1.688 0-.437-.18-.835-.437-1.125-.29-.289-.438-.652-.438-1.125a1.64 1.64 0 0 1 1.668-1.668h1.996c3.051 0 5.555-2.503 5.555-5.554C21.965 6.012 17.461 2 12 2z")
    }

    /** lucide `message-square`。 */
    val messageSquare: ImageVector by lazy {
        icon("messageSquare", "M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z")
    }

    /** lucide `mic`。 */
    val mic: ImageVector by lazy {
        icon("mic", "M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z", "M19 10v2a7 7 0 0 1-14 0v-2", "M12 19L12 22")
    }

    /** lucide `bell`。 */
    val bell: ImageVector by lazy {
        icon("bell", "M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9", "M10.3 21a1.94 1.94 0 0 0 3.4 0")
    }

    /** lucide `receipt-text`。 */
    val receiptText: ImageVector by lazy {
        icon("receiptText", "M4 2v20l2-1 2 1 2-1 2 1 2-1 2 1 2-1 2 1V2l-2 1-2-1-2 1-2-1-2 1-2-1-2 1Z", "M14 8H8", "M16 12H8", "M13 16H8")
    }

    /** lucide `info`。 */
    val info: ImageVector by lazy {
        icon("info", "M2 12A10 10 0 1 0 22 12A10 10 0 1 0 2 12Z", "M12 16v-4", "M12 8h.01")
    }

    /** lucide `chevron-right`。 */
    val chevronRight: ImageVector by lazy {
        icon("chevronRight", "m9 18 6-6-6-6")
    }

    /** lucide `chevron-down`。 */
    val chevronDown: ImageVector by lazy {
        icon("chevronDown", "m6 9 6 6 6-6")
    }

    /** lucide `chevron-up`。 */
    val chevronUp: ImageVector by lazy {
        icon("chevronUp", "m18 15-6-6-6 6")
    }

    /** lucide `plus`。 */
    val plus: ImageVector by lazy {
        icon("plus", "M5 12h14", "M12 5v14")
    }

    /** lucide `check`。 */
    val check: ImageVector by lazy {
        icon("check", "M20 6 9 17l-5-5")
    }

    /** lucide `x`。 */
    val close: ImageVector by lazy {
        icon("close", "M18 6 6 18", "m6 6 12 12")
    }

    /** lucide `arrow-left`。 */
    val arrowLeft: ImageVector by lazy {
        icon("arrowLeft", "m12 19-7-7 7-7", "M19 12H5")
    }

    /** lucide `search`。 */
    val search: ImageVector by lazy {
        icon("search", "M3 11A8 8 0 1 0 19 11A8 8 0 1 0 3 11Z", "m21 21-4.3-4.3")
    }

    /** lucide `trash-2`。 */
    val delete: ImageVector by lazy {
        icon("delete", "M3 6h18", "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6", "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2", "M10 11L10 17", "M14 11L14 17")
    }

    /** lucide `archive`。 */
    val archive: ImageVector by lazy {
        icon("archive", "M3 3H21A1 1 0 0 1 22 4V7A1 1 0 0 1 21 8H3A1 1 0 0 1 2 7V4A1 1 0 0 1 3 3Z", "M4 8v11a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8", "M10 12h4")
    }

    /** lucide `pin`。 */
    val pin: ImageVector by lazy {
        icon("pin", "M12 17v5", "M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z")
    }

    /** lucide `pencil`。 */
    val pencil: ImageVector by lazy {
        icon("pencil", "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z", "m15 5 4 4")
    }

    /** lucide `copy`。 */
    val copy: ImageVector by lazy {
        icon("copy", "M10 8H20A2 2 0 0 1 22 10V20A2 2 0 0 1 20 22H10A2 2 0 0 1 8 20V10A2 2 0 0 1 10 8Z", "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2")
    }

    /** lucide `file-text`。 */
    val fileText: ImageVector by lazy {
        icon("fileText", "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z", "M14 2v4a2 2 0 0 0 2 2h4", "M10 9H8", "M16 13H8", "M16 17H8")
    }

    /** lucide `file-code`。 */
    val fileCode: ImageVector by lazy {
        icon("fileCode", "M10 12.5 8 15l2 2.5", "m14 12.5 2 2.5-2 2.5", "M14 2v4a2 2 0 0 0 2 2h4", "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7z")
    }

    /** lucide `align-left`。 */
    val alignLeft: ImageVector by lazy {
        icon("alignLeft", "M21 6L3 6", "M15 12L3 12", "M17 18L3 18")
    }

    /** lucide `share-2`。 */
    val share: ImageVector by lazy {
        icon("share", "M15 5A3 3 0 1 0 21 5A3 3 0 1 0 15 5Z", "M3 12A3 3 0 1 0 9 12A3 3 0 1 0 3 12Z", "M15 19A3 3 0 1 0 21 19A3 3 0 1 0 15 19Z", "M8.59 13.51L15.42 17.49", "M15.41 6.51L8.59 10.49")
    }

    /** lucide `refresh-cw`。 */
    val refresh: ImageVector by lazy {
        icon("refresh", "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8", "M21 3v5h-5", "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16", "M8 16H3v5")
    }

    /** lucide `folder`。 */
    val folder: ImageVector by lazy {
        icon("folder", "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z")
    }

    /** lucide `bot`。 */
    val bot: ImageVector by lazy {
        icon("bot", "M12 8V4H8", "M6 8H18A2 2 0 0 1 20 10V18A2 2 0 0 1 18 20H6A2 2 0 0 1 4 18V10A2 2 0 0 1 6 8Z", "M2 14h2", "M20 14h2", "M15 13v2", "M9 13v2")
    }

    /** lucide `brain`。 */
    val brain: ImageVector by lazy {
        icon("brain", "M12 5a3 3 0 1 0-5.997.125 4 4 0 0 0-2.526 5.77 4 4 0 0 0 .556 6.588A4 4 0 1 0 12 18Z", "M12 5a3 3 0 1 1 5.997.125 4 4 0 0 1 2.526 5.77 4 4 0 0 1-.556 6.588A4 4 0 1 1 12 18Z", "M15 13a4.5 4.5 0 0 1-3-4 4.5 4.5 0 0 1-3 4", "M17.599 6.5a3 3 0 0 0 .399-1.375", "M6.003 5.125A3 3 0 0 0 6.401 6.5", "M3.477 10.896a4 4 0 0 1 .585-.396", "M19.938 10.5a4 4 0 0 1 .585.396", "M6 18a4 4 0 0 1-1.967-.516", "M19.967 17.484A4 4 0 0 1 18 18")
    }

    /** lucide `shield-check`。 */
    val shieldCheck: ImageVector by lazy {
        icon("shieldCheck", "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z", "m9 12 2 2 4-4")
    }

    /** lucide `tag`。 */
    val tag: ImageVector by lazy {
        icon("tag", "M12.586 2.586A2 2 0 0 0 11.172 2H4a2 2 0 0 0-2 2v7.172a2 2 0 0 0 .586 1.414l8.704 8.704a2.426 2.426 0 0 0 3.42 0l6.58-6.58a2.426 2.426 0 0 0 0-3.42z", "M7 7.5A0.5 0.5 0 1 0 8 7.5A0.5 0.5 0 1 0 7 7.5Z")
    }

    /** lucide `star`。 */
    val star: ImageVector by lazy {
        icon("star", "M12 2L15.09 8.26L22 9.27L17 14.14L18.18 21.02L12 17.77L5.82 21.02L7 14.14L2 9.27L8.91 8.26L12 2Z")
    }

    /** lucide `ellipsis-vertical`。 */
    val moreVertical: ImageVector by lazy {
        icon("moreVertical", "M11 12A1 1 0 1 0 13 12A1 1 0 1 0 11 12Z", "M11 5A1 1 0 1 0 13 5A1 1 0 1 0 11 5Z", "M11 19A1 1 0 1 0 13 19A1 1 0 1 0 11 19Z")
    }

    /** lucide `send`。 */
    val send: ImageVector by lazy {
        icon("send", "m22 2-7 20-4-9-9-4Z", "M22 2 11 13")
    }

    /** lucide `paperclip`。 */
    val paperclip: ImageVector by lazy {
        icon("paperclip", "m21.44 11.05-9.19 9.19a6 6 0 0 1-8.49-8.49l8.57-8.57A4 4 0 1 1 18 8.84l-8.59 8.57a2 2 0 0 1-2.83-2.83l8.49-8.48")
    }

    /** lucide `globe`。 */
    val globe: ImageVector by lazy {
        icon("globe", "M2 12A10 10 0 1 0 22 12A10 10 0 1 0 2 12Z", "M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20", "M2 12h20")
    }

    /** lucide `clock`。 */
    val clock: ImageVector by lazy {
        icon("clock", "M2 12A10 10 0 1 0 22 12A10 10 0 1 0 2 12Z", "M12 6L12 12L16 14")
    }

    /** lucide `calendar-clock`。 */
    val calendarClock: ImageVector by lazy {
        icon(
            "calendarClock",
            "M21 7.5V6a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h3.5",
            "M16 2v4",
            "M8 2v4",
            "M3 10h5",
            "M17.5 17.5 16 16.3V14",
            "M10 16A6 6 0 1 0 22 16A6 6 0 1 0 10 16Z",
        )
    }

    /** lucide `play`。 */
    val play: ImageVector by lazy {
        icon("play", "M6 3L20 12L6 21L6 3Z")
    }

    /** lucide `code-xml`。 */
    val code: ImageVector by lazy {
        icon("code", "m18 16 4-4-4-4", "m6 8-4 4 4 4", "m14.5 4-5 16")
    }

    /** lucide `lightbulb`。 */
    val lightbulb: ImageVector by lazy {
        icon("lightbulb", "M15 14c.2-1 .7-1.7 1.5-2.5 1-.9 1.5-2.2 1.5-3.5A6 6 0 0 0 6 8c0 1 .2 2.2 1.5 3.5.7.7 1.3 1.5 1.5 2.5", "M9 18h6", "M10 22h4")
    }

    /** lucide `package`。 */
    val packageIcon: ImageVector by lazy {
        icon("packageIcon", "m7.5 4.27 9 5.15", "M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z", "m3.3 7 8.7 5 8.7-5", "M12 22V12")
    }

    /** lucide `languages`。 */
    val languages: ImageVector by lazy {
        icon("languages", "m5 8 6 6", "m4 14 6-6 2-3", "M2 5h12", "M7 2h1", "m22 22-5-10-5 10", "M14 18h6")
    }

    /** lucide `wrench`。 */
    val wrench: ImageVector by lazy {
        icon("wrench", "M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z")
    }

    /** lucide `clipboard-list`。 */
    val clipboardList: ImageVector by lazy {
        icon("clipboardList", "M9 2H15A1 1 0 0 1 16 3V5A1 1 0 0 1 15 6H9A1 1 0 0 1 8 5V3A1 1 0 0 1 9 2Z", "M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2", "M12 11h4", "M12 16h4", "M8 11h.01", "M8 16h.01")
    }

    /** lucide `triangle-alert`。 */
    val alertTriangle: ImageVector by lazy {
        icon("alertTriangle", "m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3", "M12 9v4", "M12 17h.01")
    }

    /** lucide `message-circle`。 */
    val messageCircle: ImageVector by lazy {
        icon("messageCircle", "M7.9 20A9 9 0 1 0 4 16.1L2 22Z")
    }

    /** lucide `briefcase`。 */
    val briefcase: ImageVector by lazy {
        icon("briefcase", "M16 20V4a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16", "M4 6H20A2 2 0 0 1 22 8V18A2 2 0 0 1 20 20H4A2 2 0 0 1 2 18V8A2 2 0 0 1 4 6Z")
    }

    /** lucide `sparkles`。 */
    val sparkles: ImageVector by lazy {
        icon("sparkles", "M9.937 15.5A2 2 0 0 0 8.5 14.063l-6.135-1.582a.5.5 0 0 1 0-.962L8.5 9.936A2 2 0 0 0 9.937 8.5l1.582-6.135a.5.5 0 0 1 .963 0L14.063 8.5A2 2 0 0 0 15.5 9.937l6.135 1.581a.5.5 0 0 1 0 .964L15.5 14.063a2 2 0 0 0-1.437 1.437l-1.582 6.135a.5.5 0 0 1-.963 0z", "M20 3v4", "M22 5h-4", "M4 17v2", "M5 18H3")
    }

    /** lucide `arrow-up`。 */
    val arrowUp: ImageVector by lazy {
        icon("arrowUp", "m5 12 7-7 7 7", "M12 19V5")
    }

    /** lucide `corner-down-left`。 */
    val cornerDownLeft: ImageVector by lazy {
        icon("cornerDownLeft", "M9 10L4 15L9 20", "M20 4v7a4 4 0 0 1-4 4H4")
    }

    /** lucide `circle-help`。 */
    val circleHelp: ImageVector by lazy {
        icon("circleHelp", "M2 12A10 10 0 1 0 22 12A10 10 0 1 0 2 12Z", "M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3", "M12 17h.01")
    }

    /** lucide `zap`。 */
    val zap: ImageVector by lazy {
        icon("zap", "M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z")
    }

    /** lucide `bar-chart-3`。 */
    val barChart: ImageVector by lazy {
        icon("barChart", "M3 3v18h18", "M18 17V9", "M13 17V5", "M8 17v-3")
    }

    /** lucide `library`。 */
    val library: ImageVector by lazy {
        icon("library", "m16 6 4 14", "M12 6v14", "M8 8v12", "M4 4v16")
    }

    /** lucide `flask-conical`。 */
    val flaskConical: ImageVector by lazy {
        icon("flaskConical", "M10 2v7.527a2 2 0 0 1-.211.896L4.72 20.55a1 1 0 0 0 .9 1.45h12.76a1 1 0 0 0 .9-1.45l-5.069-10.127A2 2 0 0 1 14 9.527V2", "M8.5 2h7", "M7 16h10")
    }

    /** lucide `test-tube`。 */
    val testTube: ImageVector by lazy {
        icon("testTube", "M14.5 2v17.5c0 1.4-1.1 2.5-2.5 2.5c-1.4 0-2.5-1.1-2.5-2.5V2", "M8.5 2h7", "M14.5 16h-5")
    }

    /** lucide `microscope`。 */
    val microscope: ImageVector by lazy {
        icon("microscope", "M6 18h8", "M3 22h18", "M14 22a7 7 0 1 0 0-14h-1", "M9 14h2", "M9 12a2 2 0 0 1-2-2V6h6v4a2 2 0 0 1-2 2Z", "M12 6V3a1 1 0 0 0-1-1H9a1 1 0 0 0-1 1v3")
    }

    /** lucide `book-open`。 */
    val bookOpen: ImageVector by lazy {
        icon("bookOpen", "M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z", "M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z")
    }

    /** lucide `list`。 */
    val listBullet: ImageVector by lazy {
        icon("listBullet", "M8 6L21 6", "M8 12L21 12", "M8 18L21 18", "M3 6L3.01 6", "M3 12L3.01 12", "M3 18L3.01 18")
    }

    // ---- 以下图标取自 lucide 0.562.0，用于工具页，与官方控制台 TOOL_PRESENTATION 同源 ----

    /** lucide `file-pen-line`。 */
    val filePen: ImageVector by lazy {
        icon(
            "filePen",
            "m18.226 5.226-2.52-2.52A2.4 2.4 0 0 0 14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-.351",
            "M21.378 12.626a1 1 0 0 0-3.004-3.004l-4.01 4.012a2 2 0 0 0-.506.854l-.837 2.87a.5.5 0 0 0 .62.62l2.87-.837a2 2 0 0 0 .854-.506z",
            "M8 18h1",
        )
    }

    /** lucide `file-plus-corner`。 */
    val filePlus: ImageVector by lazy {
        icon(
            "filePlus",
            "M11.35 22H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.706.706l3.588 3.588A2.4 2.4 0 0 1 20 8v5.35",
            "M14 2v5a1 1 0 0 0 1 1h5",
            "M14 19h6",
            "M17 16v6",
        )
    }

    /** lucide `scan-search`。 */
    val scanSearch: ImageVector by lazy {
        icon(
            "scanSearch",
            "M3 7V5a2 2 0 0 1 2-2h2",
            "M17 3h2a2 2 0 0 1 2 2v2",
            "M21 17v2a2 2 0 0 1-2 2h-2",
            "M7 21H5a2 2 0 0 1-2-2v-2",
            "M9 12A3 3 0 1 0 15 12A3 3 0 1 0 9 12Z",
            "m16 16-1.9-1.9",
        )
    }

    /** lucide `mail-check`。 */
    val mailCheck: ImageVector by lazy {
        icon(
            "mailCheck",
            "M22 13V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v12c0 1.1.9 2 2 2h8",
            "m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7",
            "m16 19 2 2 4-4",
        )
    }

    /** lucide `layers`。 */
    val layers: ImageVector by lazy {
        icon(
            "layers",
            "M12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83z",
            "M2 12a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 12",
            "M2 17a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 17",
        )
    }

    /** lucide `blocks`。 */
    val blocks: ImageVector by lazy {
        icon(
            "blocks",
            "M10 22V7a1 1 0 0 0-1-1H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-5a1 1 0 0 0-1-1H2",
            "M14 2H22A1 1 0 0 1 23 3V11A1 1 0 0 1 22 12H14A1 1 0 0 1 13 11V3A1 1 0 0 1 14 2Z",
        )
    }

    /** lucide `gauge`。 */
    val gauge: ImageVector by lazy {
        icon("gauge", "m12 14 4-4", "M3.34 19a10 10 0 1 1 17.32 0")
    }

    /** lucide `send`（0.562 几何）。与聊天页的 [send] 并行存在，后者沿用旧几何。 */
    val sendPlane: ImageVector by lazy {
        icon(
            "sendPlane",
            "M14.536 21.686a.5.5 0 0 0 .937-.024l6.5-19a.496.496 0 0 0-.635-.635l-19 6.5a.5.5 0 0 0-.024.937l7.93 3.18a2 2 0 0 1 1.112 1.11z",
            "m21.854 2.147-10.94 10.939",
        )
    }

    /** lucide `list-checks`。 */
    val listChecks: ImageVector by lazy {
        icon(
            "listChecks",
            "M13 5h8",
            "M13 12h8",
            "M13 19h8",
            "m3 17 2 2 4-4",
            "m3 7 2 2 4-4",
        )
    }

    /** lucide `messages-square`。 */
    val messagesSquare: ImageVector by lazy {
        icon(
            "messagesSquare",
            "M16 10a2 2 0 0 1-2 2H6.828a2 2 0 0 0-1.414.586l-2.202 2.202A.71.71 0 0 1 2 14.286V4a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z",
            "M20 9a2 2 0 0 1 2 2v10.286a.71.71 0 0 1-1.212.502l-2.202-2.202A2 2 0 0 0 17.172 19H10a2 2 0 0 1-2-2v-1",
        )
    }

    /** lucide `users`。 */
    val users: ImageVector by lazy {
        icon(
            "users",
            "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2",
            "M16 3.128a4 4 0 0 1 0 7.744",
            "M22 21v-2a4 4 0 0 0-3-3.87",
            "M5 7A4 4 0 1 0 13 7A4 4 0 1 0 5 7Z",
        )
    }

    /** lucide `panels-top-left`。 */
    val panelsTopLeft: ImageVector by lazy {
        icon(
            "panelsTopLeft",
            "M3 3H21A2 2 0 0 1 23 5V19A2 2 0 0 1 21 21H3A2 2 0 0 1 1 19V5A2 2 0 0 1 3 3Z",
            "M3 9h18",
            "M9 21V9",
        )
    }

    /** lucide `workflow`。 */
    val workflow: ImageVector by lazy {
        icon(
            "workflow",
            "M3 3H11A2 2 0 0 1 13 5V11A2 2 0 0 1 11 13H3A2 2 0 0 1 1 11V5A2 2 0 0 1 3 3Z",
            "M7 11v4a2 2 0 0 0 2 2h4",
            "M13 13H21A2 2 0 0 1 23 15V21A2 2 0 0 1 21 23H13A2 2 0 0 1 11 21V15A2 2 0 0 1 13 13Z",
        )
    }

    /** lucide `image`。 */
    val image: ImageVector by lazy {
        icon(
            "image",
            "M3 3H21A2 2 0 0 1 23 5V19A2 2 0 0 1 21 21H3A2 2 0 0 1 1 19V5A2 2 0 0 1 3 3Z",
            "M7 9A2 2 0 1 0 11 9A2 2 0 1 0 7 9Z",
            "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21",
        )
    }

    /** lucide `monitor`。 */
    val monitor: ImageVector by lazy {
        icon(
            "monitor",
            "M2 3H22A2 2 0 0 1 24 5V17A2 2 0 0 1 22 19H2A2 2 0 0 1 0 17V5A2 2 0 0 1 2 3Z",
            "M8 21L16 21",
            "M12 17L12 21",
        )
    }

    /** lucide `folder-search`。 */
    val folderSearch: ImageVector by lazy {
        icon(
            "folderSearch",
            "M10.7 20H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.81 1.2a2 2 0 0 0 1.67.9H20a2 2 0 0 1 2 2v4.1",
            "m21 21-1.9-1.9",
            "M14 17A3 3 0 1 0 20 17A3 3 0 1 0 14 17Z",
        )
    }

    /** lucide `terminal`。 */
    val terminal: ImageVector by lazy {
        icon("terminal", "M12 19h8", "m4 17 6-6-6-6")
    }

    /** lucide `check-check`。 */
    val checkCheck: ImageVector by lazy {
        icon("checkCheck", "M18 6 7 17l-5-5", "m22 10-7.5 7.5L13 16")
    }

    /** lucide `volume-2`。用于 `view_audio`，官方分组表未收录该工具。 */
    val volume: ImageVector by lazy {
        icon(
            "volume",
            "M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z",
            "M16 9a5 5 0 0 1 0 6",
            "M19.364 18.364a9 9 0 0 0 0-12.728",
        )
    }
}
