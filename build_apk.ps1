# PowerShell script to build the MeshKachoUtility debug APK

$env:JAVA_HOME = "c:\Users\Jesus\Desktop\MeshKachoUtility\jdk-17\jdk-17.0.10+7"
Write-Host "Set JAVA_HOME to: $env:JAVA_HOME"

Write-Host "Running gradlew.bat assembleDebug..."
.\gradlew.bat assembleDebug
