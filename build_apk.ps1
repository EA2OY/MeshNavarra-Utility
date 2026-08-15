# PowerShell script to build the MeshNavarra Utility debug APK.
# Uses the bundled JDK 17 at jdk-17\jdk-17.0.10+7 (download with download_jdk.ps1).

$env:JAVA_HOME = Join-Path $PSScriptRoot "jdk-17\jdk-17.0.10+7"
Write-Host "Set JAVA_HOME to: $env:JAVA_HOME"

Write-Host "Running gradlew.bat assembleDebug..."
.\gradlew.bat assembleDebug
