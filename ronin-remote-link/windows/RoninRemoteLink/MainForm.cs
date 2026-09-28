using System.Diagnostics;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace RoninRemoteLink;

public sealed class MainForm : Form
{
    private const string AppVersion = "0.2.0";
    private const string EngineVersion = "1.4.9";
    private const string EngineMsiUrl = "https://github.com/rustdesk/rustdesk/releases/download/1.4.9/rustdesk-1.4.9-x86_64.msi";
    private const string EngineMsiSha256 = "c87d2f4cef2a5acd6003b6507dcfbf5d5168a256db082cd90b54d35193224aaa";

    private readonly TextBox txtTargetName = new();
    private readonly TextBox txtTargetId = new();
    private readonly TextBox txtServer = new();
    private readonly TextBox txtKey = new();
    private readonly TextBox txtSessionPassword = new();
    private readonly TextBox txtConfigString = new();
    private readonly TextBox txtHostPassword = new();
    private readonly Label lblEngineConnect = new();
    private readonly Label lblEngineDiag = new();
    private readonly Label lblHostId = new();
    private readonly Label lblServiceHost = new();
    private readonly Label lblServiceDiag = new();
    private readonly Label lblServer = new();
    private readonly Label lblStatus = new();
    private readonly Button btnRemote = new();
    private readonly Button btnFiles = new();

    private readonly string settingsDir =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Ronin Remote Link");
    private string SettingsPath => Path.Combine(settingsDir, "settings.json");
    private string LogPath => Path.Combine(settingsDir, "ronin-remote-link.log");

    private sealed class Settings
    {
        public string TargetName { get; set; } = "Main Desktop";
        public string TargetId { get; set; } = "";
        public string Server { get; set; } = "";
        public string Key { get; set; } = "";
    }

    public MainForm()
    {
        Text = "Ronin Remote Link";
        Width = 980;
        Height = 760;
        MinimumSize = new Size(820, 640);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.FromArgb(14, 16, 18);
        ForeColor = Color.White;
        Font = new Font("Segoe UI", 10f);

        BuildUi();
        LoadSettings();
        Shown += async (_, _) => await RefreshAllState();
    }

    private void BuildUi()
    {
        var shell = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 4,
            Padding = new Padding(26),
            BackColor = BackColor
        };
        shell.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        shell.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        shell.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        shell.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        Controls.Add(shell);

        var title = new Label
        {
            Text = "RONIN REMOTE LINK",
            AutoSize = true,
            Font = new Font("Segoe UI Semibold", 22f, FontStyle.Bold),
            ForeColor = Color.White,
            Margin = new Padding(0, 0, 0, 4)
        };
        shell.Controls.Add(title);

        var subtitle = new Label
        {
            Text = $"Private device access · Windows host/controller · v{AppVersion} · engine {EngineVersion}",
            AutoSize = true,
            ForeColor = Color.FromArgb(164, 170, 176),
            Margin = new Padding(2, 0, 0, 20)
        };
        shell.Controls.Add(subtitle);

        var tabs = new TabControl { Dock = DockStyle.Fill };
        var connectTab = NewPage("Remote");
        var hostTab = NewPage("This PC / Host");
        var diagnosticsTab = NewPage("Diagnostics");
        tabs.TabPages.Add(connectTab);
        tabs.TabPages.Add(hostTab);
        tabs.TabPages.Add(diagnosticsTab);
        shell.Controls.Add(tabs);

        BuildConnectTab(connectTab);
        BuildHostTab(hostTab);
        BuildDiagnosticsTab(diagnosticsTab);

        lblStatus.Text = "Ready.";
        lblStatus.ForeColor = Color.FromArgb(170, 176, 182);
        lblStatus.Dock = DockStyle.Fill;
        lblStatus.Padding = new Padding(2, 10, 0, 0);
        shell.Controls.Add(lblStatus);
    }

    private TabPage NewPage(string title) => new(title)
    {
        BackColor = Color.FromArgb(20, 23, 26),
        ForeColor = Color.White
    };

    private void BuildConnectTab(TabPage page)
    {
        var grid = NewGrid();
        page.Controls.Add(grid);

        AddField(grid, "DEVICE NAME", txtTargetName, 0, "Main Desktop");
        AddField(grid, "REMOTE DEVICE ID", txtTargetId, 1);
        AddField(grid, "SELF-HOSTED ID SERVER", txtServer, 2, "remote.example.com");
        AddField(grid, "SERVER PUBLIC KEY", txtKey, 3);
        txtSessionPassword.UseSystemPasswordChar = true;
        AddField(grid, "ACCESS PASSWORD", txtSessionPassword, 4, "Not stored by Windows app");

        var row = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true, Padding = new Padding(0, 12, 0, 0) };
        ConfigureButton(btnRemote, "REMOTE DESKTOP", async (_, _) => await StartRemote(false));
        ConfigureButton(btnFiles, "FILES", async (_, _) => await StartRemote(true));
        row.Controls.Add(btnRemote);
        row.Controls.Add(btnFiles);
        row.Controls.Add(ActionButton("SAVE DEVICE", (_, _) => SaveSettings()));
        row.Controls.Add(SecondaryButton("CHECK SERVER", async (_, _) => await CheckServerConnectivity()));
        grid.Controls.Add(row, 1, 5);

        lblEngineConnect.AutoSize = true;
        lblEngineConnect.ForeColor = Color.FromArgb(170, 176, 182);
        lblEngineConnect.Padding = new Padding(0, 8, 0, 0);
        grid.Controls.Add(lblEngineConnect, 1, 6);

        var note = new Label
        {
            AutoSize = true,
            MaximumSize = new Size(620, 0),
            ForeColor = Color.FromArgb(135, 141, 147),
            Text = "The session password is never written to Ronin settings. It is cleared from this window immediately after a connection request is launched."
        };
        grid.Controls.Add(note, 1, 7);
    }

    private void BuildHostTab(TabPage page)
    {
        var grid = NewGrid();
        page.Controls.Add(grid);

        var setup = ActionButton("ONE-CLICK HOST SETUP", async (_, _) => await OneClickHostSetup());
        var install = SecondaryButton("INSTALL / REPAIR ENGINE", async (_, _) => await InstallEngine());
        var open = SecondaryButton("OPEN ENGINE", (_, _) => OpenEngine());
        var service = SecondaryButton("INSTALL / START SERVICE", async (_, _) => await InstallService());
        var row = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true };
        row.Controls.Add(setup);
        row.Controls.Add(install);
        row.Controls.Add(open);
        row.Controls.Add(service);
        grid.Controls.Add(row, 1, 0);

        lblHostId.Text = "This PC ID: —";
        lblHostId.AutoSize = true;
        lblHostId.Font = new Font("Segoe UI Semibold", 14f, FontStyle.Bold);
        lblHostId.ForeColor = Color.White;
        grid.Controls.Add(lblHostId, 1, 1);

        var idRow = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true };
        idRow.Controls.Add(ActionButton("GET THIS PC ID", async (_, _) => await GetHostId()));
        idRow.Controls.Add(SecondaryButton("COPY ID", (_, _) => CopyHostId()));
        grid.Controls.Add(idRow, 1, 2);

        lblServiceHost.AutoSize = true;
        lblServiceHost.ForeColor = Color.FromArgb(170, 176, 182);
        grid.Controls.Add(lblServiceHost, 1, 3);

        txtHostPassword.UseSystemPasswordChar = true;
        AddField(grid, "UNATTENDED PASSWORD", txtHostPassword, 4, "Minimum 12 characters");
        var passRow = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true };
        passRow.Controls.Add(ActionButton("GENERATE", (_, _) => txtHostPassword.Text = GeneratePassword(24)));
        passRow.Controls.Add(ActionButton("SET HOST PASSWORD", async (_, _) => await SetHostPassword()));
        grid.Controls.Add(passRow, 1, 5);

        txtConfigString.Multiline = true;
        txtConfigString.Height = 78;
        AddField(grid, "SERVER CONFIG STRING", txtConfigString, 6, "Optional: exported RustDesk server config string");
        var cfgRow = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true };
        cfgRow.Controls.Add(ActionButton("IMPORT SERVER CONFIG", async (_, _) => await ImportConfig()));
        grid.Controls.Add(cfgRow, 1, 7);
    }

    private void BuildDiagnosticsTab(TabPage page)
    {
        var grid = NewGrid();
        page.Controls.Add(grid);

        var actions = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true };
        actions.Controls.Add(ActionButton("RUN ALL CHECKS", async (_, _) => await RefreshAllState(checkNetwork: true)));
        actions.Controls.Add(SecondaryButton("OPEN LOG FOLDER", (_, _) => OpenLogFolder()));
        grid.Controls.Add(actions, 1, 0);

        lblEngineDiag.AutoSize = true;
        lblEngineDiag.ForeColor = Color.FromArgb(190, 196, 202);
        grid.Controls.Add(LabelFor("REMOTE ENGINE"), 0, 1);
        grid.Controls.Add(lblEngineDiag, 1, 1);

        lblServiceDiag.AutoSize = true;
        lblServiceDiag.ForeColor = Color.FromArgb(190, 196, 202);
        grid.Controls.Add(LabelFor("HOST SERVICE"), 0, 2);
        grid.Controls.Add(lblServiceDiag, 1, 2);

        lblServer.Text = "Server: not checked";
        lblServer.AutoSize = true;
        lblServer.ForeColor = Color.FromArgb(190, 196, 202);
        grid.Controls.Add(LabelFor("SELF-HOSTED SERVER"), 0, 3);
        grid.Controls.Add(lblServer, 1, 3);

        var info = new Label
        {
            AutoSize = true,
            MaximumSize = new Size(620, 0),
            ForeColor = Color.FromArgb(145, 151, 157),
            Text = "Checks are local to this device. Server tests only attempt TCP connections to the RustDesk rendezvous/relay ports and do not transmit credentials."
        };
        grid.Controls.Add(info, 1, 4);
    }

    private TableLayoutPanel NewGrid()
    {
        var grid = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            AutoScroll = true,
            ColumnCount = 2,
            Padding = new Padding(22),
            BackColor = Color.FromArgb(20, 23, 26)
        };
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 225));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        for (int i = 0; i < 14; i++) grid.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        return grid;
    }

    private Label LabelFor(string text) => new()
    {
        Text = text,
        AutoSize = true,
        ForeColor = Color.FromArgb(180, 186, 192),
        Padding = new Padding(0, 5, 10, 0)
    };

    private void AddField(TableLayoutPanel grid, string label, TextBox box, int row, string? placeholder = null)
    {
        var l = LabelFor(label);
        l.Padding = new Padding(0, 9, 10, 0);
        box.Dock = DockStyle.Top;
        box.BackColor = Color.FromArgb(31, 35, 39);
        box.ForeColor = Color.White;
        box.BorderStyle = BorderStyle.FixedSingle;
        if (placeholder != null) box.PlaceholderText = placeholder;
        grid.Controls.Add(l, 0, row);
        grid.Controls.Add(box, 1, row);
    }

    private Button ActionButton(string text, EventHandler click)
    {
        var b = new Button();
        ConfigureButton(b, text, click);
        return b;
    }

    private Button SecondaryButton(string text, EventHandler click)
    {
        var b = ActionButton(text, click);
        b.BackColor = Color.FromArgb(42, 46, 50);
        b.FlatAppearance.BorderColor = Color.FromArgb(75, 81, 87);
        return b;
    }

    private void ConfigureButton(Button b, string text, EventHandler click)
    {
        b.Text = text;
        b.AutoSize = true;
        b.Height = 38;
        b.Padding = new Padding(14, 4, 14, 4);
        b.FlatStyle = FlatStyle.Flat;
        b.BackColor = Color.FromArgb(150, 27, 31);
        b.ForeColor = Color.White;
        b.Margin = new Padding(0, 0, 10, 8);
        b.FlatAppearance.BorderColor = Color.FromArgb(198, 45, 50);
        b.Click += click;
    }

    private string? EnginePath()
    {
        var candidates = new[]
        {
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "RustDesk", "rustdesk.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "RustDesk", "rustdesk.exe")
        };
        return candidates.FirstOrDefault(File.Exists);
    }

    private string EngineDescription()
    {
        var p = EnginePath();
        if (p == null) return $"Remote engine: NOT INSTALLED · expected {EngineVersion}";
        try
        {
            var fvi = FileVersionInfo.GetVersionInfo(p);
            var version = fvi.ProductVersion ?? fvi.FileVersion ?? "unknown version";
            return $"Remote engine: installed · {version} · {p}";
        }
        catch { return "Remote engine: installed · " + p; }
    }

    private async Task RefreshAllState(bool checkNetwork = false)
    {
        RefreshEngineState();
        await RefreshServiceState();
        UpdateConnectButtons();
        if (checkNetwork) await CheckServerConnectivity();
    }

    private void RefreshEngineState()
    {
        var text = EngineDescription();
        lblEngineConnect.Text = text;
        lblEngineDiag.Text = text;
    }

    private async Task RefreshServiceState()
    {
        try
        {
            var result = await RunCaptured("sc.exe", "query RustDesk", tolerateFailure: true);
            var text = result.Contains("RUNNING", StringComparison.OrdinalIgnoreCase)
                ? "Host service: RUNNING"
                : result.Contains("STOPPED", StringComparison.OrdinalIgnoreCase)
                    ? "Host service: STOPPED"
                    : "Host service: not installed / unavailable";
            lblServiceHost.Text = text;
            lblServiceDiag.Text = text;
        }
        catch
        {
            lblServiceHost.Text = "Host service: unable to query";
            lblServiceDiag.Text = "Host service: unable to query";
        }
    }

    private void UpdateConnectButtons()
    {
        var enabled = EnginePath() != null;
        btnRemote.Enabled = enabled;
        btnFiles.Enabled = enabled;
    }

    private async Task OneClickHostSetup()
    {
        try
        {
            SetBusy(true);
            Log("One-click host setup started.");
            if (EnginePath() == null) await InstallEngineCore();
            await InstallServiceCore();
            await GetHostIdCore();
            await RefreshAllState();
            SetStatus("Host setup complete. Set an unattended password and server configuration if not already configured.");
            Log("One-click host setup completed.");
        }
        catch (Exception ex) { Error(ex.Message); }
        finally { SetBusy(false); }
    }

    private async Task InstallEngine()
    {
        try
        {
            SetBusy(true);
            await InstallEngineCore();
            await InstallServiceCore();
            await RefreshAllState();
            SetStatus("Remote engine installed/repaired and host service checked.");
        }
        catch (Exception ex) { Error(ex.Message); }
        finally { SetBusy(false); }
    }

    private async Task InstallEngineCore()
    {
        SetStatus("Downloading verified RustDesk " + EngineVersion + " engine...");
        var temp = Path.Combine(Path.GetTempPath(), "ronin-rustdesk-" + EngineVersion + ".msi");
        using (var http = new HttpClient())
        {
            http.Timeout = TimeSpan.FromMinutes(5);
            await using var source = await http.GetStreamAsync(EngineMsiUrl);
            await using var dest = File.Create(temp);
            await source.CopyToAsync(dest);
        }

        SetStatus("Verifying engine SHA-256...");
        await using (var fs = File.OpenRead(temp))
        {
            var hash = Convert.ToHexString(await SHA256.HashDataAsync(fs)).ToLowerInvariant();
            if (!string.Equals(hash, EngineMsiSha256, StringComparison.OrdinalIgnoreCase))
            {
                File.Delete(temp);
                throw new InvalidOperationException("Engine SHA-256 verification failed. Installation stopped.");
            }
        }

        SetStatus("Installing remote engine...");
        var psi = new ProcessStartInfo("msiexec.exe")
        {
            UseShellExecute = true,
            Verb = "runas",
            Arguments = "/i \"" + temp + "\" /quiet /norestart"
        };
        using var p = Process.Start(psi) ?? throw new InvalidOperationException("Could not start installer.");
        await p.WaitForExitAsync();
        if (p.ExitCode != 0 && p.ExitCode != 3010)
            throw new InvalidOperationException("Installer returned exit code " + p.ExitCode + ".");
        Log("Verified remote engine installed/repaired.");
    }

    private async Task InstallService()
    {
        try
        {
            SetBusy(true);
            await InstallServiceCore();
            await RefreshServiceState();
            SetStatus("Host service installation/start requested.");
        }
        catch (Exception ex) { Error(ex.Message); }
        finally { SetBusy(false); }
    }

    private async Task InstallServiceCore()
    {
        var engine = RequireEngine();
        SetStatus("Installing/starting remote host service...");
        await RunElevated(engine, "--install-service", tolerateFailure: true);
        await RunElevated("sc.exe", "start RustDesk", tolerateFailure: true);
        Log("Host service install/start requested.");
    }

    private async Task GetHostId()
    {
        try
        {
            SetBusy(true);
            await GetHostIdCore();
        }
        catch (Exception ex) { Error(ex.Message); }
        finally { SetBusy(false); }
    }

    private async Task GetHostIdCore()
    {
        var engine = RequireEngine();
        SetStatus("Reading this PC remote ID...");
        var result = await RunCaptured(engine, "--get-id");
        var id = result.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries).LastOrDefault()?.Trim();
        lblHostId.Text = "This PC ID: " + (string.IsNullOrWhiteSpace(id) ? "unable to read" : id);
        SetStatus(string.IsNullOrWhiteSpace(id) ? "Could not read host ID." : "Host ID read.");
    }

    private void CopyHostId()
    {
        var value = lblHostId.Text.Replace("This PC ID:", "", StringComparison.OrdinalIgnoreCase).Trim();
        if (string.IsNullOrWhiteSpace(value) || value == "—" || value.Contains("unable", StringComparison.OrdinalIgnoreCase))
        {
            Error("Get this PC ID first.");
            return;
        }
        Clipboard.SetText(value);
        SetStatus("Host ID copied to clipboard.");
    }

    private async Task SetHostPassword()
    {
        try
        {
            var pw = txtHostPassword.Text;
            if (pw.Length < 12) throw new InvalidOperationException("Use an unattended password of at least 12 characters.");
            var engine = RequireEngine();
            await RunElevated(engine, "--password \"" + EscapeArg(pw) + "\"");
            txtHostPassword.Clear();
            SetStatus("Unattended password updated. It is not stored by Ronin Remote Link.");
            Log("Unattended password changed.");
        }
        catch (Exception ex) { Error(ex.Message); }
    }

    private async Task ImportConfig()
    {
        try
        {
            var cfg = txtConfigString.Text.Trim();
            if (cfg.Length < 10) throw new InvalidOperationException("Paste a valid exported RustDesk server config string first.");
            var engine = RequireEngine();
            await RunElevated(engine, "--config \"" + EscapeArg(cfg) + "\"");
            txtConfigString.Clear();
            SetStatus("Self-hosted server configuration imported into the engine.");
            Log("Server configuration imported.");
        }
        catch (Exception ex) { Error(ex.Message); }
    }

    private async Task StartRemote(bool files)
    {
        try
        {
            SaveSettings(showStatus: false);
            var engine = RequireEngine();
            var id = CleanId(txtTargetId.Text);
            if (id.Length < 3) throw new InvalidOperationException("Enter the remote device ID.");

            var server = txtServer.Text.Trim();
            var key = txtKey.Text.Trim();
            var password = txtSessionPassword.Text;
            var target = id + (string.IsNullOrWhiteSpace(server) ? "" : "/r@" + server);

            var uri = new StringBuilder("rustdesk://");
            if (files) uri.Append("file-transfer/");
            uri.Append(target);

            var hasQuery = false;
            if (!string.IsNullOrWhiteSpace(key))
            {
                uri.Append("?key=").Append(Uri.EscapeDataString(key));
                hasQuery = true;
            }
            if (!string.IsNullOrEmpty(password))
            {
                uri.Append(hasQuery ? "&" : "?")
                   .Append("password=")
                   .Append(Uri.EscapeDataString(password));
            }

            var psi = new ProcessStartInfo(engine) { UseShellExecute = false };
            psi.ArgumentList.Add(uri.ToString());
            Process.Start(psi);
            txtSessionPassword.Clear();
            var name = string.IsNullOrWhiteSpace(txtTargetName.Text) ? id : txtTargetName.Text.Trim();
            SetStatus(files ? $"File transfer opened for {name}." : $"Remote desktop opened for {name}.");
            Log((files ? "File transfer" : "Remote desktop") + " request launched for device " + id + ".");
            await Task.CompletedTask;
        }
        catch (Exception ex)
        {
            txtSessionPassword.Clear();
            Error(ex.Message);
        }
    }

    private async Task CheckServerConnectivity()
    {
        var host = NormalizeHost(txtServer.Text);
        if (string.IsNullOrWhiteSpace(host))
        {
            lblServer.Text = "Server: not configured";
            SetStatus("Enter the self-hosted ID server first.");
            return;
        }

        try
        {
            SetStatus("Checking self-hosted server...");
            var p21116 = await CanConnect(host, 21116, TimeSpan.FromSeconds(4));
            var p21117 = await CanConnect(host, 21117, TimeSpan.FromSeconds(4));
            lblServer.Text = $"Server: {host} · 21116 {(p21116 ? "OK" : "FAILED")} · 21117 {(p21117 ? "OK" : "FAILED")}";
            lblServer.ForeColor = p21116 && p21117 ? Color.FromArgb(125, 210, 145) : Color.FromArgb(235, 174, 93);
            SetStatus(p21116 && p21117 ? "Server ports are reachable." : "Server check completed with a failure. See Diagnostics.");
            Log($"Server check {host}: 21116={p21116}, 21117={p21117}.");
        }
        catch (Exception ex)
        {
            lblServer.Text = "Server: check failed · " + ex.Message;
            lblServer.ForeColor = Color.FromArgb(235, 110, 110);
            Error("Server check failed: " + ex.Message);
        }
    }

    private static async Task<bool> CanConnect(string host, int port, TimeSpan timeout)
    {
        try
        {
            using var client = new TcpClient();
            await client.ConnectAsync(host, port).WaitAsync(timeout);
            return client.Connected;
        }
        catch { return false; }
    }

    private static string NormalizeHost(string value)
    {
        var host = value.Trim();
        if (string.IsNullOrEmpty(host)) return "";
        if (!host.Contains("://")) host = "tcp://" + host;
        return Uri.TryCreate(host, UriKind.Absolute, out var uri) ? uri.Host : value.Trim();
    }

    private void OpenEngine()
    {
        try { Process.Start(new ProcessStartInfo(RequireEngine()) { UseShellExecute = true }); }
        catch (Exception ex) { Error(ex.Message); }
    }

    private void OpenLogFolder()
    {
        try
        {
            Directory.CreateDirectory(settingsDir);
            Process.Start(new ProcessStartInfo("explorer.exe", settingsDir) { UseShellExecute = true });
        }
        catch (Exception ex) { Error(ex.Message); }
    }

    private string RequireEngine() =>
        EnginePath() ?? throw new InvalidOperationException("Remote engine is not installed. Use INSTALL / REPAIR ENGINE first.");

    private static async Task RunElevated(string file, string args, bool tolerateFailure = false)
    {
        var psi = new ProcessStartInfo(file)
        {
            UseShellExecute = true,
            Verb = "runas",
            Arguments = args
        };
        using var p = Process.Start(psi) ?? throw new InvalidOperationException("Could not start elevated process.");
        await p.WaitForExitAsync();
        if (!tolerateFailure && p.ExitCode != 0)
            throw new InvalidOperationException("Command returned exit code " + p.ExitCode + ".");
    }

    private static async Task<string> RunCaptured(string file, string args, bool tolerateFailure = false)
    {
        var psi = new ProcessStartInfo(file)
        {
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true,
            Arguments = args
        };
        using var p = Process.Start(psi) ?? throw new InvalidOperationException("Could not run command.");
        var outputTask = p.StandardOutput.ReadToEndAsync();
        var errorTask = p.StandardError.ReadToEndAsync();
        await p.WaitForExitAsync();
        var output = await outputTask;
        var error = await errorTask;
        if (!tolerateFailure && p.ExitCode != 0 && string.IsNullOrWhiteSpace(output))
            throw new InvalidOperationException(string.IsNullOrWhiteSpace(error) ? "Command failed." : error.Trim());
        return output + Environment.NewLine + error;
    }

    private void LoadSettings()
    {
        try
        {
            if (!File.Exists(SettingsPath)) return;
            var s = JsonSerializer.Deserialize<Settings>(File.ReadAllText(SettingsPath));
            if (s == null) return;
            txtTargetName.Text = s.TargetName;
            txtTargetId.Text = s.TargetId;
            txtServer.Text = s.Server;
            txtKey.Text = s.Key;
        }
        catch (Exception ex) { Log("Settings load failed: " + ex.Message); }
    }

    private void SaveSettings(bool showStatus = true)
    {
        Directory.CreateDirectory(settingsDir);
        var s = new Settings
        {
            TargetName = txtTargetName.Text.Trim(),
            TargetId = txtTargetId.Text.Trim(),
            Server = txtServer.Text.Trim(),
            Key = txtKey.Text.Trim()
        };
        File.WriteAllText(SettingsPath, JsonSerializer.Serialize(s, new JsonSerializerOptions { WriteIndented = true }));
        if (showStatus) SetStatus("Device settings saved. Password was not saved.");
    }

    private static string GeneratePassword(int length)
    {
        const string chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#%*-_";
        var data = RandomNumberGenerator.GetBytes(length);
        var sb = new StringBuilder(length);
        foreach (var b in data) sb.Append(chars[b % chars.Length]);
        return sb.ToString();
    }

    private static string CleanId(string value) => value.Replace(" ", "").Trim();
    private static string EscapeArg(string value) => value.Replace("\"", "\\\"");

    private void SetBusy(bool busy)
    {
        UseWaitCursor = busy;
        btnRemote.Enabled = !busy && EnginePath() != null;
        btnFiles.Enabled = !busy && EnginePath() != null;
    }

    private void SetStatus(string message)
    {
        lblStatus.Text = message;
        lblStatus.ForeColor = Color.FromArgb(170, 176, 182);
    }

    private void Error(string message)
    {
        Log("ERROR: " + message);
        SetStatus("Error: " + message);
        MessageBox.Show(this, message, "Ronin Remote Link", MessageBoxButtons.OK, MessageBoxIcon.Error);
    }

    private void Log(string message)
    {
        try
        {
            Directory.CreateDirectory(settingsDir);
            File.AppendAllText(LogPath, $"{DateTimeOffset.Now:O} {message}{Environment.NewLine}");
        }
        catch { }
    }
}
