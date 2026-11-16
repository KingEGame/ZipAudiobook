plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}

/**
 * Inspect real archives from a terminal:
 *   ./gradlew :core:inspect -Pzips="/path/a.zip /path/b.zip"
 */
tasks.register<JavaExec>("inspect") {
    group = "verification"
    description = "Print the detected book/chapter grouping for one or more ZIP archives."
    mainClass.set("com.kingwar.zipbook.core.Cli")
    classpath = sourceSets["main"].runtimeClasspath
    val zips = (project.findProperty("zips") as String?).orEmpty()
    args = zips.split(" ").filter { it.isNotBlank() }
}
