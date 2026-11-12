@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title DouDizhu AI - Web Server

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

echo ============================================
echo   DouDizhu AI - Web Server
echo   Keep this window OPEN while playing
echo   Phone browser / float script uses the
echo   http address printed below
echo ============================================
%PY% -X utf8 web_server.py
echo.
echo Server stopped.
pause
endlocal
