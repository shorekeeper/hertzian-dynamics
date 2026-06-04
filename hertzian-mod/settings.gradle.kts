// GTNH-style settings convention. The plugin resolves itself from
// the GTNH plugin portal and stamps the entire repository graph
// (Forge, MCP, GTNH Nexus, mavenCentral) into the build before
// any project-level script runs.
//
// Switching from a vanilla RFG settings.gradle.kts to this form is
// the single hardest cut in the migration: every other change in
// build.gradle.kts and gradle.properties flows from the convention
// applied here.

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            name = "GTNH Maven"
            url = uri("https://nexus.gtnewhorizons.com/repository/public/")
        }
    }
}

plugins {
    // GTNH settings convention. Current as of late 2025.
    id("com.gtnewhorizons.gtnhsettingsconvention") version "1.0.51"
}

rootProject.name = "hertzian-mod"