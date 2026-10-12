using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
namespace TRL.MotherBase;
static class Program {
 [STAThread] static void Main(string[] args) {
  ApplicationConfiguration.Initialize();
  if(args.Contains("--smoke-test")) { using var f=new MainWindow(); f.CreateControl(); return; }
  Application.Run(new MainWindow());
 }
}
sealed class SecureStore {
 readonly string dir=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"TRL","MotherBase");
 public SecureStore(){Directory.CreateDirectory(dir);}
 public string Read(string name) {try {return Encoding.UTF8.GetString(ProtectedData.Unprotect(File.ReadAllBytes(Path.Combine(dir,name)),null,DataProtectionScope.CurrentUser));} catch {return "";}}
 public void Write(string name,string value) {
  string path=Path.Combine(dir,name),temp=path+".tmp";
  File.WriteAllBytes(temp,ProtectedData.Protect(Encoding.UTF8.GetBytes(value),null,DataProtectionScope.CurrentUser));File.Move(temp,path,true);
 }
}
sealed class MainWindow:Form {
 readonly SecureStore store=new();
 readonly HttpClient http=new(new HttpClientHandler{AllowAutoRedirect=false}){Timeout=TimeSpan.FromSeconds(45)};
 readonly TextBox feedUrl=new(){Text="https://ronin-silvertrack-master-feed.onrender.com",Width=510};
 readonly TextBox fleetUrl=new(){Text="https://ronin-trl-secure-fleet.onrender.com",Width=510};
 readonly TextBox relayUrl=new(){Text="https://ronin-patrol-link-relay.onrender.com",Width=510};
 readonly TextBox masterKey=new(){UseSystemPasswordChar=true,Width=510};
 readonly TextBox readerKey=new(){UseSystemPasswordChar=true,Width=510};
 readonly TextBox fleetKey=new(){UseSystemPasswordChar=true,Width=510};
 readonly TextBox relayKey=new(){UseSystemPasswordChar=true,Width=510};
 readonly Label status=new(){Text="Configure your relay connection in Settings.",AutoSize=true,Padding=new Padding(12)};
 readonly DataGridView checkpoints=Grid(false),vehicles=Grid(true);
 readonly TextBox activity=Report(),patrols=Report(),history=Report(),review=Report();
 readonly Dictionary<string,JsonNode> vehicleRecords=new();
 readonly System.Windows.Forms.Timer poll=new(){Interval=30000};
 long checkpointRevision=-1;bool busy=false;
 static TextBox Report()=>new(){Multiline=true,ReadOnly=true,Dock=DockStyle.Fill,ScrollBars=ScrollBars.Both,Font=new Font("Consolas",10)};
 static DataGridView Grid(bool readOnly)=>new(){Dock=DockStyle.Fill,ReadOnly=readOnly,AllowUserToAddRows=!readOnly,AllowUserToDeleteRows=!readOnly,AutoSizeColumnsMode=DataGridViewAutoSizeColumnsMode.Fill,SelectionMode=DataGridViewSelectionMode.FullRowSelect,MultiSelect=false};
 public MainWindow(){
  Text="TRL Mother Base";Width=1250;Height=820;MinimumSize=new Size(900,600);
  var tabs=new TabControl(){Dock=DockStyle.Fill};
  Controls.Add(tabs);Controls.Add(status);status.Dock=DockStyle.Bottom;
  AddTab(tabs,"Patrol 1 / 2 / 3",patrols);AddTab(tabs,"SilverTrack activity",activity);
  vehicles.Columns.Add("plate","Registration");vehicles.Columns.Add("make","Make");vehicles.Columns.Add("model","Model");vehicles.Columns.Add("colour","Colour");vehicles.Columns.Add("revision","Revision");vehicles.Columns.Add("vehicle","Vehicle");vehicles.Columns.Add("address","Address");vehicles.Columns.Add("warning","Warning level");
  var vehiclePanel=new Panel(){Dock=DockStyle.Fill};vehiclePanel.Controls.Add(vehicles);
  var vehicleButtons=new FlowLayoutPanel(){Dock=DockStyle.Top,AutoSize=true};
  vehicleButtons.Controls.Add(Button("Add vehicle",async()=>await EditVehicle(true)));
  vehicleButtons.Controls.Add(Button("Edit selected vehicle",async()=>await EditVehicle(false)));
  vehicleButtons.Controls.Add(Button("Delete selected vehicle",async()=>await DeleteVehicle()));
  vehicleButtons.Controls.Add(Button("Pair Breach device",async()=>await PairDevice()));
  vehiclePanel.Controls.Add(vehicleButtons);
  AddTab(tabs,"Vehicles",vehiclePanel);AddTab(tabs,"Breach history",history);AddTab(tabs,"Import review",review);
  foreach(string n in new[]{"id","name","group","order","lat","lon","radius","photoBase64"}) checkpoints.Columns.Add(n,n switch{"radius"=>"Scan distance (m)","photoBase64"=>"QR photo (base64)",_=>n});
  checkpoints.Columns["photoBase64"]!.Visible=false;
  var cpPanel=new Panel(){Dock=DockStyle.Fill};cpPanel.Controls.Add(checkpoints);
  var cpButtons=new FlowLayoutPanel(){Dock=DockStyle.Top,AutoSize=true};
  cpButtons.Controls.Add(Button("Load cloud checkpoints",async()=>await LoadCheckpoints()));
  cpButtons.Controls.Add(Button("Attach QR photo",AttachPhoto));
  cpButtons.Controls.Add(Button("Export checkpoint backup",ExportCheckpoints));
  cpButtons.Controls.Add(Button("Import checkpoint backup",ImportCheckpoints));
  cpButtons.Controls.Add(Button("Publish checkpoint configuration",async()=>await PublishCheckpoints()));
  cpPanel.Controls.Add(cpButtons);AddTab(tabs,"Checkpoints",cpPanel);
  var settings=new FlowLayoutPanel(){Dock=DockStyle.Fill,FlowDirection=FlowDirection.TopDown,AutoScroll=true,WrapContents=false,Padding=new Padding(20)};
  foreach(var pair in new (string,TextBox)[]{("Master feed URL",feedUrl),("Master feed admin key",masterKey),("Master feed reader key (checkpoint migration)",readerKey),("TRL fleet URL",fleetUrl),("TRL fleet admin key",fleetKey),("Patrol relay URL",relayUrl),("Patrol relay key",relayKey)}){
   settings.Controls.Add(new Label(){Text=pair.Item1,AutoSize=true});settings.Controls.Add(pair.Item2);
  }
  settings.Controls.Add(new Label(){Text="Use relay keys only. SilverTrack login credentials stay on the authorised Master collector.",AutoSize=true,MaximumSize=new Size(650,0)});
  settings.Controls.Add(Button("Save and connect",async()=>{SaveSettings();await RefreshCloud();}));
  settings.Controls.Add(Button("Refresh now",async()=>await RefreshCloud()));
  AddTab(tabs,"Settings",settings);
  LoadSettings();LoadCache();
  poll.Tick+=async(_,_)=>await RefreshCloud();Shown+=async(_,_)=>{await RefreshCloud();poll.Start();};
 }
 static void AddTab(TabControl tabs,string title,Control body){var tab=new TabPage(title);tab.Controls.Add(body);tabs.TabPages.Add(tab);}
 Button Button(string title,Func<Task> action){var b=new Button(){Text=title,AutoSize=true,Margin=new Padding(6)};b.Click+=async(_,_)=>{b.Enabled=false;try{await action();}catch(Exception e){MessageBox.Show(this,e.Message,"TRL Mother Base",MessageBoxButtons.OK,MessageBoxIcon.Information);}finally{b.Enabled=true;}};return b;}
 void LoadSettings(){try{var j=JsonNode.Parse(store.Read("settings.dpapi"));if(j==null)return;feedUrl.Text=j["feedUrl"]?.ToString()??feedUrl.Text;fleetUrl.Text=j["fleetUrl"]?.ToString()??fleetUrl.Text;relayUrl.Text=j["relayUrl"]?.ToString()??relayUrl.Text;masterKey.Text=j["masterKey"]?.ToString()??"";fleetKey.Text=j["fleetKey"]?.ToString()??"";readerKey.Text=j["readerKey"]?.ToString()??"";relayKey.Text=j["relayKey"]?.ToString()??"";}catch{}}
 void SaveSettings(){foreach(var url in new[]{feedUrl.Text,fleetUrl.Text,relayUrl.Text})ValidateUrl(url);store.Write("settings.dpapi",new JsonObject{["feedUrl"]=feedUrl.Text,["fleetUrl"]=fleetUrl.Text,["relayUrl"]=relayUrl.Text,["masterKey"]=masterKey.Text,["fleetKey"]=fleetKey.Text,["readerKey"]=readerKey.Text,["relayKey"]=relayKey.Text}.ToJsonString());}
 static Uri ValidateUrl(string url){if(!Uri.TryCreate(url.Trim(),UriKind.Absolute,out var uri)||uri.Scheme!="https"||!string.IsNullOrEmpty(uri.UserInfo)||uri.AbsolutePath!="/"||!string.IsNullOrEmpty(uri.Query)||!string.IsNullOrEmpty(uri.Fragment))throw new Exception("Use an HTTPS service root URL, without a path, query or credentials.");return uri;}
 async Task<JsonNode> Request(string root,string path,string header,string key,HttpMethod? method=null,JsonNode? data=null){
  if(string.IsNullOrWhiteSpace(key))throw new Exception("Configure the relay key in Settings first.");
  using var req=new HttpRequestMessage(method??HttpMethod.Get,new Uri(ValidateUrl(root),path));
  req.Headers.Add(header,key.Trim());if(data!=null)req.Content=new StringContent(data.ToJsonString(),Encoding.UTF8,"application/json");
  using var response=await http.SendAsync(req);
  if(!response.IsSuccessStatusCode)throw new Exception(response.StatusCode==System.Net.HttpStatusCode.Conflict?"Cloud configuration changed. Load the latest checkpoint set before publishing.":"Relay returned HTTP "+(int)response.StatusCode+". Check setup and pairing.");
  string text=await response.Content.ReadAsStringAsync();return JsonNode.Parse(text)??throw new Exception("Relay returned an empty response.");
 }
 async Task RefreshCloud(){
  if(busy)return;busy=true;var messages=new List<string>();
  try{
   await RefreshOne("activity.dpapi",async()=>await Request(feedUrl.Text,"/v1/admin/activity","x-ronin-master-key",masterKey.Text),j=>activity.Text=Pretty(j),messages,"Activity");
   await RefreshOne("vehicles.dpapi",async()=>await Request(fleetUrl.Text,"/v1/vehicles","x-trl-master-key",fleetKey.Text),ShowVehicles,messages,"Vehicles");
   await RefreshOne("history.dpapi",async()=>await Request(fleetUrl.Text,"/v1/breaches","x-trl-master-key",fleetKey.Text),j=>history.Text=Pretty(j),messages,"Breach history");
   await RefreshOne("review.dpapi",async()=>await Request(fleetUrl.Text,"/v1/import-review","x-trl-master-key",fleetKey.Text),j=>review.Text=Pretty(j),messages,"Import review");
   await RefreshOne("patrols.dpapi",async()=>await Request(relayUrl.Text,"/api/fleet","X-Ronin-Key",relayKey.Text),j=>patrols.Text=Pretty(j),messages,"Patrols");
   status.Text=string.Join(" | ",messages)+" — "+DateTime.Now.ToString("g");
  }finally{busy=false;}
 }
 async Task RefreshOne(string name,Func<Task<JsonNode>> load,Action<JsonNode> show,List<string> messages,string label){try{var j=await load();store.Write(name,j.ToJsonString());show(j);messages.Add(label+": connected");}catch(Exception){messages.Add(label+": offline / cached");}}
 static string Pretty(JsonNode j)=>j.ToJsonString(new System.Text.Json.JsonSerializerOptions{WriteIndented=true});
 void LoadCache(){foreach(var p in new (string,Action<JsonNode>)[]{("activity.dpapi",j=>activity.Text="CACHED DATA\r\n"+Pretty(j)),("vehicles.dpapi",ShowVehicles),("history.dpapi",j=>history.Text="CACHED DATA\r\n"+Pretty(j)),("review.dpapi",j=>review.Text="CACHED DATA\r\n"+Pretty(j)),("patrols.dpapi",j=>patrols.Text="CACHED DATA\r\n"+Pretty(j))})try{var j=JsonNode.Parse(store.Read(p.Item1));if(j!=null)p.Item2(j);}catch{}}
 void ShowVehicles(JsonNode j){vehicles.Rows.Clear();vehicleRecords.Clear();foreach(var v in j["vehicles"]?.AsArray()??new JsonArray()){if(v==null)continue;vehicleRecords[v["plate"]!.ToString()]=v.DeepClone();var d=v["details"];vehicles.Rows.Add(v["plate"]?.ToString(),d?["make"]?.ToString(),d?["model"]?.ToString(),d?["colour"]?.ToString(),v["revision"]?.ToString(),d?["vehicle"]?.ToString()??string.Join(" ",new[]{d?["make"]?.ToString(),d?["model"]?.ToString()}.Where(x=>!string.IsNullOrWhiteSpace(x))),d?["address"]?.ToString(),d?["breach_level"]?.ToString());}}
 async Task LoadCheckpoints(){
  JsonNode j;try{j=await Request(feedUrl.Text,"/v1/admin/checkpoints","x-ronin-master-key",masterKey.Text);}
  catch{if(string.IsNullOrWhiteSpace(readerKey.Text))throw;j=await Request(feedUrl.Text,"/v1/checkpoints","x-ronin-reader-key",readerKey.Text);}
  FillCheckpoints(j);checkpointRevision=j["generation"]?.GetValue<long>()??0;
  status.Text="Loaded checkpoint revision "+checkpointRevision+".";
 }
 void FillCheckpoints(JsonNode j){checkpoints.Rows.Clear();foreach(var c in j["checkpoints"]?.AsArray()??new JsonArray()){if(c==null)continue;checkpoints.Rows.Add(new[]{"id","name","group","order","lat","lon","radius","photoBase64"}.Select(k=>(object?)(c[k]?.ToString()??"")).ToArray());}}
 JsonArray CheckpointRows(){
  checkpoints.EndEdit();var rows=new JsonArray();
  foreach(DataGridViewRow r in checkpoints.Rows){if(r.IsNewRow)continue;var j=new JsonObject();
   foreach(string k in new[]{"id","name","group","photoBase64"})j[k]=Convert.ToString(r.Cells[k].Value)??"";
   foreach(string k in new[]{"order","lat","lon","radius"}){if(!double.TryParse(Convert.ToString(r.Cells[k].Value),System.Globalization.NumberStyles.Float,System.Globalization.CultureInfo.InvariantCulture,out var n)||!double.IsFinite(n))throw new Exception("Enter a valid number for "+k+". Use a dot for decimals.");j[k]=n;}
   rows.Add(j);
  }return rows;
 }
 Task ExportCheckpoints(){using var dialog=new SaveFileDialog(){Filter="Checkpoint backup|*.json",FileName="TRL-checkpoints-backup.json"};if(dialog.ShowDialog(this)==DialogResult.OK)File.WriteAllText(dialog.FileName,Pretty(new JsonObject{["checkpoints"]=CheckpointRows()}));return Task.CompletedTask;}
 Task ImportCheckpoints(){if(checkpointRevision<0)throw new Exception("Load the cloud checkpoint set first, then import the backup.");using var dialog=new OpenFileDialog(){Filter="Checkpoint backup|*.json"};if(dialog.ShowDialog(this)==DialogResult.OK){var file=new FileInfo(dialog.FileName);if(file.Length>12500000)throw new Exception("Backup exceeds the checkpoint payload limit.");var data=JsonNode.Parse(File.ReadAllText(dialog.FileName));if(data?["checkpoints"] is not JsonArray)throw new Exception("Select a Mother Base checkpoint backup.");FillCheckpoints(data);status.Text="Backup loaded into the editor. Publish to save it to the cloud.";}return Task.CompletedTask;}
 Task AttachPhoto(){if(checkpoints.CurrentRow==null||checkpoints.CurrentRow.IsNewRow)throw new Exception("Select a checkpoint row first.");using var dialog=new OpenFileDialog(){Filter="QR photographs|*.jpg;*.jpeg;*.png"};if(dialog.ShowDialog(this)==DialogResult.OK){var bytes=File.ReadAllBytes(dialog.FileName);if(bytes.Length>650000)throw new Exception("Choose a QR photograph smaller than 650 KB.");using var stream=new MemoryStream(bytes);using var image=Image.FromStream(stream);checkpoints.CurrentRow.Cells["photoBase64"].Value=Convert.ToBase64String(bytes);}return Task.CompletedTask;}
 async Task PublishCheckpoints(){
  if(checkpointRevision<0)throw new Exception("Load the cloud checkpoint set before editing.");
  var rows=CheckpointRows();
  var answer=await Request(feedUrl.Text,"/v1/admin/checkpoints","x-ronin-master-key",masterKey.Text,HttpMethod.Put,new JsonObject{["generation"]=checkpointRevision,["checkpoints"]=rows});
  checkpointRevision=answer["generation"]?.GetValue<long>()??checkpointRevision;
  status.Text="Checkpoint revision "+checkpointRevision+" published. Phones sync from the cloud while this PC is offline.";
 }

 string SelectedPlate(){if(vehicles.CurrentRow==null)throw new Exception("Select a vehicle first.");return Convert.ToString(vehicles.CurrentRow.Cells["plate"].Value)??throw new Exception("Select a vehicle first.");}
 Dictionary<string,string>? PromptFields(string title,Dictionary<string,string> values,bool registrationReadOnly=false){
  using var dialog=new Form(){Text=title,Width=560,Height=570,StartPosition=FormStartPosition.CenterParent,FormBorderStyle=FormBorderStyle.FixedDialog,MaximizeBox=false,MinimizeBox=false};
  var panel=new FlowLayoutPanel(){Dock=DockStyle.Fill,FlowDirection=FlowDirection.TopDown,WrapContents=false,AutoScroll=true,Padding=new Padding(15)};
  var fields=new Dictionary<string,TextBox>();
  foreach(var p in values){panel.Controls.Add(new Label(){Text=p.Key,AutoSize=true});var box=new TextBox(){Text=p.Value,Width=480,ReadOnly=registrationReadOnly&&p.Key=="Registration"};fields[p.Key]=box;panel.Controls.Add(box);}
  var save=new Button(){Text="Save",AutoSize=true,DialogResult=DialogResult.OK};panel.Controls.Add(save);dialog.AcceptButton=save;dialog.Controls.Add(panel);
  return dialog.ShowDialog(this)==DialogResult.OK?fields.ToDictionary(x=>x.Key,x=>x.Value.Text.Trim()):null;
 }
 async Task EditVehicle(bool add){
  string plate=add?"":SelectedPlate();JsonNode? old=add?null:vehicleRecords[plate];var details=old?["details"]?.DeepClone()??new JsonObject();
  var values=PromptFields(add?"Add vehicle":"Correct vehicle",new(){{"Registration",plate},{"Make",details["make"]?.ToString()??""},{"Model",details["model"]?.ToString()??""},{"Colour",details["colour"]?.ToString()??""},{"Description",details["description"]?.ToString()??""},{"Vehicle",details["vehicle"]?.ToString()??""},{"Address",details["address"]?.ToString()??""},{"Relation (at/on/across from/in front of)",details["relation"]?.ToString()??"at"},{"Parking (common/visitor)",details["parking"]?.ToString()??""},{"Correction reason",""}},!add);
  if(values==null)return;if(!add&&values["Correction reason"].Length<10)throw new Exception("Provide a correction reason of at least 10 characters.");
  foreach(var p in new[]{("Make","make"),("Model","model"),("Colour","colour"),("Description","description"),("Vehicle","vehicle"),("Address","address"),("Relation (at/on/across from/in front of)","relation"),("Parking (common/visitor)","parking")})details[p.Item2]=values[p.Item1];
  await Request(fleetUrl.Text,"/v1/vehicles","x-trl-master-key",fleetKey.Text,HttpMethod.Put,new JsonObject{["plate"]=values["Registration"],["revision"]=old?["revision"]?.GetValue<long>()??0,["details"]=details,["reason"]=values["Correction reason"]});
  await RefreshCloud();
 }
 async Task DeleteVehicle(){
  string plate=SelectedPlate();var values=PromptFields("Delete "+plate,new(){{"Reason",""}});
  if(values==null)return;if(values["Reason"].Length<10)throw new Exception("Provide a deletion reason of at least 10 characters.");
  await Request(fleetUrl.Text,"/v1/vehicles/delete","x-trl-master-key",fleetKey.Text,HttpMethod.Post,new JsonObject{["plate"]=plate,["reason"]=values["Reason"]});await RefreshCloud();
 }
 async Task PairDevice(){
  var values=PromptFields("Create Breach device pairing code",new(){{"Patrol role (P1, P2 or P3)","P1"}});
  if(values==null)return;string role=values["Patrol role (P1, P2 or P3)"].ToUpperInvariant();
  if(!new[]{"P1","P2","P3"}.Contains(role))throw new Exception("Choose P1, P2 or P3.");
  var answer=await Request(fleetUrl.Text,"/v1/invite","x-trl-master-key",fleetKey.Text,HttpMethod.Post,new JsonObject{["role"]=role});
  MessageBox.Show(this,"Pairing code (expires in 10 minutes):\r\n\r\n"+answer["code"]?.ToString(),"Breach device pairing");
 }

 protected override void Dispose(bool disposing){if(disposing){poll.Dispose();http.Dispose();}base.Dispose(disposing);}
}
