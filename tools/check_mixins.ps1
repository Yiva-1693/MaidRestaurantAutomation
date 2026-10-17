# 校验 mod jar 内 mixins 配置与 mixin class 是否一一对应（防止因删类忘删配置导致启动崩溃）
# 用法: pwsh -File tools/check_mixins.ps1 <path-to-mod.jar>
param(
    [Parameter(Mandatory = $true)][string]$JarPath
)
Add-Type -AssemblyName System.IO.Compression.FileSystem
if (-not (Test-Path $JarPath)) { Write-Host "JAR NOT FOUND: $JarPath"; exit 1 }
$z = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $JarPath))
try {
    $cfgEntry = $z.Entries | Where-Object { $_.FullName -match '\.mixins\.json$' } | Select-Object -First 1
    if (-not $cfgEntry) { Write-Host "NO MIXIN CONFIG FOUND (OK if mod has no mixins)"; exit 0 }
    $ms = New-Object System.IO.MemoryStream
    $s = $cfgEntry.Open(); $s.CopyTo($ms); $s.Close()
    $cfg = [System.Text.Encoding]::UTF8.GetString($ms.ToArray())
    $pkg = ([regex]::Match($cfg, '"package"\s*:\s*"([^"]+)"')).Groups[1].Value
    $names = [regex]::Matches($cfg, '"([A-Za-z0-9_]+)"') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ -match 'Mixin$' }
    $missing = @()
    foreach ($n in $names) {
        $cls = ($pkg -replace '\.', '/') + '/' + $n + '.class'
        $cnt = ($z.Entries | Where-Object { $_.FullName -eq $cls } | Measure-Object).Count
        if ($cnt -eq 0) { $missing += $n }
    }
    Write-Host "mixin entries: $($names.Count)"
    if ($missing.Count -gt 0) {
        Write-Host "MISSING MIXIN CLASSES: $($missing -join ', ')"
        Write-Host "RESULT: FAIL (启动会因 mixin class not found 崩溃)"
        exit 1
    }
    Write-Host "RESULT: OK (所有 mixin 配置都有对应 class)"
}
finally {
    $z.Dispose()
}
