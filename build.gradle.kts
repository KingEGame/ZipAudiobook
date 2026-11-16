// Plugins are declared here (without applying them) so the Kotlin Gradle plugin is
// loaded once for the whole build instead of separately in :core and :app, which Gradle
// warns about and which can break incremental builds.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
