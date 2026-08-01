package io.github.totomika.pocketmcp.i18n

import android.content.Context

/**
 * 应用内语言切换控制器（预留接口）。
 *
 * 当前实现：不执行任何操作，UI 文本跟随 Android 系统语言（由 `res/values[-zh-rCN]/`
 * 资源目录自动解析）。这样无需新增 androidx.appcompat 依赖，也无需将
 * [io.github.totomika.pocketmcp.MainActivity] 从 [androidx.activity.ComponentActivity]
 * 改为 [androidx.appcompat.app.AppCompatActivity]。
 *
 * 未来接入应用内语言切换时，按以下步骤即可：
 * 1. 在 `app/build.gradle.kts` 添加 `implementation("androidx.appcompat:appcompat:1.7.0")`。
 * 2. 将 [io.github.totomika.pocketmcp.MainActivity] 改为继承
 *    [androidx.appcompat.app.AppCompatActivity]。
 * 3. 在 [setLanguage] 中调用
 *    `androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
 *        androidx.core.os.LocaleListCompat.forLanguageTags(lang))`，
 *    Compose 会自动 recomposition。
 *
 * @param context 任意可用 Context（当前未使用，保留以匹配未来 API 签名）。
 * @param lang BCP 47 语言标签，如 `"en"`、`"zh-CN"`；传空串或 null 表示跟随系统。
 */
object LocaleController {

    fun setLanguage(context: Context, lang: String?) {
        // TODO: 接入 AppCompatDelegate.setApplicationLocales 后实现真正的应用内切换。
        // 当前版本仅依赖系统 locale 的资源解析，调用本方法无副作用。
    }
}