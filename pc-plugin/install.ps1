# DSHgo phone-remote plugin installer.
# Registers the plugin as a profile bundle dependency so it shows up in dsh's
# plugin manager (已安装插件) with a visible entry + toggle, and loads as a
# profile layer. Idempotent: safe to re-run after plugin updates.
$ErrorActionPreference = "Stop"
$src      = $PSScriptRoot
$userHome = $env:USERPROFILE
$bundles  = Join-Path $userHome ".dsh\bundles"
$dest     = Join-Path $bundles "dsh-phone-remote"
$linkTarget = $dest -replace '\\', '/'

Write-Host "[1/3] Installing plugin files to $dest ..."
New-Item -ItemType Directory -Force -Path $dest | Out-Null
foreach ($part in @("package.json", "cordis.patch.yml", "lib", "bridge")) {
  $s = Join-Path $src $part
  $d = Join-Path $dest $part
  if (Test-Path $s -PathType Container) {
    Copy-Item $s $d -Recurse -Force
  } else {
    Copy-Item $s $d -Force
  }
}

Write-Host "[2/3] Registering plugin in dsh profiles ..."
$profilesDir = Join-Path $userHome ".dsh\profiles"
if (Test-Path $profilesDir) {
  foreach ($prof in Get-ChildItem $profilesDir -Directory) {
    $pkgFile = Join-Path $prof.FullName "package.json"
    $nm      = Join-Path $prof.FullName "node_modules"
    if (-not (Test-Path $pkgFile)) { continue }

    # node_modules link so the package resolves from the profile
    if (Test-Path $nm) {
      $link = Join-Path $nm "dsh-phone-remote"
      if (-not (Test-Path $link)) {
        & cmd /c mklink /J "$link" "$dest" | Out-Null
      }
    } else {
      New-Item -ItemType Directory -Force -Path $nm | Out-Null
      & cmd /c mklink /J (Join-Path $nm "dsh-phone-remote") "$dest" | Out-Null
    }

    # profile manifest: list it as a dependency so the plugin manager shows it
    # and the bundle layer (dsh.bundle.patch) is applied on boot.
    try {
      $pkg = Get-Content $pkgFile -Raw | ConvertFrom-Json
      if (-not $pkg.dependencies) { $pkg | Add-Member -NotePropertyName dependencies -NotePropertyValue (New-Object PSObject) }
      $depName = $pkg.dependencies.PSObject.Properties.Name -contains "dsh-phone-remote"
      $depVal  = if ($depName) { $pkg.dependencies."dsh-phone-remote" } else { $null }
      if (-not $depName -or $depVal -ne ("link:" + $linkTarget)) {
        if ($depName) { $pkg.dependencies."dsh-phone-remote" = ("link:" + $linkTarget) }
        else { $pkg.dependencies | Add-Member -NotePropertyName "dsh-phone-remote" -NotePropertyValue ("link:" + $linkTarget) }
        $json = $pkg | ConvertTo-Json -Depth 32
        # no-BOM UTF-8: dsh's JSON.parse rejects a leading BOM
        [System.IO.File]::WriteAllText($pkgFile, $json, (New-Object System.Text.UTF8Encoding($false)))
        Write-Host ("      " + $prof.Name + ": dsh-phone-remote registered")
      } else {
        Write-Host ("      " + $prof.Name + ": already registered")
      }
    } catch {
      Write-Host ("      " + $prof.Name + ": package.json skipped (" + $_.Exception.Message + ")")
    }
  }
}

Write-Host "[3/3] Done."
Write-Host ""
Write-Host "  The plugin now appears in dsh: 插件 → 已安装插件 → 'dsh-phone-remote'."
Write-Host "  Restart dsh, then the phone-remote chain (bridge + login + Tailscale) comes up by itself."
Write-Host "  Phone side: install DSHgo.apk, open once to log in Tailscale."
Write-Host "  Uninstall: remove the 'dsh-phone-remote' dependency from each profile's package.json."
