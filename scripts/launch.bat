@echo off
REM ---------------------------------------------------------------------------
REM vulkium Windows launcher
REM
REM Usage:
REM   scripts\launch.bat              - plain run, same as `gradlew runClient`
REM   scripts\launch.bat --validation - enable Vulkan validation layers
REM   scripts\launch.bat --sync       - enable validation + sync validation
REM                                     (catches WAR/RAW hazards, barrier bugs)
REM   scripts\launch.bat --gpu-assist - enable validation + GPU-assisted
REM                                     validation (slower, finds OOB + BDA bugs)
REM   scripts\launch.bat --renderdoc  - print RenderDoc attach instructions
REM                                     and exit; does not launch the client
REM
REM Logs go to run\logs\launch-YYYYMMDD-HHMMSS.log alongside MC's own logs.
REM JAVA_HOME must be set to a JDK 21+ install, or pass it inline:
REM   set "JAVA_HOME=D:\javas\jdk-25.0.1" ^&^& scripts\launch.bat --sync
REM ---------------------------------------------------------------------------

setlocal EnableDelayedExpansion

REM --- resolve repo root ------------------------------------------------------
pushd "%~dp0.." >nul
set "REPO=%CD%"

REM --- pick a mode -----------------------------------------------------------
set "MODE=plain"
if /I "%~1"=="--validation" set "MODE=validation"
if /I "%~1"=="--sync"       set "MODE=sync"
if /I "%~1"=="--gpu-assist" set "MODE=gpu-assist"
if /I "%~1"=="--renderdoc"  set "MODE=renderdoc"

REM --- RenderDoc instructions and exit ---------------------------------------
if "%MODE%"=="renderdoc" (
    echo.
    echo RenderDoc attach / capture walkthrough:
    echo   1. Install RenderDoc ^(https://renderdoc.org/^), v1.32 or newer recommended
    echo      for Vulkan 1.4 support.
    echo   2. Launch Application tab:
    echo        Executable Path: %JAVA_HOME%\bin\javaw.exe
    echo        Working Directory: %REPO%\run
    echo        Command-line Arguments: leave empty and instead configure a
    echo        gradlew runClient first ^(this bat file does that for you^),
    echo        then use RenderDoc's `Inject into process` against javaw.exe
    echo        AFTER the client window appears.
    echo   3. In the client, press F12 when you want a capture ^(default RenderDoc
    echo      key^).
    echo   4. Close the client normally. Capture appears under the Captures tab.
    echo   5. In the capture, the vulkium cmd buffers appear as unnamed regions
    echo      unless you've landed the debug-utility-labels commit ^(recommended
    echo      for readable captures^).
    echo.
    echo Not launching client. Re-run without --renderdoc to start the game.
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
if "%MODE%"=="validation" goto :enable_core_validation
if "%MODE%"=="sync"       goto :enable_sync_validation
if "%MODE%"=="gpu-assist" goto :enable_gpu_assist
goto :validation_done

:enable_core_validation
set "VK_INSTANCE_LAYERS=VK_LAYER_KHRONOS_validation"
set "VK_LOADER_LAYERS_ENABLE=VK_LAYER_KHRONOS_validation"
echo [launch] Vulkan validation layer ENABLED ^(core^).
goto :validation_done

:enable_sync_validation
set "VK_INSTANCE_LAYERS=VK_LAYER_KHRONOS_validation"
set "VK_LOADER_LAYERS_ENABLE=VK_LAYER_KHRONOS_validation"
set "VK_LAYER_ENABLES=VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT"
echo [launch] Vulkan validation + synchronization validation ENABLED.
echo          Surfaces WAR/RAW hazards, barrier mismatches, layout errors.
goto :validation_done

:enable_gpu_assist
set "VK_INSTANCE_LAYERS=VK_LAYER_KHRONOS_validation"
set "VK_LOADER_LAYERS_ENABLE=VK_LAYER_KHRONOS_validation"
set "VK_LAYER_ENABLES=VK_VALIDATION_FEATURE_ENABLE_GPU_ASSISTED_EXT,VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT"
echo [launch] Vulkan validation + GPU-assisted + sync validation ENABLED.
echo          Slowest mode; catches out-of-bounds BDA reads and bad descriptor
echo          indices in addition to the sync checks above.
goto :validation_done

:validation_done

REM --- output log path -------------------------------------------------------
set "LOG_DIR=%REPO%\run\logs"
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"
REM YYYYMMDD-HHMMSS timestamp, locale-independent via wmic
for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "TS=%%i"
set "OUT_LOG=%LOG_DIR%\launch-%TS%.log"

echo [launch] repo=%REPO%
echo [launch] mode=%MODE%
echo [launch] JAVA_HOME=%JAVA_HOME%
echo [launch] output log=%OUT_LOG%
echo.

REM --- run ------------------------------------------------------------------
REM Use PowerShell's Tee-Object so we get both live console output AND a saved
REM log file. cmd.exe doesn't have a native `tee`. If PowerShell isn't on PATH
REM we'd have to fall back to a plain `>` redirect (output only to file).
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "& { & '%REPO%\gradlew.bat' runClient --no-daemon 2>&1 | Tee-Object -FilePath '%OUT_LOG%' }"
if errorlevel 1 (
    echo [launch] runClient exited with code %ERRORLEVEL%.
)
echo [launch] log saved to: %OUT_LOG%

popd >nul
endlocal
