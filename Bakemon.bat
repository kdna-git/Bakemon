@echo off
rem ===========================================================================
rem  Bakemon build script
rem
rem    Bakemon.bat              Build build\jar\Bakemon.jar and build\Bakemon\Bakemon.exe
rem    Bakemon.bat jar          Build only the jar
rem    Bakemon.bat run [args]   Build the jar if missing, then run it (args are forwarded)
rem    Bakemon.bat test         Compile and run the self tests in Tests\
rem    Bakemon.bat tools        Only download the Kotlin compiler
rem    Bakemon.bat clean        Delete the build folder
rem
rem  Needs a JDK 17+ (java + jpackage) on PATH or in JAVA_HOME.
rem  The Kotlin compiler is downloaded once into .tools\ (no Gradle needed).
rem ===========================================================================
setlocal EnableExtensions

set "KOTLIN_VERSION=2.4.20"
set "ROOT=%~dp0"
set "ROOT=%ROOT:~0,-1%"
set "TOOLS=%ROOT%\.tools"
set "KOTLIN_HOME=%TOOLS%\kotlin-%KOTLIN_VERSION%"
set "KOTLINC=%KOTLIN_HOME%\kotlinc\bin\kotlinc.bat"
set "BUILD=%ROOT%\build"
set "JAR_DIR=%BUILD%\jar"
set "JAR=%JAR_DIR%\Bakemon.jar"
set "TEST_JAR=%BUILD%\test\BakemonTests.jar"

rem ---- locate java / jpackage ------------------------------------------------
set "JAVA=java"
set "JPACKAGE=jpackage"
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
    if exist "%JAVA_HOME%\bin\jpackage.exe" set "JPACKAGE=%JAVA_HOME%\bin\jpackage.exe"
)
"%JAVA%" -version >nul 2>&1
if errorlevel 1 (
    echo [Bakemon] Java was not found. Install a JDK 17+ and add it to PATH or set JAVA_HOME.
    exit /b 1
)

set "CMD=%~1"
if "%CMD%"=="" set "CMD=build"

if /i "%CMD%"=="clean" goto :clean
if /i "%CMD%"=="tools" goto :tools_only
if /i "%CMD%"=="jar"   goto :jar_only
if /i "%CMD%"=="run"   goto :run
if /i "%CMD%"=="test"  goto :test
if /i "%CMD%"=="build" goto :build
echo [Bakemon] Unknown command "%CMD%". Use: build ^| jar ^| run ^| test ^| tools ^| clean
exit /b 1

rem ---------------------------------------------------------------------------
:clean
if exist "%BUILD%" rmdir /s /q "%BUILD%"
echo [Bakemon] Build folder removed.
exit /b 0

:tools_only
call :ensure_kotlinc || exit /b 1
exit /b 0

:jar_only
call :ensure_kotlinc || exit /b 1
call :compile_jar || exit /b 1
exit /b 0

:build
call :ensure_kotlinc || exit /b 1
call :compile_jar || exit /b 1
call :package_exe || exit /b 1
echo.
echo [Bakemon] Done.
echo           Jar: %JAR%
echo           Exe: %BUILD%\Bakemon\Bakemon.exe
exit /b 0

:run
if not exist "%JAR%" (
    call :ensure_kotlinc || exit /b 1
    call :compile_jar || exit /b 1
)
rem Forward the rest of the command line exactly as typed (quotes, commas, '=' and empty "" arguments included):
rem take all arguments, then cut everything up to and including the leading "run".
rem (Rebuilding it from %%1 after each shift would split "A,B" in two and stop at the first empty argument.)
set ARGS=%*
set ARGS=%ARGS:*run=%
"%JAVA%" -cp "%JAR%" MainKt %ARGS%
exit /b %errorlevel%

:test
call :ensure_kotlinc || exit /b 1
if not exist "%ROOT%\Tests" (
    echo [Bakemon] No Tests folder found.
    exit /b 1
)
echo [Bakemon] Compiling self tests...
if not exist "%BUILD%\test" mkdir "%BUILD%\test"
call "%KOTLINC%" "%ROOT%\Main.kt" "%ROOT%\Source" "%ROOT%\Tests" -jvm-target 17 -include-runtime -d "%TEST_JAR%"
if errorlevel 1 (
    echo [Bakemon] Test compilation failed.
    exit /b 1
)
"%JAVA%" -cp "%TEST_JAR%" SelfTestKt "%ROOT%"
exit /b %errorlevel%

rem ---------------------------------------------------------------------------
:ensure_kotlinc
if exist "%KOTLINC%" exit /b 0
echo [Bakemon] Downloading Kotlin compiler %KOTLIN_VERSION% (first build only)...
if not exist "%TOOLS%" mkdir "%TOOLS%"
set "ZIP=%TOOLS%\kotlin-compiler-%KOTLIN_VERSION%.zip"
curl -L --fail --silent --show-error -o "%ZIP%" "https://github.com/JetBrains/kotlin/releases/download/v%KOTLIN_VERSION%/kotlin-compiler-%KOTLIN_VERSION%.zip"
if errorlevel 1 (
    echo [Bakemon] Download failed. Check your internet connection.
    if exist "%ZIP%" del /q "%ZIP%"
    exit /b 1
)
if not exist "%KOTLIN_HOME%" mkdir "%KOTLIN_HOME%"
tar -xf "%ZIP%" -C "%KOTLIN_HOME%"
if errorlevel 1 (
    echo [Bakemon] tar failed, trying PowerShell Expand-Archive...
    powershell -NoProfile -Command "Expand-Archive -Force -LiteralPath '%ZIP%' -DestinationPath '%KOTLIN_HOME%'"
)
del /q "%ZIP%"
if not exist "%KOTLINC%" (
    echo [Bakemon] Could not extract the Kotlin compiler.
    exit /b 1
)
echo [Bakemon] Kotlin compiler installed in %KOTLIN_HOME%
exit /b 0

:compile_jar
echo [Bakemon] Compiling...
if not exist "%JAR_DIR%" mkdir "%JAR_DIR%"
call "%KOTLINC%" "%ROOT%\Main.kt" "%ROOT%\Source" -jvm-target 17 -include-runtime -d "%JAR%"
if errorlevel 1 (
    echo [Bakemon] Compilation failed.
    exit /b 1
)
echo [Bakemon] Built %JAR%
exit /b 0

:package_exe
echo [Bakemon] Packaging Bakemon.exe with jpackage...
if exist "%BUILD%\Bakemon" rmdir /s /q "%BUILD%\Bakemon"
"%JPACKAGE%" --type app-image --name Bakemon --input "%JAR_DIR%" --main-jar Bakemon.jar --main-class MainKt --dest "%BUILD%" --win-console --add-modules java.base
if errorlevel 1 (
    echo [Bakemon] jpackage failed. The jar still works: Bakemon.bat run
    exit /b 1
)
exit /b 0
