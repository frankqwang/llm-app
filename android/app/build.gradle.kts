/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
  alias(libs.plugins.android.application)
  // Note: set apply to true to enable google-services (requires google-services.json).
  alias(libs.plugins.google.services) apply false
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.protobuf)
  alias(libs.plugins.hilt.application)
  alias(libs.plugins.oss.licenses)
  alias(libs.plugins.ksp)
  kotlin("kapt")
}

import java.net.URL

val bundlePerceptionModels =
  providers.gradleProperty("bundlePerceptionModels").map { it.toBoolean() }.orElse(false)
val bundleAllBgm =
  providers.gradleProperty("bundleAllBgm").map { it.toBoolean() }.orElse(false)

tasks.register("downloadPerceptionModels") {
  val modelsDir = file("src/main/assets/models")
  val models = mapOf(
    "face_landmarker.task" to "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task",
    "nsfw_vit_int8.onnx" to "https://huggingface.co/AdamCodd/vit-base-nsfw-detector/resolve/main/onnx/model_int8.onnx",
  )
  doLast {
    if (!bundlePerceptionModels.get()) {
      println("Skipping bundled perception models. Pass -PbundlePerceptionModels=true to embed them in the APK.")
      return@doLast
    }
    modelsDir.mkdirs()
    models.forEach { (name, url) ->
      val target = File(modelsDir, name)
      if (!target.exists() || target.length() < 1_000) {
        println("Downloading perception model: $name ...")
        try {
          URL(url).openStream().use { input: java.io.InputStream ->
            target.outputStream().use { output: java.io.OutputStream -> input.copyTo(output) }
          }
          println("Downloaded $name (${target.length()} bytes)")
        } catch (t: Throwable) {
          println("WARN: Failed to download $name: ${t.message}")
          println("      Place the file manually at: ${target.absolutePath}")
          // Don't fail the build — let the developer copy manually or rely on OTA at runtime.
        }
      } else {
        println("Perception model $name already exists (${target.length()} bytes), skipping")
      }
    }
  }
}

// Optional offline bundle: by default perception models are fetched/copied at
// runtime into filesDir/models, which keeps the install APK much smaller.
afterEvaluate {
  if (bundlePerceptionModels.get()) {
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
      .configureEach { dependsOn("downloadPerceptionModels") }
  }
  if (!bundleAllBgm.get()) {
    val excludedBgm = setOf(
      "cinematic.mp3",
      "cool.mp3",
      "muted.mp3",
      "vibrant.mp3",
      "vintage.mp3",
      "warm.mp3",
    )
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
      .configureEach {
        doLast {
          fileTree(layout.buildDirectory.dir("intermediates/assets")) {
            include("**/bgm/*.mp3")
          }.matching {
            include(excludedBgm.map { "**/bgm/$it" })
          }.files.forEach { it.delete() }
        }
      }
  }
}

android {
  namespace = "com.google.ai.edge.gallery"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.google.aiedge.gallery"
    minSdk = 31
    targetSdk = 35
    versionCode = 30
    versionName = "1.0.13"

    // Needed for HuggingFace auth workflows.
    // Use the scheme of the "Redirect URLs" in HuggingFace app.
    manifestPlaceholders["appAuthRedirectScheme"] =
        "REPLACE_WITH_YOUR_REDIRECT_SCHEME_IN_HUGGINGFACE_APP"
    manifestPlaceholders["applicationName"] = "com.google.ai.edge.gallery.GalleryApplication"
    manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Only ship native libs for arm64-v8a. ffmpeg-kit + MediaPipe + TFLite +
    // ONNX Runtime + LiteRT-LM each carry .so files for armeabi-v7a / arm64-v8a
    // / x86 / x86_64 — keeping all four bloats the APK from ~150 MB to ~520 MB
    // for zero benefit on modern phones (every phone Gemma 4 actually runs on
    // is 64-bit ARM; x86 is for emulators we don't target). To run in the
    // Android Studio emulator on Intel/AMD, comment this out.
    ndk {
      abiFilters += listOf("arm64-v8a")
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  kotlinOptions {
    jvmTarget = "11"
    freeCompilerArgs += "-Xcontext-receivers"
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.compose.navigation)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlin.reflect)
  implementation(libs.material.icon.extended)
  implementation(libs.androidx.work.runtime)
  implementation(libs.androidx.datastore)
  implementation(libs.com.google.code.gson)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.webkit)
  implementation(libs.litertlm)
  implementation(libs.commonmark)
  implementation(libs.richtext)
  implementation(libs.tflite)
  implementation(libs.tflite.gpu)
  implementation(libs.tflite.support)
  implementation(libs.camerax.core)
  implementation(libs.camerax.camera2)
  implementation(libs.camerax.lifecycle)
  implementation(libs.camerax.view)
  implementation(libs.openid.appauth)
  implementation(libs.androidx.splashscreen)
  implementation(libs.protobuf.javalite)
  implementation(libs.hilt.android)
  implementation(libs.hilt.navigation.compose)
  implementation(libs.play.services.oss.licenses)
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.analytics)
  implementation(libs.firebase.messaging)
  implementation(libs.androidx.exifinterface)
  implementation(libs.moshi.kotlin)
  kapt(libs.hilt.android.compiler)
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.hilt.android.testing)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
  ksp(libs.moshi.kotlin.codegen)
  implementation(libs.mlkit.genai.prompt)

  // v3 vlogpilot
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.mediapipe.tasks.vision)
  implementation(libs.onnxruntime.android)
  implementation(libs.ffmpegKit16kb)
  // Deferred: tarsosdsp (BPM) — not on Maven Central, add via JitPack in v3.1
  implementation(libs.okhttp)
}

protobuf {
  protoc { artifact = "com.google.protobuf:protoc:4.26.1" }
  generateProtoTasks { all().forEach { it.plugins { create("java") { option("lite") } } } }
}
