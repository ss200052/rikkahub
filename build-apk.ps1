# 本地构建 RikkaHub 魔改版 release APK
#
# 与 .github/workflows/build-apk.yml 等价：跳过 web-ui 前端构建（内置网页服务用不了，
# 聊天功能不受影响），用仓库内 .github/rikka.jks.b64 还原的固定密钥签名，
# 所以产出的包与 CI 的包同签名，可直接覆盖安装、数据不丢。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File build-apk.ps1
#
# 前置：JDK 17+、Android SDK（local.properties 里的 sdk.dir）
# 网络：依赖走 dl.google.com / repo1.maven.org，直连可用时不要在
#       ~/.gradle/gradle.properties 里配一个连不通的代理，否则会报
#       "Remote host terminated the handshake"。

$ErrorActionPreference = "Stop"
Set-Location (Split-Path -Parent $MyInvocation.MyCommand.Path)

# 1. google-services.json：缺失时用占位内容，与 CI 一致
if (-not (Test-Path "app/google-services.json")) {
    $placeholder = '{"project_info":{"project_number":"0","project_id":"rikkahub","storage_bucket":"rikkahub.appspot.com"},"client":[{"client_info":{"mobilesdk_app_id":"1:0:android:0","android_client_info":{"package_name":"me.rerere.rikkahub.mine"}},"oauth_client":[],"api_key":[{"current_key":"AIzaSyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}],"services":{"appinvite_service":{"other_platform_oauth_client":[]}}}],"configuration_version":"1"}'
    Set-Content -Path "app/google-services.json" -Value $placeholder -NoNewline -Encoding ascii
    Write-Host "[1/4] 生成占位 google-services.json"
} else {
    Write-Host "[1/4] google-services.json 已存在"
}

# 2. 签名：local.properties 缺 storeFile 时，从仓库内 b64 还原并追加配置（保留原有 sdk.dir）
$lp = "local.properties"
$needSigning = $true
if (Test-Path $lp) {
    $needSigning = -not (Select-String -Path $lp -Pattern '^storeFile=' -Quiet)
}
if ($needSigning) {
    if (-not (Test-Path "app/app.key")) {
        $b64 = (Get-Content ".github/rikka.jks.b64" -Raw) -replace '\s', ''
        [IO.File]::WriteAllBytes((Join-Path $PWD "app/app.key"), [Convert]::FromBase64String($b64))
        Write-Host "      已从 .github/rikka.jks.b64 还原 app/app.key"
    }
    Add-Content -Path $lp -Value "`nstoreFile=app.key`nstorePassword=rikkahub123`nkeyAlias=rikka`nkeyPassword=rikkahub123"
    Write-Host "[2/4] 已写入签名配置"
} else {
    Write-Host "[2/4] 签名配置已存在"
}

# 3. 构建（--no-configuration-cache 规避 AGP lint 任务的配置缓存序列化报错）
Write-Host "[3/4] 开始构建，约 5-10 分钟 ..."
& .\gradlew.bat :app:assembleGithubRelease -x :web:buildWebUi --no-configuration-cache
if ($LASTEXITCODE -ne 0) {
    throw "构建失败（exit $LASTEXITCODE）"
}

# 4. 导出到 Downloads
$out = "app/build/outputs/apk/github/release"
$dest = Join-Path $env:USERPROFILE "Downloads"
if (Test-Path $dest) {
    Copy-Item "$out/*.apk" $dest -Force
    Write-Host "[4/4] APK 已复制到 $dest"
}
Get-ChildItem "$out/*.apk" | ForEach-Object { "      {0}  {1:N1} MB" -f $_.Name, ($_.Length / 1MB) }
