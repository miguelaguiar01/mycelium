plugins {
    `java-library`
    application
}

group = "mycelium"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    applicationName = "mycelium-merge"
    mainClass = "mycelium.cli.Main"
}

repositories {
    mavenCentral()
}

dependencies {
    // The LZ4 library and version that Minecraft 26.3 itself bundles.
    implementation("at.yawk.lz4:lz4-java:1.10.1")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("net.jqwik:jqwik:1.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("mycelium.fixtures", layout.projectDirectory.dir("src/test/resources/fixtures").asFile.path)
    maxHeapSize = "1g"
    testLogging {
        events("failed", "skipped")
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Passes a world through the format layer; see tools/mc/check_rewritten_world.py.
tasks.register<JavaExec>("rewriteWorld") {
    group = "verification"
    description = "Rewrites a Minecraft world through the format layer: --args=\"<world> <new world> ZLIB|LZ4|NONE\""
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "mycelium.testing.RewriteWorld"
}

// Merges three worlds several ways and compares the results; see MergeCheck.
tasks.register<JavaExec>("mergeCheck") {
    group = "verification"
    description = "Checks that dividing a merge differently gives the same world: --args=\"<base> <ours> <theirs> ours|theirs changed|neighbours|all <work folder>\""
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "mycelium.testing.MergeCheck"
    maxHeapSize = "12g"
}
