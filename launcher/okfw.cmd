@echo off
rem okf-cli launcher 1
rem Runs okfw (next to this file) with the sh.exe of Git for Windows, for cmd and PowerShell (./okfw). Written by
rem okf wrapper; refresh it with okf wrapper rather than editing it.
rem Docs: https://github.com/sharpbitstudio/okf-cli/blob/main/docs/launcher.md
setlocal
set "OKFW_SH="
rem OKFW_GIT stays "-" (not absolute) when git is not found: a substring of an undefined variable breaks the lines below.
set "OKFW_GIT=-"
for /f "delims=" %%i in ('git --exec-path 2^>nul') do set "OKFW_GIT=%%i"
rem Only an absolute exec path (X:... or \\server\... or //server/...) leads to Git's bin\sh.exe; a git that
rem prints another form (an MSYS path) is not Git for Windows, and sh.exe on the PATH is used instead.
set "OKFW_GIT_ABS="
if "%OKFW_GIT:~1,1%"==":" set "OKFW_GIT_ABS=1"
if "%OKFW_GIT:~0,2%"=="\\" set "OKFW_GIT_ABS=1"
if "%OKFW_GIT:~0,2%"=="//" set "OKFW_GIT_ABS=1"
if defined OKFW_GIT_ABS for %%i in ("%OKFW_GIT%\..\..\..\bin\sh.exe") do if exist "%%~fi" set "OKFW_SH=%%~fi"
if not defined OKFW_SH for %%i in (sh.exe) do set "OKFW_SH=%%~$PATH:i"
if not defined OKFW_SH (
  1>&2 echo okfw: sh.exe not found - okfw.cmd runs okfw with Git for Windows' sh ^(install Git for Windows^)
  exit /b 3
)
set "OKFW_SCRIPT=%~dp0okfw"
rem The last line runs okfw and exits with its code. It is one line, so okfw may replace this file while it runs; call
rem reads the exit code after okfw has ended (a bare exit /b would lose it under cmd /c).
"%OKFW_SH%" "%OKFW_SCRIPT:\=/%" %* & call exit /b %%errorlevel%%
