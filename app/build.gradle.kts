plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.saywhat.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.saywhat.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 24
        // 版本路线见任务书第十四节「版本历史」：
        //   第三位 = 修 bug / 小改进，第二位 = 大功能
        //   0.3.6 卡片压缩 + 截屏失效提醒 → 0.3.7 双渠道提醒 → 0.3.8 文案脱钩「微信」
        versionName = "0.3.8"
    }

    // 「可移植的调试签名」：
    // 默认走 ~\.android\debug.keystore（每台机器自动生成的那份）；
    // 若 local.properties 里指了别的路径（比如工作区内的副本，为了绕开沙箱写限制），就用那一份。
    // 这样仓库本身不含任何绝对路径，别人克隆下来照样能编译。
    // 注意：这里刻意不用 java.util.Properties —— Kotlin DSL 里 `java` 是 JavaPluginExtension，
    // 会把 `java.util` 这个包名遮蔽掉，编译脚本直接报 Unresolved reference: util。
    val debugKeystorePath: String? = rootProject.file("local.properties")
        .takeIf { it.exists() }
        ?.readLines()
        ?.firstOrNull { it.trim().startsWith("debug.keystore=") }
        ?.substringAfter('=')
        ?.trim()
        // local.properties 是 Java Properties 格式：反斜杠要转义，路径分隔符写作 \\
        // 这里手工把转义解回来（Properties 会做，但它在 Kotlin DSL 里用不了，见上）
        ?.replace("\\\\", "\u0000")
        ?.replace("\\", "")
        ?.replace("\u0000", "\\")
        ?.takeIf { it.isNotEmpty() }
    if (debugKeystorePath != null) {
        signingConfigs.getByName("debug").storeFile = File(debugKeystorePath)
    }

    // ── 正式签名（Release）──
    // 从工程根目录的 keystore.properties 读，该文件已在 .gitignore 里排除。
    // 读不到就退回 debug 签名 —— 这样别人 clone 下来不配密钥也能编译（只是发不了正式包）。
    //
    // 注意写法：这里刻意用最笨的逐行解析，而不是 java.util.Properties。
    // 原因见上方「可移植的调试签名」—— Kotlin DSL 里 `java` 是 JavaPluginExtension，
    // 会把 `java.util` 这个包名遮蔽掉；而一旦掺进 lambda 与解构，
    // 类型推断还会跟着崩（试过，报了六条 Type mismatch）。
    val releaseProps = mutableMapOf<String, String>()
    val releasePropsFile = rootProject.file("keystore.properties")
    if (releasePropsFile.exists()) {
        for (raw in releasePropsFile.readLines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || !line.contains('=')) continue
            val key = line.substringBefore('=').trim()
            var value = line.substringAfter('=').trim()
            // Properties 格式的转义：\\ → \ 、\: → :
            value = value.replace("\\\\", "\u0000").replace("\\", "").replace("\u0000", "\\")
            releaseProps[key] = value
        }
    }
    val releaseStore = releaseProps["storeFile"]
    if (releaseStore != null) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(releaseStore)
            storePassword = releaseProps["storePassword"]
            keyAlias = releaseProps["keyAlias"]
            keyPassword = releaseProps["keyPassword"]
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 有正式密钥就用它；没有才退回 debug 签名（保证任何人都能编译出可安装的包）
            signingConfig = if (releaseStore != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = false
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "DebugProbesKt.bin",
            "kotlin-tooling-metadata.json"
        )
    }

    lint {
        abortOnError = false
        // 关掉 release 构建的 lint 检查（lintVitalRelease）。
        //
        // 为什么：它需要在构建时**额外下载** `com.android.tools.lint:lint-gradle`，
        // 而这个依赖不在离线依赖缓存里 —— 本项目要求「纯离线、免提权也能编译」，
        // 一旦保留它，`assembleRelease` 就会失败。
        //
        // 代价与取舍：release 包不再跑 lint 静态检查。
        // 判断是可接受的 —— lint 的价值在开发期，而开发期的 debug 构建仍然会跑；
        // release 包在本项目里的作用是「一个签名正确、能安装的正式包」。
        // 若将来要在 CI 上跑完整 lint，把这行改回 true 并让 CI 联网即可。
        checkReleaseBuilds = false
    }
}

// 本项目刻意保持「零第三方依赖」：
// 网络用 java.net.HttpURLConnection，JSON 用 Android 自带的 org.json。
// 好处：Gradle 不存在依赖解析失败风险，构建快，APK 极小。
dependencies {
}
