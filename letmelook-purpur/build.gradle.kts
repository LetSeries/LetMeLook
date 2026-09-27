import org.gradle.api.tasks.SourceSetContainer
import org.gradle.jvm.tasks.Jar

plugins {
    java
}

description = "LetMeLook Purpur/Paper server plugin (CoreProtect bridge)"

dependencies {
    implementation(project(":letmelook-common"))
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly(files("../libs/CoreProtect-CE-24.1.jar"))
    compileOnly("com.google.code.gson:gson:2.11.0")
}

// bundle common classes into the plugin jar (no shadow plugin needed for scaffold)
tasks.named<Jar>("jar") {
    archiveBaseName.set("letmelook-purpur")
    from(project(":letmelook-common").extensions.getByType<SourceSetContainer>()["main"].output)
}
