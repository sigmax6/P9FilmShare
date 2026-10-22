# 发布指南：国内平台（推荐 Gitee）+ GitHub 双平台同步

> 适用项目：P9FilmShare（`com.p9filmshare.app`，MIT 协议）。
> 目标读者：首次把本仓库同时发布到「国内可秒开的平台」与「GitHub」，并保持后续同步。

---

## 1. 国内平台怎么选（2025–2026 现状）

| 平台 | 运营方 | 适合度 | 关键特点 / 坑 |
|---|---|---|---|
| **Gitee（码云）** ⭐推荐 | 开源中国 OSChina | ★★★★★ | 国内访问最快、最稳定；Pages/Wiki/Issues/PR/Release/Actions 齐全；中文社区大；**免费可建 1000 个仓库、公开/私有都免费**。坑：**强制实名认证（手机号/身份）**；公开仓库首次可能需审核；免费 Actions 算力有限；免费版**自动双向同步 GitHub 受限**（用本指南的“一库多 remote”最省心）。 |
| **AtomGit（原 GitCode 升级）** | 开放原子开源基金会 + CSDN | ★★★★ | 2025 年底由 GitCode 品牌升级；可用 CSDN 账号一键登录；**OpenHarmony/鸿蒙生态官方托管基座**，做鸿蒙相关项目有官方背书；新平台、生态与稳定性仍在成长期。 |
| GitLink | CCF + 中科院 | ★★ | 学术背书、完全开源，但社区活跃度低、Issue 响应慢，适合学术圈。 |
| CODING（腾讯云） | 腾讯 | ★★ | 企业级稳定，但免费额度少、偏企业 DevOps，个人开源不推荐。 |

**结论（针对本项目）**：

- **主力国内平台用 Gitee**：受众最广、国内 clone/下载 APK 最快、文档与 Release 体验最成熟。
- **如果你特别想要“鸿蒙官方生态”背书**，可再在 **AtomGit** 发一份（它和 Gitee 不冲突，方法同样是加一个 remote）。
- **GitHub 作为国际主仓 / Release 存档**：海外用户、开源生态、被搜索引擎收录都靠它。

> 三个平台都基于标准 Git，因此**最省事的同步方式不是平台自带镜像，而是“本地一个仓库 + 多个 remote，一条命令同时推”**（见第 4 节）。这样不依赖任何一方的镜像功能是否收费/审核。

---

## 2. 发布前准备（账号 & 合规）

1. **Gitee**：注册 https://gitee.com → 完成**实名认证**（个人实名即可，发布公开仓库基本都需要）。
2. **GitHub**：注册 https://github.com → 建议开启 **2FA 两步验证**。
3. 本机配置 Git 身份（只需一次，把下面换成你的名字/邮箱）：
   ```bash
   git config --global user.name  "your-name"
   git config --global user.email "you@example.com"
   # 让长路径/中文文件名在 Windows 上正常
   git config --global core.autocrlf false
   ```
4. 准备好 **GitHub Personal Access Token（PAT）**（用于 https 推送；用 SSH key 可跳过，见第 6 节）：
   GitHub 右上头像 → `Settings` → `Developer settings` → `Personal access tokens` →
   `Tokens (classic)` → `Generate new token`，勾选 **`repo`**（若要用 Gitee 自动 Pull 镜像还要 `admin:repo_hook`），生成后**只显示一次，复制保存**。

---

## 3. 在两个平台各建一个空仓库

两边都建**同名**（建议 `P9FilmShare`）、**完全空**的仓库：**不要**勾选 “初始化 README / .gitignore / License”，否则首次推送会因历史不一致而冲突。

- GitHub：右上 `+` → `New repository` → 名字 `P9FilmShare` → `Public` → **不勾**任何初始化项 → Create。
- Gitee：右上 `+` → `新建仓库` → 仓库名 `P9FilmShare` → 选「**开源/公开**」→ 语言选 Java →
  **不要**勾“使用 Readme 初始化” → 创建。（开源许可证不用选，仓库里已带 `LICENSE`。）

记下两个地址（HTTPS 形式）：
```
GitHub : https://github.com/<你的用户名>/P9FilmShare.git
Gitee  : https://gitee.com/<你的用户名>/P9FilmShare.git
```

---

## 4. 本地初始化 + “一库多 remote”同时发布（推荐）

在本目录（`P9FilmShare-OpenSource/`）执行：

```bash
cd P9FilmShare-OpenSource

# 1) 初始化并做首次提交
git init -b main
git add .
git status                 # 确认没有误提交 APK/本地路径（.gitignore 已忽略 *.apk、build/、local.properties）
git commit -m "Initial commit: P9FilmShare - hotspot-bridged photo transfer for legacy Android ↔ HarmonyOS"

# 2) 配两个 remote：origin 指 Gitee（国内主用），github 指 GitHub
git remote add origin  https://gitee.com/<你的用户名>/P9FilmShare.git
git remote add github  https://github.com/<你的用户名>/P9FilmShare.git

# 3) 一条命令同时推到两个平台
git push -u origin  main
git push -u github  main
```

之后**日常更新**只需：
```bash
git add .
git commit -m "你的提交说明"
git push origin main && git push github main
```

> 想更省事可定义一个批量推送别名（以后 `git pushall` 一次推两边）：
> ```bash
> git config alias.pushall '!git push origin main && git push github main'
> ```

**打 Release 版本（含 APK 附件）**——标签会随 push 同步，Release 附件需在各网页端上传：
```bash
git tag -a v1.0 -m "P9FilmShare v1.0 (first stable release)"
git push origin v1.0 && git push github v1.0
```
然后分别在 Gitee 仓库「发行版/Release」和 GitHub 「Releases」里，基于该标签写更新说明、上传 `P9FilmShare-v1.0-release.apk`
（GitHub 单文件 ≤2GB、Gitee Release 附件也够用；本 APK 约 0.9MB）。
> Release 版 APK 由工程内 `P9FilmShare-release.jks` 签名，签名密码存于 `local.properties`（已被 .gitignore 忽略，不会进仓库）；上传时用 `app/build/outputs/apk/release/app-release.apk`（已重命名为 `P9FilmShare-v1.0-release.apk`）。

---

## 5. 备选：用 Gitee 自带“仓库镜像”自动同步 GitHub

如果你希望**只推一个平台、另一个自动更新**，可用 Gitee 的镜像功能（不强制，和第 4 节二选一）：

- 路径：Gitee 仓库 →「管理」→「仓库镜像管理」→「添加镜像」，按提示绑定 GitHub 账号、填入 PAT。
  - **Push 方向**：推到 Gitee → 自动同步到 GitHub；
  - **Pull 方向**：GitHub → Gitee，PAT 需含 `repo`，要“自动同步”还需 `admin:repo_hook`；
  - 可勾“自动镜像”，最短触发间隔约 5 分钟。
- ⚠️ 官方提示：**双向镜像存在代码丢失风险，谨慎使用**；免费版能力可能调整。
- **因此更推荐第 4 节的“一库多 remote、手动一条命令双推”**：简单、可控、不依赖第三方镜像是否收费。

---

## 6.（可选）改用 SSH，免去每次输 Token

```bash
# 生成密钥（一路回车）
ssh-keygen -t ed25519 -C "you@example.com"
# 公钥内容（Windows）：
type %USERPROFILE%\.ssh\id_ed25519.pub        # Git Bash 用：cat ~/.ssh/id_ed25519.pub
```
把公钥分别贴到：
- GitHub → `Settings` → `SSH and GPG keys` → `New SSH key`；
- Gitee →「设置」→「SSH 公钥」。

然后把 remote 改成 SSH 地址：
```bash
git remote set-url origin  git@gitee.com:<你的用户名>/P9FilmShare.git
git remote set-url github  git@github.com:<你的用户名>/P9FilmShare.git
```

---

## 7. 发布检查清单

- [ ] 仓库内无本地绝对路径 / 账号 / 凭据（已扫描：无 `Administrator`、`android-dev`、`workbuddy`、`local.properties`）。
- [ ] `app/build.gradle` 的 `applicationId` 为 `com.p9filmshare.app`（已脱敏）。
- [ ] 含 `LICENSE`(MIT)、`README.md`、`.gitignore`、`docs/index.html`。
- [ ] `.gitignore` 已忽略 `*.apk / build/ .gradle/ local.properties *.jks *.keystore`。
- [ ] GitHub Pages（可选）：仓库 `Settings → Pages`，Source 选 `main` 分支、`/docs` 目录，即可用 `docs/index.html` 做出官网。
- [ ] Release 上传 APK，并在 README 顶部加一行下载链接（两个平台各放一份 APK，国内用户走 Gitee 更快）。

## 8. 关于“华为分享 / Huawei Share”命名的合规提醒

本项目是**兼容桥接**（标准 HTTP + 个人热点），**不含、不逆向**任何厂商私有协议。
仓库与对外文案建议用 **P9FilmShare / 胶片传 / 华为分享兼容助手** 这类描述性名称，
避免直接以官方 “Huawei Share / 华为分享” 作为产品名或商标，以免产生混淆或商标问题。
