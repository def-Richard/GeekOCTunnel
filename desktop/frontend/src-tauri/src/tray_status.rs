use std::sync::{Mutex, OnceLock};
use tauri::{Manager, image::Image, menu::{Menu, MenuItem, PredefinedMenuItem}};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Status { Idle, Connecting, Login, Certificate, Permission, Connected, Reconnecting, Disconnecting, Failed, CleanupFailed }
impl Status {
    fn text(self) -> &'static str {
        match self {
            Self::Idle=>"未连接", Self::Connecting=>"正在连接", Self::Login=>"等待登录",
            Self::Certificate=>"等待证书确认", Self::Permission=>"等待授权", Self::Connected=>"已连接",
            Self::Reconnecting=>"正在重连", Self::Disconnecting=>"正在断开", Self::Failed=>"连接失败",
            Self::CleanupFailed=>"清理待确认",
        }
    }
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Row { pub name: String, pub status: Status }
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Kind { Idle, Connected, Busy, Partial, Attention }
#[derive(Clone, Debug, PartialEq, Eq)]
struct Presentation { kind: Kind, connected: usize, summary: String, rows: Vec<Row>, tooltip: String }
#[derive(Default)]
pub struct TrayState(Mutex<Option<Presentation>>);

fn shorten(text: &str, limit: usize) -> String {
    let clean: String=text.chars().filter(|c|!c.is_control() && !matches!(*c,'\u{202a}'..='\u{202e}'|'\u{2066}'..='\u{2069}')).collect();
    if clean.encode_utf16().count()<=limit {return clean;}
    let mut result=String::new();let mut size=0;
    for c in clean.chars() {if size+c.len_utf16()>=limit {break;} result.push(c);size+=c.len_utf16();}
    result.push('…');result
}
impl Presentation {
    fn new(mut rows: Vec<Row>) -> Self {
        rows.retain(|row|row.status!=Status::Idle);
        for row in &mut rows {row.name=shorten(&row.name,128);if row.name.trim().is_empty() {row.name="服务器".into();}}
        rows.sort_by(|a,b|(a.status!=Status::Connected,&a.name).cmp(&(b.status!=Status::Connected,&b.name)));
        let connected=rows.iter().filter(|row|row.status==Status::Connected).count();
        let issues=rows.iter().filter(|row|matches!(row.status,Status::Failed|Status::CleanupFailed)).count();
        let pending=rows.len()-connected-issues;
        let kind=if connected>0 {if pending+issues>0 {Kind::Partial} else {Kind::Connected}}
            else if issues>0 {Kind::Attention} else if pending>0 {Kind::Busy} else {Kind::Idle};
        let mut summary=if connected>0 {format!("已连接 {connected} 台")} else {"无活跃连接".into()};
        if pending>0 {summary.push_str(&format!(" · {pending} 台处理中"));}
        if issues>0 {summary.push_str(&format!(" · {issues} 台需关注"));}
        let mut tooltip=format!("GeekOCTunnel\n{summary}");
        // Windows has a 128-WCHAR tray tip buffer. Full names remain in the menu.
        for (index,row) in rows.iter().enumerate() {
            let line=format!("\n{}：{}",shorten(&row.name,14),row.status.text());
            let remaining=rows.len()-index;
            let suffix=format!("\n另有 {remaining} 项，右键查看");
            let reserve=if remaining>1 {suffix.encode_utf16().count()} else {0};
            if tooltip.encode_utf16().count()+line.encode_utf16().count()+reserve>127 {
                tooltip.push_str(&suffix);break;
            }
            tooltip.push_str(&line);
        }
        Self{kind,connected,summary,rows,tooltip}
    }
    fn icon(&self) -> Image<'static> {
        static ATLAS: OnceLock<Image<'static>>=OnceLock::new();
        let atlas=ATLAS.get_or_init(||Image::from_bytes(include_bytes!("../icons/tray-states.png")).expect("bundled tray atlas"));
        let column=match self.kind {Kind::Idle=>0,Kind::Connected=>1,Kind::Busy=>2,Kind::Partial=>3,Kind::Attention=>4};
        let row=self.connected.clamp(1,8)-1;
        let mut rgba=Vec::with_capacity(32*32*4);
        for y in 0..32 {
            let start=((row*32+y)*160+column*32)*4;
            rgba.extend_from_slice(&atlas.rgba()[start..start+32*4]);
        }
        Image::new_owned(rgba,32,32)
    }
    fn menu(&self, app: &tauri::AppHandle) -> tauri::Result<Menu<tauri::Wry>> {
        let menu=Menu::new(app)?;
        menu.append(&MenuItem::with_id(app,"show","显示主窗口",true,None::<&str>)?)?;
        menu.append(&PredefinedMenuItem::separator(app)?)?;
        menu.append(&MenuItem::new(app,&self.summary,false,None::<&str>)?)?;
        for row in &self.rows {
            let text=format!("{}：{}",shorten(&row.name,48),row.status.text()).replace('&',"&&");
            menu.append(&MenuItem::new(app,text,false,None::<&str>)?)?;
        }
        menu.append(&PredefinedMenuItem::separator(app)?)?;
        menu.append(&MenuItem::with_id(app,"ask","恢复关闭时询问",true,None::<&str>)?)?;
        menu.append(&MenuItem::with_id(app,"exit","断开全部并退出",true,None::<&str>)?)?;
        Ok(menu)
    }
}
pub fn initial(app: &tauri::AppHandle) -> tauri::Result<(Image<'static>,String,Menu<tauri::Wry>)> {
    let view=Presentation::new(Vec::new());
    Ok((view.icon(),view.tooltip.clone(),view.menu(app)?))
}
pub fn refresh(app: &tauri::AppHandle) {
    let handle=app.clone();
    let _=app.run_on_main_thread(move || {
        let Some(tray)=handle.tray_by_id("geekoctunnel") else {return;};
        let view=Presentation::new(crate::vpn::tray_rows(&handle));
        let state=handle.state::<TrayState>();
        let mut previous=state.0.lock().unwrap_or_else(|e|e.into_inner());
        if previous.as_ref()==Some(&view) {return;}
        let result=(|| -> tauri::Result<()> {
            if previous.as_ref().map(|p|(p.kind,p.connected))!=Some((view.kind,view.connected)) {tray.set_icon(Some(view.icon()))?;}
            tray.set_tooltip(Some(&view.tooltip))?;
            tray.set_menu(Some(view.menu(&handle)?))?;
            Ok(())
        })();
        if result.is_ok() {*previous=Some(view);}
    });
}

#[cfg(test)]
pub fn verified_tooltip(app: &tauri::AppHandle) -> String {
    app.state::<TrayState>().0.lock().unwrap().as_ref().expect("native tray updated").tooltip.clone()
}

#[cfg(test)]
mod tests {
    use super::*;
    fn row(name:&str,status:Status)->Row {Row{name:name.into(),status}}
    #[test]
    fn counts_only_connected_tunnels_and_explains_partial_success() {
        let empty=Presentation::new(vec![row("Example",Status::Idle)]);
        assert_eq!(empty.kind,Kind::Idle);assert!(empty.tooltip.contains("无活跃连接"));
        let view=Presentation::new(vec![row("Office",Status::Reconnecting),row("Home",Status::Connected)]);
        assert_eq!(view.kind,Kind::Partial);assert_eq!(view.connected,1);
        assert!(view.tooltip.contains("Home：已连接") && view.tooltip.contains("Office：正在重连"));
        assert_eq!(Presentation::new(vec![row("A",Status::Connected),row("B",Status::Connected)]).connected,2);
        assert_eq!(Presentation::new(vec![row("A",Status::Login)]).kind,Kind::Busy);
        assert_eq!(Presentation::new(vec![row("A",Status::Failed)]).kind,Kind::Attention);
    }
    #[test]
    fn long_multilingual_names_fit_windows_and_keep_menu_rows() {
        let rows=(0..8).map(|_|row(&"😀测试\n\u{202e}&".repeat(30),Status::Connected)).collect();
        let view=Presentation::new(rows);
        assert!(view.tooltip.encode_utf16().count()<=127);assert!(view.tooltip.contains("右键查看"));
        assert_eq!(view.rows.len(),8);assert!(!view.rows[0].name.contains('\n'));assert!(!view.rows[0].name.contains('\u{202e}'));
        assert_eq!(view.icon().rgba().len(),32*32*4);
        assert_ne!(view.icon().rgba(),Presentation::new(Vec::new()).icon().rgba());
    }
}
