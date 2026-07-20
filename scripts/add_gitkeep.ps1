# Adds .gitkeep to empty directories (excludes .git, node_modules, dist)
$root = Get-Location
$dirs = Get-ChildItem -Directory -Recurse | Where-Object {
    $files = Get-ChildItem -Path $_.FullName -File -Recurse -ErrorAction SilentlyContinue
    ($files.Count -eq 0) -and ($_.FullName -notmatch '\\.git($|\\\\)') -and ($_.FullName -notmatch 'node_modules') -and ($_.FullName -notmatch '\\dist($|\\\\)')
}
if ($dirs.Count -eq 0) { Write-Output "No empty directories found."; exit 0 }
foreach ($d in $dirs) {
    $path = Join-Path $d.FullName '.gitkeep'
    if (-not (Test-Path $path)) { New-Item -Path $path -ItemType File -Force | Out-Null; Write-Output "Added: $path" } else { Write-Output "Already exists: $path" }
}
Write-Output "Done creating .gitkeep files."