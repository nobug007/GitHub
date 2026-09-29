param([switch]$Verify)
$ErrorActionPreference = 'Stop'
$dotnetPath = Join-Path $env:ProgramFiles 'dotnet\dotnet.exe'
if (!(Test-Path -LiteralPath $dotnetPath)) { throw '.NET 8 이상 x64 SDK가 필요합니다.' }
$sdkSource = 'C:\GitHub\EDSDK_64\Dll'
$sdkTarget = Join-Path $PSScriptRoot 'lib\EDSDK\x64'
New-Item -ItemType Directory -Force -Path $sdkTarget | Out-Null
foreach ($dll in @('EDSDK.dll', 'EdsImage.dll')) {
    $source = Join-Path $sdkSource $dll
    $target = Join-Path $sdkTarget $dll
    if (Test-Path -LiteralPath $source) { Copy-Item -LiteralPath $source -Destination $target -Force }
    if (!(Test-Path -LiteralPath $target)) { throw "Canon SDK DLL이 없습니다: $source" }
}
& $dotnetPath build (Join-Path $PSScriptRoot 'ThreeScreen.csproj') -c Release --nologo
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
if ($Verify) {
    $exe = Join-Path $PSScriptRoot 'bin\Release\net8.0-windows\ShineMung.ThreeScreen.exe'
    $process = Start-Process -FilePath $exe -ArgumentList '--verify' -WindowStyle Hidden -PassThru
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw '검증 실패: 출력 폴더의 verification-error.txt를 확인하세요.' }
    Get-Content (Join-Path $PSScriptRoot 'bin\Release\net8.0-windows\verification\result.txt')
}
