@echo off
rem Baut GuevenPlayer mit dem Java und Android SDK von Android Studio.
rem Das komplette Protokoll landet in build-log.txt.
setlocal
cd /d "%~dp0"
set "LOG=%~dp0build-log.txt"
set "RELEASE_OK="
title GuevenPlayer - APK wird gebaut

rem Java aus Android Studio verwenden (JDK 21, passt zum Android Gradle Plugin).
set "JBR="
if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JBR=%ProgramFiles%\Android\Android Studio\jbr"
if not defined JBR if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe" set "JBR=%LOCALAPPDATA%\Programs\Android Studio\jbr"
if defined JBR set "JAVA_HOME=%JBR%"

rem Android SDK: Standardort von Android Studio.
if not defined ANDROID_HOME set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
rem local.properties wird hier bewusst nicht geschrieben: cmd wuerde Umlaute im Pfad falsch kodieren.

> "%LOG%" echo Build gestartet: %date% %time%
>>"%LOG%" echo JAVA_HOME=%JAVA_HOME%
>>"%LOG%" echo ANDROID_HOME=%ANDROID_HOME%
if exist "%ANDROID_HOME%\platforms" (
  >>"%LOG%" echo SDK-Plattformen:
  dir /b "%ANDROID_HOME%\platforms" >>"%LOG%" 2>&1
) else (
  >>"%LOG%" echo WARNUNG: Android SDK nicht gefunden
)

echo.
echo  GuevenPlayer wird gebaut. Das dauert beim ersten Mal einige Minuten,
echo  weil Gradle und alle Bibliotheken heruntergeladen werden.
echo  Dieses Fenster schliesst sich am Ende von selbst.
echo.

del /q "GuevenPlayer.apk" "GuevenPlayer-debug.apk" 2>nul
>>"%LOG%" echo ===== SCHRITT assembleDebug =====
call gradlew.bat assembleDebug --console=plain >>"%LOG%" 2>&1
if errorlevel 1 (
  >>"%LOG%" echo ===== ERGEBNIS assembleDebug FEHLER =====
  goto ende
)
>>"%LOG%" echo ===== ERGEBNIS assembleDebug OK =====

>>"%LOG%" echo ===== SCHRITT testDebugUnitTest =====
call gradlew.bat testDebugUnitTest --console=plain >>"%LOG%" 2>&1
if errorlevel 1 (
  >>"%LOG%" echo ===== ERGEBNIS testDebugUnitTest FEHLER =====
) else (
  >>"%LOG%" echo ===== ERGEBNIS testDebugUnitTest OK =====
)

>>"%LOG%" echo ===== SCHRITT assembleRelease =====
call gradlew.bat assembleRelease --console=plain >>"%LOG%" 2>&1
if errorlevel 1 (
  >>"%LOG%" echo ===== ERGEBNIS assembleRelease FEHLER =====
) else (
  >>"%LOG%" echo ===== ERGEBNIS assembleRelease OK =====
  set "RELEASE_OK=1"
)

rem Fertige APKs gut auffindbar direkt in den Projektordner legen (nur frisch gebaute).
if exist "app\build\outputs\apk\debug\GuevenPlayer-debug.apk" copy /Y "app\build\outputs\apk\debug\GuevenPlayer-debug.apk" "GuevenPlayer-debug.apk" >nul
if "%RELEASE_OK%"=="1" copy /Y "app\build\outputs\apk\release\GuevenPlayer-release.apk" "GuevenPlayer.apk" >nul
if exist "GuevenPlayer.apk" >>"%LOG%" echo APK: %~dp0GuevenPlayer.apk

:ende
>>"%LOG%" echo ===== FERTIG: %date% %time% =====
endlocal
