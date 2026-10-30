package io.qwenpaw.node.data

import io.qwenpaw.node.data.model.AuthStatusDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「是否需要登录」的判据。
 *
 * 这个判定式出现在四处（验证预判、引导页第 2 步、探测智能体、真正连接），
 * 任何一处写法不一致都会造成具体故障：引导页该跳过时不跳过，用户会卡在
 * 一个填不了账号的页面；该填时跳过，连接会以 401 失败却没有提示。
 */
class VerifyResultTest {

    @Test
    fun `enabled with users requires login`() {
        assertTrue(InstanceRepository.loginRequired(AuthStatusDto(enabled = true, hasUsers = true)))
    }

    /** 只开开关却没有用户时登录接口无从登录，按不需要处理。 */
    @Test
    fun `enabled without users does not require login`() {
        assertFalse(InstanceRepository.loginRequired(AuthStatusDto(enabled = true, hasUsers = false)))
    }

    @Test
    fun `disabled never requires login`() {
        assertFalse(InstanceRepository.loginRequired(AuthStatusDto(enabled = false, hasUsers = true)))
        assertFalse(InstanceRepository.loginRequired(AuthStatusDto(enabled = false, hasUsers = false)))
    }

    /** 默认值即本机实例的形态：两个开关都关。 */
    @Test
    fun `default dto needs no login`() {
        assertFalse(InstanceRepository.loginRequired(AuthStatusDto()))
    }

    /** 验证结果本身要带上实际命中的地址，界面据此显示「已回退到 …」。 */
    @Test
    fun `verify result keeps hit url`() {
        val result = VerifyResult(baseUrl = "http://b:8088", version = "2.2.2", needsLogin = true)
        assertTrue(result.baseUrl.endsWith("b:8088"))
        assertTrue(result.needsLogin)
    }
}