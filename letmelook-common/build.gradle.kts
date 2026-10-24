plugins {
    `java-library`
}

description = "LetMeLook common protocol (shared by purpur server plugin and fabric client)"

dependencies {
    api("com.google.code.gson:gson:2.11.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}
