param([switch]$OwnClient)
$ErrorActionPreference = 'Stop'
$exe = Join-Path $PSScriptRoot 'tools\rclone.exe'
$configPath = Join-Path $env:LOCALAPPDATA 'ShineMungSM004\rclone.conf'
New-Item -ItemType Directory -Force -Path (Split-Path $configPath) | Out-Null
Write-Host 'Sign in using nobug007@gmail.com. Credentials are saved only in LocalAppData.'
if ($OwnClient) {
    Write-Host 'Create a Desktop OAuth client with Google Drive API enabled: https://rclone.org/drive/#making-your-own-client-id'
    $driveClientId = Read-Host 'Your Google OAuth client ID'
    $driveClientSecret = Read-Host 'Your Google OAuth client secret' -AsSecureString
    $credential = [pscredential]::new('client', $driveClientSecret)
    & $exe config create sm004 drive scope drive root_folder_id 1gRB5LYbYu2TbqFG4-ExW2CpeFP5K5bqs client_id $driveClientId client_secret $credential.GetNetworkCredential().Password config_refresh_token true config_is_local true config_change_team_drive false --config $configPath --no-output --obscure
} elseif ((Test-Path -LiteralPath $configPath) -and (Select-String -LiteralPath $configPath -Pattern '^\[sm004\]$' -Quiet)) {
    & $exe config reconnect sm004: --config $configPath
} else {
    & $exe config create sm004 drive scope drive root_folder_id 1gRB5LYbYu2TbqFG4-ExW2CpeFP5K5bqs config_shared_client_id true config_is_local true config_change_team_drive false --config $configPath --no-output --obscure
}
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $exe about sm004: --json --config $configPath --contimeout 15s --timeout 30s --retries 1
exit $LASTEXITCODE

