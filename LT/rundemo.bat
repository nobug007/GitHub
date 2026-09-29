@echo off
cd /d C:\GitHub\LT
"C:\Program Files\dotnet\dotnet.exe" run --project src\MongLightroomBridge.DryRunDemo -c Debug -- "C:\GitHub\Photo\_sample_input" "C:\GitHub\Photo\output" > dryrun_demo_output.txt 2>&1
echo DEMO_EXIT_CODE=%errorlevel% >> dryrun_demo_output.txt
echo DONE_MARKER >> dryrun_demo_output.txt
