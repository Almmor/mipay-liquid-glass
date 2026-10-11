plugins {
    id("com.android.application")
}

android {
    namespace = "com.liquidglass.mipay"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.liquidglass.mipay"
        minSdk = 26
        targetSdk = 34
        versionCode = 36
        versionName = "1.6.7"

        // Xposed 模块无需多语言资源压缩之外的额外配置
        resourceConfigurations += listOf("zh", "en")
    }

    buildTypes {
        release {
            // Xposed 入口类名必须保留，禁止混淆 / 压缩
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 个人使用场景：默认复用 debug 签名，保证 assembleRelease 产物可直接安装。
            // 如需正式发布，请替换为你自己的 signingConfig。
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += setOf("META-INF/*.version", "META-INF/*.kotlin_module")
        }
    }
}

dependencies {
    // Xposed API 由框架在运行时提供，编译期只需 compileOnly
    compileOnly("de.robv.android.xposed:api:82")
}
