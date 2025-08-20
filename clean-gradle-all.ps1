# 停止所有 Gradle 守护进程
Write-Host "Stopping Gradle daemons..."
& ./gradlew --stop

# 设置 Gradle 缓存根目录
$gradleCacheRoot = "$env:USERPROFILE\.gradle\caches"

# 要清理的子目录
$targets = @("8.13\transforms", "daemon", "buildOutputCleanup")

foreach ($t in $targets) {
    $path = Join-Path $gradleCacheRoot $t
    if (Test-Path $path) {
        Write-Host "Deleting $path ..."
        try {
            Remove-Item -Recurse -Force -Path $path
            Write-Host "$t deleted successfully."
        } catch {
            Write-Host "Error deleting $t : $_"
        }
    } else {
        Write-Host "No cache found at $path"
    }
}

# 可选：重新编译并刷新依赖
Write-Host "Cleaning and rebuilding project..."
& ./gradlew clean build --refresh-dependencies
