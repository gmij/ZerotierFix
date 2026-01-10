<h1 align="center">
  <img src="https://github.com/kaaass/ZerotierFix/blob/master/app/src/main/ic_launcher-playstore.png?raw=true" alt="Zerotier Fix" width="200">
  <br>Zerotier Fix<br>
</h1>


本版本完善了全局路由和指定应用路由功能

<h4 align="center">An unofficial Zerotier Android client patched from official client.</h4>

<p align="center">
  <img src="screenshots/main.png" alt="main" width="150"/>
  <img src="screenshots/peers.png" alt="peers" width="150"/>
  <img src="screenshots/moons.png" alt="moons" width="150"/>
</p>

<p align="center">
    <a href="https://github.com/kaaass/ZerotierFix/actions/workflows/build-app.yml">
        <img src="https://github.com/kaaass/ZerotierFix/actions/workflows/build-app.yml/badge.svg" alt="Build APP"/>
    </a>
</p>

## Features

- Self-hosted Moon Support
- Add custom planet config via file and URL
- View peers list
- Chinese translation
- **Global routing mode**: Route all device traffic through ZeroTier network
- **Per-app routing mode**: Selectively route only specified apps through ZeroTier network
  - Global and per-app routing modes are mutually exclusive
  - Easy configuration directly from network details page

## Download

### 📦 Stable Releases

每次推送到 master 分支时，会自动创建新的 Release。查看 [Releases 页面](https://github.com/gmij/ZerotierFix/releases) 获取最新稳定版本。

Releases are automatically created for every push to the master branch. Check the [Releases page](https://github.com/gmij/ZerotierFix/releases) for the latest stable version.

### 🚧 Development Builds

如果你想尝试开发版本，可以从 [GitHub Actions](https://github.com/gmij/ZerotierFix/actions/workflows/build-app.yml?query=branch%3Amaster) 下载。
但请注意，开发版本可能包含 **BUG** 并且 **不稳定**。

If you want to try development builds, you can download them from [GitHub Actions](https://github.com/gmij/ZerotierFix/actions/workflows/build-app.yml?query=branch%3Amaster).
But please note that development builds may be **BUGGY** and **UNSTABLE**.

> 💡 **提示 / Tip**: 所有 Release 都包含自动生成的变更日志，详细列出了自上次版本以来的所有更改。  
> All releases include an automatically generated changelog detailing all changes since the last version.

## Usage

### Basic Network Connection

1. Open the app and tap "Add Network"
2. Enter your ZeroTier network ID
3. Tap "Connect" to join the network
4. Authorize the device in your ZeroTier Central panel

### Routing Configuration

You can configure how traffic is routed through ZeroTier:

- **Global Routing**: Routes all device traffic through the ZeroTier network. Enable the "Route Via ZeroTier" option in network details.
- **Per-App Routing**: Routes only specific apps through ZeroTier. Enable the "Per-App Routing" option and tap "Configure Apps" to select which apps should use the ZeroTier network.

**Note**: Global routing and per-app routing are mutually exclusive. Enabling one will automatically disable the other.

## Copyright

The code for this repository is based on the reverse engineering of the official Android client. The
original author is Grant Limberg (glimberg@gmail.com). See [AUTHORS.md](https://github.com/zerotier/ZeroTierOne/blob/master/AUTHORS.md#primary-authors) for more details.

- Zerotier JNI Sdk is located in git submodule `externals/core`
- Original Android client code is located in `net.kaaass.zerotierfix` (renamed from `com.zerotier.one`)
- App logo is a trademark of `ZeroTier, Inc.` and made by myself. 


## Roadmap

- [X] Add moon config persistent & file config
- [x] Add peer list view
- [x] Support planet config
- [x] Replace pre-built JNI library
- [x] Rewrite & update UI to fit Material Design
- [x] Global routing mode
- [x] Per-app routing mode
- [ ] *WIP* Rewrite whole APP in v2
