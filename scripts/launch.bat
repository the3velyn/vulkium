@echo off
REM ---------------------------------------------------------------------------
REM vulkium Windows launcher
REM
REM Usage:
REM   scripts\launch.bat              - plain run, live console output
REM   scripts\launch.bat --validation - enable Vulkan validation layers
REM   scripts\launch.bat --sync       - enable validation + sync validation
REM                                     (catches WAR/RAW hazards, barrier bugs)
REM   scripts\launch.bat --gpu-assist - enable validation + GPU-assisted
REM                                     validation (slower, finds OOB + BDA bugs)
REM   scripts\launch.bat --log        - save output to run\logs\launch-*.log
REM                                     instead of streaming to console
REM                                     (combine with any of the above, e.g.
REM                                      scripts\launch.bat --sync --log)
REM   scripts\launch.bat --renderdoc  - print RenderDoc attach instructions
REM                                     and exit; does not launch the client
REM
REM JAVA_HOME must point at a JDK 21+ install:
REM   set "JAVA_HOME=D:\javas\jdk-25.0.1"
REM ---------------------------------------------------------------------------

setlocal EnableDelayedExpansion

REM --- resolve repo root -----------------------------------------------------
pushd "%~dp0.." >nul
set "REPO=%CD%"

REM --- parse args ------------------------------------------------------------
set "MODE=plain"
set "LOG_TO_FILE=0"
:arg_loop
if "%~1"=="" goto :arg_done
if /I "%~1"=="--validation" set "MODE=validation"
if /I "%~1"=="--sync"       set "MODE=sync"
if /I "%~1"=="--gpu-assist" set "MODE=gpu-assist"
if /I "%~1"=="--renderdoc"  set "MODE=renderdoc"
if /I "%~1"=="--log"        set "LOG_TO_FILE=1"
shift
goto :arg_loop
:arg_done

REM --- RenderDoc walkthrough -------------------------------------------------
if "%MODE%"=="renderdoc" (
    echo.
    echo RenderDoc capture walkthrough:
    echo   1. Install RenderDoc ^(https://renderdoc.org/^), v1.32 or newer
    echo      recommended for Vulkan 1.4 support.
    echo   2. Start the game via this bat ^(plain mode^).
    echo   3. Open RenderDoc, File ^> Inject into Process. Filter by "javaw"
    echo      and attach to the running MC process.
    echo   4. Press F12 in the MC window to capture a frame.
    echo   5. Close the client normally; the capture appears in the Captures tab.
    echo.
    echo Not launching. Re-run without --renderdoc to start the game.
    popd >nul
    exit /b 0
)

REM --- ensure JAVA_HOME ------------------------------------------------------
if not defined JAVA_HOME (
    echo [launch] ERROR: JAVA_HOME not set. Point it at a JDK 21+ install.
    echo          Example: set "JAVA_HOME=D:\javas\jdk-25.0.1"
    popd >nul
    exit /b 1
)
if not exist "%JAVA_HOME%\bin\javaw.exe" (
    echo [launch] ERROR: %%JAVA_HOME%%\bin\javaw.exe not found ^("%JAVA_HOME%"^).
    popd >nul
    exit /b 1
)

REM --- Vulkan validation wiring ----------------------------------------------
if "%MODE%"=="validation" (
    set "VK_INSTANCE_LAYERS=VK_LAYER_KHRONOS_validation"
    set "VK_LOADER_LAYERS_ENABLE=VK_LAYER_KHRONOS_validation"
    echo [launch] Vulkan validation layer ENABLED ^(core^).
)
if "%MODE%"=="sync" (
    set "VK_INSTANCE_LAYERS=VK_LAYER_KHRONOS_validation"
    set "VK_LOADER_LAYERS_ENABLE=VK_LAYER_KHRONOS_validation"
    set "VK_LAYER_ENABLES=VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT"
    echo [launch] Vulkan validation + synchronization validation ENABLED.
    echo          Surfaces WAR/RAW hazards, barrier mismatches, layout errors.
)
if "%MODE%"=="gpu-assist" (
    set "VK_INSTANCE_LAYERS=VK_LAYER_KHRONOS_validation"
    set "VK_LOADER_LAYERS_ENABLE=VK_LAYER_KHRONOS_validation"
    set "VK_LAYER_ENABLES=VK_VALIDATION_FEATURE_ENABLE_GPU_ASSISTED_EXT,VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT"
    echo [launch] Vulkan validation + GPU-assisted + sync validation ENABLED.
    echo          Slowest mode; catches OOB BDA reads and bad descriptor indices
    echo          in addition to the sync checks.
)

REM --- print status ----------------------------------------------------------
echo [launch] repo=%REPO%
echo [launch] mode=%MODE%
echo [launch] JAVA_HOME=%JAVA_HOME%

REM --- run -------------------------------------------------------------------
REM Two modes: stream to console OR redirect to log file. No tee in cmd.exe,
REM and the PowerShell tee dance turned out flaky under `^` continuation. If
REM you need both live output and a saved log, open a second terminal after
REM launch and run:  powershell -NoProfile -Command "Get-Content -Wait <path>"
if "%LOG_TO_FILE%"=="1" (
    set "LOG_DIR=%REPO%\run\logs"
    if not exist "!LOG_DIR!" mkdir "!LOG_DIR!"
    for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "TS=%%i"
    set "OUT_LOG=!LOG_DIR!\launch-!TS!.log"
    echo [launch] logging to: !OUT_LOG!
    call "%REPO%\gradlew.bat" runClient --no-daemon > "!OUT_LOG!" 2>&1
    echo [launch] exit code %ERRORLEVEL%
    echo [launch] log saved to: !OUT_LOG!
) else (
    echo [launch] streaming to console; pass --log to save to file.
    echo.
    call "%REPO%\gradlew.bat" runClient --no-daemon
    echo [launch] exit code %ERRORLEVEL%
)

popd >nul
endlocal
