// Top-level build file. All version catalog entries live in gradle/libs.versions.toml.
plugins {
  alias(libs.plugins.android.application) apply false
  // AGP 9.x includes built-in Kotlin; org.jetbrains.kotlin.android is not applied.
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
  alias(libs.plugins.roborazzi) apply false
  alias(libs.plugins.secrets) apply false
  alias(libs.plugins.google.services) apply false
}
