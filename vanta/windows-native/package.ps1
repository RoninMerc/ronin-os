$ErrorActionPreference='Stop'
$root=(Get-Location).Path
$out=Join-Path $root 'artifacts/installer'
New-Item -ItemType Directory -Force $out | Out-Null
$command=Get-Command ISCC.exe -ErrorAction SilentlyContinue
$iscc=if($command){$command.Source}else{'C:\Program Files (x86)\Inno Setup 6\ISCC.exe'}
if(!(Test-Path $iscc)){choco install innosetup --no-progress -y; if($LASTEXITCODE -ne 0){throw 'Inno Setup installation failed'}}
$installerSource=Join-Path $root 'vanta/windows-native/installer.iss'
$publish=Join-Path $root 'artifacts/app'
Copy-Item $installerSource ronin-vanta-windows/installer.iss
& $iscc "/DAppSource=$publish" "/DOutput=$out" $installerSource 2>&1 | Tee-Object artifacts/installer-build.log
if($LASTEXITCODE -ne 0){throw 'Installer compilation failed'}
$setup=Join-Path $out 'Ronin-Vanta-Windows-0.1.1-Setup.exe'
$info=Get-Item $setup
if($info.Length -lt 1000000){throw 'Installer is unexpectedly small'}
$install=Join-Path $env:RUNNER_TEMP 'Vanta Installed QA'
$profile=Join-Path $env:RUNNER_TEMP 'Vanta Installed QA Data'
$env:VANTA_DATA_DIR=$profile
$env:DOTNET_ROOT=Join-Path $env:RUNNER_TEMP 'no-runtime-installed-here'
$env:DOTNET_MULTILEVEL_LOOKUP='0'
$record=[ordered]@{version='0.1.1';architecture='win-x64';platform=[System.Environment]::OSVersion.ToString();signing=(Get-AuthenticodeSignature $setup).Status.ToString();installerSha256=(Get-FileHash $setup).Hash;installerBytes=$info.Length}
Add-Type @'
using System; using System.Runtime.InteropServices;
public static class VantaSmokeWindow {
 [StructLayout(LayoutKind.Sequential)] public struct Rect { public int Left,Top,Right,Bottom; }
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h,out Rect r);
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h,int n);
 [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
}
'@
Add-Type -AssemblyName System.Drawing
function Install-Vanta {
 $p=Start-Process $setup -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART',('/DIR="'+$install+'"'),('/LOG="'+(Join-Path $root 'artifacts/installer-install.log')+'"')) -Wait -PassThru
 if($p.ExitCode -ne 0){throw "Installer failed: $($p.ExitCode)"}
 if(!(Test-Path "$install/RoninVanta.exe") -or !(Test-Path "$install/coreclr.dll")){throw 'Missing executable or bundled runtime'}
}
function Launch-Vanta([string]$label) {
 $clock=[Diagnostics.Stopwatch]::StartNew();$p=Start-Process "$install/RoninVanta.exe" -PassThru
 $until=(Get-Date).AddSeconds(40)
 do {Start-Sleep -Milliseconds 100;$p.Refresh();if($p.HasExited){throw 'Installed application exited during startup'};$h=$p.MainWindowHandle;$r=New-Object VantaSmokeWindow+Rect;if($h -ne [IntPtr]::Zero){[void][VantaSmokeWindow]::GetWindowRect($h,[ref]$r)}} while(($h -eq [IntPtr]::Zero -or ($r.Right-$r.Left)-lt 850) -and (Get-Date)-lt $until)
 if($h -eq [IntPtr]::Zero -or ($r.Right-$r.Left)-lt 850){Stop-Process $p -Force;throw 'Installed main workspace never opened'}
 $record[$label+'_startup_ms']=$clock.Elapsed.TotalMilliseconds
 Start-Sleep -Seconds 2
 [void][VantaSmokeWindow]::SetForegroundWindow($h)
 $bitmap=New-Object Drawing.Bitmap ($r.Right-$r.Left),($r.Bottom-$r.Top)
 $graphics=[Drawing.Graphics]::FromImage($bitmap);$graphics.CopyFromScreen($r.Left,$r.Top,0,0,$bitmap.Size)
 $bitmap.Save((Join-Path $out ($label+'.png')),[Drawing.Imaging.ImageFormat]::Png);$graphics.Dispose();$bitmap.Dispose()
 $p.Refresh();$record[$label+'_working_set_mb']=$p.WorkingSet64/1MB
 $cpu=$p.TotalProcessorTime.TotalMilliseconds;Start-Sleep -Seconds 12;$p.Refresh();$record[$label+'_idle_cpu_ms_over_12s']=$p.TotalProcessorTime.TotalMilliseconds-$cpu
 [void][VantaSmokeWindow]::ShowWindow($h,6);Start-Sleep -Milliseconds 500;if(![VantaSmokeWindow]::IsIconic($h)){throw 'Minimize failed'}
 [void][VantaSmokeWindow]::ShowWindow($h,9);Start-Sleep -Milliseconds 500
 [void]$p.CloseMainWindow();if(!$p.WaitForExit(15000)){Stop-Process $p -Force;throw 'Application did not close cleanly'}
 $record[$label+'_exit_code']=$p.ExitCode;if($p.ExitCode -ne 0){throw 'Installed application reported a failure'}
}
Install-Vanta
Launch-Vanta 'fresh'
if(!(Test-Path "$profile/vanta.sqlite") -or !(Test-Path "$profile/account.key")){throw 'Persistent private workspace not created'}
$key=(Get-FileHash "$profile/account.key").Hash
Install-Vanta
Launch-Vanta 'upgrade'
if((Get-FileHash "$profile/account.key").Hash -ne $key){throw 'Reinstall changed account encryption identity'}
$uninstall=Start-Process "$install/unins000.exe" -ArgumentList '/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART' -Wait -PassThru
if($uninstall.ExitCode -ne 0){throw 'Uninstall failed'}
if(!(Test-Path "$profile/vanta.sqlite") -or (Get-FileHash "$profile/account.key").Hash -ne $key){throw 'Uninstall erased personal data'}
$record['install']='passed';$record['reinstall_and_data_preservation']='passed';$record['uninstall_and_data_preservation']='passed';$record['provider_testing']='No live API calls; clean offline profile'
$record | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $out 'installer-verification.json')
Get-FileHash $setup | Format-List | Out-File (Join-Path $out 'SHA256.txt')
Remove-Item $profile -Recurse -Force
Write-Output 'INSTALLER VERIFICATION PASSED: installed, launched, minimized/restored, upgraded, relaunched and uninstalled; private data retained.'
