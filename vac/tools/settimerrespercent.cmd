@if not "%EnableCmdEcho%" == "1" echo off

setlocal

set Val=%~1

if "%1" == "" (

  echo Usage: %~n0 ^<Timer resolution percent of event period^>
  pause
  exit /b 1

)

call "%~dp0setregdword" * "Timer resolution in percents of event period" %Val% 10 100
