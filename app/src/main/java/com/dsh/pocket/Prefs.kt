package com.dsh.pocket

import android.content.Context

/**
 * 轻量设置持久化：保存用户在登录页输入的服务地址与密码。
 * 默认回退到 Config 中的值，无需手动配置即可使用。
 */
object Prefs {
    lateinit var ctx: Context
    private const val NAME = "dsh_settings"
    private val sp get() = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun url(): String = sp.getString("url", Config.SERVER_URL) ?: Config.SERVER_URL
    fun pin(): String = sp.getString("pin", Config.PIN) ?: Config.PIN

    fun save(url: String, pin: String) {
        sp.edit().putString("url", url).putString("pin", pin).apply()
    }

    /** 是否已保存过配置（首次启动判断）。 */
    fun hasConfig(): Boolean = sp.contains("url") && sp.contains("pin")

    /** 上次打开的会话（启动直达，对标浏览器）。null 表示不记忆。 */
    /** 主题模式：light / dark / system。对标浏览器 ui-theme。 */
    fun theme(): String = sp.getString("theme", "system") ?: "system"
    fun saveTheme(t: String) { sp.edit().putString("theme", t).apply() }

    fun webSearch(): Boolean = sp.getBoolean("webSearch", true)
    fun saveWebSearch(b: Boolean) { sp.edit().putBoolean("webSearch", b).apply() }
    fun logLevel(): String = sp.getString("logLevel", "info") ?: "info"
    fun saveLogLevel(l: String) { sp.edit().putString("logLevel", l).apply() }
    fun shell(): String = sp.getString("shell", "auto") ?: "auto"
    fun saveShell(s: String) { sp.edit().putString("shell", s).apply() }
    fun voiceInput(): Boolean = sp.getBoolean("voiceInput", true)
    fun saveVoiceInput(b: Boolean) { sp.edit().putBoolean("voiceInput", b).apply() }
    fun autoApprove(): Boolean = sp.getBoolean("autoApprove", false)
    fun saveAutoApprove(b: Boolean) { sp.edit().putBoolean("autoApprove", b).apply() }

    fun lastSessionId(): String? = sp.getString("lastSession", null)
    fun saveLastSession(id: String?) {
        val e = sp.edit()
        if (id == null) e.remove("lastSession") else e.putString("lastSession", id)
        e.apply()
    }
}
