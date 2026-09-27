using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace RoninRemoteLink;

public sealed class MainForm : Form
{
    private const string EngineVersion = "1.4.9";
    private const string EngineMsiUrl = "https://github.com/rustdesk/rustdesk/releases/download/1.4.9/rustdesk-1.4.9-x86_64.msi";
    private const string EngineMsiSha256 = "c87d2f4cef2a5acd6003b6507dcfbf5d5168a256db082cd90b54d35193224aaa";

    private readonly TextBox txtTargetId = new();
    private readonly TextBox txtServer = new();
    private readonly TextBox txtKey = new();
    private readonly TextBox txtSessionPassword = new();
    private readonly TextBox txtConfigString = new();
    private readonly TextBox txtHostPassword = new();
    private readonly Label lblEngine = new();
    private readonly Label lblHostId = new();
    private readonly Label lblStatus = new();

    private readonly string settingsDir =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Ronin Remote Link");
    private string SettingsPath => Path.Combine(settingsDir, "settings.json");

    private sealed class Settings
    {
        public string TargetId { get; set; } = "";
        public string Server { get; set; } = "";
        public string Key { get; set; } = "";
    }

    public MainForm()
    {
        Text = "Ronin Remote Link";
        Width = 920;
        Height = 690;
        MinimumSize = new Size(760, 570);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.FromArgb(14, 16, 18);
        ForeColor = Color.White;
        Font = new Font("Segoe UI", 10f);

        BuildUi();
        LoadSettings();
        RefreshEngineState();
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
            Text = "Private device access · Windows host/controller · v0.1.0",
            AutoSize = true,
            ForeColor = Color.FromArgb(164, 170, 176),
            Margin = new Padding(2, 0, 0, 20)
        };
        shell.Controls.Add(subtitle);

        var tabs = new TabControl { Dock = DockStyle.Fill };
        var connectTab = new TabPage("Connect") { BackColor = Color.FromArgb(20, 23, 26), ForeColor = Color.White };
        var hostTab = new TabPage("This PC / Host") { BackColor = Color.FromArgb(20, 23, 26), ForeColor = Color.White };
        tabs.TabPages.Add(connectTab);
        tabs.TabPages.Add(hostTab);
        shell.Controls.Add(tabs);

        BuildConnectTab(connectTab);
        BuildHostTab(hostTab);

        lblStatus.Text = "Ready.";
        lblStatus.ForeColor = Color.FromArgb(170, 176, 182);
        lblStatus.Dock = DockStyle.Fill;
        lblStatus.Padding = new Padding(2, 10, 0, 0);
        shell.Controls.Add(lblStatus);
    }

    private void BuildConnectTab(TabPage page)
    {
        var grid = NewGrid();
        page.Controls.Add(grid);

        AddField(grid, "MAIN DESKTOP ID", txtTargetId, 0);
        AddField(grid, "SELF-HOSTED ID SERVER", txtServer, 1, "e.g. remote.example.com");
        AddField(grid, "SERVER PUBLIC KEY", txtKey, 2);
        txtSessionPassword.UseSystemPasswordChar = true;
        AddField(grid, "ACCESS PASSWORD (not saved)", txtSessionPassword, 3);

        var row = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true, Padding = new Padding(0, 12, 0, 0) };
        row.Controls.Add(ActionButton("REMOTE DESKTOP", async (_, _) => await StartRemote(false)));
        row.Controls.Add(ActionButton("FILES", async (_, _) => await StartRemote(true)));
        row.Controls.Add(ActionButton("SAVE DEVICE", (_, _) => SaveSettings()));
        grid.Controls.Add(row, 1, 4);

        lblEngine.AutoSize = true;
        lblEngine.ForeColor = Color.FromArgb(170, 176, 182);
        grid.Controls.Add(lblEngine, 1, 5);
    }

    private void BuildHostTab(TabPage page)
    {
        var grid = NewGrid();
        page.Controls.Add(grid);

        var install = ActionButton("INSTALL / REPAIR ENGINE", async (_, _) => await InstallEngine());
        var open = ActionButton("OPEN ENGINE", (_, _) => OpenEngine());
        var service = ActionButton("INSTALL / START SERVICE", async (_, _) => await InstallService());
        var row = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true };
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
        grid.Controls.Add(idRow, 1, 2);

        txtHostPassword.UseSystemPasswordChar = true;
        AddField(grid, "UNATTENDED PASSWORD", txtHostPassword, 3);
        var passRow = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true };
        passRow.Controls.Add(ActionButton("GENERATE", (_, _) => txtHostPassword.Text = GeneratePassword(24)));
        passRow.Controls.Add(ActionButton("SET HOST PASSWORD", async (_, _) => await SetHostPassword()));
        grid.Controls.Add(passRow, 1, 4);

        txtConfigString.Multiline = true;
        txtConfigString.Height = 70;
        AddField(grid, "RUSTDESK SERVER CONFIG STRING (optional)", txtConfigString, 5);
        var cfgRow = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true };
        cfgRow.Controls.Add(ActionButton("IMPORT SERVER CONFIG", async (_, _) => await ImportConfig()));
        grid.Controls.Add(cfgRow, 1, 6);
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
        for (int i = 0; i < 12; i++) grid.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        return grid;
    }

    private void AddField(TableLayoutPanel grid, string label, TextBox box, int row, string? placeholder = null)
    {
        var l = new Label
        {
            Text = label,
            AutoSize = true,
            ForeColor = Color.FromArgb(180, 186, 192),
            Padding = new Padding(0, 9, 10, 0)
        };
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
        var b = new Button
        {
            Text = text,
            AutoSize = true,
            Height = 38,
            Padding = new Padding(14, 4, 14, 4),
            FlatStyle = FlatStyle.Flat,
            BackColor = Color.FromArgb(150, 27, 31),
            ForeColor = Color.White,
            Margin = new Padding(0, 0, 10, 8)
        };
        b.FlatAppearance.BorderColor = Color.FromArgb(198, 45, 50);
        b.Click += click;
        return b;
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

    private void RefreshEngineState()
    {
        var p = EnginePath();
        lblEngine.Text = p == null ? "Remote engine: NOT INSTALLED" : "Remote engine: installed · " + p;
    }

    private async Task InstallEngine()
    {
        try
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

            var hash = Convert.ToHexString(SHA256.HashData(await File.ReadAllBytesAsync(temp))).ToLowerInvariant();
            if (!string.Equals(hash, EngineMsiSha256, StringComparison.OrdinalIgnoreCase))
            {
                File.Delete(temp);
                throw new InvalidOperationException("Engine SHA-256 verification failed. Installation stopped.");
            }

            SetStatus("Installing remote engine...");
            var psi = new ProcessStartInfo("msiexec.exe")
            {
                UseShellExecute = true,
                Verb = "runas",
                Arguments = "/i "" + temp + "" /quiet /norestart"
            };
            using var p = Process.Start(psi) ?? throw new InvalidOperationException("Could not start installer.");
            await p.WaitForExitAsync();
            if (p.ExitCode != 0 && p.ExitCode != 3010)
                throw new InvalidOperationException("Installer returned exit code " + p.ExitCode + ".");

            RefreshEngineState();
            await InstallService();
            SetStatus("Remote engine installed and service checked.");
        }
        catch (Exception ex)
        {
            Error(ex.Message);
        }
    }

    private async Task InstallService()
    {
        try
        {
            var engine = RequireEngine();
            SetStatus("Installing/starting remote host service...");
            await RunElevated(engine, "--install-service");
            await RunElevated("sc.exe", "start RustDesk", tolerateFailure: true);
            SetStatus("Host service installation/start requested.");
        }
        catch (Exception ex) { Error(ex.Message); }
    }

    private async Task GetHostId()
    {
        try
        {
            var engine = RequireEngine();
            SetStatus("Reading this PC remote ID...");
            var result = await RunCaptured(engine, "--get-id");
            var id = result.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries).LastOrDefault()?.Trim();
            lblHostId.Text = "This PC ID: " + (string.IsNullOrWhiteSpace(id) ? "unable to read" : id);
            SetStatus("Host ID read.");
        }
        catch (Exception ex) { Error(ex.Message); }
    }

    private async Task SetHostPassword()
    {
        try
        {
            var pw = txtHostPassword.Text;
            if (pw.Length < 12) throw new InvalidOperationException("Use an unattended password of at least 12 characters.");
            var engine = RequireEngine();
            await RunElevated(engine, "--password "" + EscapeArg(pw) + """);
            txtHostPassword.Clear();
            SetStatus("Unattended password updated. It is not stored by Ronin Remote Link.");
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
            await RunElevated(engine, "--config "" + EscapeArg(cfg) + """);
            SetStatus("Self-hosted server configuration imported into the engine.");
        }
        catch (Exception ex) { Error(ex.Message); }
    }

    private async Task StartRemote(bool files)
    {
        try
        {
            SaveSettings();
            var engine = RequireEngine();
            var id = txtTargetId.Text.Trim().Replace(" ", "");
            if (id.Length < 3) throw new InvalidOperationException("Enter the main desktop ID.");

            var target = id;
            var server = txtServer.Text.Trim();
            var key = txtKey.Text.Trim();
            if (!string.IsNullOrWhiteSpace(server))
            {
                target += "/r@" + server;
                if (!string.IsNullOrWhiteSpace(key))
                    target += "?key=" + Uri.EscapeDataString(key);
            }

            var psi = new ProcessStartInfo(engine) { UseShellExecute = false };
            psi.ArgumentList.Add(files ? "--file-transfer" : "--connect");
            psi.ArgumentList.Add(target);
            if (!string.IsNullOrEmpty(txtSessionPassword.Text))
            {
                psi.ArgumentList.Add("--password");
                psi.ArgumentList.Add(txtSessionPassword.Text);
            }
            Process.Start(psi);
            SetStatus(files ? "File transfer opened." : "Remote desktop opened.");
            await Task.CompletedTask;
        }
        catch (Exception ex) { Error(ex.Message); }
    }

    private void OpenEngine()
    {
        try { Process.Start(new ProcessStartInfo(RequireEngine()) { UseShellExecute = true }); }
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

    private static async Task<string> RunCaptured(string file, string args)
    {
        var psi = new ProcessStartInfo(file)
        {
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true,
            Arguments = args
        };
        using var p = Process.Start(psi) ?? throw new InvalidOperationException("Could not run engine.");
        var outputTask = p.StandardOutput.ReadToEndAsync();
        var errorTask = p.StandardError.ReadToEndAsync();
        await p.WaitForExitAsync();
        var output = await outputTask;
        var error = await errorTask;
        if (p.ExitCode != 0 && string.IsNullOrWhiteSpace(output))
            throw new InvalidOperationException(string.IsNullOrWhiteSpace(error) ? "Engine command failed." : error.Trim());
        return output;
    }

    private void LoadSettings()
    {
        try
        {
            if (!File.Exists(SettingsPath)) return;
            var s = JsonSerializer.Deserialize<Settings>(File.ReadAllText(SettingsPath));
            if (s == null) return;
            txtTargetId.Text = s.TargetId;
            txtServer.Text = s.Server;
            txtKey.Text = s.Key;
        }
        catch { }
    }

    private void SaveSettings()
    {
        Directory.CreateDirectory(settingsDir);
        var s = new Settings { TargetId = txtTargetId.Text.Trim(), Server = txtServer.Text.Trim(), Key = txtKey.Text.Trim() };
        File.WriteAllText(SettingsPath, JsonSerializer.Serialize(s, new JsonSerializerOptions { WriteIndented = true }));
        SetStatus("Device settings saved. Password was not saved.");
    }

    private static string GeneratePassword(int length)
    {
        const string chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#%*-_";
        var data = RandomNumberGenerator.GetBytes(length);
        var sb = new StringBuilder(length);
        foreach (var b in data) sb.Append(chars[b % chars.Length]);
        return sb.ToString();
    }

    private static string EscapeArg(string value) => value.Replace("\"", "\\\"");

    private void SetStatus(string message)
    {
        lblStatus.Text = message;
        lblStatus.ForeColor = Color.FromArgb(170, 176, 182);
    }

    private void Error(string message)
    {
        SetStatus("Error: " + message);
        MessageBox.Show(this, message, "Ronin Remote Link", MessageBoxButtons.OK, MessageBoxIcon.Error);
    }
}
