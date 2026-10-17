# 安卓 App："多少瓦"

插上充电线，看充电功率（W），还要多久充满。

## 适配手机型号：OPPO Find X8s

**仅在一台 OPPO Find X8s（Android 16）上实测验证过，其他机型没有测试**

<p align="center">
  <img src="screenshots/充电中.png" width="240" />
  <img src="screenshots/未充电.png" width="240" />
  <img src="screenshots/原始数据.png" width="240" />
</p>

<p align="center">
  <sub>充电中 · 拔掉线 · 长按大数字看原始读数</sub>
</p>

## 为什么还要做一个充电速度 App

同类 App 上百个，但要么收费、要么看广告；更麻烦的是它们在这台机器上大多读不出数——ColorOS 用 SELinux 封掉了 `/sys/class/power_supply`，系统给的电流单位又和 Android 文档写的差 1000 倍，于是只显示 `-- W`。

这个项目只解这一件事：把「这台手机上到底多少瓦」算对，做成零依赖、零权限、不开后台的单屏工具。

开源是为了让人少踩同一个坑——照着这份代码走，能省半小时到一小时。

## 安装

下载 [最新 APK](https://github.com/1c7/ChargeScope/releases/latest)，传到手机点击安装，需允许「安装未知来源应用」。调试签名，仅供自装。

技术细节见 [`doc/`](doc/)。许可 [MIT](LICENSE)。

## 你可以让你的 AI 参考这个 App，去开发适合你手机的应用（华为，荣耀，小米，VIVO，等等）