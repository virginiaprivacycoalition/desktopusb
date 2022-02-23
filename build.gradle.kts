plugins {
    kotlin("jvm") version "1.6.20-M1"
    `java-library`
    java
    `maven-publish`
}

group = "com.github.virginiaprivacycoalition"
version = "1.4.7"

repositories {
    mavenCentral()
    //maven("https://jitpack.io")
    mavenLocal()
}

dependencies {
    implementation(kotlin("stdlib"))
    testImplementation("io.kotest:kotest-runner-junit5:5.1.0")
    testImplementation("io.kotest:kotest-assertions-core:5.1.0")
    testImplementation("io.kotest:kotest-property:5.1.0")
    implementation("org.usb4java:usb4java:1.3.0")
    implementation("org.usb4java:usb4java-javax:1.3.0")
    implementation("org.apache.commons:commons-collections4:4.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.0-native-mt")
    api("com.github.virginiaprivacycoalition:sdr:0.7.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}

tasks.kotlinSourcesJar {
    archiveClassifier.set("sources")
}

publishing {
    publications {
        create<MavenPublication>("release") {
            group = "com.github.virginiaprivacycoalition"
            artifactId = "desktopusb"
            version = "1.4.7"
            from(components["java"])
            artifact(tasks.kotlinSourcesJar)
        }
    }
}