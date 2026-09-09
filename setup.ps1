# NeoForge 1.21.1 开发环境初始化脚本
# 运行一次即可：下载 Gradle Wrapper JAR 并同步依赖

$ErrorActionPreference = "Stop"
$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$wrapperJar = Join-Path $projectDir "gradle\wrapper\gradle-wrapper.jar"
$wrapperUrl = "https://raw.githubusercontent.com/gradle/gradle/v8.8.0/gradle/wrapper/gradle-wrapper.jar"

# 如果 wrapper jar 不存在则下载
if (-not (Test-Path $wrapperJar)) {
    Write-Host "Downloading gradle-wrapper.jar ..." -ForegroundColor Cyan
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $wrapperJar) | Out-Null
    try {
        Invoke-WebRequest -Uri $wrapperUrl -OutFile $wrapperJar -UseBasicParsing
    } catch {
        Write-Host "Failed to download wrapper jar from GitHub, trying local Gradle installations ..." -ForegroundColor Yellow
        $found = $false
        $localPaths = @(
            "C:\Program Files\Gradle\gradle-8.8\lib\plugins\gradle-wrapper-8.8.jar",
            "C:\ProgramData\chocolatey\lib\gradle\tools\gradle-8.8\lib\plugins\gradle-wrapper-8.8.jar",
            "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.8-bin\*\gradle-8.8\lib\plugins\gradle-wrapper-8.8.jar",
            "$env:USERPROFILE\scoop\apps\gradle\8.8\lib\plugins\gradle-wrapper-8.8.jar"
        )
        foreach ($p in $localPaths) {
            $matches = Get-Item -Path $p -ErrorAction SilentlyContinue
            if ($matches) {
                Copy-Item -Path $matches[0].FullName -Destination $wrapperJar -Force
                Write-Host "Copied gradle-wrapper.jar from $($matches[0].FullName)" -ForegroundColor Green
                $found = $true
                break
            }
        }
        if (-not $found) {
            Write-Host "Local copy not found, trying services.gradle.org ..." -ForegroundColor Yellow
            $wrapperUrl = "https://services.gradle.org/distributions/gradle-8.8-bin.zip"
            $zip = Join-Path $env:TEMP "gradle-8.8-bin.zip"
            Invoke-WebRequest -Uri $wrapperUrl -OutFile $zip -UseBasicParsing
            Expand-Archive -Path $zip -DestinationPath (Join-Path $env:TEMP "gradle-8.8") -Force
            $srcJar = Join-Path $env:TEMP "gradle-8.8\gradle-8.8\lib\plugins\gradle-wrapper-8.8.jar"
            if (Test-Path $srcJar) {
                Copy-Item -Path $srcJar -Destination $wrapperJar -Force
            } else {
                throw "Could not locate gradle-wrapper.jar"
            }
        }
    }
    Write-Host "gradle-wrapper.jar ready." -ForegroundColor Green
} else {
    Write-Host "gradle-wrapper.jar already exists." -ForegroundColor Green
}

# 首次 Gradle 同步
Write-Host "Running first Gradle sync (this may take a while) ..." -ForegroundColor Cyan
& (Join-Path $projectDir "gradlew.bat") --refresh-dependencies

Write-Host "Setup complete. You can now run 'gradlew runClient' or 'gradlew build'." -ForegroundColor Green
