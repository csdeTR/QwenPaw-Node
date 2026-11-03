package io.qwenpaw.node.ui.chat

import io.qwenpaw.node.data.model.ChatSpecDto

/**
 * 会话列表的频道筛选。
 *
 * 候选值来自服务端返回的 `channel` 字段（`GET /api/chats` 的元素自带），
 * 服务端也支持 `?channel=` 过滤，但这一版先在客户端过滤：会话列表本来就是
 * 一次全量拉取，多打一次网络只为切个视图没有必要，等列表真的分页了再改签名。
 *
 * 频道名不做映射：取值域由服务端与插件决定，客户端穷举不了，原样显示最稳。
 */

/**
 * 候选频道，按名称排序。
 *
 * **必须传未过滤的完整列表。** 若从筛选后的结果算，一旦选中某个频道候选就只剩它自己，
 * 控件随之消失，用户被锁死在该频道里。
 */
internal fun channelCandidates(chats: List<ChatSpecDto>): List<String> =
    chats.map { it.channel }.filter { it.isNotBlank() }.distinct().sorted()

/** 候选多于一个才值得显示筛选控件；只有一个渠道时加一行等于白占空间。 */
internal fun shouldShowChannelFilter(candidates: List<String>): Boolean = candidates.size > 1

/** 按频道切片。`channel` 为 null 表示「全部」。 */
internal fun filterByChannel(chats: List<ChatSpecDto>, channel: String?): List<ChatSpecDto> =
    if (channel == null) chats else chats.filter { it.channel == channel }

/**
 * 选中值是否仍然有效。
 *
 * 刷新可能删掉某个渠道的最后一个会话，此时残留的选中值会让列表变成空页，
 * 用户看到的却是「没有匹配的对话」——因此失效时必须回落到「全部」。
 */
internal fun channelStillValid(channel: String?, candidates: List<String>): Boolean =
    channel == null || channel in candidates