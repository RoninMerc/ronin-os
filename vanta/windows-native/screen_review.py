from pathlib import Path
root=Path('ronin-vanta-windows')
p=root/'src/Vanta.Windows/MainWindow.cs';s=p.read_text(encoding='utf-8')
old='        Loaded += (_, _) => { ConfigureTray(); Navigate("Chat"); };'
assert s.count(old)==1
s=s.replace(old,old+'\n        ContentRendered += (_,_) => NativeWindow.FitToMonitor(this);',1)
marker='    public static void ActivateExisting()'
assert s.count(marker)==1
s=s.replace(marker,'''    [StructLayout(LayoutKind.Sequential)] private struct Bounds {public int Left,Top,Right,Bottom;}
    [StructLayout(LayoutKind.Sequential)] private struct MonitorInfo {public int Size;public Bounds Monitor,Work;public uint Flags;}
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)] [DllImport("user32.dll")] private static extern bool GetWindowRect(IntPtr handle,out Bounds rect);
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)] [DllImport("user32.dll")] private static extern IntPtr MonitorFromWindow(IntPtr handle,uint flags);
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)] [DllImport("user32.dll",CharSet=CharSet.Auto)] private static extern bool GetMonitorInfo(IntPtr handle,ref MonitorInfo info);
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)] [DllImport("user32.dll")] private static extern uint GetDpiForWindow(IntPtr handle);
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)] [DllImport("user32.dll")] private static extern bool SetWindowPos(IntPtr handle,IntPtr after,int x,int y,int cx,int cy,uint flags);
    public static void FitToMonitor(Window window)
    {
        if(window.WindowState!=WindowState.Normal)return;
        var handle=new WindowInteropHelper(window).Handle;
        var monitor=new MonitorInfo{Size=Marshal.SizeOf<MonitorInfo>()};
        if(handle==IntPtr.Zero||!GetWindowRect(handle,out var bounds)||!GetMonitorInfo(MonitorFromWindow(handle,2),ref monitor))return;
        var work=monitor.Work;int availableWidth=work.Right-work.Left,availableHeight=work.Bottom-work.Top;
        double scale=Math.Max(96,GetDpiForWindow(handle))/96d;
        window.MinWidth=Math.Min(window.MinWidth,availableWidth/scale);window.MinHeight=Math.Min(window.MinHeight,availableHeight/scale);
        int width=Math.Min(bounds.Right-bounds.Left,availableWidth),height=Math.Min(bounds.Bottom-bounds.Top,availableHeight);
        int x=Math.Clamp(bounds.Left,work.Left,work.Right-width),y=Math.Clamp(bounds.Top,work.Top,work.Bottom-height);
        if(x!=bounds.Left||y!=bounds.Top||width!=bounds.Right-bounds.Left||height!=bounds.Bottom-bounds.Top)
            SetWindowPos(handle,IntPtr.Zero,x,y,width,height,0x0004|0x0010);
    }
    public static bool WithinMonitor(Window window)
    {
        var handle=new WindowInteropHelper(window).Handle;var monitor=new MonitorInfo{Size=Marshal.SizeOf<MonitorInfo>()};
        if(!GetWindowRect(handle,out var b)||!GetMonitorInfo(MonitorFromWindow(handle,2),ref monitor))return false;
        return b.Left>=monitor.Work.Left-1&&b.Top>=monitor.Work.Top-1&&b.Right<=monitor.Work.Right+1&&b.Bottom<=monitor.Work.Bottom+1;
    }
''' + marker,1)
p.write_text(s,encoding='utf-8')
p=root/'tests/Vanta.Tests/DesktopTests.cs';s=p.read_text(encoding='utf-8')
old='            await Test("Native minimise, maximise, resize and restore",'
assert s.count(old)==1
s=s.replace(old,'''            await Test("Oversized off-screen window returns inside the current physical monitor",async()=>{
                window.WindowState=WindowState.Normal;window.Width=1800;window.Height=1100;window.Left=-6000;window.Top=-3000;await Layout();NativeWindow.FitToMonitor(window);await Layout();True(NativeWindow.WithinMonitor(window));Shot("monitor-bounds-verified");window.Width=1440;window.Height=920;window.Left=0;window.Top=0;
            });
'''+old,1);p.write_text(s,encoding='utf-8')
p=Path('vanta/windows-native/package.ps1');s=p.read_text(encoding='utf-8')
old='public static class VantaSmokeWindow {';assert s.count(old)==1
s=s.replace(old,old+'\n [DllImport("user32.dll")] public static extern IntPtr SetThreadDpiAwarenessContext(IntPtr context);',1)
old='Add-Type -AssemblyName System.Drawing';assert s.count(old)==1
s=s.replace(old,old+'\n[void][VantaSmokeWindow]::SetThreadDpiAwarenessContext([IntPtr](-4))',1)
old=' Start-Sleep -Seconds 2\n [void][VantaSmokeWindow]::SetForegroundWindow($h)';assert s.count(old)==1
s=s.replace(old,' Start-Sleep -Seconds 2\n [void][VantaSmokeWindow]::GetWindowRect($h,[ref]$r)\n [void][VantaSmokeWindow]::SetForegroundWindow($h)',1)
p.write_text(s,encoding='utf-8')
print('Monitor-bounds and DPI-aware installer capture corrections applied.')
