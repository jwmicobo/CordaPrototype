@if "%DEBUG%" == "" @echo off
@rem ##########################################################################
@rem
@rem  demobench startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%" == "" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%..

@rem Add default JVM options here. You can also use JAVA_OPTS and DEMOBENCH_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS="-Djava.util.logging.config.class=net.corda.demobench.config.LoggingConfig" "-Dorg.jboss.logging.provider=slf4j"

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if "%ERRORLEVEL%" == "0" goto init

echo.
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
echo.
echo Please set the JAVA_HOME variable in your environment to match the
echo location of your Java installation.

goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto init

echo.
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
echo.
echo Please set the JAVA_HOME variable in your environment to match the
echo location of your Java installation.

goto fail

:init
@rem Get command-line arguments, handling Windows variants

if not "%OS%" == "Windows_NT" goto win9xME_args

:win9xME_args
@rem Slurp the command line arguments.
set CMD_LINE_ARGS=
set _SKIP=2

:win9xME_args_slurp
if "x%~1" == "x" goto execute

set CMD_LINE_ARGS=%*

:execute
@rem Setup the command line

set CLASSPATH=%APP_HOME%\lib\demobench-3.0-SNAPSHOT.jar;%APP_HOME%\lib\tornadofx-1.7.10.jar;%APP_HOME%\lib\controlsfx-8.40.12.jar;%APP_HOME%\lib\corda-rpc-3.0-SNAPSHOT.jar;%APP_HOME%\lib\corda-node-api-3.0-SNAPSHOT.jar;%APP_HOME%\lib\cordform-common-3.0.3.jar;%APP_HOME%\lib\corda-finance-3.0-SNAPSHOT.jar;%APP_HOME%\lib\h2-1.4.194.jar;%APP_HOME%\lib\jna-platform-4.1.0.jar;%APP_HOME%\lib\artemis-core-client-2.2.0.jar;%APP_HOME%\lib\artemis-commons-2.2.0.jar;%APP_HOME%\lib\guava-21.0.jar;%APP_HOME%\lib\purejavacomm-0.0.18.jar;%APP_HOME%\lib\log4j-over-slf4j-1.7.25.jar;%APP_HOME%\lib\jul-to-slf4j-1.7.25.jar;%APP_HOME%\lib\log4j-slf4j-impl-2.9.1.jar;%APP_HOME%\lib\corda-confidential-identities-3.0-SNAPSHOT.jar;%APP_HOME%\lib\corda-core-3.0-SNAPSHOT.jar;%APP_HOME%\lib\log4j-core-2.9.1.jar;%APP_HOME%\lib\config-1.3.1.jar;%APP_HOME%\lib\fontawesomefx-fontawesome-4.7.0-5.jar;%APP_HOME%\lib\terminal-331a005d6793e52cefc9e2cec6774e62d5a546b1.jar;%APP_HOME%\lib\pty4j-0.7.2.jar;%APP_HOME%\lib\javax.json-1.0.4.jar;%APP_HOME%\lib\kotlin-stdlib-jre8-1.1.60.jar;%APP_HOME%\lib\kotlin-reflect-1.1.60.jar;%APP_HOME%\lib\jsr305-3.0.2.jar;%APP_HOME%\lib\quasar-core-0.7.9-jdk8.jar;%APP_HOME%\lib\jna-4.1.0.jar;%APP_HOME%\lib\jcl-over-slf4j-1.7.25.jar;%APP_HOME%\lib\slf4j-api-1.7.25.jar;%APP_HOME%\lib\log4j-api-2.9.1.jar;%APP_HOME%\lib\fontawesomefx-commons-8.15.jar;%APP_HOME%\lib\kotlin-stdlib-jre7-1.1.60.jar;%APP_HOME%\lib\kotlin-stdlib-1.1.60.jar;%APP_HOME%\lib\rxjava-1.2.4.jar;%APP_HOME%\lib\commons-jexl3-3.0.jar;%APP_HOME%\lib\jackson-databind-2.9.2.jar;%APP_HOME%\lib\eddsa-0.2.0.jar;%APP_HOME%\lib\bcpkix-jdk15on-1.57.jar;%APP_HOME%\lib\bcprov-jdk15on-1.57.jar;%APP_HOME%\lib\hibernate-core-5.2.6.Final.jar;%APP_HOME%\lib\hibernate-jpa-2.1-api-1.0.0.Final.jar;%APP_HOME%\lib\commons-collections4-4.1.jar;%APP_HOME%\lib\commons-beanutils-1.9.3.jar;%APP_HOME%\lib\commons-fileupload-1.3.3.jar;%APP_HOME%\lib\kryo-serializers-0.41.jar;%APP_HOME%\lib\kryo-4.0.0.jar;%APP_HOME%\lib\proton-j-0.21.0.jar;%APP_HOME%\lib\jackson-annotations-2.9.0.jar;%APP_HOME%\lib\jackson-core-2.9.2.jar;%APP_HOME%\lib\hibernate-commons-annotations-5.0.1.Final.jar;%APP_HOME%\lib\jboss-logging-3.3.0.Final.jar;%APP_HOME%\lib\javassist-3.20.0-GA.jar;%APP_HOME%\lib\antlr-2.7.7.jar;%APP_HOME%\lib\geronimo-jta_1.1_spec-1.1.1.jar;%APP_HOME%\lib\jandex-2.0.3.Final.jar;%APP_HOME%\lib\classmate-1.3.0.jar;%APP_HOME%\lib\dom4j-1.6.1.jar;%APP_HOME%\lib\cdi-api-1.1.jar;%APP_HOME%\lib\commons-collections-3.2.2.jar;%APP_HOME%\lib\jgroups-3.6.13.Final.jar;%APP_HOME%\lib\netty-all-4.1.9.Final.jar;%APP_HOME%\lib\geronimo-json_1.0_spec-1.0-alpha-1.jar;%APP_HOME%\lib\johnzon-core-0.9.5.jar;%APP_HOME%\lib\commons-io-2.2.jar;%APP_HOME%\lib\reflectasm-1.11.3.jar;%APP_HOME%\lib\minlog-1.3.0.jar;%APP_HOME%\lib\objenesis-2.2.jar;%APP_HOME%\lib\el-api-2.2.jar;%APP_HOME%\lib\jboss-interceptors-api_1.1_spec-1.0.0.Beta1.jar;%APP_HOME%\lib\jsr250-api-1.0.jar;%APP_HOME%\lib\javax.inject-1.jar;%APP_HOME%\lib\asm-5.0.4.jar;%APP_HOME%\lib\annotations-13.0.jar

@rem Execute demobench
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %DEMOBENCH_OPTS%  -classpath "%CLASSPATH%" net.corda.demobench.DemoBench %CMD_LINE_ARGS%

:end
@rem End local scope for the variables with windows NT shell
if "%ERRORLEVEL%"=="0" goto mainEnd

:fail
rem Set variable DEMOBENCH_EXIT_CONSOLE if you need the _script_ return code instead of
rem the _cmd.exe /c_ return code!
if  not "" == "%DEMOBENCH_EXIT_CONSOLE%" exit 1
exit /b 1

:mainEnd
if "%OS%"=="Windows_NT" endlocal

:omega
