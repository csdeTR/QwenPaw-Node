package io.qwenpaw.node

import android.app.Application
import io.qwenpaw.node.data.InstanceRepository
import io.qwenpaw.node.data.NetworkMonitor
import io.qwenpaw.node.data.local.AppDatabase
import io.qwenpaw.node.data.local.InstanceStore
import io.qwenpaw.node.data.remote.ChatStreamClient
import io.qwenpaw.node.data.remote.QwenPawApi
import io.qwenpaw.node.font.FontManager

/** 简单的手工依赖装配（项目规模不需要 DI 框架）。 */
class QwenPawApp : Application() {

    lateinit var api: QwenPawApi
        private set
    lateinit var streamClient: ChatStreamClient
        private set
    lateinit var toolStreamClient: io.qwenpaw.node.data.stream.ToolStreamClient
        private set
    lateinit var database: AppDatabase
        private set
    lateinit var instanceRepository: InstanceRepository
        private set
    lateinit var fontManager: FontManager
        private set
    lateinit var networkMonitor: NetworkMonitor
        private set
    lateinit var avatarStore: io.qwenpaw.node.avatar.AvatarStore
        private set
    lateinit var serverAvatar: io.qwenpaw.node.data.remote.ServerAvatarClient
        private set
    lateinit var chatIconStore: io.qwenpaw.node.avatar.ChatIconStore
        private set
    lateinit var pluginProbe: io.qwenpaw.node.data.plugin.PluginProbe
        private set
    lateinit var transcriptionProbe: io.qwenpaw.node.data.transcription.TranscriptionProbe
        private set
    lateinit var downloadClient: io.qwenpaw.node.data.remote.DownloadClient
        private set

    /** 端上识别能力。`full` 变体有声，`_no-sherpa` 恒为不可用。 */
    lateinit var localAsr: io.qwenpaw.node.voice.LocalAsr
        private set

    override fun onCreate() {
        super.onCreate()
        api = QwenPawApi()
        // 由变体源码决定：full 是 sherpa，_no-sherpa 是不可用桩。
        localAsr = io.qwenpaw.node.voice.LocalAsrProvider.create(this)
        pluginProbe = io.qwenpaw.node.data.plugin.PluginProbe(api)
        transcriptionProbe = io.qwenpaw.node.data.transcription.TranscriptionProbe(api)
        streamClient = ChatStreamClient(api, QwenPawApi.defaultClient())
        toolStreamClient = io.qwenpaw.node.data.stream.ToolStreamClient(
            api,
            QwenPawApi.defaultClient(),
        )
        database = AppDatabase.build(this)
        // 下载走独立的流式通道：与聊天、工具流互不影响，也不受 JSON 解码路径限制。
        downloadClient = io.qwenpaw.node.data.remote.DownloadClient(QwenPawApi.defaultClient())
        networkMonitor = NetworkMonitor(this)
        chatIconStore = io.qwenpaw.node.avatar.ChatIconStore(
            this,
            getSharedPreferences("qwenpaw_chat_icons", MODE_PRIVATE),
        )
        avatarStore = io.qwenpaw.node.avatar.AvatarStore(this)
        serverAvatar = io.qwenpaw.node.data.remote.ServerAvatarClient(
            api,
            java.io.File(filesDir, "server-avatar"),
        )
        instanceRepository = InstanceRepository(InstanceStore(this), api, networkMonitor)
        fontManager = FontManager(this)
        // 进程级监听：地址选择与断网恢复都依赖它，生命周期跟应用一致。
        networkMonitor.start()
    }
}
