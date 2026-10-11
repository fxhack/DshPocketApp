@echo off
setlocal
rem DshPocket one-shot build. Uses the Gradle daemon so the second run is incremental.
rem Usage:
rem   build-release.bat            -> build only
rem   build-release.bat --install  -> build + adb install -r
pushd "%~dp0"

set JAVA_HOME=D:\dev\jdk-17
set ANDROID_HOME=D:\dev\android-sdk
set ANDROID_SDK_ROOT=D:\dev\android-sdk

echo [1/2] gradle :app:assembleRelease
call gradlew.bat --console=plain :app:assembleRelease
if errorlevel 1 (
  echo BUILD FAILED
  popd
  exit /b 1
)

set APK=app\build\outputs\apk\release\app-release.apk
echo [2/2] APK: %APK%

if /i "%1"=="--install" (
  echo installing to device...
  adb install -r "%APK%"
  if errorlevel 1 (
    echo INSTALL FAILED
    popd
    exit /b 1
  )
)

echo DONE
popd
endlocal
