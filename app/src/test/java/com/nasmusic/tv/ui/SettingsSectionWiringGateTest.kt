package com.nasmusic.tv.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 分区枚举的三处接线必须同步（方案 §4.6/§5.7 G3）。
 *
 * 新增一个 SettingsSection 而漏掉 `when` 分支 ⇒ 编译失败（when 非穷举），有编译器兜底；
 * 但**漏注册 `navFocusRequesters` ⇒ 编译通过、TV 上从侧栏按「下」进不去内容区**，
 * 漏 `values-en` 标题 ⇒ 编译通过、竖屏一级列表渲染时崩。这两类只有文本门禁能锁。
 */
class SettingsSectionWiringGateTest {

    @Test
    fun `每个分区都必须注册焦点请求器`() {
        val screen = mainSource("ui/screens/SettingsScreen.kt")
        val sections = enumEntries(mainSource("ui/screens/settings/SettingsSection.kt"))
        assertTrue("没扫到任何分区枚举项 —— 枚举写法变了，门禁需同步", sections.isNotEmpty())
        // 口径：对每个分区名，SettingsScreen 全文必须出现 ≥2 次（when 分支 + 焦点注册）
        for (s in sections) {
            val hits = Regex("SettingsSection\\.$s\\b").findAll(screen).count()
            assertTrue("SettingsScreen 里 `$s` 只出现 $hits 次（需 when 分支 + navFocusRequesters 各一处）", hits >= 2)
        }
    }

    @Test
    fun `每个分区的标题字符串必须中英齐备`() {
        val enumSrc = mainSource("ui/screens/settings/SettingsSection.kt")
        val titles = Regex("""(\w+)\(R\.string\.(\w+),\s*Icons""").findAll(enumSrc).map { it.groupValues[2] }.toList()
        assertTrue("没扫到任何 titleRes —— 枚举写法变了，门禁需同步", titles.isNotEmpty())
        val zh = resXml("values"); val en = resXml("values-en")
        val zhMissing = titles.filterNot { zh.contains("""name="$it"""") }
        val enMissing = titles.filterNot { en.contains("""name="$it"""") }
        assertTrue("values/strings.xml 缺分区标题：$zhMissing", zhMissing.isEmpty())
        assertTrue("values-en/strings.xml 缺分区标题（编译能过、渲染会崩）：$enMissing", enMissing.isEmpty())
    }

    /** 负向自证：伪造一个没有字符串的分区名，上一条必须判负。 */
    @Test
    fun `缺英文标题的分区必须被判负`() {
        val en = resXml("values-en")
        assertTrue("护栏空转：values-en 里居然真的有 settings_bogus_section",
            !en.contains("""name="settings_bogus_section""""))
    }

    /**
     * 凭据负向断言（§3.3 / Task 7）：设置页**不得内嵌任何凭据输入框** ——
     * 服务器地址/账号/密码/API Token/访问码一律在独立的 `ServerConnectScreen` 里填
     * （五条硬依据见 §3.3：HTTP 服务生命周期、焦点域冲突、BACK 栈、竖屏层级、整屏宽度）。
     * 密码类三件套出现在设置页源码里 = 有人把表单搬进来了，必须当场拦下。
     */
    @Test
    fun `设置页不得内嵌凭据输入框`() {
        val offenders = settingsKtFiles().flatMap { f ->
            credentialBanned.filter { f.readText(Charsets.UTF_8).contains(it) }
                .map { "${f.name}: $it" }
        }
        assertTrue(
            "设置页源码里出现了凭据输入框三件套（凭据一律在 ServerConnectScreen 填，§3.3）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
        // 空转自证：确实扫到了分区源文件
        assertTrue("设置源码目录里没有 .kt 文件？门禁在空转", settingsKtFiles().isNotEmpty())
    }

    /** 负向自证：把 TextFieldValue 塞进样例文本，判据必须判负。 */
    @Test
    fun `凭据判据不是空转`() {
        val sample = "val v = TextFieldValue(\"\")"
        assertTrue(
            "护栏失效：样例里的 TextFieldValue 没被判为违禁",
            credentialBanned.any { sample.contains(it) },
        )
    }

    private val credentialBanned = listOf(
        "TextFieldValue",
        "PasswordVisualTransformation",
        "KeyboardType.Password",
    )

    private fun settingsKtFiles(): Array<File> {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv/ui/screens/settings"),
            File("app/src/main/java/com/nasmusic/tv/ui/screens/settings"),
            File("../app/src/main/java/com/nasmusic/tv/ui/screens/settings"),
        )
        val found = candidates.firstOrNull { it.isDirectory }
        assertTrue("找不到设置源码目录（user.dir=${File("").absolutePath}）", found != null)
        return found!!.listFiles { f -> f.isFile && f.extension == "kt" } ?: emptyArray()
    }

    private fun enumEntries(src: String): List<String> =
        Regex("""^\s{4}([A-Z][A-Z_]+)\(R\.string\.""", RegexOption.MULTILINE)
            .findAll(src).map { it.groupValues[1] }.toList()

    /**
     * `user.dir` 在 Gradle 测试 worker 下可能是 `app/`、仓库根，也可能是带 `../` 的变体 ——
     * 照 `LocalMusicGateTest.mainSourceRoot()` 的多候选写法，⛔ 不写绝对路径、不用行号锚点。
     */
    private fun mainSource(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv/$relativePath"),
            File("app/src/main/java/com/nasmusic/tv/$relativePath"),
            File("../app/src/main/java/com/nasmusic/tv/$relativePath"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue("找不到 $relativePath（user.dir=${File("").absolutePath}）；已尝试：${candidates.joinToString { it.absolutePath }}", found != null)
        return found!!.readText(Charsets.UTF_8)
    }

    private fun resXml(valuesDir: String): String {
        val candidates = listOf(
            File("src/main/res/$valuesDir/strings.xml"),
            File("app/src/main/res/$valuesDir/strings.xml"),
            File("../app/src/main/res/$valuesDir/strings.xml"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue("找不到 $valuesDir/strings.xml（user.dir=${File("").absolutePath}）", found != null)
        return found!!.readText(Charsets.UTF_8)
    }
}
