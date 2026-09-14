// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PersistableBundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.*
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var content: FrameLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true; setPadding(8, 32, 8, 8) }
        root.addView(TextView(this).apply { text = "SillyTavern · Android 前台实验版"; textSize = 18f })
        root.addView(TextView(this).apply { text = "后台/锁屏、语音、导出尚未验收。请勿作为聊天数据唯一副本。"; textSize = 12f })
        val row = LinearLayout(this)
        fun action(label: String, callback: () -> Unit) {
            row.addView(Button(this).apply { text = label; setOnClickListener { try { callback() } catch (error: Exception) { notice(error.message ?: "操作失败") } } })
        }
        action("启动") { confirmExperiment { SessionController.start() } }
        action("重启") { confirmStop { SessionController.stop(restart = true) } }
        action("停止") { confirmStop { SessionController.stop() } }
        action("诊断") { copyDiagnostic() }
        action("启动日志") { showStartupLog() }
        action("说明") { showAbout() }
        root.addView(HorizontalScrollView(this).apply { addView(row) })
        status = TextView(this).apply { text = "点击启动；首次解包可能需要较长时间" }
        root.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        root.addView(progress)
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        SessionController.attach(this)
    }
    fun attachBrowser(view: WebView) {
        (view.parent as? ViewGroup)?.removeView(view)
        content.removeAllViews()
        content.addView(view, FrameLayout.LayoutParams(-1, -1))
    }
    fun showState(value: JSONObject) {
        val phase = value.optString("phase", "idle")
        val all = value.optInt("total"); val done = value.optInt("done")
        status.text = "$phase · ${value.optString("detail")} ${if (all > 0) "$done/$all" else ""}"
        progress.visibility = if (phase in setOf("connecting", "preparing", "copying", "unpacking", "verifying", "starting", "checking")) View.VISIBLE else View.GONE
        progress.isIndeterminate = all <= 0
        if (all > 0) { progress.max = all; progress.progress = done }
    }
    private fun confirmExperiment(action: () -> Unit) {
        AlertDialog.Builder(this).setTitle("实验版提示")
            .setMessage("此包首次在Android集成真实ST，仍需要设备验证。请保持前台；不会自动续接中断的生成。文件导入可试用，Blob/认证下载导出未实现。卸载/清除数据会删除私有聊天和密钥。")
            .setPositiveButton("开始实验") { _, _ -> action() }.setNegativeButton("取消", null).show()
    }
    private fun confirmStop(action: () -> Unit) {
        AlertDialog.Builder(this).setTitle("中断服务？")
            .setMessage("停止或重启会中断当前生成，未保存内容可能丢失。已保存数据不删除。")
            .setPositiveButton("继续") { _, _ -> action() }.setNegativeButton("取消", null).show()
    }
    fun chooseFiles(multiple: Boolean) {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            }, OPEN_FILE)
        } catch (error: Exception) { SessionController.fileCallback?.onReceiveValue(null); SessionController.fileCallback = null; notice("无法打开文件选择器") }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != OPEN_FILE) return
        val callback = SessionController.fileCallback; SessionController.fileCallback = null
        val uris = mutableListOf<Uri>()
        if (resultCode == RESULT_OK) {
            data?.clipData?.let { clips -> if (clips.itemCount <= 256) repeat(clips.itemCount) { uris.add(clips.getItemAt(it).uri) } }
            if (uris.isEmpty()) data?.data?.let(uris::add)
        }
        callback?.onReceiveValue(uris.filter { it.scheme == "content" }.takeIf { it.isNotEmpty() }?.toTypedArray())
    }
    private fun copyDiagnostic() {
        val value = SessionController.diagnostic().toString(2)
        val clip = ClipData.newPlainText("ST Android diagnostics", value)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        notice("已复制诊断状态（不含密钥、Cookie、聊天或启动日志）")
    }
    private fun showStartupLog() {
        val file = java.io.File(AppFiles.state(this), "startup.log")
        val log = if (file.isFile && file.length() <= 65536) file.readText() else "暂无启动日志"
        val view = TextView(this).apply {
            setPadding(20, 20, 20, 20); setTextIsSelectable(true)
            text = "仅捕获启动阶段，不自动导出。日志仍可能包含敏感配置，分享前请检查并移除密钥。\n\n" + log
        }
        AlertDialog.Builder(this).setTitle("私有启动日志")
            .setView(ScrollView(this).apply { addView(view) }).setPositiveButton("关闭", null).show()
    }
    private fun showAbout() {
        val text = TextView(this).apply {
            setTextIsSelectable(true); setPadding(20, 20, 20, 20)
            text = "非官方前台实验版，未完成M1/M3/M3.5验收。Node26 + 官方ST源码；适配位于外层。\n" +
                "WebView仅允许本机ST origin；外站链接交系统浏览器，直接访问外站的扩展/资源可能不可用。\n" +
                "本轮不保证后台生成，不支持麦克风/相机授权及完整下载导出。全局扩展修改运行目录会导致后续完整性检查拒绝启动。\n" +
                "密钥存于App私有数据；不是已加密导出。诊断不会自动复制启动日志，启动日志仍可能含敏感配置。\n" +
                "公开分发前需完成对应源码/第三方许可证复核；源码和构建步骤见随工程README。许可证保存在assets/licenses及ST源码中。\n\n" +
                assets.open("licenses/AGPL-3.0.txt").bufferedReader().use { it.readText() }
        }
        AlertDialog.Builder(this).setTitle("实验边界 / 许可证")
            .setView(ScrollView(this).apply { addView(text) }).setPositiveButton("关闭", null).show()
    }
    fun notice(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    override fun onDestroy() { SessionController.detach(this); super.onDestroy() }
    companion object { private const val OPEN_FILE = 101 }
}
