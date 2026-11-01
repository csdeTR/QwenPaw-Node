package io.qwenpaw.node.data.remote

import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 服务端头像清单，对应 `GET /api/profile/avatars`。
 *
 * [selected] 是当前生效的那一张的 id，没有设置头像时为 null。
 * 服务端只保留 [history] 里最近的若干张（实测上限 12），
 * 客户端不展示历史，只在 [selected] 变化时重新取图。
 */
@Serializable
data class AvatarProfileDto(
    val selected: String? = null,
    val history: List<AvatarImageDto> = emptyList(),
)

/** 历史头像中的一项。 */
@Serializable
data class AvatarImageDto(
    val id: String,
    val mime: String = "",
    @SerialName("created_at") val createdAt: String? = null,
)

/**
 * 服务端头像的本地镜像。
 *
 * 服务端的 `avatar_owner()` 固定返回 `"console"`，没有智能体头像端点，
 * 因此这里只服务用户侧的一种头像。
 *
 * 缓存按服务端 id 命名（`{id}.img`），另有一个 `index.txt` 记当前选中的 id。
 * 用 id 而不是固定文件名，是因为同一路径被覆写时图片加载器的内存缓存
 * 仍按路径命中，会继续显示旧图；换 id 即换路径，不依赖额外的失效信号。
 *
 * 读取分两步：先 [cached] 拿上次的结果立即上屏，再 [refresh] 联网核对。
 * 冷启动时不联网也能显示上次的头像，断网也不影响。
 */
class ServerAvatarClient(private val api: QwenPawApi, private val dir: File) {

    private fun indexFile(): File = File(dir, "index.txt")

    private fun imageFile(id: String): File = File(dir, "$id.img")

    /** 上次缓存下来的头像文件。没有缓存时返回 null，不联网。 */
    fun cached(): File? = readIndex()?.let { imageFile(it) }?.takeIf { it.isFile }

    /**
     * 联网核对服务端当前头像，需要时更新缓存。
     *
     * 返回可显示的本地文件，服务端没有设置头像时返回 null 并清空缓存。
     * 例外：本地已有一份缓存且请求失败时会抛异常，由调用方决定是否保留旧图——
     * 这里不静默吞掉错误，否则用户会以为服务端头像被删了。
     */
    fun refresh(conn: Connection): File? {
        dir.mkdirs()
        val profile = api.avatarProfile(conn)
        val selected = profile.selected?.takeIf { it.isNotBlank() }
        if (selected == null) {
            clear()
            return null
        }
        // id 没变就直接复用：图片内容由 id 唯一确定，不必重新下载 2 MB 的字节。
        val cachedId = readIndex()
        val existing = imageFile(selected)
        if (cachedId == selected && existing.isFile) return existing

        val bytes = api.avatarImage(conn, selected)
        if (bytes.isEmpty()) {
            clear()
            return null
        }
        // 先写临时文件再改名：写到一半被杀进程，不会留下一个截断的图片。
        val part = File(dir, "$selected.img.part")
        part.writeBytes(bytes)
        existing.delete()
        if (!part.renameTo(existing)) {
            part.delete()
            // 改名失败通常意味着目标被占用，退回直接写。
            existing.writeBytes(bytes)
        }
        indexFile().writeText(selected)
        prune(keep = selected)
        return existing
    }

    /** 清空缓存。服务端删掉头像后调用，避免旧图继续显示。 */
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun readIndex(): String? =
        runCatching { indexFile().readText().trim() }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * 只保留当前 id 的文件。
     *
     * 服务端换头像会产生新的 id，旧文件在客户端没有任何用处，
     * 而且每张上限 2 MB，攒几张就很可观。
     */
    private fun prune(keep: String) {
        dir.listFiles()?.forEach { file ->
            if (file.name != "$keep.img" && file.name != "index.txt") file.delete()
        }
    }
}