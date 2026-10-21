# DSHgo phone-remote plugin installer.
# Copies the plugin into ~/.dsh/bundles and wires it into every dsh profile.
# Idempotent: safe to run again after plugin updates.
$ErrorActionPreference = "Stop"
$src     = $PSScriptRoot
$userHome = $env:USERPROFILE
$bundles = Join-Path $userHome ".dsh\bundles"
$dest    = Join-Path $bundles "dsh-phone-remote"

Write-Host "[1/3] Installing plugin files to $dest ..."
New-Item -ItemType Directory -Force -Path $dest | Out-Null
foreach ($part in @("package.json", "lib", "bridge")) {
  $s = Join-Path $src $part
  $d = Join-Path $dest $part
  if (Test-Path $s -PathType Container) {
    Copy-Item $s $d -Recurse -Force
  } else {
    Copy-Item $s $d -Force
  }
}

Write-Host "[2/3] Wiring dsh profiles ..."
$linkTarget = $dest -replace '\\', '/'
$patchBlock = @"

# --- dsh phone remote (DSHgo) [installed by dsh-phone-remote/install.ps1] ---
- insert:
    - id: phone-remote
      name: "dsh-phone-remote"
"@

$profilesDir = Join-Path $userHome ".dsh\profiles"
if (Test-Path $profilesDir) {
  foreach ($prof in Get-ChildItem $profilesDir -Directory) {
    $patchFile = Join-Path $prof.FullName "cordis.patch.yml"
    $pkgFile   = Join-Path $prof.FullName "package.json"
    $nm        = Join-Path $prof.FullName "node_modules"

    if (Test-Path $patchFile) {
      $text = Get-Content $patchFile -Raw
      if ($text -notmatch [regex]::Escape('name: "dsh-phone-remote"')) {
        Add-Content -Path $patchFile -Value $patchBlock
        Write-Host ("      " + $prof.Name + ": plugin entry added")
      } else {
        Write-Host ("      " + $prof.Name + ": plugin entry already present")
      }
    }

    if (Test-Path $nm) {
      $link = Join-Path $nm "dsh-phone-remote"
      if (-not (Test-Path $link)) {
        & cmd /c mklink /J "$link" "$dest" | Out-Null
        Write-Host ("      " + $prof.Name + ": node_modules link created")
      }
    }

    if (Test-Path $pkgFile) {
      try {
        $pkg = Get-Content $pkgFile -Raw | ConvertFrom-Json
        if (-not $pkg.dependencies) { $pkg | Add-Member -NotePropertyName dependencies -NotePropertyValue (New-Object PSObject) }
        $has = $pkg.dependencies.PSObject.Properties.Name -contains "dsh-phone-remote"
        if (-not $has) {
          $pkg.dependencies | Add-Member -NotePropertyName "dsh-phone-remote" -NotePropertyValue ("link:" + $linkTarget)
          $json = $pkg | ConvertTo-Json -Depth 32
          # Windows PowerShell's UTF8 writer adds a BOM and dsh's JSON.parse
          # rejects it — write no-BOM UTF-8 explicitly.
          [System.IO.File]::WriteAllText($pkgFile, $json, (New-Object System.Text.UTF8Encoding($false)))
          Write-Host ("      " + $prof.Name + ": package.json dependency added")
        }
      } catch {
        Write-Host ("      " + $prof.Name + ": package.json skipped (" + $_.Exception.Message + ")")
      }
    }
  }
}

Write-Host "[3/3] Done."
Write-Host ""
Write-Host "  Restart dsh (the desktop app), and the phone remote chain comes up by itself."
Write-Host "  Phone side: install DSHgo.apk, open it once to log in Tailscale."
Write-Host "  Uninstall: remove the 'phone-remote' block from each profile's cordis.patch.yml."
