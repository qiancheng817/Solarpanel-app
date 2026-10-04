# solarpanel 安卓客户端

为自托管 [Solar-Panel](https://github.com/Ozero-top/Solar-Panel) 导航面板打造的原生安卓 App。所有页面都在应用内打开，不跳转外部浏览器；界面与操作习惯针对手机重新设计。

## 下载安装

到 [Releases](https://github.com/qiancheng817/solarpanel-app/releases) 页面下载最新的 `solarpanel-版本号.apk`，在手机上直接安装即可。

- 支持 Android 7.0（API 24）及以上
- 正式签名安装包，覆盖安装升级无需卸载旧版

## 首次使用

1. 打开 App，填写你部署在 NAS / 服务器上的 solarpanel 地址，例如 `192.168.1.10:8080`
2. 支持 `http://`、`https://`，也可以只填 `IP:端口`
3. 点击「连接」即可进入面板

之后可在右上角菜单中随时「修改服务器地址」。

## 操作方式

- **左缘手势后退**：从屏幕左边缘向右滑动，在网页内即返回上一页
- **左缘手势退出**：已在面板首页时再次左缘滑动，确认后退出 App
- **系统返回键**：优先返回网页历史，根页面双击退出
- 分组切换由面板本体在移动端原生支持（圆形纯图标横向滑动、选中高亮），App 不再额外注入
- **右上角三点菜单**：刷新、内外网切换、切换桌面 / 手机模式、修改服务器地址、清除缓存、用系统浏览器打开、关于
- 顶栏**固定显示**，白色背景与手机状态栏融为一体，不会与系统状态栏重叠

## 手机端适配

- 面板卡片排版、分组切换全部由面板本体在移动端原生呈现，App 不再注入任何布局 / 样式代码
- 边到边（edge-to-edge）布局：自动避让状态栏与底部手势导航条
- 跟随系统深色模式

## 功能一览

- 面板所有链接、`target="_blank"` 页面全部在应用内打开
- 内网 / 外网地址模式切换（状态与面板网页端共享）
- 桌面 / 手机显示模式切换（自定义 UA）
- 支持 HTTP 明文与自签名 HTTPS 证书（自托管场景）
- 文件上传、系统下载管理（含通知栏进度）
- 一键回到面板首页
- 面板 PWA Service Worker / 缓存清理
- 渲染进程异常退出时自动重建并恢复当前页面

## 在线构建

本仓库通过 **GitHub Actions** 在云端自动构建，无需在本地配置 Android 环境：

- 每次向 `main` 分支推送代码都会自动触发构建
- 工作流执行 `assembleRelease`，使用仓库内的正式签名密钥
- 构建成功后自动创建 / 更新对应版本的 [Release](https://github.com/qiancheng817/solarpanel-app/releases) 并上传 APK

如需自行构建：JDK 17、Android SDK 34、Gradle 8.9，执行 `./gradlew assembleRelease`，产物位于 `app/build/outputs/apk/release/`。

## 技术栈

Kotlin · AndroidX · Material 3 · WebView · GitHub Actions

## 致谢

- 面板本体：[Ozero-top/Solar-Panel](https://github.com/Ozero-top/Solar-Panel)
- 本仓库的 Git 历史中保留了早期 Java WebView 套壳版本
