# Open Existing Clone

一个 JetBrains 平台插件(IntelliJ IDEA / WebStorm / PyCharm / RustRover / DataGrip 通用)。

## 解决什么问题

想再次打开某个以前 clone 过的仓库时,通常是去 Welcome 界面点 **Get from VCS**、粘贴 URL,
结果 IDE 提示 *目录已存在且非空*,Clone 按钮被禁用;然后只能自己手动翻文件夹找到那个目录再打开。

这个插件把这一步自动化:**粘贴仓库 URL,发现本地已经 clone 过,直接打开(并加入最近项目)**,
不用再自己找目录。

## 两个入口

### 1. Get from VCS 对话框里的 "Local (本地已有)" 标签页

Welcome 界面 → **Get from VCS**,或菜单 **File → New → Project from Version Control…**,
在左侧来源列表里多出一个 **Local (本地已有)**:

- 该页复刻了默认 "Repository URL" 页的完整表单(版本控制下拉 + More via plugins 链接 + URL + 目录 + shallow 等全部行),
  外观与默认页一致
- 粘贴仓库地址后插件在后台搜索本地克隆;**找到后 OK 按钮变为 Open (打开)**,点击即打开本地已有项目 ——
  复用平台标准的"检出完成"管线,信任项目提示、加入最近项目列表等行为与正常 clone 完成后一致
- 本地未找到时按钮保持 **Clone(克隆)**,走平台正常克隆流程,不会挡路

### 2. File 菜单:Open Existing Clone…

**File → Open Existing Clone…**(在 Open 项目组里):弹窗粘贴 URL → 列出本地匹配 → **Open** 或双击直接打开。
打开前 URL 会自动存入历史,下次可直接下拉选择。

## 搜索范围

默认扫描这些根目录(存在才扫,最多向下 2 层):

```
~/IdeaProjects  ~/PycharmProjects  ~/WebStormProjects  ~/RustRoverProjects
~/GoLandProjects  ~/CLionProjects  ~/DataGripProjects  ~/PhpStormProjects
~/AndroidStudioProjects  ~/dev  ~/projects  ~/code  ~/repos  ~/src  ~/github  ~/work  ~/workspace …
```

可在 **Settings → Tools → Open Existing Clone** 里:

- 追加自定义搜索根目录(每行一个绝对路径,支持 `~`)
- 开关"同名目录也算匹配"
- 调整扫描深度(1–4 层)

匹配不依赖 `git` 命令:插件直接读取候选目录的 `.git/config` 解析 `origin` 远程地址,
兼容 worktree / submodule(`.git` 为文件的情况)。

## 构建与安装

需要 JDK 21。工程使用 Gradle 8.14.3 Wrapper + IntelliJ Platform Gradle Plugin 2.11.0
(更高版本的插件需要 Gradle 9),默认以本机安装的 IntelliJ IDEA(`local("/Applications/IntelliJ IDEA.app"`)
作为编译目标 —— 本插件只使用 2020 年起就稳定存在的平台 API,产物兼容 `sinceBuild 253`(2025.3+)的所有 IDE。

```bash
./gradlew buildPlugin
# 产物: build/distributions/open-existing-clone-1.0.0.zip
```

在各个 IDE 中安装:**Settings/Preferences → Plugins → ⚙️ → Install Plugin from Disk…**
选择上面的 zip,重启 IDE。四个 IDE(IntelliJ IDEA、WebStorm、PyCharm、RustRover)装同一个 zip 即可,
插件只依赖平台与 VCS 模块,无 IDE 专属依赖。

开发时可用 `./gradlew runIde` 在沙箱 IDE 里试(已验证插件在 2026.1 沙箱中正常加载)。

## 工程结构

```
src/main/kotlin/dev/zzzz/openexistingclone/
├── GitUrls.kt                    # Git URL 解析/归一化(https、ssh、git@、裸 host/path 互认)
├── LocalCloneSearcher.kt         # 本地克隆扫描 + .git/config origin 匹配
├── OpenExistingCloneSettings.kt  # 设置持久化(自定义根目录 / 同名匹配 / 扫描深度)
├── CloneSearchPanel.kt           # 共享搜索面板(URL 输入 + 结果列表 + 后台扫描)
├── LocalCloneDialogExtension.kt  # Get from VCS 对话框 "Local" 标签页
├── OpenExistingCloneAction.kt    # File 菜单动作 + 弹窗
└── OpenExistingCloneConfigurable.kt # Settings 页面
```

用到的平台扩展点:`com.intellij.openapi.vcs.ui.cloneDialog.VcsCloneDialogExtension`
(Get from VCS 对话框官方扩展点,内置 "Repository URL" 页签就是它实现的)、
`CompositeCheckoutListener`(检出完成 → 打开项目并加入最近项目的标准管线)。
