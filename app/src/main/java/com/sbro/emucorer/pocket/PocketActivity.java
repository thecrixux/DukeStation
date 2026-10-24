package com.sbro.emucorer.pocket;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.util.AtomicFile;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.Arrays;
import com.sbro.emucorer.core.NativeApp;

/** Independent PocketStation session. All native calls are serialized by lock. */
public final class PocketActivity extends Activity {
    private final Object lock = new Object();
    private long handle;
    private HandlerThread thread;
    private Handler worker;
    private volatile boolean running, muted;
    private volatile int touchButtons, keyButtons;
    private boolean resumed;
    private boolean cardOperation;
    private File exportFile;
    private AudioTrack audio;
    private File dir, cardFile, biosFile;
    private String cardName = "";
    private long lastSave;
    private byte[] savedCard;
    private TextView status;
    private Button play;
    private LcdView lcd;
    private int frames;
    private final int gold = 0xFFFFB800;
    private String tr(String es, String en) { return getResources().getConfiguration().getLocales().get(0).getLanguage().equals("es") ? es : en; }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override protected void attachBaseContext(android.content.Context base) {
        // Follow DukeStation's selected language, not just the phone's language.
        android.content.Context selected = com.sbro.emucorer.core.AppLocaleManager.INSTANCE.wrap(base);
        String language = selected.getResources().getConfiguration().getLocales().get(0).getLanguage();
        java.util.Locale pocketLocale = new java.util.Locale("es".equals(language) ? "es" : "en");
        android.content.res.Configuration configuration = new android.content.res.Configuration(selected.getResources().getConfiguration());
        configuration.setLocales(new android.os.LocaleList(pocketLocale));
        super.attachBaseContext(selected.createConfigurationContext(configuration));
    }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF101012);
        getWindow().setNavigationBarColor(0xFF101012);
        dir = new File(getFilesDir(), "dukepocket"); dir.mkdirs();
        biosFile = new File(dir,"pocketstation.bin");
        cardName = getPreferences(0).getString("card", "");
        if(!cardName.isEmpty()) cardFile = new File(dir,cardName);
        if(b!=null && b.getString("exportFile")!=null) exportFile = new File(dir,b.getString("exportFile"));
        thread = new HandlerThread("DukePocket"); thread.start(); worker = new Handler(thread.getLooper());
        LinearLayout root = new LinearLayout(this); root.setOrientation(1); root.setPadding(dp(20),dp(12),dp(20),dp(24)); root.setBackgroundColor(0xFF101012);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(root); setContentView(scroll);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(dp(20),dp(12)+insets.getSystemWindowInsetTop(),dp(20),dp(24)+insets.getSystemWindowInsetBottom()); return insets;});
        button(root, "‹ DukeStation", this::finish);
        TextView title = label("DukePocket", 30); title.setTextColor(gold); root.addView(title);
        root.addView(label(tr("PocketStation · Vista previa", "PocketStation · Preview"),16));
        status = label("",14); root.addView(status);
        lcd = new LcdView(); root.addView(lcd, new LinearLayout.LayoutParams(-1,dp(270)));
        LinearLayout up = row(root); pad(up,"▲",1);
        LinearLayout middle = row(root); pad(middle,"◀",8); pad(middle,"●",16); pad(middle,"▶",2);
        LinearLayout down = row(root); pad(down,"▼",4);
        play = button(root,tr("Iniciar / continuar","Start / resume"),()->{if(running) pauseSession(); else startSession();});
        Switch sound = new Switch(this); sound.setText(tr("Sonido","Sound")); sound.setTextColor(0xFFF2F2F2); sound.setChecked(true);
        sound.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{gold,0xFF9A9AA2}));
        sound.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{0xFF66501E,0xFF38383E}));
        sound.setOnCheckedChangeListener((v,checked)->muted=!checked); root.addView(sound);
        button(root,tr("Importar BIOS de PocketStation","Import PocketStation BIOS"),()->pick(1));
        button(root,tr("Importar tarjeta o aplicación","Import card or application"),()->pick(2));
        button(root,tr("Copiar tarjeta de DukeStation (PS1)","Copy DukeStation card (PS1)"),this::choosePs1Card);
        button(root,tr("Devolver progreso a PS1","Return progress to PS1"),this::returnToPs1);
        button(root,tr("Exportar respaldo de PS1","Export PS1 backup"),this::exportPs1Backup);
        button(root,tr("Mis tarjetas de PocketStation","My PocketStation cards"),this::chooseCard);
        button(root,tr("Exportar tarjeta (.mcd)","Export card (.mcd)"),this::exportCard);
        root.addView(label(tr("Se utiliza una copia propia. El archivo original no se modifica. Guardado automático cada 5 segundos y al pausar.\n\nTras la animación de inicio: abajo, acción; después derecha y acción para abrir la aplicación.","Uses a private copy. The original file is not modified. Autosaves every 5 seconds and on pause.\n\nAfter the boot animation: Down, Action; then Right and Action to open the app."),14));
        button(root,"PokketStation · mentalfoundry · MIT",()->{try(InputStream in=getAssets().open("licenses/pokketstation-MIT.txt")) {new AlertDialog.Builder(this).setTitle("PokketStation").setMessage(new String(read(in,16384),java.nio.charset.StandardCharsets.UTF_8)).setPositiveButton("OK",null).show();}catch(Exception e){error(e);}});
        refresh();
    }
    private TextView label(String text,int size) { TextView v=new TextView(this); v.setText(text); v.setTextColor(0xFFF2F2F2); v.setTextSize(size); v.setPadding(0,dp(8),0,dp(8)); return v; }
    private Button button(LinearLayout parent,String text,Runnable action) { Button b=new Button(this); b.setText(text); b.setAllCaps(false); b.setTextColor(gold); b.setBackgroundTintList(ColorStateList.valueOf(0xFF242420)); b.setOnClickListener(v->action.run()); parent.addView(b,new LinearLayout.LayoutParams(-1,-2)); return b; }
    private LinearLayout row(LinearLayout parent) {LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER); parent.addView(row); return row;}
    private void pad(LinearLayout parent,String text,int mask) {
        Button b=new Button(this); b.setText(text); b.setTextColor(gold); b.setTextSize(24); b.setBackgroundTintList(ColorStateList.valueOf(0xFF242420)); b.setContentDescription(text.equals("●")?tr("Acción","Action"):text);
        parent.addView(b,new LinearLayout.LayoutParams(dp(76),dp(56)));
        b.setOnTouchListener((v,event)->{switch(event.getActionMasked()) {case MotionEvent.ACTION_DOWN: touchButtons|=mask; v.setPressed(true); v.getParent().requestDisallowInterceptTouchEvent(true); return true; case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: touchButtons&=~mask; v.setPressed(false); v.getParent().requestDisallowInterceptTouchEvent(false); return true;} return true;});
        b.setOnClickListener(v->{touchButtons|=mask; b.postDelayed(()->touchButtons&=~mask,80);});
    }
    private void refresh() {
        String text=(biosFile.exists()?tr("BIOS lista","BIOS ready"):tr("Importa una BIOS de PocketStation (16 KiB)","Import a PocketStation BIOS (16 KiB)"));
        text+="\n"+(cardFile!=null?cardFile.getName():tr("Importa una tarjeta o aplicación compatible","Import a compatible card or app"));
        if(PocketCardLink.isLinked(cardFile))text+="\n"+tr("Vinculada a PS1 · devolución manual","Linked to PS1 · manual return");
        status.setText(text);
    }
    private void error(Exception e) {runOnUiThread(()->new AlertDialog.Builder(this).setTitle(tr("DukePocket: aviso","DukePocket: notice")).setMessage(e.getMessage()==null?e.toString():e.getMessage()).setPositiveButton("OK",null).show());}
    private void pick(int code) {pauseSession(); try {startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),code);}catch(Exception e){error(e);}}
    private static byte[] read(InputStream in,int max) throws IOException {if(in==null)throw new IOException("Cannot open file"); ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] b=new byte[8192]; int n; while((n=in.read(b))!=-1){if(out.size()+n>max)throw new IOException("File too large"); out.write(b,0,n);} return out.toByteArray();}
    private static byte[] load(File file) throws IOException {try(InputStream in=new AtomicFile(file).openRead()){return read(in,140000);}}
    private static void store(File file,byte[] data) throws IOException {AtomicFile atomic=new AtomicFile(file); FileOutputStream out=null; try{out=atomic.startWrite();out.write(data);atomic.finishWrite(out);}catch(IOException e){if(out!=null)atomic.failWrite(out);throw e;}}
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data); if(result!=RESULT_OK || data==null || data.getData()==null)return;
        try {
            Uri uri=data.getData();
            if(request==3){ if(exportFile==null)throw new IOException("No export selected"); try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("Cannot write file");out.write(load(exportFile));} exportFile=null; Toast.makeText(this,tr("Tarjeta exportada","Card exported"),Toast.LENGTH_LONG).show();return; }
            byte[] imported; try(InputStream in=getContentResolver().openInputStream(uri)){imported=read(in,request==1?16384:140000);}
            if(request==1){if(imported.length!=16384)throw new IOException(tr("La BIOS debe tener exactamente 16384 bytes.","BIOS must be exactly 16384 bytes.")); synchronized(lock){closeCore();store(biosFile,imported);} }
            else if(request==2){
                if(!biosFile.exists())throw new IOException(tr("Importa primero la BIOS.","Import the BIOS first."));
                long probe=PocketNative.open(load(biosFile),imported); if(probe==0)throw new IOException(tr("Formato no compatible. Usa una tarjeta raw de 128 KiB, .mcs, .pss o .gme.","Unsupported format. Use a raw 128 KiB card, .mcs, .pss or .gme."));
                byte[] full; try{full=PocketNative.card(probe);}finally{PocketNative.close(probe);}
                String sourceName="Pocket";
                try(android.database.Cursor cursor=getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)) {
                    if(cursor!=null && cursor.moveToFirst()) {String value=cursor.getString(0);if(value!=null)sourceName=value.replaceAll("[^A-Za-z0-9._-]","_");}
                }
                if(sourceName.length()>60)sourceName=sourceName.substring(0,60);
                String name=sourceName+"-"+System.currentTimeMillis()+".mcd";
                File target=new File(dir,name); store(target,full);
                // Keep the untouched import alongside the working copy for recovery.
                store(new File(dir,name+".original"),full);
                synchronized(lock){closeCore();cardFile=target;cardName=name;savedCard=null;}
                getPreferences(0).edit().putString("card",name).apply();
            }
            refresh();
        }catch(Exception e){error(e);}
    }
    private void chooseCard(){pauseSession();File[] cards=dir.listFiles((d,n)->n.endsWith(".mcd"));if(cards==null||cards.length==0){error(new IOException(tr("No hay tarjetas importadas.","No imported cards.")));return;}Arrays.sort(cards,(a,b)->a.getName().compareTo(b.getName()));String[] names=new String[cards.length];for(int i=0;i<cards.length;i++)names[i]=cards[i].getName();new AlertDialog.Builder(this).setTitle(tr("Elegir tarjeta","Choose card")).setItems(names,(d,i)->{synchronized(lock){closeCore();cardFile=cards[i];cardName=names[i];savedCard=null;}getPreferences(0).edit().putString("card",cardName).apply();refresh();}).show();}
    private void exportCard(){if(!pauseSession()||cardFile==null)return;requestExport(cardFile,cardFile.getName());}
    private void requestExport(File file,String name){exportFile=file;try{startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,name),3);}catch(Exception e){exportFile=null;error(e);}}
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);if(exportFile!=null)b.putString("exportFile",exportFile.getName());}
    private interface CardTask {void run() throws Exception;}
    private void offline(CardTask task,Runnable success){
        if(cardOperation)return;
        cardOperation=true;
        AlertDialog progress=new AlertDialog.Builder(this).setMessage(tr("Preparando tarjeta…","Preparing card…")).setCancelable(false).create();progress.show();
        worker.post(()->{
            Exception[] failure={null};
            try{boolean available=NativeApp.withStoppedPs1(()->{try{task.run();}catch(Exception e){failure[0]=e;}});
                if(!available)failure[0]=new IOException(tr("Cierra el juego de PS1 y vuelve a la biblioteca antes de intercambiar tarjetas. Pausarlo no basta.","Close the PS1 game and return to the library before exchanging cards. Pausing is not enough."));
            }catch(Exception e){failure[0]=e;}
            runOnUiThread(()->{cardOperation=false;progress.dismiss();if(isFinishing()||isDestroyed())return;if(failure[0]!=null){
                if(failure[0] instanceof PocketCardLink.Conflict)error(new IOException(tr("La tarjeta de PS1 cambió desde la copia. No se ha sobrescrito. Exporta tu progreso de PocketStation y crea una copia nueva desde PS1; no se pueden fusionar automáticamente.","The PS1 card changed since copying. It was not overwritten. Export your PocketStation progress and copy the current PS1 card again; automatic merging is not supported.")));
                else error(failure[0]);
            }else success.run();});
        });
    }
    private void choosePs1Card(){
        if(!pauseSession())return;
        java.util.ArrayList<File> cards=new java.util.ArrayList<>();
        offline(()->cards.addAll(PocketCardLink.discover(NativeApp.pocketCardRoots(this))),()->{
            if(cards.isEmpty()){error(new IOException(tr("No hay tarjetas guardadas en las carpetas actuales de DukeStation. Guarda dentro del juego de PS1, ciérralo y vuelve aquí.","No saved cards were found in DukeStation's current folders. Save in the PS1 game, close it and return here.")));return;}
            String[] names=new String[cards.size()];for(int i=0;i<names.length;i++)names[i]=cards.get(i).getName()+"\n"+cards.get(i).getParent();
            new AlertDialog.Builder(this).setTitle(tr("Copiar tarjeta PS1","Copy PS1 card")).setItems(names,(d,i)->importPs1Card(cards.get(i))).setNegativeButton(tr("Cancelar","Cancel"),null).show();
        });
    }
    private void importPs1Card(File source){
        File[] imported={null};boolean[] hasApp={false};
        offline(()->{imported[0]=PocketCardLink.importCard(source,dir,NativeApp.pocketCardRoots(this));hasApp[0]=PocketCardLink.hasPocketApp(PocketCardLink.read(imported[0]));},()->{
            synchronized(lock){closeCore();cardFile=imported[0];cardName=cardFile.getName();savedCard=null;}
            getPreferences(0).edit().putString("card",cardName).apply();refresh();lcd.pixels=null;lcd.invalidate();
            new AlertDialog.Builder(this).setTitle(tr("Tarjeta copiada","Card copied")).setMessage(hasApp[0]?tr("Se detectó una aplicación PocketStation. Pulsa Iniciar para probarla.","A PocketStation app was detected. Press Start to try it."):tr("No se detectó una aplicación PocketStation en esta tarjeta. Una partida PS1 normal no contiene necesariamente un minijuego. Esta versión todavía no emula la descarga desde el juego de PS1.","No PocketStation app was detected on this card. A normal PS1 save does not necessarily contain a minigame. This version does not yet emulate downloading from a PS1 game.")).setPositiveButton("OK",null).show();
        });
    }
    private void returnToPs1(){
        if(!pauseSession())return;
        if(!PocketCardLink.isLinked(cardFile)){error(new IOException(tr("Primero usa Copiar tarjeta de DukeStation. Las importaciones sueltas se devuelven mediante Exportar tarjeta.","Use Copy DukeStation card first. Standalone imports can be returned using Export card.")));return;}
        final File selected=cardFile;
        try{String source=PocketCardLink.link(selected).getProperty("source");
            new AlertDialog.Builder(this).setTitle(tr("Devolver progreso a PS1","Return progress to PS1")).setMessage(tr("Se actualizará esta tarjeta completa, creando antes un respaldo:\n\n","This entire card will be updated, with a backup created first:\n\n")+source+"\n\n"+tr("Después inicia el juego normalmente y carga la partida desde su menú. Evita cargar un save state antiguo.","Afterwards boot the game normally and load the save from its menu. Avoid loading an old save state.")).setNegativeButton(tr("Cancelar","Cancel"),null).setPositiveButton(tr("Devolver","Return"),(d,w)->{
                File[] backup={null};offline(()->backup[0]=PocketCardLink.commit(selected,NativeApp.pocketCardRoots(this)),()->new AlertDialog.Builder(this).setTitle("DukePocket").setMessage(backup[0]==null?tr("La tarjeta ya está actualizada.","The card is already up to date."):tr("Progreso devuelto. Puedes exportar el respaldo anterior desde esta pantalla.","Progress returned. You can export the previous backup from this screen.")).setPositiveButton("OK",null).show());
            }).show();
        }catch(Exception e){error(e);}
    }
    private void exportPs1Backup(){if(!pauseSession())return;try{if(cardFile==null)throw new IOException(tr("Selecciona una tarjeta vinculada.","Select a linked card."));requestExport(PocketCardLink.backup(cardFile),"PS1-backup.mcd");}catch(Exception e){error(e);}}

    private void startSession(){
        if(!resumed)return;
        try{synchronized(lock){if(running)return;if(!biosFile.exists()||cardFile==null)throw new IOException(tr("Importa BIOS y tarjeta primero.","Import BIOS and card first."));if(handle==0){handle=PocketNative.open(load(biosFile),load(cardFile));if(handle==0)throw new IOException("Cannot load PocketStation");savedCard=load(cardFile);}
            int min=AudioTrack.getMinBufferSize(8000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
            try{audio=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).setAudioFormat(new AudioFormat.Builder().setSampleRate(8000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(Math.max(4096,min)).setTransferMode(AudioTrack.MODE_STREAM).build();audio.play();}catch(Exception e){if(audio!=null)audio.release();audio=null;Toast.makeText(this,tr("Audio no disponible","Audio unavailable"),Toast.LENGTH_LONG).show();}
            running=true;lastSave=SystemClock.uptimeMillis();frames=0;worker.post(tick);
        }play.setText(tr("Pausar y guardar","Pause and save"));getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}catch(Exception e){error(e);}
    }
    private final Runnable tick=new Runnable(){public void run(){long start=SystemClock.uptimeMillis();synchronized(lock){if(!running||handle==0)return;try{
        int[] pixels=new int[1024];short[] samples=new short[1024];int n=PocketNative.tick(handle,touchButtons|keyButtons,pixels,samples);
        if(n<0)throw new IOException(tr("El núcleo se ha detenido. Se conserva el último guardado válido; vuelve a abrir la tarjeta.","The core stopped. Last valid save is preserved; reopen the card."));
        lcd.pixels=pixels;lcd.postInvalidate();if(audio!=null&&n>0){if(muted)Arrays.fill(samples,(short)0);audio.write(samples,0,n,AudioTrack.WRITE_NON_BLOCKING);}
        if(start-lastSave>=5000){saveCard();lastSave=start;}
        frames++; // 31/31/31/32 ms avoids long-term drift at 32 Hz.
        worker.postDelayed(this,Math.max(1,(frames%4==0?32:31)-(SystemClock.uptimeMillis()-start)));
    }catch(Exception e){running=false;touchButtons=keyButtons=0;if(audio!=null){audio.release();audio=null;}runOnUiThread(()->{play.setText(tr("Iniciar / continuar","Start / resume"));getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);error(e);});}}}};
    private void saveCard() throws IOException {if(handle==0||cardFile==null)return;byte[] b=PocketNative.card(handle);if(b==null)throw new IOException(tr("No se puede guardar una sesión detenida por un fallo. Vuelve a elegir la tarjeta para recuperar el último guardado.","Cannot save a faulted session. Select the card again to recover the last save."));if(!Arrays.equals(b,savedCard)){store(cardFile,b);savedCard=b;}}
    private boolean pauseSession(){boolean saved=true;synchronized(lock){running=false;touchButtons=keyButtons=0;if(worker!=null)worker.removeCallbacks(tick);try{saveCard();}catch(Exception e){saved=false;error(e);}if(audio!=null){audio.pause();audio.flush();audio.release();audio=null;}}if(play!=null)play.setText(tr("Iniciar / continuar","Start / resume"));getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);return saved;}
    private void closeCore(){if(handle!=0){PocketNative.close(handle);handle=0;}}
    @Override protected void onResume(){super.onResume();resumed=true;}
    @Override protected void onPause(){resumed=false;pauseSession();super.onPause();}
    @Override protected void onDestroy(){pauseSession();synchronized(lock){closeCore();}if(thread!=null)thread.quitSafely();super.onDestroy();}
    @Override public boolean dispatchKeyEvent(KeyEvent event){int mask;switch(event.getKeyCode()){case KeyEvent.KEYCODE_DPAD_UP:mask=1;break;case KeyEvent.KEYCODE_DPAD_RIGHT:mask=2;break;case KeyEvent.KEYCODE_DPAD_DOWN:mask=4;break;case KeyEvent.KEYCODE_DPAD_LEFT:mask=8;break;case KeyEvent.KEYCODE_BUTTON_A:case KeyEvent.KEYCODE_BUTTON_X:mask=16;break;default:return super.dispatchKeyEvent(event);}if(!running)return super.dispatchKeyEvent(event);if(event.getAction()==KeyEvent.ACTION_DOWN)keyButtons|=mask;else if(event.getAction()==KeyEvent.ACTION_UP)keyButtons&=~mask;return true;}
    private final class LcdView extends View {
        volatile int[] pixels; private final Paint paint=new Paint(); LcdView(){super(PocketActivity.this);setContentDescription("PocketStation LCD");}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float side=Math.min(getWidth(),getHeight())-dp(16),x=(getWidth()-side)/2,y=(getHeight()-side)/2,cell=side/32;paint.setColor(0xFFB8C9A1);c.drawRect(x,y,x+side,y+side,paint);int[] p=pixels;if(p!=null)for(int i=0;i<1024;i++){paint.setColor(p[i]);float a=x+(i%32)*cell,b=y+(i/32)*cell;c.drawRect(a,b,a+cell+.5f,b+cell+.5f,paint);}}
    }
}
