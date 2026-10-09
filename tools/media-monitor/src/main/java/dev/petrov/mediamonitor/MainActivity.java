package dev.petrov.mediamonitor;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import java.io.*;
import static dev.petrov.mediamonitor.ReportStore.*;

public final class MainActivity extends Activity {
    private TextView status;
    private Button start,stop,share,save;
    private Spinner player;
    private File savedCopy;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refresh=new Runnable(){ public void run(){ update(); handler.postDelayed(this,1000); }};
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(state!=null && state.getString("copy")!=null) savedCopy=new File(state.getString("copy"));
        ScrollView scroll=new ScrollView(this); LinearLayout body=new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL); int pad=dp(18); body.setPadding(pad,pad,pad,pad); scroll.addView(body); setContentView(scroll);
        label(body,"Media Monitor",26);
        label(body,"Диагностика приборной панели на ГУ автомобиля\nFMPLAY · YMPlayer 2 · Яндекс Музыка\n1.0.0beta-build1",17);
        label(body,"Записывает только медиасессии и медиауведомления этих трёх приложений: названия, состояния и наличие обложек. Без аккаунтов, токенов, интернета и управления плеерами. Android выдаёт доступ ко всем уведомлениям, но остальные приложения отбрасываются до чтения их содержимого.",16);
        button(body,"1. Доступ к уведомлениям",()->{
            try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
            catch(ActivityNotFoundException e){ message("Прошивка не предоставляет экран доступа к уведомлениям. Обойти это ограничение утилита не может."); }
        });
        status=label(body,"",16);
        start=button(body,"2. Новая запись",()->{
            if(!access() || MonitorService.live==null){ message("Сначала включите доступ к уведомлениям и дождитесь подключения службы. Если служба не подключается, ограничение может быть в прошивке ГУ."); return; }
            if(file(this).length()>0) new AlertDialog.Builder(this).setMessage("Заменить предыдущий отчёт? Сначала сохраните его, если он нужен.").setNegativeButton("Отмена",null).setPositiveButton("Начать",(d,w)->begin()).show();
            else begin();
        });
        label(body,"3. По очереди откройте каждый плеер, запустите звук, смените трек/станцию, сделайте паузу и продолжите. Перед следующим приложением остановите предыдущий плеер самостоятельно. Утилита продолжит запись в фоне.",16);
        for(int i=0;i<PACKAGES.length;i++) { final int n=i; button(body,"Открыть "+LABELS[i],()->{
            Intent intent=getPackageManager().getLaunchIntentForPackage(PACKAGES[n]);
            if(intent==null) message(LABELS[n]+": приложение не установлено или не имеет доступного экрана запуска.");
            else try { startActivity(intent); } catch(Exception e) { message("Не удалось открыть приложение."); }
        }); }
        label(body,"Отметка результата на приборке (для выбранного ниже приложения):",16);
        player=new Spinner(this); player.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,LABELS));
        body.addView(player,new LinearLayout.LayoutParams(-1,dp(56)));
        button(body,"Приборка показывает",()->mark(true));
        button(body,"Приборка не показывает",()->mark(false));
        stop=button(body,"4. Остановить запись",()->{ ReportStore.stop(this,"user"); MonitorService.refresh(); update(); });
        share=button(body,"5. Отправить отчёт",()->export(true));
        save=button(body,"Сохранить отчёт в файл",()->export(false));
        label(body,"Один сеанс: до 30 минут или 4 МБ. Не отключайте утилиту принудительно во время записи. При перезапуске службы разрыв фиксируется в отчёте. После проверки можно выключить доступ к уведомлениям и удалить утилиту. Отчёт содержит названия прослушанной музыки — перед отправкой его можно прочитать.",16);
        label(body,"Это снимок стандартных API Android. Он не читает MCU/CAN и сам по себе не доказывает, как приборная панель получает данные.",16);
    }
    private void begin(){ try{ ReportStore.start(this); MonitorService.refresh(); update(); } catch(IOException e){ message("Не удалось создать отчёт."); } }
    private void mark(boolean shown){
        if(!recording(this)){ message("Начните запись перед отметкой."); return; }
        write(this,"INSTRUMENT_CLUSTER",PACKAGES[player.getSelectedItemPosition()],with("visible",shown)); update();
    }
    private boolean access(){
        ComponentName c=new ComponentName(this,MonitorService.class);
        if(Build.VERSION.SDK_INT>=27) return ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).isNotificationListenerAccessGranted(c);
        String enabled=Settings.Secure.getString(getContentResolver(),"enabled_notification_listeners");
        if(enabled!=null) for(String item:enabled.split(":")) if(c.equals(ComponentName.unflattenFromString(item))) return true;
        return false;
    }
    private void update(){
        if(status==null) return;
        boolean granted=access(), connected=MonitorService.live!=null;
        status.setText("Доступ: "+(granted?"включён":"выключен")+" · Служба: "+(connected?"подключена":"не подключена")+"\n"+ReportStore.status(this));
        start.setEnabled(granted && connected && !recording(this)); stop.setEnabled(recording(this));
        share.setEnabled(file(this).length()>0); save.setEnabled(file(this).length()>0);
    }
    private void export(boolean sending){
        try {
            savedCopy=ReportStore.export(this);
            if(sending){
                Uri uri=new Uri.Builder().scheme("content").authority(getPackageName()+".reports").appendPath(savedCopy.getName()).build();
                Intent intent=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM,uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setClipData(ClipData.newRawUri("Media report",uri));
                startActivity(Intent.createChooser(intent,"Отправить отчёт"));
            } else {
                Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_TITLE,savedCopy.getName()); startActivityForResult(intent,10);
            }
        } catch(ActivityNotFoundException e){
            if(!sending) saveWithoutPicker();
            else message("На ГУ нет приложения для отправки. Попробуйте «Сохранить отчёт в файл».");
        }
        catch(Exception e){ message("Не удалось подготовить отчёт."); }
    }
    private void saveWithoutPicker(){
        if(Build.VERSION.SDK_INT>=29){
            Uri destination=null;
            try {
                ContentValues values=new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME,savedCopy.getName()); values.put(MediaStore.Downloads.MIME_TYPE,"text/plain");
                values.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/MediaMonitor"); values.put(MediaStore.Downloads.IS_PENDING,1);
                destination=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
                if(destination==null) throw new IOException();
                try(InputStream in=new FileInputStream(savedCopy); OutputStream out=getContentResolver().openOutputStream(destination,"w")){
                    if(out==null) throw new IOException(); copy(in,out);
                }
                ContentValues ready=new ContentValues(); ready.put(MediaStore.Downloads.IS_PENDING,0); getContentResolver().update(destination,ready,null,null);
                message("Отчёт сохранён в Downloads/MediaMonitor/"+savedCopy.getName()+". Его можно скопировать файловым менеджером на USB или отправить с ГУ."); return;
            } catch(Exception e){ if(destination!=null) try{getContentResolver().delete(destination,null,null);}catch(Exception ignored){} }
        }
        try {
            File dir=getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if(dir==null) throw new IOException();
            File target=new File(dir,savedCopy.getName());
            try(InputStream in=new FileInputStream(savedCopy); OutputStream out=new FileOutputStream(target)){ copy(in,out); }
            message("На ГУ отсутствует системный выбор файла. Отчёт сохранён:\n"+target.getAbsolutePath()+"\n\nНа Android 10 его можно скопировать файловым менеджером на USB. Не удаляйте утилиту до копирования отчёта.");
        } catch(Exception e){ message("Не удалось сохранить отчёт в папку утилиты. Попробуйте «Отправить отчёт»."); }
    }
    @Override public void onSaveInstanceState(Bundle out){ if(savedCopy!=null) out.putString("copy",savedCopy.getAbsolutePath()); super.onSaveInstanceState(out); }
    @Override public void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=10 || result!=RESULT_OK || data==null || data.getData()==null || savedCopy==null) return;
        final File source=savedCopy; final Uri uri=data.getData();
        new Thread(()->{
            try(InputStream in=new FileInputStream(source); OutputStream out=getContentResolver().openOutputStream(uri,"wt")) {
                if(out==null) throw new IOException(); copy(in,out); runOnUiThread(()->message("Отчёт сохранён."));
            } catch(Exception e){ runOnUiThread(()->message("Не удалось сохранить файл. Попробуйте «Отправить отчёт».")); }
        },"report-export").start();
    }
    @Override public void onResume(){ super.onResume(); handler.post(refresh); }
    @Override public void onPause(){ handler.removeCallbacks(refresh); super.onPause(); }
    private int dp(int x){ return (int)(x*getResources().getDisplayMetrics().density+0.5f); }
    private TextView label(LinearLayout body,String text,int size){ TextView v=new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(Color.rgb(235,233,228)); v.setPadding(0,dp(8),0,dp(8)); body.addView(v); return v; }
    private Button button(LinearLayout body,String title,Runnable action){ Button b=new Button(this); b.setText(title); b.setAllCaps(false); b.setTextSize(18); b.setMinHeight(dp(56)); b.setOnClickListener(v->action.run()); body.addView(b,new LinearLayout.LayoutParams(-1,-2)); return b; }
    private void message(String text){ new AlertDialog.Builder(this).setMessage(text).setPositiveButton("ОК",null).show(); }
}
