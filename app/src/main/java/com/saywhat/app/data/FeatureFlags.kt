package com.saywhat.app.data

/**
 * 版本功能开关。
 *
 * ═══ 为什么用开关而不是删代码 ═══
 * 定位是「傻瓜式」：界面上**只留一条能走通的路**，不给用户任何会选错的机会。
 * 但 Jev 引擎和自定义服务商的代码是好的，只是还没到时候 ——
 * 所以做成开关，条件具备时改一个 `true` 即可恢复。
 *
 * ═══ 各开关的解锁条件（刻意不写版本号）═══
 * - [JEV_ENGINE]：**等 TypeSafe 恢复放量**。原先写的是「0.1.1 上线」，
 *   但 Jev 官方已暂停发放资格，与版本号脱钩了 —— 写成版本号只会误导后来人
 *   （工程已经走到 0.3.x，那个「0.1.1」早就过期了）。
 * - [CUSTOM_PROVIDER]：等确认小白不会被「接口地址填什么」卡住时再开。
 */
object FeatureFlags {

    /**
     * 是否在引擎下拉里露出 Jev / System One。
     *
     * 保持 `false`：Jev 目前拿不到 key（官方暂停发放资格），
     * 它的 `analyze()` 会直接返回「尚未接入」的失败。
     * 把它露出来等于给用户一个**必然报错**的选项 —— 这是最糟的「傻瓜化」。
     */
    const val JEV_ENGINE: Boolean = false

    /** 是否允许用户自填接口地址 / 选其他服务商 */
    const val CUSTOM_PROVIDER: Boolean = false

    /**
     * 界面上的通用规则：可选项少于 2 个时，整个选择区都不显示。
     * 只给一个选项的下拉框纯属干扰。
     */
    fun showChooser(optionCount: Int): Boolean = optionCount > 1
}
