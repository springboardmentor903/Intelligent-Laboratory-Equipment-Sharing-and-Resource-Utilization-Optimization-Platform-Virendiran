@echo off
setlocal
set "MAVEN_VERSION=3.9.16"
set "MAVEN_DIR=%~dp0.mvn\apache-maven-%MAVEN_VERSION%"
set "MAVEN_ZIP=%~dp0.mvn\apache-maven-%MAVEN_VERSION%-bin.zip"

if not exist "%MAVEN_DIR%\bin\mvn.cmd" (
  echo Maven %MAVEN_VERSION% is not installed for this project. Downloading it from Apache...
  if not exist "%~dp0.mvn" mkdir "%~dp0.mvn"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Invoke-WebRequest -Uri 'https://dlcdn.apache.org/maven/maven-3/%MAVEN_VERSION%/binaries/apache-maven-%MAVEN_VERSION%-bin.zip' -OutFile '%MAVEN_ZIP%'; Expand-Archive -LiteralPath '%MAVEN_ZIP%' -DestinationPath '%~dp0.mvn' -Force"
  if errorlevel 1 (
    echo Maven download failed. Check your internet connection and try again.
    exit /b 1
  )
)

call "%MAVEN_DIR%\bin\mvn.cmd" %*
exit /b %errorlevel%
