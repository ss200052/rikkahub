# 跟随官方版本更新

这份文件说明如何把官方新版本合并进这条魔改线。**它比任何一次具体的合并都重要**：
仓库的历史结构决定了这件事以后是否只是「一条 merge 命令」。

## 当前基线

- 上游官方：3.1.1（提交 `ad2c443`）
- 本仓库：在官方 3.1.0（`eeef60b`）之上合并了 3.1.1
- 合并提交有两个父：父一是这条魔改线，父二是官方 3.1.1

这个「两条线在 merge 提交处汇合」的结构是关键。有了它，以后再合并官方版本时，
git 会自动把**上一个官方版本**当作共同祖先，冲突只会出现在双方都改过的文件上，
而不是几百个文件全量冲突。

> 注意：历史上的第一次合并曾经用「下载 zip 自己造提交」的等价快照做过一遍，
> 那是没有共同祖先时的权宜之计。**现在不要再用那种方式**——直接取官方真实 tag，
> 历史才是真实对齐的。

## 升级步骤

```bash
# 1. 取官方新版本（remote upstream 已配置好）
git fetch upstream --tags

# 2. 合并到当前开发线
git checkout master
git merge <新版本 tag>

# 3. 解决冲突（见下节），然后验证
# 4. 推送
git push origin master
```

如果 `git fetch upstream --tags` 因网络失败（这个环境里 GitHub 的 git 协议时通时不通），
可以先用 GitHub API 下载 tag 的 zipball 看内容，但**不要**用它造提交替代官方历史；
等网络恢复后再 fetch，保持历史真实。

## 冲突高发的文件（改之前先看这里）

这几个文件是双方都在动的，合并时大概率冲突：

| 文件 | 我们改了什么 | 官方在动什么 |
| --- | --- | --- |
| `data/datastore/SettingsFields.kt` | 批量生成、提示词方案等字段 | 不断新增字段、重排顺序 |
| `data/datastore/Settings.kt` | 同上 | 同上 |
| `data/ai/tools/WorkspaceTools.kt` | 文案改走 `PromptRuntime` | 工作区 backend 抽象（`layout.workspaceDir` 等） |
| `data/ai/transformers/WorkspaceReminderTransformer.kt` | 整段改为骨架渲染 | SSH 远程工作区、连不上分支 |
| `data/db/fts/MessageFtsManager.kt` | 思维链列、搜索范围、`recreateSchema` | 分词与索引结构重构 |
| `data/db/AppDatabaseFactory.kt` | FTS 建表版本 | 建表入口与旧词典清理 |
| `data/repository/ConversationRepository.kt` | 搜索范围参数 | import 布局调整 |

合并顺序的经验：**先取官方版本的实现结构，再把我们的改动叠上去**。
反过来做（保住我们的整份文件、再补官方改动）容易漏掉官方的新参数与新分支。

## 验证清单

```bash
# 官方 3.1.1 起改成了 github/play 渠道，任务名带 flavor
./gradlew :app:compileGithubDebugKotlin -x :web:buildWebUi -x :app:processGithubDebugGoogleServices
./gradlew :app:testGithubDebugUnitTest -x :web:buildWebUi -x :app:processGithubDebugGoogleServices
./gradlew spotlessApply -x :web:buildWebUi
```

**已知的固定失败**：在 Windows 上 `SettingsStoreTest`（6 个）与 `ProviderAccountManagerTest`（1 个）
会因 DataStore 临时文件重命名失败（`Unable to rename ... settings.preferences_pb.tmp`）。
这是平台问题，不是代码缺陷；官方 CI 在 Linux 上不会出现。判断「有没有引入新问题」时，
看失败集合是否仍是这 7 个。

## 有意为之的偏差（不要试图消除，否则构建会坏）

- **`material3/material-color-utilities` 是内联内容**，官方用的是 submodule。发布用的 zip 归档
  不含子模块，内联才能构建；`.gitmodules` 相应地保持删除状态。
- **`applicationId = me.rerere.rikkahub.mine`**，为了和官方版共存安装。
- **`.github/workflows/build-apk.yml` + `.github/rikka.jks.b64`** 是自建打包流程与固定签名，
  官方没有这两个文件。
- **`.claude/skills` 以 symlink 形式（`core.symlinks=false`）保存**，与官方一致。
  不要在 Windows 上让它被展开成目录，否则每次合并都会产生几十个文件的噪音。

## 打包

### 本地构建（当前可用的路径）

```powershell
powershell -ExecutionPolicy Bypass -File build-apk.ps1
```

等价于下面这条（与 CI 完全一致的参数）：

```bash
./gradlew :app:assembleGithubRelease -x :web:buildWebUi --no-configuration-cache
```

- `-x :web:buildWebUi` 必须带上：`web-ui/node_modules` 没装也能出包，内置网页服务用不了，
  聊天功能不受影响（CI 一直如此）。
- `--no-configuration-cache` 用来绕开 `:common:generateReleaseLintModel` 的配置缓存序列化报错。
- 首次构建要从 `dl.google.com` 拉依赖。若 `~/.gradle/gradle.properties` 里配了连不通的代理，
  会报 `Remote host terminated the handshake`；直连可用时把那些 `systemProp.*.proxy*` 注释掉。
- 产物在 `app/build/outputs/apk/github/release/`：`arm64-v8a`（手机用）、`x86_64`、`universal`。
  签名与 CI 产物一致（keystore md5 `2f336489f7f70a7a090ddf20526d3fd8`），可直接覆盖安装。

### GitHub Actions（fork 受限）

`ss200052/rikkahub` 是 fork。GitHub 对 fork 的 Actions 用量设有保护阈值，累计用满之后会在
Actions 页面显示：

> Workflows are not currently being run on this fork due to the scale of GitHub Actions usage.

此时 `workflow_dispatch` 一律返回 422 `Actions has been disabled for this repository`，
即使 `PUT /repos/{owner}/{repo}/actions/permissions` 返回 204、`GET` 显示 `enabled: true` 也没用
—— 那是仓库级开关，管不到 fork 这层限制。

恢复方式三选一（前两个需要仓库 admin 的网页操作）：

1. **Leave fork network**（Settings → General → Danger Zone → Leave fork network）。前置条件是
   public / 小于 1 GB / 没有子 fork，三条当前都满足；操作不可逆，但保留全部提交历史。
2. 按 Actions 页面提示重新启用该 fork 的 workflows（用量再次触顶会二次被禁）。
3. 新建一个非 fork 的公开仓库，整仓推过去 —— public 仓库的 Actions 免费额度用不完。

Actions 恢复后，触发方式如下（手动，push 不会自动出包）：

```bash
curl -X POST -H "Authorization: token <token>" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/<owner>/rikkahub/actions/workflows/build-apk.yml/dispatches \
  -d '{"ref":"master"}'
```

产物是 actions artifact（`rikkahub-apk`），下载需要带 Authorization 的请求。

**无论选哪种，都该先处理 `.github/workflows/daily-build.yml`**：它每天 UTC 09:00 / 18:00
各跑一次完整构建，是 fork 用量触顶的主要来源。删掉或改成低频，否则重新启用后还会再被禁。
