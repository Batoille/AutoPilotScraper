@if not "%EnableCmdEcho%" == "1" echo off

setlocal

set Level=%~1

if "%Level%" == "" (

  echo Usage: %~n0 {^<Debug print level ^(0..9^)^> ^| - ^(delete value^)}
  pause
  exit /b 1

)

call "%~dp0setregdword" Debug GlobalPrintLevel %Level% 0 9
