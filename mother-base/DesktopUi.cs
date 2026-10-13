using System.Globalization;
using System.Text.Json.Nodes;
namespace TRL.MotherBase;
sealed partial class MainWindow {
 static readonly Color ThemeBackground=Color.FromArgb(16,19,25),ThemePanel=Color.FromArgb(24,28,36),ThemeInput=Color.FromArgb(31,36,46),ThemeText=Color.FromArgb(235,238,244),ThemeMuted=Color.FromArgb(154,165,181),ThemeRed=Color.FromArgb(216,38,61),ThemeLine=Color.FromArgb(43,50,62);
 readonly Panel contentHost=new(){Dock=DockStyle.Fill,Padding=new Padding(28,20,28,20)};
 readonly Label pageTitle=new(){AutoSize=true,Font=new Font("Segoe UI Semibold",24),ForeColor=ThemeText};
 readonly Label pageDescription=new(){AutoSize=true,ForeColor=ThemeMuted};
 readonly Dictionary<string,Panel> pages=new();
 readonly Dictionary<string,Button> navigation=new();
 readonly Dictionary<string,Label> serviceStates=new();
 readonly Dictionary<string,Label> metricValues=new();
 readonly Dictionary<string,Label> patrolStates=new();
 readonly TextBox vehicleSearch=new(){Width=330,PlaceholderText="Search registration, vehicle or address"};
 readonly Label vehicleCount=new(){AutoSize=true,ForeColor=ThemeMuted,Padding=new Padding(8)};
 readonly Label checkpointState=new(){AutoSize=true,Text="Load the cloud configuration to begin",ForeColor=ThemeMuted,Padding=new Padding(8)};
 readonly TextBox vehicleDetails=DetailsBox(),historyDetails=DetailsBox(),reviewDetails=DetailsBox(),patrolDetails=DetailsBox();
 bool constructing=true;
 static TextBox DetailsBox()=>new(){Multiline=true,ReadOnly=true,Dock=DockStyle.Fill,ScrollBars=ScrollBars.Vertical,BorderStyle=BorderStyle.None,BackColor=ThemePanel,ForeColor=ThemeText,Font=new Font("Segoe UI",10)};
 static Button ActionButton(string title)=>new(){Text=title,AutoSize=true,MinimumSize=new Size(110,38),FlatStyle=FlatStyle.Flat,BackColor=ThemeInput,ForeColor=ThemeText,Padding=new Padding(12,5,12,5),Margin=new Padding(0,0,9,8),Cursor=Cursors.Hand};
 void BuildInterface(){
  var root=new TableLayoutPanel(){Dock=DockStyle.Fill,ColumnCount=2,RowCount=1,BackColor=ThemeBackground,Margin=Padding.Empty};
  root.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute,224));root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));Controls.Add(root);
  var sidebar=new Panel(){Dock=DockStyle.Fill,BackColor=Color.FromArgb(12,15,20),Padding=new Padding(20,26,14,20),Margin=Padding.Empty};root.Controls.Add(sidebar,0,0);
  var brand=new Label(){Text="TRL",Dock=DockStyle.Top,Height=58,Font=new Font("Segoe UI",30,FontStyle.Bold),ForeColor=ThemeText};
  var brandCaption=new Label(){Text="MOTHER BASE",Dock=DockStyle.Top,Height=24,Font=new Font("Segoe UI Semibold",11),ForeColor=ThemeMuted};
  var accent=new Panel(){Dock=DockStyle.Top,Height=3,BackColor=ThemeRed,Margin=new Padding(0,12,0,22)};
  var links=new FlowLayoutPanel(){Dock=DockStyle.Fill,FlowDirection=FlowDirection.TopDown,WrapContents=false,Padding=new Padding(0,26,0,0)};
  sidebar.Controls.Add(links);sidebar.Controls.Add(accent);sidebar.Controls.Add(brandCaption);sidebar.Controls.Add(brand);
  sidebar.Controls.Add(new Label(){Text="THIN RED LINE SECURITY\r\nv0.3.0  •  Windows",Dock=DockStyle.Bottom,Height=52,ForeColor=ThemeMuted,Font=new Font("Segoe UI",8.5f)});
  var right=new Panel(){Dock=DockStyle.Fill,Margin=Padding.Empty};root.Controls.Add(right,1,0);
  var heading=new TableLayoutPanel(){Dock=DockStyle.Top,Height=118,ColumnCount=2,Padding=new Padding(28,20,28,12),BackColor=ThemeBackground};
  heading.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));heading.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute,140));
  var titles=new FlowLayoutPanel(){Dock=DockStyle.Fill,FlowDirection=FlowDirection.TopDown,WrapContents=false};titles.Controls.Add(pageTitle);titles.Controls.Add(pageDescription);heading.Controls.Add(titles,0,0);
  var refresh=Button("Refresh now",async()=>await RefreshCloud());refresh.Anchor=AnchorStyles.Right;refresh.BackColor=ThemeRed;refresh.FlatAppearance.BorderSize=0;heading.Controls.Add(refresh,1,0);
  status.Dock=DockStyle.Bottom;status.Height=42;status.AutoSize=false;status.Padding=new Padding(22,11,10,8);status.BackColor=ThemePanel;status.ForeColor=ThemeMuted;status.Font=new Font("Segoe UI",8.5f);
  right.Controls.Add(contentHost);right.Controls.Add(status);right.Controls.Add(heading);
  AddPage(links,"Overview","Operations at a glance",BuildOverview());
  AddPage(links,"Patrol fleet","Duty, last contact and GPS for Patrol 1 / 2 / 3",BuildPatrolPage());
  Columns(activity,("guard","Guard"),("property","Property"),("issue","Issue"),("date","Reported"));
  AddPage(links,"SilverTrack activity","Reports received from the authorised Master collector",activity);
  Columns(vehicles,("plate","Registration"),("vehicle","Vehicle"),("colour","Colour"),("address","Address"),("warning","Warning"),("make","Make"),("model","Model"),("revision","Revision"));
  vehicles.Columns["make"]!.Visible=false;vehicles.Columns["model"]!.Visible=false;vehicles.Columns["revision"]!.Visible=false;
  vehicles.Columns["plate"]!.FillWeight=70;vehicles.Columns["address"]!.FillWeight=160;vehicles.Columns["warning"]!.FillWeight=65;
  vehicles.SelectionChanged+=(_,_)=>ShowDetails(vehicles,vehicleDetails,"Select a vehicle to see its details.");
  var vehicleTools=Toolbar();vehicleTools.Controls.Add(vehicleSearch);vehicleTools.Controls.Add(vehicleCount);
  vehicleSearch.TextChanged+=(_,_)=>{if(!constructing)FilterVehicles();};
  var vehicleActions=Toolbar();
  vehicleActions.Controls.Add(Button("Add vehicle",async()=>await EditVehicle(true)));
  vehicleActions.Controls.Add(Button("Edit vehicle",async()=>await EditVehicle(false)));
  vehicleActions.Controls.Add(Button("Delete vehicle",async()=>await DeleteVehicle()));
  var pairing=Button("Pair Breach device",async()=>await PairDevice());pairing.BackColor=ThemeRed;pairing.FlatAppearance.BorderSize=0;vehicleActions.Controls.Add(pairing);
  AddPage(links,"Vehicles","One register for paired Breach devices and reviewed SilverTrack imports",GridPage(vehicles,vehicleDetails,vehicleActions,vehicleTools));
  Columns(history,("plate","Registration"),("warning","Warning"),("property","Property"),("time","Reported"),("revision","Revision"));
  history.SelectionChanged+=(_,_)=>ShowDetails(history,historyDetails,"Select a breach to see the original report fields and revision.");
  AddPage(links,"Breach history","Source reports retained by registration, with revisions and duplicate protection",GridPage(history,historyDetails));
  Columns(review,("plate","Registration"),("reason","Needs attention"),("received","Received"));
  review.Columns["reason"]!.FillWeight=230;
  review.SelectionChanged+=(_,_)=>ShowDetails(review,reviewDetails,"Incomplete imports appear here. Review the report, then correct the matching vehicle in Vehicles.");
  AddPage(links,"Import review","Check incomplete details before they become native Breach records",GridPage(review,reviewDetails));
  Columns(checkpoints,("id","ID"),("name","Checkpoint"),("group","Group"),("order","Route order"),("lat","Latitude"),("lon","Longitude"),("radius","Distance (m)"),("photoBase64","QR photograph"));
  checkpoints.Columns["photoBase64"]!.Visible=false;checkpoints.Columns["id"]!.Visible=false;
  checkpoints.Columns.Add("photoStatus","QR photo");checkpoints.Columns["photoStatus"]!.ReadOnly=true;
  checkpoints.CellFormatting+=(_,e)=>{if(e.RowIndex>=0&&checkpoints.Columns[e.ColumnIndex].Name=="photoStatus"){e.Value=string.IsNullOrEmpty(Convert.ToString(checkpoints.Rows[e.RowIndex].Cells["photoBase64"].Value))?"—":"Attached";e.FormattingApplied=true;}};checkpoints.Columns["name"]!.FillWeight=150;
  var cpTools=Toolbar();
  foreach(var item in new (string,Func<Task>)[]{("Load cloud checkpoints",LoadCheckpoints),("Attach QR photo",AttachPhoto),("Export checkpoint backup",ExportCheckpoints),("Import checkpoint backup",ImportCheckpoints),("Publish checkpoint configuration",PublishCheckpoints)})cpTools.Controls.Add(Button(item.Item1,item.Item2));
  var cp=new Panel(){Dock=DockStyle.Fill};cp.Controls.Add(checkpoints);cp.Controls.Add(checkpointState);checkpointState.Dock=DockStyle.Bottom;cp.Controls.Add(cpTools);
  AddPage(links,"Checkpoints","Configure names, groups, route order, GPS, scan distance and QR photos",cp);
  AddPage(links,"Settings","Secure cloud connections saved for this Windows account",BuildSettings());
  StyleControl(this);constructing=false;Navigate("Overview");
 }
 static FlowLayoutPanel Toolbar()=>new(){Dock=DockStyle.Top,AutoSize=true,WrapContents=true,Padding=new Padding(0,0,0,8),BackColor=ThemeBackground};
 static void Columns(DataGridView grid,params (string key,string title)[] columns){foreach(var c in columns)grid.Columns.Add(c.key,c.title);}
 static Panel GridPage(DataGridView grid,TextBox details,params Control[] tools){
  var body=new Panel(){Dock=DockStyle.Fill};
  var split=new SplitContainer(){Dock=DockStyle.Fill,Orientation=Orientation.Horizontal,Height=580,SplitterWidth=10,BackColor=ThemeBackground,Panel1MinSize=140,Panel2MinSize=100,SplitterDistance=380};
  split.Panel1.Controls.Add(grid);
  var footer=new Panel(){Dock=DockStyle.Fill,BackColor=ThemePanel,Padding=new Padding(18,12,18,12)};footer.Controls.Add(details);split.Panel2.Controls.Add(footer);body.Controls.Add(split);
  foreach(var tool in tools)body.Controls.Add(tool);
  return body;
 }
 void AddPage(FlowLayoutPanel links,string title,string description,Control body){
  var page=new Panel(){Dock=DockStyle.Fill,Visible=false};page.Controls.Add(body);pages[title]=page;contentHost.Controls.Add(page);
  var button=ActionButton(title);button.Width=188;button.Height=44;button.AutoSize=false;button.TextAlign=ContentAlignment.MiddleLeft;button.Padding=new Padding(16,0,0,0);button.FlatAppearance.BorderSize=0;button.BackColor=Color.FromArgb(12,15,20);button.ForeColor=ThemeMuted;button.Tag=description;button.Click+=(_,_)=>Navigate(title);navigation[title]=button;links.Controls.Add(button);
 }
 void Navigate(string title){
  foreach(var p in pages)p.Value.Visible=p.Key==title;
  foreach(var p in navigation){p.Value.BackColor=p.Key==title?ThemeRed:Color.FromArgb(12,15,20);p.Value.ForeColor=p.Key==title?Color.White:ThemeMuted;}
  pageTitle.Text=title;pageDescription.Text=navigation[title].Tag?.ToString()??"";pages[title].BringToFront();
 }
 Panel BuildOverview(){
  var panel=new Panel(){Dock=DockStyle.Fill,AutoScroll=true};
  var intro=new Label(){Text="Your cloud remains available while this PC is closed.",AutoSize=true,ForeColor=ThemeMuted,Location=new Point(0,0)};panel.Controls.Add(intro);
  var cards=new TableLayoutPanel(){Location=new Point(0,48),Width=920,Height=140,ColumnCount=4,Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right};
  foreach(var key in new[]{"Vehicles","Breach history","Import review","Checkpoints"}){
   cards.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,25));var card=new Panel(){Dock=DockStyle.Fill,BackColor=ThemePanel,Margin=new Padding(0,0,14,0),Padding=new Padding(18)};
   var name=new Label(){Text=key.ToUpperInvariant(),Dock=DockStyle.Top,Height=30,ForeColor=ThemeMuted,Font=new Font("Segoe UI Semibold",9)};
   var count=new Label(){Text="—",Dock=DockStyle.Fill,Font=new Font("Segoe UI Semibold",30),ForeColor=ThemeText};metricValues[key]=count;card.Controls.Add(count);card.Controls.Add(name);cards.Controls.Add(card);
  }panel.Controls.Add(cards);
  var connections=new FlowLayoutPanel(){Location=new Point(0,226),Width=920,Height=310,FlowDirection=FlowDirection.TopDown,WrapContents=false,Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right};
  connections.Controls.Add(new Label(){Text="Cloud connections",Font=new Font("Segoe UI Semibold",15),AutoSize=true,Margin=new Padding(0,0,0,16)});
  foreach(var key in new[]{"Activity","Vehicles","Breach history","Import review","Patrols"}){
   var line=new Panel(){Width=890,Height=43,BackColor=ThemePanel,Margin=new Padding(0,0,0,8)};
   line.Controls.Add(new Label(){Text=key=="Activity"?"SilverTrack Master feed":key=="Patrols"?"Patrol GPS relay":key,Location=new Point(16,10),AutoSize=true});
   var state=new Label(){Text="Not checked",ForeColor=ThemeMuted,Location=new Point(290,10),AutoSize=true};serviceStates[key]=state;line.Controls.Add(state);connections.Controls.Add(line);
  }panel.Controls.Add(connections);
  var next=new Label(){Text="SilverTrack collection runs on the Master phone. Imported reports appear in Vehicles,\r\nBreach history or Import review after the Master publishes them.",Location=new Point(0,570),AutoSize=true,ForeColor=ThemeMuted};panel.Controls.Add(next);
  panel.Resize+=(_,_)=>{cards.Width=Math.Max(620,panel.ClientSize.Width-12);connections.Width=cards.Width;foreach(Control c in connections.Controls)if(c is Panel)c.Width=connections.Width-10;};
  return panel;
 }
 Panel BuildPatrolPage(){
  Columns(patrols,("role","Patrol"),("duty","Duty"),("state","Movement"),("contact","Last contact"),("gps","GPS accuracy"),("version","App version"));
  patrols.SelectionChanged+=(_,_)=>ShowDetails(patrols,patrolDetails,"Select a patrol to inspect the last received device report.");
  var cards=new TableLayoutPanel(){Dock=DockStyle.Top,Height=112,ColumnCount=3};
  foreach(var role in new[]{"P1","P2","P3"}){
   cards.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,33.33f));
   var card=new Panel(){Dock=DockStyle.Fill,BackColor=ThemePanel,Padding=new Padding(18,12,18,12),Margin=new Padding(0,0,12,12)};
   card.Controls.Add(new Label(){Text="PATROL "+role[1],Dock=DockStyle.Top,Height=28,ForeColor=ThemeMuted,Font=new Font("Segoe UI Semibold",10)});
   var state=new Label(){Text="Awaiting relay connection",Dock=DockStyle.Bottom,Height=32,ForeColor=ThemeMuted,Font=new Font("Segoe UI Semibold",12)};patrolStates[role]=state;card.Controls.Add(state);cards.Controls.Add(card);
  }
  return GridPage(patrols,patrolDetails,cards);
 }
 Panel BuildSettings(){
  var body=new Panel(){Dock=DockStyle.Fill,AutoScroll=true};
  var flow=new FlowLayoutPanel(){AutoSize=true,FlowDirection=FlowDirection.TopDown,WrapContents=false,Location=new Point(0,0),Padding=new Padding(0,0,0,12)};
  void Section(string title,string description,params (string,TextBox)[] fields){
   var section=new FlowLayoutPanel(){AutoSize=true,FlowDirection=FlowDirection.TopDown,WrapContents=false,BackColor=ThemePanel,Padding=new Padding(20),Margin=new Padding(0,0,0,16),Width=750};
   section.Controls.Add(new Label(){Text=title,Font=new Font("Segoe UI Semibold",15),AutoSize=true,Margin=new Padding(0,0,0,8)});
   section.Controls.Add(new Label(){Text=description,ForeColor=ThemeMuted,AutoSize=true,MaximumSize=new Size(680,0),Margin=new Padding(0,0,0,18)});
   foreach(var pair in fields){section.Controls.Add(new Label(){Text=pair.Item1,AutoSize=true,ForeColor=ThemeMuted,Margin=new Padding(0,6,0,7)});pair.Item2.Width=680;pair.Item2.Margin=new Padding(0,0,0,12);section.Controls.Add(pair.Item2);}flow.Controls.Add(section);
  }
  Section("SilverTrack Master feed","The Master phone publishes reports. Keep its existing Publisher and Reader keys.",("Master feed URL",feedUrl),("Master feed admin key",masterKey),("Master feed reader key",readerKey));
  Section("TRL Breach","For the consolidated service, use the same URL as the Master feed.",("TRL fleet URL",fleetUrl),("TRL fleet admin key",fleetKey));
  Section("Patrol GPS relay","Connect the existing Patrol Link relay to display device duty and GPS status.",("Patrol relay URL",relayUrl),("Patrol relay key",relayKey));
  flow.Controls.Add(new Label(){Text="Keys are encrypted for your Windows account. SilverTrack login details stay on the Master phone.",AutoSize=true,MaximumSize=new Size(720,0),ForeColor=ThemeMuted,Margin=new Padding(0,0,0,18)});
  var save=Button("Save and connect",async()=>{SaveSettings();await RefreshCloud();});save.BackColor=ThemeRed;save.FlatAppearance.BorderSize=0;flow.Controls.Add(save);body.Controls.Add(flow);return body;
 }
 static void StyleControl(Control control){
  if(control is DataGridView grid){
   grid.BackgroundColor=ThemePanel;grid.BorderStyle=BorderStyle.None;grid.GridColor=ThemeLine;grid.EnableHeadersVisualStyles=false;grid.RowHeadersVisible=false;grid.ColumnHeadersHeight=44;grid.ColumnHeadersHeightSizeMode=DataGridViewColumnHeadersHeightSizeMode.DisableResizing;
   grid.ColumnHeadersDefaultCellStyle=new DataGridViewCellStyle(){BackColor=ThemeInput,ForeColor=ThemeMuted,Font=new Font("Segoe UI Semibold",9),Padding=new Padding(12,0,8,0),SelectionBackColor=ThemeInput,SelectionForeColor=ThemeText};
   grid.DefaultCellStyle=new DataGridViewCellStyle(){BackColor=ThemePanel,ForeColor=ThemeText,Font=new Font("Segoe UI",10),Padding=new Padding(12,5,8,5),SelectionBackColor=Color.FromArgb(72,33,44),SelectionForeColor=Color.White};
   grid.AlternatingRowsDefaultCellStyle.BackColor=Color.FromArgb(27,32,41);grid.RowTemplate.Height=43;grid.CellBorderStyle=DataGridViewCellBorderStyle.SingleHorizontal;grid.AllowUserToResizeRows=false;
  }else if(control is TextBox box){box.BackColor=ThemeInput;box.ForeColor=ThemeText;box.BorderStyle=box.Multiline?BorderStyle.None:BorderStyle.FixedSingle;box.Font=new Font("Segoe UI",11);}
  else if(control is Button button){button.FlatStyle=FlatStyle.Flat;button.FlatAppearance.BorderColor=ThemeLine;button.FlatAppearance.MouseOverBackColor=Color.FromArgb(66,43,52);button.FlatAppearance.MouseDownBackColor=Color.FromArgb(94,39,53);}
  else if(control is Form form){form.BackColor=ThemeBackground;form.ForeColor=ThemeText;form.Font=new Font("Segoe UI",10);}
  else if(control is Panel panel&&panel.BackColor==SystemColors.Control)panel.BackColor=ThemeBackground;
  else if(control is FlowLayoutPanel flow&&flow.BackColor==SystemColors.Control)flow.BackColor=ThemeBackground;
  foreach(Control child in control.Controls)StyleControl(child);
 }
 void SetConnection(string name,bool connected){if(!serviceStates.TryGetValue(name,out var label))return;label.Text=connected?"Connected":"Offline • showing saved data";label.ForeColor=connected?Color.FromArgb(92,212,163):Color.FromArgb(239,184,94);}
 static string Value(JsonNode? node,string path){
  try{foreach(var key in path.Split('.'))node=node is JsonObject?node[key]:null;return node is JsonValue?node.ToString():"";}catch{return "";}
 }
 static string First(JsonNode? node,params string[] paths){foreach(var path in paths){var s=Value(node,path);if(!string.IsNullOrWhiteSpace(s))return s;}return "";}
 static string Warning(string value)=>int.TryParse(value,out var n)?n==0?"No warning":"Warning "+n:"Needs review";
 static string TimeLabel(string value){
  if(long.TryParse(value,out var ms)&&ms>100000000000)try{return DateTimeOffset.FromUnixTimeMilliseconds(ms).ToOffset(TimeSpan.FromHours(10)).ToString("dd MMM yyyy, HH:mm");}catch{}
  if(DateTimeOffset.TryParse(value,CultureInfo.InvariantCulture,DateTimeStyles.None,out var dt)&&value.Contains('T')&&(value.EndsWith("Z")||value.Contains('+')))return dt.ToOffset(TimeSpan.FromHours(10)).ToString("dd MMM yyyy, HH:mm");
  return string.IsNullOrWhiteSpace(value)?"Not supplied":value;
 }
 void FilterVehicles(){
  vehicles.Rows.Clear();var query=vehicleSearch.Text.Trim();
  foreach(var pair in vehicleRecords.OrderBy(x=>x.Key,StringComparer.OrdinalIgnoreCase)){
   var v=pair.Value;var d=v["details"];string vehicle=First(d,"vehicle");if(vehicle.Length==0)vehicle=string.Join(" ",new[]{Value(d,"make"),Value(d,"model")}.Where(x=>x.Length>0));
   var values=new[]{pair.Key,vehicle,Value(d,"colour"),Value(d,"address"),Warning(Value(d,"breach_level")),Value(d,"make"),Value(d,"model"),Value(v,"revision")};
   if(query.Length>0&&!string.Join(" ",values).Contains(query,StringComparison.OrdinalIgnoreCase))continue;
   int i=vehicles.Rows.Add(values.Cast<object>().ToArray());vehicles.Rows[i].Tag=v.DeepClone();
  }
  vehicleCount.Text=vehicles.Rows.Count+" shown / "+vehicleRecords.Count+" vehicles";
  if(metricValues.TryGetValue("Vehicles",out var count))count.Text=vehicleRecords.Count.ToString();
  if(vehicles.Rows.Count==0)vehicleDetails.Text=query.Length>0?"No matching vehicles. Try a registration or address.":"No vehicles received yet. Pair a Breach device or import a reviewed SilverTrack report.";
 }
 void ShowActivity(JsonNode j){
  activity.Rows.Clear();foreach(var r in j["rows"]?.AsArray()??new JsonArray()){if(r==null)continue;activity.Rows.Add(Value(r,"guard"),Value(r,"property"),Value(r,"issue"),First(r,"date","occurredAt"));}
  activity.Columns["issue"]!.FillWeight=220;
 }
 void ShowHistory(JsonNode j){
  history.Rows.Clear();foreach(var r in j["breaches"]?.AsArray()??new JsonArray()){if(r==null)continue;int i=history.Rows.Add(Value(r,"plate"),Warning(Value(r,"event.warningLevel")),Value(r,"event.property"),TimeLabel(First(r,"event.occurredAt","event.sourceDate")),Value(r,"revision"));history.Rows[i].Tag=r.DeepClone();}
  metricValues["Breach history"].Text=history.Rows.Count.ToString();if(history.Rows.Count==0)historyDetails.Text="No SilverTrack breach reports have been imported yet. Collection runs on the authorised Master phone.";
 }
 void ShowReview(JsonNode j){
  review.Rows.Clear();foreach(var r in j["review"]?.AsArray()??new JsonArray()){if(r==null)continue;int i=review.Rows.Add(First(r,"plate","source.vehicle.registration","source.vehicle.plate"),Value(r,"reason"),TimeLabel(Value(r,"received_at")));review.Rows[i].Tag=r.DeepClone();}
  metricValues["Import review"].Text=review.Rows.Count.ToString();if(review.Rows.Count==0)reviewDetails.Text="No imports currently need review.";
 }
 void ShowPatrols(JsonNode j){
  patrols.Rows.Clear();foreach(var r in j["patrols"]?.AsArray()??new JsonArray()){
   if(r==null)continue;string role=Value(r,"patrolId");bool duty=Value(r,"onDuty")=="true";long.TryParse(Value(r,"receivedAt"),out var received);bool recent=received>0&&DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()-received<120000;
   string state=received==0?"No contact yet":recent?(duty?"On duty":"Off duty"):"Last contact is stale";
   int i=patrols.Rows.Add(role,duty?"On duty":"Off duty",Value(r,"state"),received>0?TimeLabel(received.ToString()):"No contact yet",Value(r,"accuracy")+" m",Value(r,"versionName"));patrols.Rows[i].Tag=r.DeepClone();
   if(patrolStates.TryGetValue(role,out var label)){label.Text=state;label.ForeColor=recent?Color.FromArgb(92,212,163):ThemeMuted;}
  }
 }
 static string Friendly(string key)=>key switch{
  "plate"=>"Registration","breach_level"=>"Warning level","last_breach_at"=>"Last warning","source_id"=>"Source report ID","sourceReportId"=>"Source report ID","received_at"=>"Received","occurredAt"=>"Report time","sourceDate"=>"Source date","warningLevel"=>"Warning level","property"=>"Property","parkingPosition"=>"Parking position","receivedAt"=>"Last contact","fixTime"=>"GPS fix","lat"=>"Latitude","lon"=>"Longitude","accuracy"=>"GPS accuracy","onDuty"=>"On duty","versionName"=>"App version",_=>CultureInfo.InvariantCulture.TextInfo.ToTitleCase(key.Replace('_',' '))};
 static void Describe(JsonNode? node,List<string> lines,int depth=0){
  if(node is not JsonObject obj)return;
  foreach(var entry in obj){
   if(entry.Value is JsonObject){lines.Add("");if(entry.Key is not ("details" or "event"))lines.Add(Friendly(entry.Key).ToUpperInvariant());Describe(entry.Value,lines,depth+1);}
   else if(entry.Value is JsonValue){var value=entry.Value.ToString();if(value.Length==0)continue;if(entry.Key is "received_at" or "occurredAt" or "last_breach_at" or "receivedAt" or "fixTime")value=TimeLabel(value);lines.Add(Friendly(entry.Key)+": "+value);}
  }
 }
 static void ShowDetails(DataGridView grid,TextBox box,string empty){
  if(grid.CurrentRow?.Tag is not JsonNode node){box.Text=empty;return;}var lines=new List<string>();Describe(node,lines);box.Text=string.Join("\r\n",lines);
 }
 void ShowPairingCode(string code){
  using var dialog=new Form(){Text="Pair a Breach device",Width=720,Height=350,StartPosition=FormStartPosition.CenterParent,FormBorderStyle=FormBorderStyle.FixedDialog,MaximizeBox=false,MinimizeBox=false,BackColor=ThemeBackground,ForeColor=ThemeText};
  var body=new FlowLayoutPanel(){Dock=DockStyle.Fill,FlowDirection=FlowDirection.TopDown,WrapContents=false,Padding=new Padding(28)};
  body.Controls.Add(new Label(){Text="Connect your Breach phone",Font=new Font("Segoe UI Semibold",20),AutoSize=true,Margin=new Padding(0,0,0,12)});
  body.Controls.Add(new Label(){Text="Enter this code in Breach → Vehicles → Connect Mother Base.\r\nSingle use • expires in 10 minutes.",AutoSize=true,ForeColor=ThemeMuted,Margin=new Padding(0,0,0,20)});
  var text=new TextBox(){Text=code,ReadOnly=true,Width=635,Font=new Font("Consolas",17),BackColor=ThemeInput,ForeColor=ThemeText,BorderStyle=BorderStyle.FixedSingle};body.Controls.Add(text);
  var row=new FlowLayoutPanel(){AutoSize=true,Margin=new Padding(0,18,0,0)};
  var copy=ActionButton("Copy code");copy.BackColor=ThemeRed;copy.FlatAppearance.BorderSize=0;copy.Click+=(_,_)=>{Clipboard.SetText(code);copy.Text="Copied";};row.Controls.Add(copy);
  var done=ActionButton("Done");done.DialogResult=DialogResult.OK;row.Controls.Add(done);body.Controls.Add(row);dialog.Controls.Add(body);dialog.AcceptButton=done;StyleControl(dialog);text.Font=new Font("Consolas",17);dialog.ShowDialog(this);
 }
}
