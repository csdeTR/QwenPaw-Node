package io.qwenpaw.node.ui.common

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/**
 * 选择一个或多个文件，由系统列出可用的来源。
 *
 * 与 `ActivityResultContracts.OpenMultipleDocuments` 的差别在 Intent 动作：
 *
 * | 契约 | 动作 | 实际表现 |
 * | --- | --- | --- |
 * | `OpenMultipleDocuments` | `ACTION_OPEN_DOCUMENT` | 由系统文档提供器接管；小米上固定进自家文件管理器，选不到第三方来源 |
 * | 本契约 | `ACTION_GET_CONTENT` | 系统弹出「用什么打开」，可列出文件管理器、WPS、网盘、相册等 |
 *
 * 官方网页用的是普通 `<input type="file">`，Chrome 内部同样走 `ACTION_GET_CONTENT`，
 * 因此这是与网页版行为对齐的做法。代价是每次多一步来源选择。
 *
 * 只申请当次读取权限，不调用 `takePersistableUriPermission`：文件仅用于本次上传，
 * 上传完成后 URI 即失效，不需要长期持有。
 */
class GetMultipleContent : ActivityResultContract<Unit, List<Uri>>() {

    override fun createIntent(context: Context, input: Unit): Intent {
        val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        // 包一层 chooser：部分 ROM 会对 GET_CONTENT 仍套用默认应用，
        // 显式 chooser 能把「用哪个应用选择」这一步摆到用户面前。
        return Intent.createChooser(pick, "选择文件")
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        // 多选时结果在 ClipData 里，单选时在 data 里；两者可能同时出现同一项。
        val fromClip = intent.clipData
            ?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it)?.uri } }
            .orEmpty()
        val single = intent.data
        return (fromClip + listOfNotNull(single)).distinct()
    }
}