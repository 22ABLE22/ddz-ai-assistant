@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title DouDizhu AI Assistant

rem 优先使用本机已激活的 python，其次回退到 py 启动器
set "PY="
where python >nul 2>nul && set "PY=python"
if not defined PY (
    where py >nul 2>nul && set "PY=py -3"
)
if not defined PY (
    echo [ERROR] 未找到 Python，请先安装 Python 3.9+ 并加入 PATH
    pause
    exit /b 1
)

echo Starting DouDizhu AI Assistant...
%PY% -X utf8 ddz_gui.py
echo.
echo Exited with code %ERRORLEVEL%
pause
endlocal
