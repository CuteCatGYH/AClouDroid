@echo off
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"

if not exist "%VSWHERE%" (
    echo Error: vswhere.exe not found at "%VSWHERE%".
    echo Please install Visual Studio with the Desktop development with C++ workload.
    exit /b 1
)

set "VSINSTALL="
for /f "usebackq delims=" %%i in (`""!VSWHERE!" -latest -prerelease -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath"`) do set "VSINSTALL=%%i"
if defined VSINSTALL goto :found_vs
for /f "usebackq delims=" %%i in (`""!VSWHERE!" -latest -prerelease -products * -property installationPath"`) do set "VSINSTALL=%%i"

:found_vs

if not defined VSINSTALL (
    echo Error: Visual Studio C++ x64 build tools not found.
    exit /b 1
)

set "VCVARS=%VSINSTALL%\VC\Auxiliary\Build\vcvars64.bat"
if not exist "%VCVARS%" (
    echo Error: vcvars64.bat not found at "%VCVARS%".
    exit /b 1
)

call "%VCVARS%" >nul
if errorlevel 1 (
    echo Error: Failed to initialize MSVC x64 environment.
    exit /b 1
)

where cl.exe >nul 2>&1
if errorlevel 1 (
    rem Minimal Build Tools installs may omit vsdevcmd\ext\vcvars.bat; add VC\Tools\MSVC manually.
    set "VCVER="
    if exist "%VSINSTALL%\VC\Auxiliary\Build\Microsoft.VCToolsVersion.default.txt" (
        set /p VCVER=<"%VSINSTALL%\VC\Auxiliary\Build\Microsoft.VCToolsVersion.default.txt"
    )
    if not defined VCVER (
        for /f "delims=" %%d in ('dir /b /ad /o-n "%VSINSTALL%\VC\Tools\MSVC" 2^>nul') do (
            if not defined VCVER set "VCVER=%%d"
        )
    )
    if defined VCVER (
        set "MSVC_DIR=%VSINSTALL%\VC\Tools\MSVC\!VCVER!"
        if exist "!MSVC_DIR!\bin\Hostx64\x64\cl.exe" (
            set "PATH=!MSVC_DIR!\bin\Hostx64\x64;!PATH!"
            set "INCLUDE=!MSVC_DIR!\include;!INCLUDE!"
            set "LIB=!MSVC_DIR!\lib\x64;!LIB!"
        )
    )
)

where cl.exe >nul 2>&1
if errorlevel 1 (
    echo Error: cl.exe not found after initializing MSVC environment.
    exit /b 1
)

pushd "%SCRIPT_DIR%"
if not exist "obj" mkdir "obj"

echo Building anisette-server.exe (x64, C++17)...
cl.exe /nologo /std:c++17 /O2 /EHsc /W4 /utf-8 /DUNICODE /D_UNICODE ^
    /Foobj\ ^
    /Fe:anisette-server.exe ^
    src\main.cpp src\config.cpp src\anisette.cpp src\http_server.cpp src\tls.cpp ^
    ws2_32.lib iphlpapi.lib bcrypt.lib advapi32.lib shell32.lib ole32.lib secur32.lib crypt32.lib version.lib

set "RC=%ERRORLEVEL%"
popd

if not "%RC%"=="0" (
    echo Build failed with exit code %RC%.
    exit /b %RC%
)

echo Build succeeded: %SCRIPT_DIR%anisette-server.exe
exit /b 0
