plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover) apply false
}

tasks.register("validateVersion") {
    group = "verification"
    description = "Validates project version matches X.Y format"
    doLast {
        subprojects.forEach { sub ->
            sub.extensions.findByType(com.android.build.gradle.AppExtension::class.java)?.let { android ->
                val version = android.defaultConfig.versionName
                require(version != null && version.matches(Regex("^\\d+\\.\\d+$"))) {
                    "VERSION ERROR: ${sub.name} version '$version' must be in X.Y format"
                }
            }
        }
    }
}
