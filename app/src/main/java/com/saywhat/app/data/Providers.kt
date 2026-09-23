package com.saywhat.app.data

/**
 * 服务商预设。
 *
 * ═══ 为什么需要这个 ═══
 * 「傻瓜式」最容易翻车的地方就在填 Key 这一步：
 * 用户拿到了 DeepSeek 的 Key，却不知道「接口地址」和「模型名」该填什么，
 * 于是卡在那两个输入框上，然后放弃。
 *
 * 所以这里把常见的服务商做成预设：选一下，地址和模型名自动填好，
 * 用户**只需要粘贴一个 Key**。
 *
 * ⚠️ 预填值可能随服务商调整而变化。界面上有提示让用户以官方文档为准。
 */
data class Provider(
    val id: String,
    val name: String,
    /** 空串表示「让用户自己填」 */
    val baseUrl: String,
    val model: String,
    val keyHint: String,
    /** 告诉用户去哪儿弄 Key */
    val guide: String,
    /** 取 Key 的官网地址；空串表示没有可直达的页面 */
    val signupUrl: String = ""
)

object Providers {

    val DEEPSEEK = Provider(
        id = "deepseek",
        name = "DeepSeek 官方",
        baseUrl = "https://api.deepseek.com",
        model = "deepseek-flash",
        keyHint = "sk-开头的字符串",
        guide = "在 platform.deepseek.com 注册，进「API Keys」页面创建一个，复制过来即可。\n" +
                "默认模型 deepseek-flash 是 DeepSeek V4.1 Flash，原生多模态。\n\n" +
                "点这里打开官网 →",
        signupUrl = "https://platform.deepseek.com"
    )

    val CUSTOM = Provider(
        id = "custom",
        name = "其他服务 / 中转站",
        baseUrl = "",
        model = "",
        keyHint = "服务商给你的那串字符",
        guide = "任何兼容 OpenAI 协议的服务都能用。\n" +
                "接口地址通常形如 https://xxx.com/v1，模型名以服务商文档为准。"
    )

    val ALL_DEFINED: List<Provider> = listOf(DEEPSEEK, CUSTOM)

    /**
     * 当前版本对用户可见的服务商。
     * 0.1.0 只留 DeepSeek 官方 —— 小白拿到 Key 后不需要纠结「选哪个服务商」。
     */
    val ALL: List<Provider> = ALL_DEFINED.filter { p ->
        p.id != CUSTOM.id || FeatureFlags.CUSTOM_PROVIDER
    }

    /** 解析已保存的服务商 id。即使用户之前存的是已隐藏的项，也要能解析出来 */
    fun byId(id: String?): Provider = ALL_DEFINED.firstOrNull { it.id == id } ?: DEEPSEEK
}
