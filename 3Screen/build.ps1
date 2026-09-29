$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
& "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe" /nologo /target:winexe /platform:anycpu /optimize+ /out:ShineMung3Screen.exe /win32manifest:app.manifest /r:System.Windows.Forms.dll /r:System.Drawing.dll /r:System.Core.dll /r:System.Xml.dll Program.cs
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
Write-Host 'Built ShineMung3Screen.exe'
