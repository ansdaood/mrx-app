@echo off
setlocal
set "APP_HOME=%~dp0"
cd /d "%APP_HOME%"
if not exist "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" if exist "%APP_HOME%tools\ensure-gradle-wrapper.ps1" (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%APP_HOME%tools\ensure-gradle-wrapper.ps1"
)
if exist "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" (
  where java >nul 2>&1
  if errorlevel 1 (
    echo Java is required to run Gradle Wrapper.
    exit /b 1
  )
  java -jar "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" %*
  exit /b %errorlevel%
)
if exist "%APP_HOME%.tools\gradle-9.7.1\bin\gradle.bat" (
  call "%APP_HOME%.tools\gradle-9.7.1\bin\gradle.bat" --project-dir "%APP_HOME%" %*
  exit /b %errorlevel%
)
where gradle >nul 2>&1
if not errorlevel 1 (
  gradle --project-dir "%APP_HOME%" %*
  exit /b %errorlevel%
)
echo Gradle Wrapper JAR is unavailable. With network access run tools\fetch-gradle-wrapper.ps1; otherwise install Gradle 9.7.1 or run tools\bootstrap-windows.ps1.
exit /b 127
