@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem Build the WAR, copy to WildFly deployments, then start WildFly.
set "PAUSE_ON_ERROR=1"
set "PAUSE_ON_END=1"
if /I "%~1"=="--no-pause" (
  set "PAUSE_ON_ERROR=0"
  set "PAUSE_ON_END=0"
)

set "PROJECT_DIR=%~dp0"
pushd "%PROJECT_DIR%" >nul

rem WildFly home (override by setting WILDFLY_HOME env var)
if "%WILDFLY_HOME%"=="" set "WILDFLY_HOME=C:\wildfly-27.0.1.Final"
set "DEPLOY_DIR=%WILDFLY_HOME%\standalone\deployments"
set "STANDALONE_BAT=%WILDFLY_HOME%\bin\standalone.bat"
set "JBOSS_CLI=%WILDFLY_HOME%\bin\jboss-cli.bat"
set "APP_URL=http://127.0.0.1:8080/jsf-primefaces-app-1.0/login.xhtml"
set "WAIT_SECONDS=90"

echo [1/3] Building WAR...
set "BUILD_ATTEMPT=1"
:build_try
echo Build attempt !BUILD_ATTEMPT!/2...
if exist "%PROJECT_DIR%mvnw.cmd" (
  call "%PROJECT_DIR%mvnw.cmd" -q clean package
) else (
  call mvn -q clean package
)
if not errorlevel 1 goto :build_ok
if "!BUILD_ATTEMPT!"=="2" goto :build_fail
echo Build failed on attempt !BUILD_ATTEMPT!, retrying...
set /a BUILD_ATTEMPT+=1
timeout /t 2 >nul
goto :build_try

:build_ok

:build_fail
if errorlevel 1 (
  echo Build failed.
  goto :fail1
)

echo [2/3] Copying WAR to WildFly deployments...
if not exist "%DEPLOY_DIR%\" (
  echo WildFly deployments folder not found: "%DEPLOY_DIR%"
  echo Set WILDFLY_HOME or install WildFly at the default path.
  goto :fail2
)

set "WAR="
for /f "delims=" %%F in ('dir /b /a:-d /o:-d "%PROJECT_DIR%target\*.war" 2^>nul') do (
  set "WAR=%PROJECT_DIR%target\%%F"
  goto :foundWar
)

:foundWar
if "%WAR%"=="" (
  echo No WAR found in "%PROJECT_DIR%target".
  goto :fail3
)

for %%A in ("%WAR%") do set "WAR_NAME=%%~nxA"

del /q "%DEPLOY_DIR%\%WAR_NAME%.deployed" 2>nul
del /q "%DEPLOY_DIR%\%WAR_NAME%.failed" 2>nul
del /q "%DEPLOY_DIR%\%WAR_NAME%.isdeploying" 2>nul
del /q "%DEPLOY_DIR%\%WAR_NAME%.isundeploying" 2>nul
del /q "%DEPLOY_DIR%\%WAR_NAME%.pending" 2>nul

copy /y "%WAR%" "%DEPLOY_DIR%\" >nul
if errorlevel 1 (
  echo Copy failed.
  goto :fail4
)
echo Deployed: "%DEPLOY_DIR%\%WAR_NAME%"

echo [3/3] Starting WildFly...
if not exist "%STANDALONE_BAT%" (
  echo WildFly standalone not found: "%STANDALONE_BAT%"
  goto :fail5
)

call :is_wildfly_running
if not errorlevel 1 (
  echo WildFly deja detecte sur le port 9990. Arret en cours...
  call :stop_wildfly
  if errorlevel 1 goto :fail8
  call :wait_for_port_free 9990 45
  if errorlevel 1 goto :fail9
)

start "WildFly" "%STANDALONE_BAT%"
call :wait_for_deployment "%WAR_NAME%" %WAIT_SECONDS%
if errorlevel 1 goto :fail6
call :wait_for_url %WAIT_SECONDS%
if errorlevel 1 goto :fail7
if "%PAUSE_ON_END%"=="1" (
  echo.
  echo Script termine. Appuyez sur une touche pour fermer...
  pause >nul
)
popd >nul
exit /b 0

:fail1
if "%PAUSE_ON_ERROR%"=="1" (
  echo.
  echo Appuyez sur une touche pour fermer...
  pause >nul
)
popd >nul
exit /b 1

:fail2
if "%PAUSE_ON_ERROR%"=="1" (
  echo.
  echo Appuyez sur une touche pour fermer...
  pause >nul
)
popd >nul
exit /b 2

:fail3
if "%PAUSE_ON_ERROR%"=="1" (
  echo.
  echo Appuyez sur une touche pour fermer...
  pause >nul
)
popd >nul
exit /b 3

:fail4
if "%PAUSE_ON_ERROR%"=="1" (
  echo.
  echo Appuyez sur une touche pour fermer...
  pause >nul
)
popd >nul
exit /b 4

:fail5
if "%PAUSE_ON_ERROR%"=="1" (
  echo.
  echo Appuyez sur une touche pour fermer...
  pause >nul
)
popd >nul
exit /b 5

:fail6
if "%PAUSE_ON_ERROR%"=="1" (
  echo.
  echo Appuyez sur une touche pour fermer...
  pause >nul
)
popd >nul
exit /b 6

:fail7
if "%PAUSE_ON_ERROR%"=="1" (
  echo.
  echo L'application n'est pas accessible sur:
  echo %APP_URL%
  echo Appuyez sur une touche pour fermer...
  pause >nul
)
popd >nul
exit /b 7

:fail8
if "%PAUSE_ON_ERROR%"=="1" (
  echo.
  echo Impossible d'arreter proprement WildFly.
  echo Appuyez sur une touche pour fermer...
  pause >nul
)
popd >nul
exit /b 8

:fail9
if "%PAUSE_ON_ERROR%"=="1" (
  echo.
  echo Le port 9990 est toujours occupe apres l'arret de WildFly.
  echo Appuyez sur une touche pour fermer...
  pause >nul
)
popd >nul
exit /b 9

:health_check
powershell -NoProfile -Command "try { $u='%APP_URL%'; $r = Invoke-WebRequest -Uri $u -UseBasicParsing -TimeoutSec 15; if ($r.StatusCode -ge 200 -and $r.StatusCode -lt 400) { Write-Host '[OK] URL:' $u; exit 0 } else { Write-Host '[ERR] HTTP:' $r.StatusCode; exit 1 } } catch { Write-Host '[ERR] URL inaccessible:' $u; Write-Host $_.Exception.Message; exit 1 }"
exit /b %errorlevel%

:is_wildfly_running
netstat -ano | findstr /R /C:":9990 .*LISTENING" >nul
exit /b %errorlevel%

:stop_wildfly
if exist "%JBOSS_CLI%" (
  call "%JBOSS_CLI%" --connect command=:shutdown >nul 2>nul
  if not errorlevel 1 exit /b 0
)
powershell -NoProfile -Command "$c = Get-NetTCPConnection -LocalPort 9990 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1; if ($null -eq $c) { exit 0 }; try { Stop-Process -Id $c.OwningProcess -Force -ErrorAction Stop; exit 0 } catch { exit 1 }"
exit /b %errorlevel%

:wait_for_port_free
set "WAIT_PORT=%~1"
set "WAIT_LIMIT=%~2"
set /a WAIT_COUNT=0
:wait_for_port_free_loop
powershell -NoProfile -Command "if (Get-NetTCPConnection -LocalPort %WAIT_PORT% -State Listen -ErrorAction SilentlyContinue) { exit 1 } else { exit 0 }" >nul
if not errorlevel 1 exit /b 0
if "%WAIT_COUNT%"=="%WAIT_LIMIT%" exit /b 1
set /a WAIT_COUNT+=1
timeout /t 1 >nul
goto :wait_for_port_free_loop

:wait_for_deployment
set "WAIT_WAR=%~1"
set "WAIT_LIMIT=%~2"
set /a WAIT_COUNT=0
:wait_for_deployment_loop
if exist "%DEPLOY_DIR%\%WAIT_WAR%.deployed" (
  echo Deployment termine: "%DEPLOY_DIR%\%WAIT_WAR%.deployed"
  exit /b 0
)
if exist "%DEPLOY_DIR%\%WAIT_WAR%.isdeploying" (
  if "%WAIT_COUNT%"=="%WAIT_LIMIT%" (
    echo Deployment timeout apres %WAIT_LIMIT% secondes.
    exit /b 1
  )
  set /a WAIT_COUNT+=1
  timeout /t 1 >nul
  goto :wait_for_deployment_loop
)
if exist "%DEPLOY_DIR%\%WAIT_WAR%.failed" (
  echo Deployment failed: "%DEPLOY_DIR%\%WAIT_WAR%.failed"
  type "%DEPLOY_DIR%\%WAIT_WAR%.failed"
  exit /b 1
)
if "%WAIT_COUNT%"=="%WAIT_LIMIT%" (
  echo Deployment timeout apres %WAIT_LIMIT% secondes.
  exit /b 1
)
set /a WAIT_COUNT+=1
timeout /t 1 >nul
goto :wait_for_deployment_loop

:wait_for_url
set "WAIT_LIMIT=%~1"
set /a WAIT_COUNT=0
:wait_for_url_loop
call :health_check >nul
if not errorlevel 1 (
  call :health_check
  exit /b 0
)
if "%WAIT_COUNT%"=="%WAIT_LIMIT%" exit /b 1
set /a WAIT_COUNT+=1
timeout /t 1 >nul
goto :wait_for_url_loop
