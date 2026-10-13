$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Security
Add-Type -AssemblyName System.Windows.Forms
$trlTrainingSettingsPath = Join-Path $env:LOCALAPPDATA 'TRL\MotherBase\settings.dpapi'
if (-not (Test-Path $trlTrainingSettingsPath)) { throw 'Open Mother Base and save its connection settings first.' }
$trlTrainingProtected = [System.IO.File]::ReadAllBytes($trlTrainingSettingsPath)
$trlTrainingPlain = [System.Security.Cryptography.ProtectedData]::Unprotect($trlTrainingProtected, $null, [System.Security.Cryptography.DataProtectionScope]::CurrentUser)
$trlTrainingSettings = [System.Text.Encoding]::UTF8.GetString($trlTrainingPlain) | ConvertFrom-Json
$trlTrainingRoot = [Uri]$trlTrainingSettings.fleetUrl
if ($trlTrainingRoot.Scheme -ne 'https' -or $trlTrainingRoot.UserInfo -or $trlTrainingRoot.AbsolutePath -ne '/' -or $trlTrainingRoot.Query -or $trlTrainingRoot.Fragment) { throw 'Mother Base fleet URL must be an HTTPS service root.' }
if (-not $trlTrainingSettings.fleetKey) { throw 'Save the TRL fleet admin key in Mother Base settings first.' }
$trlTrainingHeaders = @{ 'x-trl-master-key' = $trlTrainingSettings.fleetKey }
$trlTrainingPicker = New-Object System.Windows.Forms.FolderBrowserDialog
$trlTrainingPicker.Description = 'Choose a private folder for TRL report photos and training labels'
if ($trlTrainingPicker.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) { exit }
$trlTrainingOutput = Join-Path $trlTrainingPicker.SelectedPath 'TRL-SilverTrack-Training'
New-Item -ItemType Directory -Force -Path $trlTrainingOutput | Out-Null
$trlTrainingRecords = New-Object 'System.Collections.Generic.List[object]'
$trlTrainingCursor = ''
for ($trlTrainingPage = 0; $trlTrainingPage -lt 200; $trlTrainingPage++) {
 $trlTrainingUri = [Uri]::new($trlTrainingRoot, '/v1/training-evidence?after=' + [Uri]::EscapeDataString($trlTrainingCursor))
 $trlTrainingManifest = Invoke-RestMethod -Uri $trlTrainingUri -Headers $trlTrainingHeaders -TimeoutSec 90
 foreach ($trlTrainingPhoto in $trlTrainingManifest.photos) {
  if ($trlTrainingPhoto.id -notmatch '^[0-9a-fA-F-]{36}$') { throw 'Invalid photo ID received.' }
  $trlTrainingImagePath = Join-Path $trlTrainingOutput ($trlTrainingPhoto.id + '.jpg')
  $trlTrainingExpected = $trlTrainingPhoto.sha256
  if (-not (Test-Path $trlTrainingImagePath) -or (Get-FileHash $trlTrainingImagePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $trlTrainingExpected) {
   $trlTrainingDownload = [Uri]::new($trlTrainingRoot, '/v1/training-evidence/' + $trlTrainingPhoto.id + '.jpg')
   Invoke-WebRequest -Uri $trlTrainingDownload -Headers $trlTrainingHeaders -OutFile ($trlTrainingImagePath + '.part') -UseBasicParsing -TimeoutSec 90 | Out-Null
   if ((Get-FileHash ($trlTrainingImagePath + '.part') -Algorithm SHA256).Hash.ToLowerInvariant() -ne $trlTrainingExpected) { throw 'Photo hash mismatch; partial file retained for inspection.' }
   Move-Item -Force ($trlTrainingImagePath + '.part') $trlTrainingImagePath
  }
  $trlTrainingRecords.Add($trlTrainingPhoto)
  Write-Progress -Activity 'Exporting protected TRL report photos' -Status ($trlTrainingRecords.Count.ToString() + ' photos verified')
 }
 if (-not $trlTrainingManifest.nextCursor) { break }
 $trlTrainingCursor = $trlTrainingManifest.nextCursor
}
$trlTrainingRecords | ConvertTo-Json -Depth 12 | Set-Content -Encoding UTF8 (Join-Path $trlTrainingOutput 'source-manifest.json')
$trlTrainingRecords | ForEach-Object {
 [PSCustomObject]@{ image = ($_.id + '.jpg'); plate = $_.plate; source_report_id = $_.meta.sourceReportId; warning_level = $_.meta.warningLevel; property = $_.meta.property; source_date = $_.meta.sourceDate; label_provenance = $_.meta.plateLabelProvenance; sha256 = $_.sha256 }
} | Export-Csv -NoTypeInformation -Encoding UTF8 (Join-Path $trlTrainingOutput 'report-labels.csv')
Write-Progress -Activity 'Exporting protected TRL report photos' -Completed
[System.Windows.Forms.MessageBox]::Show(($trlTrainingRecords.Count.ToString() + ' photos exported to ' + $trlTrainingOutput + '. Verify report labels before model training.'), 'TRL training photos') | Out-Null
