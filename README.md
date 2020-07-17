# 🎥 scrcast

## Your drop in screen recording solution on Android

A fully, featured replacement for screen recording needs backed by Kotlin with the power of Coroutines and Android Jetpack. scrcast is:

* <b>Easy to use</b>: scrcast's API leverages Kotlin languages features for simplicity, ease of use, and little-to-no boilerplate. Simply configure and `record()`
* <b>Modern</b>: scrcast is Kotlin-first and uses modern libraries including Coroutines and Android Jetpack.

## Download

scrcast is available on `mavenCentral()`.

`implementation ("dev.bmcreations:scrcast:0.1.0")`

## Quick Start

scrcast provides a variety of configuration options for capturing, storing, and providing user interactions with your screen recordings.

```kotlin
@Parcelize
data class Options @JvmOverloads constructor(
    val video: VideoConfig = VideoConfig(),
    val storage: StorageConfig = StorageConfig(),
    val notification: NotificationConfig = NotificationConfig(),
    val moveTaskToBack: Boolean = false,
    val startDelayMs: Long = 0,
    val stopOnScreenOff: Boolean = false
): Parcelable
```

## Requirements

* AndroidX
* `minSdkVersion` 23+
* `compileSdkVersion` 28+
* Java 8+

Gradle (`.gradle`)

```kotlin
android {
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
}

tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile).all {
    kotlinOptions {
        jvmTarget = "1.8"
    }
}
```

Gradle Kotlin DSL (`.gradle.kts`)

```kotlin
android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "1.8"
    }
}
```

## License

```text
Copyright 2020 bmcreations

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

```