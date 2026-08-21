plugins {
    application
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
}

group = "nl.roosterandroid"
version = "0.10.0"

kotlin {
    jvmToolchain(17)
}

sourceSets {
    main {
        kotlin.srcDir("../app/src/main/java")
        kotlin.exclude(
            "**/MainActivity.kt",
            "**/AdminScreen.kt",
            "**/ScheduleStorage.kt",
            "**/RosterPdfExporter.kt"
        )
    }
    test {
        kotlin.srcDir("../app/src/test/java")
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.formdev:flatlaf:3.5.4")
    implementation("com.github.librepdf:openpdf:2.0.3")

    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("nl.roosterandroid.desktop.DesktopMainKt")
    applicationName = "RoosterPlanner"
}

tasks.jar {
    archiveBaseName.set("roosterplanner-windows")
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
        attributes["Implementation-Title"] = "RoosterPlanner"
        attributes["Implementation-Version"] = project.version.toString()
    }
}

tasks.test {
    useJUnit()
}
