# Open Existing Clone

一个 JetBrains 平台插件(IntelliJ IDEA / WebStorm / PyCharm / RustRover / DataGrip 通用)。

## 解决什么问题

想再次打开某个以前 clone 过的仓库时,通常是 Welcome 界面点 **Get from VCS**、粘贴 URL,
结果 IDE 提示 *"The directory already exists and it is not empty"*,Clone 按钮被禁用;
然后只能自己手动翻文件夹找到那个目录再打开。

这个插件把这一步自动化:**粘贴仓库地址,如果克隆目标目录在本地已存在,按钮自动变成
Open (打开),点击直接打开该项目(并加入最近项目);目录不存在则保持 Clone,走正常克隆流程。**

## 使用方式

打开 **Get from Version Control**(Welcome 界面按钮,或 File → New → Project from Version Control…):

- 对话框默认落在 **Local (本地已有)** 标签页(插件覆盖了打开动作,用 `forExtension` 预选)
- 标签页内是**官方原版克隆表单**:Version control 下拉(默认选中 Git)、URL、Directory、Shallow clone
  ——与内置 "Repository URL" 标签页完全同源(外壳照搬 `RepositoryUrlCloneDialogExtension`,
  表单直接使用各 VCS 插件自己的 `VcsCloneComponent`)
- 粘贴 URL 后插件在后台检查 **Directory 字段指向的那个目录**是否已存在且非空:
  - 存在 → 按钮变 **Open (打开)**(同时放行官方"目录已存在"的拦截校验),点击即打开
  - 不存在 → 按钮保持 **Clone**,官方正常克隆
- 想完全用官方原版流程:左侧切到 **Repository URL** 标签页(未被修改)

**检测范围刻意不跨目录**:只看表单当前指向的目录(默认为该 IDE 的默认项目目录 + 仓库名,
即 IDEA 看 `~/IdeaProjects`、RustRover 看 `~/RustRoverProjects`),各 IDE 各管各的,互不越界,
Directory 字段也绝不会被插件改写。

## 安装

构建产物:`build/distributions/open-existing-clone-1.0.0.zip`

各 IDE 中:**Settings/Preferences → Plugins → ⚙️ → Install Plugin from Disk…** 选择 zip,重启。
四个 IDE 装同一个 zip,插件只依赖平台与 VCS 模块。

- 兼容:`sinceBuild 253`(2025.3+,含 2026.1)
- 打开项目复用平台标准"检出完成"管线(`CheckoutProvider.Listener`):
  信任提示、加入最近项目等行为与正常克隆完成完全一致

## 构建

需要 JDK 21。Gradle 8.14.3 Wrapper + IntelliJ Platform Gradle Plugin 2.11.0
(更高版本需要 Gradle 9),以本机 IntelliJ IDEA 作为编译目标
(`local("/Applications/IntelliJ IDEA.app"`):插件所用 API 自 2020 年起稳定,产物对 2025.3+ 通用。

```bash
./gradlew buildPlugin   # 产出 build/distributions/open-existing-clone-1.0.0.zip
./gradlew runIde        # 沙箱 IDE 调试
```

## 工程结构

```
src/main/kotlin/dev/zzzz/openexistingclone/
├── GitUrls.kt                   # Git URL 解析/归一化(https、ssh、git@、裸 host/path 互认)
├── Support.kt                   # LocalCloneMatch、UrlHistory(本地记录)
├── LocalCloneDialogExtension.kt # Get from VCS "Local" 标签页:官方外壳+真实克隆表单+智能打开
└── SmartGetFromVcsAction.kt     # 覆盖 Get from Version Control 动作,默认预选 Local 标签页
```

用到的机制:`com.intellij.openapi.vcs.ui.cloneDialog.VcsCloneDialogExtension`
(Get from VCS 对话框官方扩展点)、`VcsCloneDialog.Builder.forExtension`
(官方提供的预选标签页入口)、动作覆盖(`overrides="true"`)、
`CheckoutProvider.Listener` 检出完成管线(须在非 EDT 线程调用——平台有线程断言)。

## 注意

- 项目已建 git 仓库;`git status` 可随时确认没有别的会话在改动文件。
- 已知取舍:在 A IDE 里打开 B IDE 克隆过的仓库不会被检测到(不跨目录),会走正常克隆。
