# Prints ALL web ports the desktop app listens on (one per line), sorted.
$proc = Get-CimInstance Win32_Process -Filter "Name='DeepSeek Harness.exe'" |
  Where-Object { $_.CommandLine -match 'desktop-host' } |
  Select-Object -First 1
if ($proc) {
  netstat -ano | Select-String ("LISTENING\s+" + $proc.ProcessId + "$") | ForEach-Object {
    $cols = ($_.ToString().Trim() -split '\s+')
    [int](($cols[1] -split ':')[-1])
  } | Sort-Object -Unique
}
