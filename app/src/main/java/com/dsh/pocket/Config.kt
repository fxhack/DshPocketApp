package com.dsh.pocket

/**
 * 应用配置：DSH Pocket 服务地址与访问密码。
 * 首次启动时在设置页填入。
 */
object Config {
    /** dsh-pocket 服务地址（默认局域网直连；公网映射可能因 NAT 破坏 cookie 握手而失败）。结尾不要加斜杠。
     *  留空则首次启动在设置页填入。 */
    const val SERVER_URL = ""

    /** 局域网访问密码（自动登录时填入登录表单）。留空则首次启动在设置页填入。 */
    const val PIN = ""

    /** SERVER_URL 的 host，用于判断导航是否同源。 */
    val HOST: String = run {
        val noScheme = SERVER_URL.substringAfter("://")
        noScheme.substringBefore(':').substringBefore('/')
    }
}
