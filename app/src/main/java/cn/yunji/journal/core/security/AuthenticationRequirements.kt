package cn.yunji.journal.core.security

/** Check prerequisites before preparing authentication-bound keys or covering a usable session. */
internal fun authenticationRequirementMessage(deviceSecure: Boolean, authenticationAvailable: Boolean,
    legacyStrict: Boolean): String? = when {
    !deviceSecure -> "手机尚未设置安全锁屏。请先在系统设置中设置锁屏密码，再返回重试。现有资料与安全设置未更改。"
    !authenticationAvailable && legacyStrict -> "此手机的严格模式需要可用的强生物识别。请先在系统设置中录入指纹，再返回重试。"
    !authenticationAvailable -> "系统身份认证暂不可用，请检查手机锁屏密码或生物识别设置后重试。现有资料与安全设置未更改。"
    else -> null
}
