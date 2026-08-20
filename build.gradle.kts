plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.11.0"
}

group = "dev.zzzz"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Build against the locally installed IDE — the APIs used by this plugin are
        // stable since ~2020, so the produced artifact stays compatible with 2025.3+ IDEs.
        local("/Applications/IntelliJ IDEA.app")
    }
    // vcs-impl (VcsDialogUtils, VcsCloneDialog) is not exported by the local() dependency
    // but is a platform lib jar bundled in every IDE — needed at compile time only.
    compileOnly(files("/Applications/IntelliJ IDEA.app/Contents/lib/intellij.platform.vcs.impl.jar"))
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.zzzz.open-existing-clone"
        name = "Clone or Open"
        version = project.version.toString()
        description = "When you paste a repository URL, detect that it was already cloned locally and open the existing project instead of cloning again."
        vendor {
            name = "zzzz"
        }
        ideaVersion {
            sinceBuild = "253"
            untilBuild = provider { null }
        }
        changeNotes = "Initial release."
    }
}

tasks.matching { it.name == "buildSearchableOptions" }.configureEach {
    enabled = false
}
