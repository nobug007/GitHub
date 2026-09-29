# YOLOv8n-pose ONNX 모델을 앱 assets 에 내려받는다.
#
# 모델은 이미 저장소에 포함되어 있다. 이 스크립트는 모델이 손상됐거나 다른 크기(s/m)로
# 바꿔볼 때만 쓴다. 인터넷이 있는 PC 에서 실행할 것 — 카메라 AP 에는 인터넷이 없다.
#
#   PS> .\tools\fetch_model.ps1
#   PS> .\tools\fetch_model.ps1 -Variant yolov8s-pose    # 더 정확, 더 느림

param(
    [ValidateSet('yolov8n-pose', 'yolov8s-pose')]
    [string]$Variant = 'yolov8n-pose'
)

$ErrorActionPreference = 'Stop'

$root   = Split-Path -Parent $PSScriptRoot
$assets = Join-Path $root 'app\src\main\assets'
$dest   = Join-Path $assets 'yolov8n-pose.onnx'   # PoseEngine 이 참조하는 파일명 (고정)
$url    = "https://huggingface.co/Xenova/$Variant/resolve/main/onnx/model.onnx"

if (-not (Test-Path $assets)) { New-Item -ItemType Directory -Path $assets -Force | Out-Null }

Write-Host "다운로드: $url"
$tmp = "$dest.part"
Invoke-WebRequest -Uri $url -OutFile $tmp -UseBasicParsing

$size = (Get-Item $tmp).Length
if ($size -lt 5MB) {
    Remove-Item $tmp -Force
    throw "받은 파일이 너무 작습니다 ($size 바이트). URL 이 바뀌었는지 확인하세요."
}

# ONNX 는 protobuf 다. 앞부분에 'onnx' 생산자 문자열이 없으면 HTML 오류 페이지를 받은 것이다.
$head = [System.IO.File]::ReadAllBytes($tmp)[0..255]
if (-not ([System.Text.Encoding]::ASCII.GetString($head) -match 'onnx|pytorch')) {
    Remove-Item $tmp -Force
    throw "ONNX 파일이 아닙니다. 응답 내용을 확인하세요."
}

Move-Item -Path $tmp -Destination $dest -Force
Write-Host ("완료: {0} ({1:N1} MB)" -f $dest, ($size / 1MB))

if ($Variant -ne 'yolov8n-pose') {
    Write-Warning "파일명은 yolov8n-pose.onnx 로 유지됩니다 (PoseEngine.MODEL_ASSET 이 이 이름을 참조)."
}
