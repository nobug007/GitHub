using System;
using System.IO;
using System.Linq;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;
using System.Xml.Serialization;
using Microsoft.Win32;
using System.Collections.Generic;

public class Settings {
 public string[] Devices = new string[3];
 public string[] Paths = new string[3];
 public int[] Modes = new int[] {0,1,0};
}
public class ImageView : Control {
 public Image Photo; public bool Fill;
 public ImageView() { DoubleBuffered=true; BackColor=Color.FromArgb(15,20,25); }
 protected override void OnPaint(PaintEventArgs e) {
  base.OnPaint(e); if(Photo==null) return;
  float sx=(float)Width/Photo.Width, sy=(float)Height/Photo.Height;
  float scale=Fill?Math.Max(sx,sy):Math.Min(sx,sy);
  float w=Photo.Width*scale,h=Photo.Height*scale;
  e.Graphics.InterpolationMode=InterpolationMode.HighQualityBicubic;
  e.Graphics.DrawImage(Photo,(Width-w)/2,(Height-h)/2,w,h);
 }
}
public class Output : Form {
 public ImageView View=new ImageView();
 public Output(string title, Action stop) {
  Text=title; FormBorderStyle=FormBorderStyle.None; StartPosition=FormStartPosition.Manual;
  BackColor=Color.Black; KeyPreview=true; View.Dock=DockStyle.Fill; Controls.Add(View);
  KeyDown+=(s,e)=>{if(e.KeyCode==Keys.Escape) stop();};
  FormClosing+=(s,e)=>{if(e.CloseReason==CloseReason.UserClosing){e.Cancel=true;stop();}};
 }
}
public class MainForm : Form {
 readonly string root=AppDomain.CurrentDomain.BaseDirectory;
 readonly string config=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"ShineMung3Screen","settings.xml");
 readonly ComboBox[] devices=new ComboBox[3], modes=new ComboBox[3];
 readonly ImageView[] thumbs=new ImageView[3];
 readonly Image[] images=new Image[3];
 readonly string[] titles={"강아지 사진","촬영 배경","메인 · 샤인멍"};
 readonly Output[] outputs=new Output[2];
 readonly Panel controls=new Panel();
 readonly ImageView logo=new ImageView();
 readonly Label status=new Label();
 Screen[] screens; Settings settings=new Settings(); bool running; bool testing;
 public MainForm(bool test) {
  AutoScaleDimensions=new SizeF(96,96); AutoScaleMode=AutoScaleMode.Dpi; testing=test; Text="샤인멍 · 3 SCREEN CONTROL"; Font=new Font("맑은 고딕",10);
  BackColor=Color.FromArgb(16,23,30); ForeColor=Color.White; StartPosition=FormStartPosition.CenterScreen;
  ClientSize=new Size(1100,760); MinimumSize=new Size(980,700); KeyPreview=true;
  settings.Paths=new[]{Path.Combine(root,"assets","dog.jpg"),Path.Combine(root,"assets","background.jpg"),Path.Combine(root,"assets","logo.png")};
  MakeLogo(settings.Paths[2]);
  if(!test) try { if(File.Exists(config)) {using(var f=File.OpenRead(config)) settings=(Settings)new XmlSerializer(typeof(Settings)).Deserialize(f);} } catch { }
  if(settings.Paths==null || settings.Paths.Length!=3 || settings.Devices==null || settings.Devices.Length!=3 || settings.Modes==null || settings.Modes.Length!=3) settings=new Settings{Paths=new[]{Path.Combine(root,"assets","dog.jpg"),Path.Combine(root,"assets","background.jpg"),Path.Combine(root,"assets","logo.png")}};
  for(int i=0;i<3;i++) { try {images[i]=ReadImage(settings.Paths[i]);} catch {settings.Paths[i]=Path.Combine(root,"assets",i==0?"dog.jpg":i==1?"background.jpg":"logo.png");images[i]=ReadImage(settings.Paths[i]);} }
  logo.Photo=images[2]; logo.Dock=DockStyle.Fill; Controls.Add(logo);
  controls.Dock=DockStyle.Bottom; controls.Height=370; controls.Padding=new Padding(22); controls.BackColor=Color.FromArgb(25,34,44); Controls.Add(controls);
  var grid=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=3,RowCount=1};
  for(int i=0;i<3;i++) grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,33.333f));
  var actions=new FlowLayoutPanel{Dock=DockStyle.Bottom,Height=50,Padding=new Padding(0,8,0,0)};
  actions.Controls.Add(Button("적용 / 출력 시작",()=>Apply(true)));
  actions.Controls.Add(Button("출력 중지 (Esc)",Stop));
  actions.Controls.Add(Button("모니터 식별",Identify));
  actions.Controls.Add(Button("모니터 새로고침",()=>RefreshScreens()));
  actions.Controls.Add(Button("로고만 보기 (F11)",ToggleControls));
  status.Dock=DockStyle.Bottom; status.Height=30; status.ForeColor=Color.FromArgb(134,218,197);
  controls.Controls.Add(grid); controls.Controls.Add(actions); controls.Controls.Add(status);
  for(int i=0;i<3;i++) {
   int index=i; var card=new Panel{Dock=DockStyle.Fill,Padding=new Padding(8),Margin=new Padding(4)};
   var title=new Label{Text=String.Format("0{0}  {1}",i+1,titles[i]),Dock=DockStyle.Top,Height=28,Font=new Font(Font,FontStyle.Bold)};
   thumbs[i]=new ImageView{Photo=images[i],Dock=DockStyle.Fill};
   devices[i]=new ComboBox{Dock=DockStyle.Bottom,DropDownStyle=ComboBoxStyle.DropDownList,Height=30};
   modes[i]=new ComboBox{Dock=DockStyle.Bottom,DropDownStyle=ComboBoxStyle.DropDownList};
   modes[i].Items.AddRange(new object[]{"전체 보이기 · 비율 유지","화면 채우기 · 가장자리 자름"}); modes[i].SelectedIndex=Math.Max(0,Math.Min(1,settings.Modes[i]));
   var choose=Button("이미지 변경…",()=>ChooseImage(index)); choose.Dock=DockStyle.Bottom; choose.Height=32;
   card.Controls.Add(thumbs[i]);card.Controls.Add(title);card.Controls.Add(choose);card.Controls.Add(modes[i]);card.Controls.Add(devices[i]);grid.Controls.Add(card,i,0);
  }
  for(int i=0;i<2;i++) outputs[i]=new Output("샤인멍 · "+titles[i],Stop);
  RefreshScreens();
  KeyDown+=(s,e)=>{if(e.KeyCode==Keys.Escape)Stop();if(e.KeyCode==Keys.F11)ToggleControls();};
  SystemEvents.DisplaySettingsChanged+=DisplayChanged;
  FormClosed+=(s,e)=>{SystemEvents.DisplaySettingsChanged-=DisplayChanged;foreach(var o in outputs)o.Dispose();foreach(var im in images)im.Dispose();};
  Shown+=(s,e)=>{if(test) BeginInvoke(new Action(SelfTest)); else if(Environment.GetCommandLineArgs().Contains("--start")) BeginInvoke(new Action(()=>Apply(true)));};
 }
 static Image ReadImage(string path) {using(var src=Image.FromFile(path))return new Bitmap(src);}
 static Button Button(string text, Action action) {var b=new Button{Text=text,AutoSize=true,Height=34,FlatStyle=FlatStyle.Flat,BackColor=Color.FromArgb(42,59,72),ForeColor=Color.White,Margin=new Padding(0,0,8,0)};b.Click+=(s,e)=>{try{action();}catch(Exception ex){MessageBox.Show(ex.Message,"샤인멍",MessageBoxButtons.OK,MessageBoxIcon.Warning);}};return b;}
 void DisplayChanged(object sender,EventArgs e) {if(!IsDisposed && IsHandleCreated)BeginInvoke(new Action(()=>{Stop();RefreshScreens();status.Text="모니터 연결이 바뀌었습니다. 배정을 확인하고 출력을 시작하세요.";}));}
 void RefreshScreens() {
  screens=Screen.AllScreens.OrderBy(s=>s.Bounds.X).ToArray();
  for(int i=0;i<3;i++) {
   string previous=devices[i].SelectedIndex>=0 && devices[i].SelectedIndex<devices[i].Items.Count?((MonitorItem)devices[i].SelectedItem).Device:settings.Devices[i];
   devices[i].Items.Clear();foreach(var s in screens)devices[i].Items.Add(new MonitorItem(s));
   int idx=Array.FindIndex(screens,s=>s.DeviceName==previous);
   if(idx<0) {var primary=Array.FindIndex(screens,s=>s.Primary); var other=screens.Select((s,n)=>n).Where(n=>n!=primary).ToArray();idx=i==2?primary:(i<other.Length?other[i]:-1);}
   devices[i].SelectedIndex=idx;
  }
  status.Text="연결된 모니터 "+screens.Length+"대  ·  서로 다른 모니터를 선택한 후 적용하세요.";
 }
 string ValidateTargets() {
  if(devices.Any(d=>d.SelectedIndex<0))return "3개 화면에 각각 모니터를 지정해 주세요. 확장 모드 모니터 3대가 필요합니다.";
  if(devices.Select(d=>((MonitorItem)d.SelectedItem).Device).Distinct().Count()!=3)return "화면마다 서로 다른 모니터를 선택해 주세요.";
  if(devices.Any(d=>!Screen.AllScreens.Any(s=>s.DeviceName==((MonitorItem)d.SelectedItem).Device)))return "모니터 연결이 변경됐습니다. 새로고침 후 다시 선택해 주세요.";
  return null;
 }
 void Apply(bool save) {
  string error=ValidateTargets(); if(error!=null){status.Text=error;return;}
  for(int i=0;i<3;i++){settings.Devices[i]=((MonitorItem)devices[i].SelectedItem).Device;settings.Modes[i]=modes[i].SelectedIndex;}
  var current=Screen.AllScreens; WindowState=FormWindowState.Normal;FormBorderStyle=FormBorderStyle.None;
  Bounds=current.First(s=>s.DeviceName==settings.Devices[2]).Bounds;
  for(int i=0;i<2;i++){outputs[i].View.Photo=images[i];outputs[i].View.Fill=settings.Modes[i]==1;outputs[i].Bounds=current.First(s=>s.DeviceName==settings.Devices[i]).Bounds;outputs[i].View.Invalidate();outputs[i].Show();}
  logo.Fill=settings.Modes[2]==1;logo.Invalidate();running=true;Activate();
  status.Text="출력 중  ·  F11: 로고만 보기 / 제어판  ·  Esc: 출력 중지  ·  Alt+F4: 종료";
  if(save && !testing) try {Directory.CreateDirectory(Path.GetDirectoryName(config));using(var f=File.Create(config))new XmlSerializer(typeof(Settings)).Serialize(f,settings);}catch(Exception ex){status.Text="출력 중 · 설정 저장 실패: "+ex.Message;}
 }
 void Stop() {foreach(var o in outputs)o.Hide();running=false;controls.Visible=true;FormBorderStyle=FormBorderStyle.Sizable;WindowState=FormWindowState.Normal;Bounds=Screen.PrimaryScreen.WorkingArea;status.Text="출력 중지됨 · 적용 / 출력 시작으로 다시 표시하세요.";Activate();}
 void ToggleControls(){controls.Visible=!controls.Visible;}
 void ChooseImage(int i) {using(var dialog=new OpenFileDialog{Filter="이미지|*.jpg;*.jpeg;*.png;*.bmp;*.gif",Title=titles[i]+" 선택"}){if(dialog.ShowDialog(this)!=DialogResult.OK)return;Image next=ReadImage(dialog.FileName);Image old=images[i];images[i]=next;settings.Paths[i]=dialog.FileName;thumbs[i].Photo=next;thumbs[i].Invalidate();if(i==2){logo.Photo=next;logo.Invalidate();}else{outputs[i].View.Photo=next;outputs[i].View.Invalidate();}old.Dispose();status.Text="이미지가 변경됐습니다. 적용 버튼을 누르면 설정이 저장됩니다.";}}
 void Identify(){foreach(var screen in Screen.AllScreens){var f=new Form{FormBorderStyle=FormBorderStyle.None,StartPosition=FormStartPosition.Manual,BackColor=Color.FromArgb(29,98,94),TopMost=true,ShowInTaskbar=false,Size=new Size(420,150)};f.Location=new Point(screen.Bounds.X+40,screen.Bounds.Y+40);f.Controls.Add(new Label{Dock=DockStyle.Fill,Text=screen.DeviceName+"\n"+screen.Bounds.Width+" × "+screen.Bounds.Height,TextAlign=ContentAlignment.MiddleCenter,ForeColor=Color.White,Font=new Font("맑은 고딕",26,FontStyle.Bold)});var timer=new Timer{Interval=2500};timer.Tick+=(s,e)=>{timer.Stop();timer.Dispose();f.Dispose();};f.Show();timer.Start();}}
 static void MakeLogo(string path) {
  if(File.Exists(path))return;using(var b=new Bitmap(1920,1080))using(var g=Graphics.FromImage(b)) {
   g.SmoothingMode=SmoothingMode.AntiAlias;g.Clear(Color.FromArgb(16,23,30));
   using(var brush=new SolidBrush(Color.FromArgb(132,223,197))) {g.FillEllipse(brush,885,250,150,112);g.FillEllipse(brush,860,196,45,55);g.FillEllipse(brush,915,168,45,60);g.FillEllipse(brush,973,168,45,60);g.FillEllipse(brush,1020,196,45,55);}
   var center=new StringFormat{Alignment=StringAlignment.Center};using(var font=new Font("맑은 고딕",180,FontStyle.Bold,GraphicsUnit.Pixel))g.DrawString("샤인멍",font,Brushes.White,new RectangleF(0,370,1920,300),center);
   using(var brush=new SolidBrush(Color.FromArgb(132,223,197)))using(var font=new Font("Segoe UI",42,FontStyle.Regular,GraphicsUnit.Pixel))g.DrawString("SHINE MUNG  /  PHOTO STUDIO",font,brush,new RectangleF(0,720,1920,100),center);
   b.Save(path,System.Drawing.Imaging.ImageFormat.Png);
  }
 }
 void Assert(bool condition,string name,List<string> log){if(!condition)throw new Exception("FAIL: "+name);log.Add("PASS: "+name);}
 void SelfTest() {
  var log=new List<string>();try {
   Assert(screens.Length>=3,"three extended displays detected",log);
   foreach(var s in screens)log.Add(s.DeviceName+" "+s.Bounds);
   Assert(images.All(im=>im.Width>100 && im.Height>100),"three local images decoded",log);
   Apply(false);Application.DoEvents();Assert(running && outputs.All(o=>o.Visible),"one controller and two visible outputs",log);
   for(int i=0;i<2;i++)Assert(outputs[i].Bounds==screens[devices[i].SelectedIndex].Bounds,"exact physical output bounds "+i,log);
   Assert(Bounds==screens[devices[2].SelectedIndex].Bounds,"main display bounds",log);
   int a=devices[0].SelectedIndex,b=devices[1].SelectedIndex;devices[0].SelectedIndex=b;devices[1].SelectedIndex=a;Apply(false);Application.DoEvents();Assert(outputs[0].Bounds==screens[b].Bounds && outputs[1].Bounds==screens[a].Bounds,"live monitor reassignment",log);
   devices[1].SelectedIndex=b;Assert(ValidateTargets()!=null,"duplicate assignment rejected",log);
   devices[0].SelectedIndex=-1;Assert(ValidateTargets()!=null,"missing assignment rejected",log);
   devices[0].SelectedIndex=a;devices[1].SelectedIndex=b;
   ToggleControls();Assert(!controls.Visible,"logo-only mode",log);ToggleControls();
   Stop();Assert(!outputs.Any(o=>o.Visible),"stop hides both outputs",log);
   Apply(false);Assert(outputs.All(o=>o.Visible),"restart outputs",log);
   log.Add("ALL TESTS PASSED");File.WriteAllLines(Path.Combine(root,"test-results.txt"),log);Environment.ExitCode=0;
  }catch(Exception ex){log.Add(ex.ToString());File.WriteAllLines(Path.Combine(root,"test-results.txt"),log);Environment.ExitCode=1;}finally{Close();}
 }
 class MonitorItem {public string Device;readonly Screen screen;public MonitorItem(Screen s){screen=s;Device=s.DeviceName;}public override string ToString(){return Device.Replace("\\\\.\\","")+" · "+screen.Bounds.Width+"×"+screen.Bounds.Height+(screen.Primary?" · 주 모니터":"");}}
 [STAThread] public static void Main(string[] args) {Application.EnableVisualStyles();Application.SetCompatibleTextRenderingDefault(false);try{Application.Run(new MainForm(args.Contains("--self-test")));}catch(Exception e){File.WriteAllText(Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"error.log"),e.ToString());MessageBox.Show(e.Message,"샤인멍 시작 오류");Environment.ExitCode=1;}}
}
