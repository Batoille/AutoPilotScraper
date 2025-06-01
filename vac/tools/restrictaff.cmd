@if not "%EnableCmdEcho%" == "1" echo off

setlocal

set TargetArg=%~1
set Num=%~2

if "%~2" == "" (

  echo Usage: %~n0 ^<Target^> ^<Minimum number of cables^>
  pause
  exit /b 1

)

set Target=

if /i "%TargetArg%" == "client" (

  set Target=client

) else if /i "%TargetArg%" == "kmixer" (

  set Target=KMixer

) else (

  echo Unknown target %TargetArg%
  pause
  exit /b 1

)

set ValueName=Number of cables to restrict %Target% thread affinity

call "%~dp0setregdword" * "%ValueName%" %Num% 0 256
