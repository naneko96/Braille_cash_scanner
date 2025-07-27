// Top-level build file where you can add configuration options common to all sub-projects/modules.

plugins {
    // No plugins here (or add Gradle plugins you need)
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Use the correct Android Gradle Plugin version here:
        classpath("com.android.tools.build:gradle:8.8.0")
        // Add other classpaths here if needed (e.g., Kotlin plugin)
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
