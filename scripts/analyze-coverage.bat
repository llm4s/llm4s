@echo off
REM Wrapper script for coverage analysis on Windows
REM Makes it easier to run the Python coverage analysis

setlocal enabledelayedexpansion

REM Get the directory where this script is located
set "SCRIPT_DIR=%~dp0"
set "PROJECT_ROOT=%SCRIPT_DIR%.."

REM Check if Python is available
python --version >nul 2>&1
if errorlevel 1 (
    echo Error: Python is not installed or not in PATH
    exit /b 1
)

REM Change to project root and run the analysis script
cd /d "%PROJECT_ROOT%"
python "%SCRIPT_DIR%analyze-coverage.py" %*
