package io.qwenpaw.node.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.data.NetworkMonitor
import io.qwenpaw.node.data.local.AddressEntry
import io.qwenpaw.node.data.local.NetworkHint
import io.qwenpaw.node.ui.theme.LocalCaptionColor

/**
 * 连接地址编辑器。
 *
 * 同一台服务端常有多个入口：家里走局域网，外出走 EasyTier 之类的隧道。
 * 每条地址可以带一个别名，让「10.8.0.2:8088」在卡片上显示成「EasyTier」，
 * 也可以绑定一个网络条件，使客户端在当前网络命中时优先选它。
 *
 * 列表顺序仍然有意义：第一行是默认地址。绑定网络条件只是把某条**提到最前**，
 * 不做排他——条件命中但该地址坏了，依然会继续尝试后面的地址。
 */
@Composable
fun AddressListEditor(
    entries: List<AddressEntry>,
    /** 第二个参数为 true 表示这次输入里做过全角标点折叠，供上层提示。 */
    onChange: (List<AddressEntry>, Boolean) -> Unit,
    /** 任一地址输入框出错时的标红依据。 */
    isError: Boolean = false,
    /** 当前网络类型，用于给用户看「现在会优先用哪条」。 */
    currentKind: String = NetworkMonitor.Snapshot.KIND_OTHER,
    /** 当前 WiFi 名称，用于按具体网络绑定。 */
    currentSsid: String? = null,
    maxCount: Int = MAX_ADDRESS_COUNT,
) {
    fun replace(index: Int, next: AddressEntry, folded: Boolean = false) {
        onChange(entries.toMutableList().also { it[index] = next }, folded)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        entries.forEachIndexed { index, entry ->
            val hitNow = entry.preferOn?.matches(currentKind, currentSsid) == true
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        if (hitNow) 1.5.dp else 1.dp,
                        if (hitNow) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(10.dp),
                    )
                    .padding(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = entry.url,
                        onValueChange = { raw ->
                            val folded = foldFullWidth(raw)
                            replace(index, entry.copy(url = folded), folded != raw)
                        },
                        placeholder = { Text(if (index == 0) "192.168.1.10:8088" else "10.8.0.2:8088") },
                        label = { Text(if (index == 0) "默认地址" else "备用地址 $index") },
                        singleLine = true,
                        isError = isError && index == 0 && entry.url.isBlank(),
                        shape = RoundedCornerShape(10.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.weight(1f),
                    )
                    // 第一条地址不能删：删空之后就没有地方填地址了。
                    if (index > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "删除",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clickable {
                                    onChange(entries.filterIndexed { i, _ -> i != index }, false)
                                }
                                .padding(horizontal = 6.dp, vertical = 8.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = entry.alias.orEmpty(),
                    onValueChange = { replace(index, entry.copy(alias = it.ifBlank { null })) },
                    placeholder = { Text("别名，例如「本地」「EasyTier」") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "使用时机",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalCaptionColor.current,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PreferChip(
                        text = "不自动切换",
                        selected = entry.preferOn == null,
                        onClick = { replace(index, entry.copy(preferOn = null)) },
                    )
                    PreferChip(
                        text = "VPN 时优先",
                        selected = entry.preferOn?.kind == NetworkMonitor.Snapshot.KIND_VPN,
                        onClick = {
                            replace(
                                index,
                                entry.copy(preferOn = NetworkHint(NetworkMonitor.Snapshot.KIND_VPN)),
                            )
                        },
                    )
                    PreferChip(
                        text = if (currentSsid.isNullOrBlank()) "WiFi 时优先" else "当前 WiFi 优先",
                        selected = entry.preferOn?.kind == NetworkMonitor.Snapshot.KIND_WIFI,
                        onClick = {
                            replace(
                                index,
                                entry.copy(
                                    preferOn = NetworkHint(
                                        kind = NetworkMonitor.Snapshot.KIND_WIFI,
                                        ssid = currentSsid?.takeIf { it.isNotBlank() },
                                    ),
                                ),
                            )
                        },
                    )
                }
                if (hitNow) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "当前网络正使用这条地址",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                entry.preferOn?.ssid?.takeIf { it.isNotBlank() }?.let { ssid ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "仅在网络「$ssid」下优先",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalCaptionColor.current,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (entries.size < maxCount) {
            Text(
                text = "添加地址",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .clickable { onChange(entries + AddressEntry(url = ""), false) }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
        Text(
            text = "连接失败时，QwenPaw Node 默认依据列表顺序逐一尝试，" +
                "便于外出时通过诸如 EasyTier 网络的方式访问 QwenPaw。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** 网络条件小标签。选中的填底色，未选中的只有描边。 */
@Composable
private fun PreferChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
                else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 最多几个地址。再多只是让回退更慢，实际场景里用不到。 */
const val MAX_ADDRESS_COUNT = 5

/** 把中文标点折成半角，真机上输入法常把点和冒号打成全角。 */
internal fun foldFullWidth(raw: String): String = raw
    .replace('。', '.')
    .replace('：', ':')
    .replace('／', '/')
    .replace('　', ' ')
    .replace('，', ',')