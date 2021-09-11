plugins {
    kotlin("jvm") version "1.5.30"
    `java-library`
    java
    `maven-publish`
}

group = "com.github.virginiaprivacycoalition"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation(kotlin("stdlib"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.6.0")
    implementation("org.usb4java:usb4java:1.3.0")
    implementation("com.github.virginiaprivacycoalition:sdr:0.2.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("release") {
            group = "com.github.virginiaprivacycoalition"
            artifactId = "desktopusb"
            version = "1.0.0"
            from(components["java"])
        }
    }
}