# DSH Pocket（原生安卓 App）

把电脑上 DeepSeek Harness 的 dsh-pocket 入口包装成一个**原生安卓应用**：启动即进入主界面，自动登录，实时同屏。

## 配置

打开 `app/src/main/java/com/dsh/pocket/Config.kt` 修改两行即可适配你的环境：

```kotlin
const val SERVER_URL = "http://192.168.x.x:3081"        // dsh-pocket 代理地址（局域网，首次启动在设置页填写）
const val PIN = ""                                       // 局域网访问密码（首次启动在设置页填写）
```

> 默认留空：首次启动进入设置页填写你自己的服务地址与 PIN，之后自动登录（保存在本地 SharedPreferences）。

## 自动登录原理

1. 启动 → WebView 加载 `SERVER_URL`。
2. 若 cookie 仍有效（30 天内、dsh web 未重启）→ 直达 DSH 主界面。
3. 若未认证 → dsh-pocket 返回登录页（`form[action="/pocket-login"]`），WebView 在
   `onPageFinished` 注入 JS 自动填入 PIN 并提交表单 —— 走官方 `POST /pocket-login`
   流程（含会话握手），成功后 dsh-pocket 下发 HttpOnly cookie（`dsh_pocket_token`，
   `sha256(PIN:sessionKey)`，30 天），由 WebView CookieManager 持久化。
4. dsh web 重启致 cookie 失效时，下次启动会再次自动登录。

## 构建

环境：JDK 17、Android SDK（platform android-34、build-tools 34.0.0）、Gradle 8.7。

```sh
# 已附 gradle wrapper；首次会自动下载 Gradle 8.7
./gradlew :app:assembleRelease      # 产物：app/build/outputs/apk/release/app-release.apk
# 或 debug：
./gradlew :app:assembleDebug        # 产物：app/build/outputs/apk/debug/app-debug.apk
```

签名配置见 `keystore.properties`（指向 `app/dshpocket.keystore`）。**请自行生成你的 keystore 并填入本地 `keystore.properties`（已被 .gitignore 忽略，勿提交）。**
**重要：后续要更新已安装的 App，必须用同一 keystore 签名**，否则安卓拒绝覆盖安装。
所以请妥善保管 `app/dshpocket.keystore` 与 `keystore.properties`（已加入 .gitignore，勿提交）。

## 安装

```sh
adb install -r DshPocket-1.0.apk
```

或把 apk 传到手机点击安装（需开启“允许安装未知来源应用”）。

## 使用

- 手机与电脑连同一局域网，打开 App 即进入 DSH 主界面（自动登录）。
- 下拉刷新 = 重新加载页面（断线后可手动重连；DSH 自身也有 WebSocket 心跳+自动重连）。
- 返回键：先回退页面历史，再退出 App。
- 不在同一网络 / 电脑端未运行时，显示“无法连接”重试页。

## 架构要点

- 单 Activity + 全屏 WebView（`DshWebView`：滚动到顶部才允许下拉刷新）。
- `network_security_config.xml` 允许明文 HTTP（dsh-pocket 局域网入口是 http://）。
- 资源/会话/服务地址全部在 `Config.kt`，无其它硬编码。
