package io.qwenpaw.node.data.channel

import androidx.annotation.StringRes
import io.qwenpaw.node.R
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * 扫码轮询的一次结论。
 *
 * 服务端的状态词不统一：微信成功时给 `confirmed`，企业微信、钉钉、飞书、QQ 给 `success`。
 * 判定集中在这里，界面与测试用同一套规则。
 */
sealed interface QrOutcome {
    /** 还在等扫码或等确认（`waiting`、微信的 `scanned`）。 */
    data object Pending : QrOutcome

    /** 授权通过，[credentials] 就是写回配置的凭据。 */
    data class Success(val credentials: JsonObject) : QrOutcome

    /** 授权失败；[reason] 来自 `credentials.fail_reason`，可能为空。 */
    data class Failed(val reason: String?) : QrOutcome

    /** 二维码过期，需要重新获取。 */
    data object Expired : QrOutcome
}

/** 成功值取两种写法的并集：`confirmed` 只出现在微信，`success` 出现在其余四个频道。 */
private val SUCCESS_STATUSES = setOf("confirmed", "success")

fun qrOutcome(status: String, credentials: JsonObject): QrOutcome = when (status.trim().lowercase()) {
    in SUCCESS_STATUSES -> QrOutcome.Success(credentials)
    "expired" -> QrOutcome.Expired
    "fail" -> QrOutcome.Failed(
        (credentials["fail_reason"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
    )

    // waiting / scanned 以及未来新增的中间态都按未完成处理，避免误判成功。
    else -> QrOutcome.Pending
}

/**
 * 把扫码得到的凭据合并进当前频道配置。
 *
 * 服务端的 `PUT /api/config/channels/{channel}` 是**整段替换**：它用频道配置模型重建对象，
 * 缺省字段回落默认值。只提交凭据会把 `enabled`、`bot_prefix`、`dm_policy` 等全部冲掉。
 * 因此以当前配置为底，覆盖凭据键，并显式启用。
 */
fun mergeChannelCredentials(current: JsonObject, credentials: JsonObject): JsonObject =
    JsonObject(current.toMutableMap().apply {
        putAll(credentials)
        put("enabled", JsonPrimitive(true))
    })

/** 服务端有二维码处理的五个内置频道。仅在 2.2.2（schemas 不含 `qrcode_available`）时作为回落。 */
val QR_CAPABLE_CHANNELS = listOf("wechat", "wecom", "dingtalk", "feishu", "qq")

fun isQrCapable(key: String): Boolean = key in QR_CAPABLE_CHANNELS

/** 一个频道的能力与状态，来自 `GET /api/config/channels/schemas` 的同一行。 */
data class ChannelCapability(
    /** 服务端是否声明可扫码。2.3.0 起由频道插件的能力描述决定。 */
    val qrAvailable: Boolean,
    /** `missing_code` / `failed` / `validated` / `running`；2.2.2 为 null。 */
    val availability: String?,
    /** 服务端给的显示名，插件频道同样有。 */
    val label: String?,
    /** `repair.action == "install"` 时可直接安装的来源，如 `bundled:dingtalk`。 */
    val installSource: String?,
    /** 失败诊断原文。 */
    val diagnostic: String?,
)

/**
 * 解析频道能力。
 *
 * 2.3.0 起频道实现是插件，能否扫码由 `qrcode_available` 声明；2.2.2 没有这个字段，
 * 回落到内置五频道名单。用「字段是否存在」区分两者，而不是猜服务端版本。
 */
fun channelCapability(key: String, schema: JsonObject?): ChannelCapability {
    val hasFlag = schema?.containsKey("qrcode_available") == true
    val repair = schema?.get("repair") as? JsonObject
    val repairAction = repair?.let { jsonString(it, "action") }
    return ChannelCapability(
        qrAvailable = if (hasFlag) {
            jsonBoolean(schema!!, "qrcode_available")
        } else {
            isQrCapable(key)
        },
        availability = schema?.let { jsonString(it, "availability") },
        label = schema?.let { jsonString(it, "label") },
        installSource = if (repairAction == "install") {
            repair?.let { jsonString(it, "source") }
        } else {
            null
        },
        diagnostic = schema?.let { jsonString(it, "diagnostic") },
    )
}

/** 取字符串字段；非字符串或空串返回 null。 */
fun jsonString(obj: JsonObject, key: String): String? =
    (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

/** 频道键对应的名称资源。插件频道不在表里，调用方原样显示键名。 */
@StringRes
fun channelDisplayNameRes(key: String): Int? = when (key) {
    "wechat" -> R.string.channels_name_wechat
    "wecom" -> R.string.channels_name_wecom
    "dingtalk" -> R.string.channels_name_dingtalk
    "feishu" -> R.string.channels_name_feishu
    "qq" -> R.string.channels_name_qq
    "telegram" -> R.string.channels_name_telegram
    "discord" -> R.string.channels_name_discord
    "slack" -> R.string.channels_name_slack
    "mattermost" -> R.string.channels_name_mattermost
    "mqtt" -> R.string.channels_name_mqtt
    "console" -> R.string.channels_name_console
    "matrix" -> R.string.channels_name_matrix
    "voice" -> R.string.channels_name_voice
    "sip" -> R.string.channels_name_sip
    "xiaoyi" -> R.string.channels_name_xiaoyi
    "yuanbao" -> R.string.channels_name_yuanbao
    "imessage" -> R.string.channels_name_imessage
    "onebot" -> R.string.channels_name_onebot
    else -> null
}

/**
 * 读取频道列表里的某个布尔字段。
 *
 * 列表里的配置形状随频道而异（未保存过配置时只有 `enabled` 与 `bot_prefix`），
 * 用宽松取值避免为每个频道建一份 DTO。
 */
fun jsonBoolean(obj: JsonObject, key: String, default: Boolean = false): Boolean =
    (obj[key] as? JsonPrimitive)?.booleanOrNull ?: default