package com.nasmusic.tv.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `SettingsGroupCard` 的**视觉编码契约**（方案 §4.1/§4.2）。
 *
 * 项目无 `compose-ui-test`，无法断言真实布局，因此锁「三层明度阶梯 + 描边 + 不可聚焦」
 * 这三条设计判据的源码锚点：一旦有人把卡片改成 `FocusableSurface`（TV 会多出一跳、
 * 破坏 navFocusRequesters 节奏）或把外壳调成比行瓦片更亮（分组边界反变模糊），
 * 本测试立刻红。
 */
class SettingsGroupCardContractTest {

    @Test
    fun `卡片外壳必须略亮于页面背景、略暗于行瓦片并带描边`() {
        val source = settingsSource("SettingsComponents.kt")
        assertTrue("缺 SettingsGroupCard —— 分组原语被删了？", source.contains("internal fun SettingsGroupCard"))
        assertTrue(
            "卡片外壳必须用 NasMusicColors.Surface.copy(alpha = 0.45f)（三层明度阶梯的中层）",
            source.contains("NasMusicColors.Surface.copy(alpha = 0.45f)"),
        )
        assertTrue("卡片必须 1dp NasMusicColors.Border 描边", source.contains("border(1.dp, NasMusicColors.Border"))
        assertTrue("卡片圆角必须走令牌（⛔ 不许在组件里写字面量）", source.contains("SettingsTokens.cardShape"))
    }

    @Test
    fun `卡片圆角取值必须是 18dp 且只在 SettingsTokens 里出现一次`() {
        val tokens = settingsSource("SettingsTokens.kt")
        assertTrue("SettingsTokens.cardShape 必须是 RoundedCornerShape(18.dp)（与行瓦片 12.dp 形成层级差）",
            Regex("""cardShape\s*=\s*RoundedCornerShape\(18\.dp\)""").containsMatchIn(tokens))
        assertTrue("两级圆角不可互换：cardShape(18) 与 rowShape(12) 必须是不同取值",
            Regex("""rowShape\s*=\s*RoundedCornerShape\(12\.dp\)""").containsMatchIn(tokens) &&
                !Regex("""rowShape\s*=\s*RoundedCornerShape\(18""").containsMatchIn(tokens))
    }

    @Test
    fun `卡片本身绝不可聚焦`() {
        val source = settingsSource("SettingsComponents.kt")
        val body = source.substringAfter("internal fun SettingsGroupCard").substringBefore("\n}\n")
        assertTrue(
            "SettingsGroupCard 里出现了 FocusableSurface —— 卡片参与聚焦会让 TV 每组多一跳",
            body.isNotEmpty() && !body.contains("FocusableSurface"),
        )
    }

    /** 负向自证：把 FocusableSurface 塞进卡片体，上一条必须判负。 */
    @Test
    fun `卡片体内塞入 FocusableSurface 后不可聚焦断言必须判负`() {
        val sabotaged = "internal fun SettingsGroupCard(...) {\n FocusableSurface(onClick = {})\n}\n"
        val body = sabotaged.substringAfter("internal fun SettingsGroupCard").substringBefore("\n}\n")
        assertTrue("护栏失效：塞入 FocusableSurface 后仍判通过", body.contains("FocusableSurface"))
    }

    private fun settingsSource(fileName: String): String {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv/ui/screens/settings/$fileName"),
            File("app/src/main/java/com/nasmusic/tv/ui/screens/settings/$fileName"),
            File("../app/src/main/java/com/nasmusic/tv/ui/screens/settings/$fileName"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue("找不到 $fileName（user.dir=${File("").absolutePath}）", found != null)
        return found!!.readText()
    }
}
