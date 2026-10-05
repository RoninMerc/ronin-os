$ErrorActionPreference='Stop'
$env:DOTNET_CLI_TELEMETRY_OPTOUT='1'
$baseline=Get-ChildItem artifacts/baseline -Filter Ronin-Vanta-Windows-0.4.2-Setup.exe -Recurse | Select-Object -First 1
if(!$baseline){throw 'Previous delivered installer was not retrieved'}
if((Get-FileHash $baseline.FullName -Algorithm SHA256).Hash.ToLowerInvariant() -ne '689d9f2b728c6b6bef977f9a7dd8fd27bf555646fec77f8235a1cbb8936cc297'){throw 'Baseline installer fingerprint mismatch'}
$new=(Resolve-Path artifacts/installer/Ronin-Vanta-Windows-0.4.3-Setup.exe).Path
$install=Join-Path $env:RUNNER_TEMP 'vanta-043-upgrade-app'
$profile=Join-Path $env:RUNNER_TEMP 'vanta-043-upgrade-fixture'
$env:VANTA_DATA_DIR=$profile
New-Item -ItemType Directory -Force artifacts/qa | Out-Null
function Install-Checked($exe,$log){
    $p=Start-Process -FilePath $exe -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART',('/DIR="'+$install+'"'),('/LOG="'+(Join-Path (Get-Location) $log)+'"')) -Wait -PassThru
    if($p.ExitCode -ne 0){throw "Installer failed: $($p.ExitCode)"}
}
function Open-Close-App($expected){
    $exe=Join-Path $install 'RoninVanta.exe'
    $p=Start-Process -FilePath $exe -PassThru
    for($i=0;$i -lt 100;$i++){Start-Sleep -Milliseconds 200;$p.Refresh();if($p.HasExited){throw 'Application exited during launch'};if($p.MainWindowHandle -ne 0 -and $p.MainWindowTitle -match 'Vanta'){break}}
    if($p.MainWindowHandle -eq 0){throw 'Application did not expose its native window'}
    $version=[System.Diagnostics.FileVersionInfo]::GetVersionInfo((Join-Path $install 'RoninVanta.dll')).FileVersion
    if($version -ne $expected){throw "Unexpected installed file version: $version"}
    $record=@{version=$version;windowTitle=$p.MainWindowTitle;responding=$p.Responding;pid=$p.Id}
    if(!$p.Responding){throw 'Native window is not responding'}
    Start-Sleep -Seconds 2
    $null=$p.CloseMainWindow()
    if(!$p.WaitForExit(20000)){throw 'Application failed to close cleanly'}
    return $record
}
Install-Checked $baseline.FullName 'artifacts/qa/upgrade-install-042.log'
$seed=Join-Path $env:RUNNER_TEMP 'vanta-upgrade-seed'
New-Item -ItemType Directory -Force $seed | Out-Null
$core=(Join-Path $install 'Vanta.Core.dll')
@"
<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><OutputType>Exe</OutputType><TargetFramework>net10.0-windows</TargetFramework><ImplicitUsings>enable</ImplicitUsings></PropertyGroup><ItemGroup><Reference Include="Vanta.Core"><HintPath>$core</HintPath></Reference></ItemGroup></Project>
"@ | Set-Content (Join-Path $seed 'Seed.csproj')
@'
using System.Text.Json;
using System.Text.Json.Nodes;
using Vanta.Core;
using var store = new Store(args[0]);
store.SetKey("featherless","upgrade-fixture-not-a-live-key");
store.SetKey("github","upgrade-worker-fixture");
store.Save("settings","application",new AppPreferences { TrayEnabled=false, ForgeMode="Select model", ForgeModel="featherless|qa-upgrade-model" });
store.Save("settings","build",new BuildPreferences { Repository="qa/worker",Branch="fixture-branch" });
var conversation=new Conversation { Draft="Retain this Windows draft",Title="Upgrade fixture",Messages=new(){new(){Text="Fictional upgrade fixture"}}};
store.Save("conversation",conversation.Id,conversation);
var project=new ProjectRecord { Name="Fictional upgrade source",ModelKey="featherless|qa-upgrade-model",Files=new(){new(){Path="Program.cs",Content="Console.WriteLine(42);"}}};
store.Save("project",project.Id,project);
var job=new JobRecord { Type="forge",Title="Retained failed build",ProjectId=project.Id,ModelKey=project.ModelKey,State="Action required",Attempt=2,Error="Saved compiler fixture" };
store.Save("job",job.Id,job);
store.Save("job-input",job.Id,new JsonObject { ["project"]=JsonSerializer.SerializeToNode(project),["manual_model"]=project.ModelKey });
store.Save("job-step",job.Id+":source",project);
store.Save("job-step",job.Id+":author-repair-1-file-fixture",new JsonObject { ["text"]="completed fixture source" });
store.Save("job-partial",job.Id,"unfinished answer fixture");
Console.WriteLine("Seeded encrypted fixture using installed 0.4.2 Core assembly.");
'@ | Set-Content (Join-Path $seed 'Program.cs')
dotnet run --project (Join-Path $seed 'Seed.csproj') -c Release -- $profile 2>&1 | Tee-Object artifacts/qa/upgrade-seed.log
if($LASTEXITCODE -ne 0){throw 'Baseline fixture seed failed'}
$oldLaunch=Open-Close-App '0.4.2.0'
$keyBefore=(Get-FileHash (Join-Path $profile 'account.key')).Hash
Install-Checked $new 'artifacts/qa/upgrade-install-043.log'
$newLaunch=Open-Close-App '0.4.3.0'
if((Get-FileHash (Join-Path $profile 'account.key')).Hash -ne $keyBefore){throw 'Windows-bound encryption identity changed'}
dotnet run --project ronin-vanta-windows/tests/Vanta.Tests/Vanta.Tests.csproj -c Release --no-build -- --verify-upgrade $profile artifacts/qa/upgrade-state-verification.json 2>&1 | Tee-Object artifacts/qa/upgrade-verify.log
if($LASTEXITCODE -ne 0){throw 'Upgraded state verification failed'}
@{old=$oldLaunch;updated=$newLaunch;accountKeyUnchanged=$true;installerSignature=(Get-AuthenticodeSignature $new).Status.ToString();baselineSHA256=(Get-FileHash $baseline.FullName).Hash;updatedSHA256=(Get-FileHash $new).Hash;scope='Actual 0.4.2 installer -> 0.4.3 installer; fictional encrypted state; no live API usage'} | ConvertTo-Json -Depth 6 | Set-Content artifacts/qa/upgrade-installer-results.json
$uninstall=Join-Path $install 'unins000.exe'
$p=Start-Process $uninstall -ArgumentList '/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART' -Wait -PassThru
if($p.ExitCode -ne 0){throw 'Fixture uninstall failed'}
if(!(Test-Path (Join-Path $profile 'account.key')) -or !(Test-Path (Join-Path $profile 'vanta.sqlite'))){throw 'Uninstall deleted private data'}
Remove-Item Env:VANTA_DATA_DIR
Write-Output 'PASS actual predecessor upgrade, native launches, encrypted task/source/model/credential continuity and uninstall data retention.'
