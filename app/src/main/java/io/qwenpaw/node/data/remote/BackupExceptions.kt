package io.qwenpaw.node.data.remote

import io.qwenpaw.node.data.model.BackupMetaDto
import java.io.IOException

/**
 * 导入时目标备份 id 已存在。
 *
 * 服务端在 409 的 body 里保留 `existing` 与 `pending_token`，并用后者指向
 * 暂存的上传文件；确认覆盖后只带 token 重发，**不再上传文件**。
 */
class BackupImportConflictException(
    val existing: BackupMetaDto,
    val pendingToken: String,
) : IOException("backup conflict")

/**
 * 备份签名不是本机签发。
 *
 * [code] 取值 `backup_legacy_unsigned`、`backup_signature_mismatch`、
 * `backup_unknown_signature_scheme`。前两种可以显式信任后继续，
 * 第三种没有信任通路，界面只展示 [serverMessage]。
 */
class BackupTrustException(
    val code: String,
    val serverMessage: String,
) : IOException(serverMessage)
