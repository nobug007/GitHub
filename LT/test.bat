@echo off
cd /d C:\GitHub\LT
"C:\Program Files\dotnet\dotnet.exe" test MongLightroomBridge.sln -c Debug -p:Platform=x64 > test_output.txt 2>&1
echo TEST_EXIT_CODE=%errorlevel% >> test_output.txt
echo DONE_MARKER >> test_output.txt
