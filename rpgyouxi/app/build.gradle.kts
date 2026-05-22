plugins {
    kotlin("jvm") version "1.9.22"
}

group = "com.example"
version = "1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Kotlin standard library & Coroutines
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Gson for JSON Serialization
    implementation("com.google.code.gson:gson:2.10.1")

    // Testing dependencies
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.22")

    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.junit.platform:junit-platform-launcher:1.8.2")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register("printTestClasspath") {
    doLast {
        val testRuntimeClasspath = project.extensions.getByType<SourceSetContainer>()["test"].runtimeClasspath
        println("TEST_CLASSPATH=" + testRuntimeClasspath.asPath)
    }
}

tasks.register<JavaExec>("runManualTests") {
    mainClass.set("com.example.pythonrpg.engine.ManualTestRunnerKt")
    classpath = project.extensions.getByType<SourceSetContainer>()["test"].runtimeClasspath
}

