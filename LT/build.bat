@echo off
cd /d C:\GitHub\LT
"C:\Program Files\dotnet\dotnet.exe" build MongLightroomBridge.sln -c Debug -p:Platform=x64 > build_output.txt 2>&1
echo BUILD_EXIT_CODE=%errorlevel% >> build_output.txt
echo DONE_MARKER >> build_output.txt
