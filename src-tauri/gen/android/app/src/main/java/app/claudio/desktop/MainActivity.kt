package app.claudio.desktop

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

class MainActivity : TauriActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    // enableEdgeToEdge：让 WebView 铺到状态栏 / 手势区底下，状态栏 + 导航栏透明。
    // SystemBarStyle.auto 会随着 system 主题切深浅图标 —— 但我们的封面背景色是
    // 动态的，所以传 dark 让图标永远 light，浅底封面再单独由前端 JS 拿封面亮度
    // 切换 (status-bar-color-controller 后续可以加)。先求一个不会"白底白图标"
    // 的安全默认：图标用浅色（dark scrim），背景透明。
    val transparent = Color.TRANSPARENT
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(transparent),
      navigationBarStyle = SystemBarStyle.dark(transparent),
    )
    super.onCreate(savedInstanceState)
    // 拉起前台服务，保 WebView 进程不被系统在后台 kill
    MediaPlaybackService.start(this)
  }

  // Tauri / Wry 创建完 WebView 后会回调这里。把 MediaController 的
  // JavascriptInterface 在这一刻挂上去，JS 侧 window.__ClaudioMedia 就能直接调
  // setMetadata / setPlaybackState 进 Kotlin 进程更新 MediaSession。
  @SuppressLint("JavascriptInterface")
  override fun onWebViewCreate(webView: WebView) {
    super.onWebViewCreate(webView)
    MediaController.attachWebView(webView)
  }

  override fun onDestroy() {
    // 跟 Activity 生命周期绑定 —— Activity 真正销毁（如用户从最近任务划掉）才停服务
    MediaPlaybackService.stop(this)
    super.onDestroy()
  }

  override fun onLowMemory() {
    super.onLowMemory()
    // 低内存时让 WebView 释放可丢弃缓存（Tauri 自带 WebView 已做过 onLowMemory），
    // 这里不额外处理；前台服务保住进程不被 kill 已经是关键
    @Suppress("KotlinConstantConditions")
    if (Build.VERSION.SDK_INT >= 0) {
      // placeholder：避免上面 SDK gate 逻辑被空块 lint 标记
    }
  }
}
