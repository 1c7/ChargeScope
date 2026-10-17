# 安卓 App "多少瓦"

[English](README.en.md) | **中文**

插上充电线，看充电功率（W），还要多久充满。

## 安卓 App 截图

<p align="center">
  <img src="android/doc/images/充电中.png" width="240" />
  <img src="android/doc/images/未充电.png" width="240" />
  <img src="android/doc/images/原始数据.png" width="240" />
</p>

<p align="center">
  <sub>充电中 · 拔掉线 · 长按大数字看原始读数</sub>
</p>

## 适配手机型号：OPPO Find X8s
仅在一台 OPPO Find X8s（Android 16）上实测验证过，其他机型没有测试

## 为什么还要做一个充电功率 App？

同类 App 上百个，但要么收费、要么看广告，而且在这台手机上大多读不出数。这个只做一件事——打开就有一个准数，不花钱不看广告；源码全部公开，照着改能省你半天。

## 安装

下载 [最新 APK](https://github.com/1c7/ChargeScope/releases/latest)，传到手机点击安装，需允许「安装未知来源应用」。调试签名，仅供自装。

技术细节见 [`android/doc/`](android/doc/)。许可 [MIT](LICENSE)。

## 你可以让你的 AI 参考这个 App，去开发适合你手机的应用（华为，荣耀，小米，VIVO，等等）

这份代码的读法只对 OPPO 有效。其他品牌（华为、荣耀、小米、vivo、三星）开放的电池接口、权限、电流单位都不一样，直接抄过去大概率读不出数。

把整个仓库连同 [`android/doc/`](android/doc/) 交给你的 AI，让它照 [`android/doc/2`](android/doc/2-各家手机读取电池数据的差异.md) 的思路在你的机型上重新实测一遍——比自己摸索快得多，也比等别人适配快。
