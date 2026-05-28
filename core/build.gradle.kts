plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("com.mobiledivecontrol.app.MainKt")
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.coroutines.test)
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

