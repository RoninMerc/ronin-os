from pathlib import Path
source=Path('vanta/windows-043/upgrade.ps1').read_text(encoding='utf-8')
a=source.index('function Open-Close-App('); b=source.index("Install-Checked $baseline.FullName", a)
replacement=r'''Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class UpgradeWindow {
 public delegate bool Callback(IntPtr window, IntPtr parameter);
 [StructLayout(LayoutKind.Sequential)] public struct Rect { public int Left,Top,Right,Bottom; }
 [DllImport("user32.dll")] public static extern bool EnumWindows(Callback callback, IntPtr parameter);
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr window, out uint process);
 [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr window);
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr window, out Rect rect);
 [DllImport("user32.dll",SetLastError=true)] public static extern bool PostMessage(IntPtr window,uint message,IntPtr wparam,IntPtr lparam);
 public static IntPtr Main(int process) { IntPtr found=IntPtr.Zero; EnumWindows((window,p)=> { uint id;GetWindowThreadProcessId(window,out id); Rect rect; if(id==(uint)process && IsWindowVisible(window) && GetWindowRect(window,out rect) && rect.Right-rect.Left>=700 && rect.Bottom-rect.Top>=400) {found=window;return false;}return true; },IntPtr.Zero);return found; }
}
"@
function Open-Close-App($expected){
    $exe=Join-Path $install 'RoninVanta.exe'
    Write-Host "Launching installed $expected and waiting for the full native workspace, not its splash window."
    $p=Start-Process -FilePath $exe -PassThru
    $window=[IntPtr]::Zero
    for($i=0;$i -lt 200;$i++){
        Start-Sleep -Milliseconds 250
        $p.Refresh()
        if($p.HasExited){throw "Installed $expected exited during launch (code $($p.ExitCode))"}
        $window=[UpgradeWindow]::Main($p.Id)
        if($window -ne [IntPtr]::Zero){break}
    }
    if($window -eq [IntPtr]::Zero){throw "Installed $expected did not expose its native workspace"}
    Start-Sleep -Seconds 2
    $p.Refresh()
    $window=[UpgradeWindow]::Main($p.Id)
    if($window -eq [IntPtr]::Zero){throw 'Native workspace disappeared before close check'}
    $version=[System.Diagnostics.FileVersionInfo]::GetVersionInfo((Join-Path $install 'RoninVanta.dll')).FileVersion
    if($version -ne $expected){throw "Unexpected installed file version: $version"}
    $record=@{version=$version;windowTitle=$p.MainWindowTitle;responding=$p.Responding;pid=$p.Id;nativeWindow=$window.ToInt64()}
    if(!$p.Responding){throw 'Native window is not responding'}
    Write-Host "Closing the verified main workspace handle for $expected."
    if(![UpgradeWindow]::PostMessage($window,0x0010,[IntPtr]::Zero,[IntPtr]::Zero)){throw 'Native close message could not be delivered'}
    if(!$p.WaitForExit(45000)){throw "Installed $expected failed to close the verified main window cleanly"}
    Write-Host "PASS installed $expected native launch, response and clean shutdown."
    return $record
}
'''
source=source[:a]+replacement+source[b:]
Path('vanta/windows-043-verification/upgrade-main-window.ps1').write_text(source,encoding='utf-8')
print('Upgrade harness now waits for and refreshes the native main HWND; no app/installer bytes changed.')
