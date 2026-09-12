@echo off
setlocal EnableExtensions
chcp 936 >nul
cd /d "%~dp0"
title DouDizhu AI Assistant

set "CONDA_ROOT=D:\ProgramData\miniconda3"
set "ENV_PYTHON=%CONDA_ROOT%\envs\PEMAE\python.exe"

echo ============================================
echo   DouDizhu AI Assistant - Starting GUI
echo ============================================
echo.

if exist "%ENV_PYTHON%" (
    echo [OK] Python: %ENV_PYTHON%
    "%ENV_PYTHON%" -X utf8 ddz_gui.py
    set "EXITCODE=%ERRORLEVEL%"
    goto :finish
)

echo [WARN] PEMAE python not found. Trying conda activate ...
if exist "%CONDA_ROOT%\condabin\conda.bat" (
    call "%CONDA_ROOT%\condabin\conda.bat" activate PEMAE
) else (
    call conda activate PEMAE
)

if errorlevel 1 (
    echo [ERROR] Cannot activate conda env PEMAE
    echo Open Anaconda PowerShell Prompt and run:
    echo   conda activate PEMAE
    echo   cd /d "%~dp0"
    echo   python ddz_gui.py
    pause
    exit /b 1
)

echo [OK] Conda env activated: PEMAE
python -X utf8 ddz_gui.py
set "EXITCODE=%ERRORLEVEL%"

:finish
if not "%EXITCODE%"=="0" (
    echo.
    echo [ERROR] Program exited with code %EXITCODE%
)
echo.
pause
endlocal
