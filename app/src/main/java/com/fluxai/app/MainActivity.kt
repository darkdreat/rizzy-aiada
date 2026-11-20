package com.fluxai.app

import android.app.Activity
import android.content.*
import android.graphics.*
import android.hardware.display.*
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricPrompt
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.*

private const val PREF="fluxai_prefs"
private const val HIST="history"
private const val FAV="favorites"

class MainActivity: ComponentActivity() {
    private var projection:MediaProjection?=null
    private var reader:ImageReader?=null
    private var display:VirtualDisplay?=null
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FluxApp(onCapture={capture()}, initialPage=if(intent.getBooleanExtra("open_mode",false)) "mode" else "home") }
        if(intent.getBooleanExtra("quick_capture",false)) window.decorView.postDelayed({capture()},600)
    }
    private fun capture() {
        val mgr=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION") startActivityForResult(mgr.createScreenCaptureIntent(),7001)
    }
    @Deprecated("Deprecated")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=7001 || resultCode!=Activity.RESULT_OK || data==null) return
        val mgr=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection=mgr.getMediaProjection(resultCode,data)
        val dm=resources.displayMetrics; val w=dm.widthPixels; val h=dm.heightPixels
        reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2)
        projection?.registerCallback(object:MediaProjection.Callback(){ override fun onStop(){ display?.release(); reader?.close() } },Handler(Looper.getMainLooper()))
        display=projection?.createVirtualDisplay("FluxAI",w,h,dm.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader?.surface,null,null)
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val image=reader?.acquireLatestImage() ?: return@postDelayed
                val p=image.planes[0]; val stride=p.rowStride/p.pixelStride
                val raw=Bitmap.createBitmap(stride,h,Bitmap.Config.ARGB_8888)
                raw.copyPixelsFromBuffer(p.buffer); val shot=Bitmap.createBitmap(raw,0,0,w,h); image.close()
                display?.release(); reader?.close(); projection?.stop(); display=null; reader=null; projection=null
                window.decorView.post { (window.decorView.tag as? ((Bitmap)->Unit))?.invoke(shot) }
            } catch(_:Exception) { Toast.makeText(this,"Ekran görüntüsü alınamadı.",Toast.LENGTH_LONG).show() }
        },500)
    }
    override fun onDestroy(){display?.release();reader?.close();projection?.stop();super.onDestroy()}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FluxApp(onCapture:()->Unit, initialPage:String="home") {
    val ctx= LocalContext.current
    val prefs= remember { ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE) }
    val scope= rememberCoroutineScope()
    var page by remember { mutableStateOf(initialPage) }
    var screenshot by remember { mutableStateOf<Bitmap?>(null) }
    var selectedImage by remember { mutableStateOf<Bitmap?>(null) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(prefs.getString("mode","Arkadaş") ?: "Arkadaş") }
    var keepMode by remember { mutableStateOf(prefs.getBoolean("keepMode",false)) }
    var style by remember { mutableStateOf(prefs.getString("style","Cool") ?: "Cool") }
    var emoji by remember { mutableStateOf(prefs.getString("emoji","Az emojili") ?: "Az emojili") }
    var goal by remember { mutableStateOf(prefs.getString("goal","Sohbeti sürdürme") ?: "Sohbeti sürdürme") }
    var language by remember { mutableStateOf(prefs.getString("language","Otomatik") ?: "Otomatik") }
    var replyLength by remember { mutableStateOf(prefs.getString("replyLength","Orta") ?: "Orta") }
    var flirtLevel by remember { mutableStateOf(prefs.getString("flirtLevel","Dengeli") ?: "Dengeli") }
    var customInstruction by remember { mutableStateOf(prefs.getString("customInstruction","") ?: "") }
    var customStyle by remember { mutableStateOf(prefs.getString("customStyle","") ?: "") }
    var personProfile by remember { mutableStateOf(prefs.getString("personProfile","Genel") ?: "Genel") }
    var privateFilter by remember { mutableStateOf(prefs.getBoolean("privateFilter",true)) }
    var autoDelete by remember { mutableStateOf(prefs.getBoolean("autoDelete",true)) }
    var historyQuery by remember { mutableStateOf("") }
    var keyTestStatus by remember { mutableStateOf("") }
    var theme by remember { mutableStateOf(prefs.getString("theme","Kırmızı-siyah") ?: "Kırmızı-siyah") }
    var apiKey by remember { mutableStateOf(prefs.getString("apiKey",BuildConfig.GROQ_API_KEY) ?: "") }
    var pin by remember { mutableStateOf(prefs.getString("pin","") ?: "") }
    var lockEnabled by remember { mutableStateOf(prefs.getBoolean("lock",false)) }
    var showLock by remember { mutableStateOf(prefs.getBoolean("lock",false) && (prefs.getString("pin","") ?: "").isNotBlank()) }
    var pinInput by remember { mutableStateOf("") }
    var recognized by remember { mutableStateOf("") }
    var answers by remember { mutableStateOf(listOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var rerolls by remember { mutableIntStateOf(3) }
    val red=Color(0xFFE50932)
    val black=theme!="Kırmızı-siyah"

    fun addHistory(list:List<String>) {
        val old=JSONArray(prefs.getString(HIST,"[]") ?: "[]")
        val entry=JSONObject().put("mode",mode).put("style",style).put("answers",JSONArray(list)).put("date",System.currentTimeMillis())
        val next=JSONArray().put(entry)
        for(i in 0 until min(old.length(),24)) next.put(old.getJSONObject(i))
        prefs.edit().putString(HIST,next.toString()).apply()
    }
    fun favorites():MutableList<String> {
        val a=JSONArray(prefs.getString(FAV,"[]") ?: "[]")
        return MutableList(a.length()){a.optString(it)}
    }
    fun runAI(img:Bitmap?, textOverride:String="") {
        if(apiKey.isBlank()){error="Groq API anahtarı bulunamadı. Ayarlara ekleyin veya GitHub Secret GROQ_API_KEY tanımlayın.";page="settings";return}
        busy=true;error=""
        scope.launch {
            try {
                val pair=withContext(Dispatchers.IO){Groq.generate(apiKey,img,textOverride,mode,style,emoji,goal,language,replyLength,flirtLevel,customInstruction,customStyle,personProfile)}
                recognized=pair.first; answers=pair.second; addHistory(answers); page="results"; if(autoDelete){ selectedImage?.recycle(); selectedImage=null; screenshot?.recycle(); screenshot=null }
            } catch(e:Exception){error=e.message ?: "Analiz başarısız."}
            finally{busy=false}
        }
    }

    MaterialTheme(colorScheme=darkColorScheme(primary=red,secondary=Color(0xFFFF6680),background=Color.Black,surface=Color(0xFF24151A))) {
        if(showLock) {
            Column(Modifier.fillMaxSize().background(Color.Black).padding(24.dp),verticalArrangement=Arrangement.Center) {
                Text("FluxAI kilitli",fontSize=24.sp,fontWeight=FontWeight.Bold)
                OutlinedTextField(pinInput,{pinInput=it.filter(Char::isDigit).take(8)},label={Text("PIN")})
                Button(onClick={if(pinInput==pin)showLock=false},modifier=Modifier.fillMaxWidth()){Text("PIN ile aç")}
                OutlinedButton(onClick={
                    val activity=ctx as? FragmentActivity
                    if(activity!=null){
                        val prompt=BiometricPrompt(activity,ContextCompat.getMainExecutor(ctx),object:BiometricPrompt.AuthenticationCallback(){
                            override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult){showLock=false}
                        })
                        val info=BiometricPrompt.PromptInfo.Builder().setTitle("FluxAI kilidini aç").setSubtitle("Biyometrik doğrulama kullan").setNegativeButtonText("PIN kullan").build()
                        prompt.authenticate(info)
                    }
                },modifier=Modifier.fillMaxWidth()){Text("Biyometrik ile aç")}
            }
        } else Scaffold(containerColor=if(black)Color.Black else Color(0xFF10090C),
            topBar={TopAppBar(title={Text("FLUXAI",fontWeight=FontWeight.Black,letterSpacing=3.sp)},actions={IconButton(onClick={page="settings"}){Icon(Icons.Default.Settings,null)}},colors=TopAppBarDefaults.topAppBarColors(containerColor=Color.Transparent))}) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                when(page) {
                    "home" -> {
                        Logo()
                        Text("Ekrandaki konuşmayı analiz et, sana uygun cevapları bul.",color=Color.LightGray)
                        Button(onClick=onCapture,modifier=Modifier.fillMaxWidth().height(56.dp)){Icon(Icons.Default.Crop,null);Text("  Ekrandan alan seç")}
                        OutlinedButton(onClick={page="settings"},modifier=Modifier.fillMaxWidth()){Text("Groq API anahtarı ve AI ayarları")}
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick={page="favorites"},modifier=Modifier.weight(1f)){Text("Favoriler")}
                            OutlinedButton(onClick={page="history"},modifier=Modifier.weight(1f)){Text("Geçmiş")}
                        }
                        Button(onClick={
                            if(!Settings.canDrawOverlays(ctx)) ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:${ctx.packageName}")))
                            else ContextCompat.startForegroundService(ctx,Intent(ctx,BubbleService::class.java))
                        },modifier=Modifier.fillMaxWidth()){Text("Yüzen FluxAI logosunu etkinleştir")}
                    }
                    "crop" -> {
                        Text("Ekran görüntüsünde alan seç",fontSize=20.sp,fontWeight=FontWeight.Bold)
                        CropView(screenshot){selectedImage=it;if(privateFilter)showPrivacyDialog=true else if(keepMode)runAI(it)else page="mode"}
                        if(showPrivacyDialog) AlertDialog(
                            onDismissRequest={showPrivacyDialog=false;selectedImage?.recycle();selectedImage=null},
                            title={Text("Gizlilik kontrolü")},
                            text={Text("Seçtiğin alanda ad, profil fotoğrafı, telefon numarası veya başka özel bilgi var mı? Gereksiz alanları kırp. Görüntü, cevap üretmek için Groq servisine gönderilecek.")},
                            confirmButton={TextButton(onClick={showPrivacyDialog=false;if(keepMode)runAI(selectedImage)else page="mode"}){Text("Kontrol ettim, devam et")}},
                            dismissButton={TextButton(onClick={showPrivacyDialog=false;selectedImage?.recycle();selectedImage=null}){Text("Vazgeç")}}
                        )
                        TextButton(onClick={page="home"}){Text("Geri")}
                    }
                    "mode" -> {
                        Text("Hangi modu kullanacağız?",fontSize=21.sp,fontWeight=FontWeight.Bold)
                        Button(onClick={mode="Arkadaş";prefs.edit().putString("mode",mode).apply();if(selectedImage!=null)runAI(selectedImage)else page="home"},modifier=Modifier.fillMaxWidth()){Text("Arkadaş Olmak")}
                        Button(onClick={mode="Sevgili";prefs.edit().putString("mode",mode).apply();if(selectedImage!=null)runAI(selectedImage)else page="home"},modifier=Modifier.fillMaxWidth()){Text("Sevgili Olmak")}
                        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(keepMode,{keepMode=it;prefs.edit().putBoolean("keepMode",it).apply()});Text("Her zaman bu modu kullan")}
                        TextButton(onClick={page="crop"}){Text("Geri")}
                    }
                    "results" -> {
                        Text("3 mesaj önerin",fontSize=22.sp,fontWeight=FontWeight.Bold)
                        Text("$mode • $style • $replyLength • $personProfile",color=Color.LightGray)
                        if(recognized.isNotBlank()) varDummy(recognized){recognized=it}
                        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            itemsIndexed(answers){index,item->
                                Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF29171D))) {
                                    Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                        Text("ÖNERİ ${index+1}",color=Color(0xFFFF7187),fontWeight=FontWeight.Bold)
                                        Text(item,fontSize=16.sp)
                                        Row {
                                            OutlinedButton(onClick={val cm=ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager;cm.setPrimaryClip(ClipData.newPlainText("FluxAI",item));Toast.makeText(ctx,"Kopyalandı",Toast.LENGTH_SHORT).show()}){Icon(Icons.Default.ContentCopy,null);Text("Kopyala")}
                                            Spacer(Modifier.width(6.dp))
                                            OutlinedButton(onClick={val f=favorites();if(!f.contains(item))f.add(0,item);prefs.edit().putString(FAV,JSONArray(f).toString()).apply();Toast.makeText(ctx,"Favorilere eklendi",Toast.LENGTH_SHORT).show()}){Icon(Icons.Default.FavoriteBorder,null);Text("Favori")}
                                            OutlinedButton(onClick={runAI(null,"$recognized\nMevcut cevap: $item\nBu cevabı daha doğal, akıcı ve bağlama uygun şekilde yeniden yaz; üç farklı seçenek üret.")},enabled=!busy){Text("Yeniden yaz / geliştir")}
                                        }
                                    }
                                }
                            }
                        }
                        Button(onClick={if(rerolls>0){rerolls--;runAI(selectedImage,recognized)}},enabled=rerolls>0&&!busy,modifier=Modifier.fillMaxWidth()){Text("Reroll ($rerolls/3)")}
                        OutlinedButton(onClick={page="crop"},modifier=Modifier.fillMaxWidth()){Text("Geri / Yeni alan seç")}
                    }
                    "favorites" -> {
                        Text("Favori mesajlar",fontSize=22.sp,fontWeight=FontWeight.Bold)
                        val f=favorites()
                        LazyColumn(Modifier.weight(1f)){itemsIndexed(f){i,s->
                            Card(Modifier.fillMaxWidth().padding(vertical=4.dp),colors=CardDefaults.cardColors(containerColor=Color(0xFF29171D))){Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically){Text(s,Modifier.weight(1f));IconButton(onClick={val n=favorites();n.removeAt(i);prefs.edit().putString(FAV,JSONArray(n).toString()).apply();page="home";page="favorites"}){Icon(Icons.Default.Delete,null)}}}
                        }}
                        TextButton(onClick={page="home"}){Text("Geri")}
                    }
                    "history" -> {
                        Text("Geçmiş öneriler",fontSize=22.sp,fontWeight=FontWeight.Bold)
                        OutlinedTextField(historyQuery,{historyQuery=it},label={Text("Mesaj geçmişinde ara")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                        val h=JSONArray(prefs.getString(HIST,"[]") ?: "[]")
                        val historyItems=(0 until h.length()).map{h.getJSONObject(it)}.filter{e->historyQuery.isBlank() || e.toString().contains(historyQuery,true)}
                        LazyColumn(Modifier.weight(1f)){items(historyItems.size){i->
                            val e=historyItems[i];Card(Modifier.fillMaxWidth().padding(vertical=4.dp),colors=CardDefaults.cardColors(containerColor=Color(0xFF29171D))){Column(Modifier.padding(12.dp)){Text("${e.optString("mode")} • ${e.optString("style")}",color=Color(0xFFFF7187));val a=e.optJSONArray("answers")?:JSONArray();for(j in 0 until a.length())Text("• ${a.optString(j)}")}}
                        }}
                        TextButton(onClick={page="home"}){Text("Geri")}
                    }
                    "settings" -> {
                        Text("Kişiselleştirme",fontSize=22.sp,fontWeight=FontWeight.Bold)
                        Drop("Mesaj tarzı",style,listOf("Cool","Samimi","Özgüvenli")){style=it;prefs.edit().putString("style",it).apply()}
                        Drop("Emoji",emoji,listOf("Emojisiz","Az emojili","Bol emojili")){emoji=it;prefs.edit().putString("emoji",it).apply()}
                        Drop("Konuşma amacı",goal,listOf("Tanışma","Sohbeti sürdürme","İltifat","Barışma","Buluşma")){goal=it;prefs.edit().putString("goal",it).apply()}
                        Drop("Dil / çeviri",language,listOf("Otomatik","Türkçe","English","Deutsch","Français","Español","Italiano","Русский","العربية")){language=it;prefs.edit().putString("language",it).apply()}
                        Drop("Cevap uzunluğu",replyLength,listOf("Kısa","Orta","Uzun")){replyLength=it;prefs.edit().putString("replyLength",it).apply()}
                        Drop("Flört seviyesi",flirtLevel,listOf("Kapalı","Hafif","Dengeli","Belirgin")){flirtLevel=it;prefs.edit().putString("flirtLevel",it).apply()}
                        Drop("Kişi profili",personProfile,listOf("Genel","Yeni tanıştığım kişi","Arkadaş","Yakın arkadaş","İş / okul")){personProfile=it;prefs.edit().putString("personProfile",it).apply()}
                        OutlinedTextField(customStyle,{customStyle=it;prefs.edit().putString("customStyle",it).apply()},label={Text("Özel cevap tarzı oluştur")},modifier=Modifier.fillMaxWidth(),minLines=2)
                        OutlinedTextField(customInstruction,{customInstruction=it;prefs.edit().putString("customInstruction",it).apply()},label={Text("Kendi AI talimatın")},modifier=Modifier.fillMaxWidth(),minLines=3)
                        Drop("Tema",theme,listOf("Kırmızı-siyah","Tamamen siyah","AMOLED")){theme=it;prefs.edit().putString("theme",it).apply()}
                        Text("Groq API bağlantısı",fontWeight=FontWeight.Bold)
                        OutlinedTextField(apiKey,{apiKey=it;prefs.edit().putString("apiKey",it).apply()},label={Text("Groq API anahtarı")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                        Button(onClick={keyTestStatus="Bağlantı test ediliyor…";scope.launch{keyTestStatus=withContext(Dispatchers.IO){Groq.test(apiKey)} }},enabled=apiKey.isNotBlank()&&!busy,modifier=Modifier.fillMaxWidth()){Text("API anahtarını test et")}
                        if(keyTestStatus.isNotBlank()) Text(keyTestStatus,color=if(keyTestStatus.contains("başarılı"))Color(0xFF75D69A) else Color.LightGray,fontSize=12.sp)
                        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(keepMode,{keepMode=it;prefs.edit().putBoolean("keepMode",it).apply()});Text("Son kullanılan kişiyi ve modu hatırla")}
                        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(privateFilter,{privateFilter=it;prefs.edit().putBoolean("privateFilter",it).apply()});Text("Göndermeden önce gizlilik uyarısı")}
                        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(autoDelete,{autoDelete=it;prefs.edit().putBoolean("autoDelete",it).apply()});Text("Analiz sonrası ekran görüntüsünü sil")}
                        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(lockEnabled,{lockEnabled=it;prefs.edit().putBoolean("lock",it).apply();if(it&&pin.isNotBlank())showLock=true});Text("Uygulama PIN kilidi")}
                        var pinNew by remember{mutableStateOf("")}
                        OutlinedTextField(pinNew,{pinNew=it.filter(Char::isDigit).take(8)},label={Text("Yeni PIN (4-8 rakam)")},singleLine=true)
                        Button(onClick={if(pinNew.length>=4){pin=pinNew;prefs.edit().putString("pin",pin).apply();pinNew="";Toast.makeText(ctx,"PIN kaydedildi",Toast.LENGTH_SHORT).show()}},enabled=pinNew.length>=4){Text("PIN kaydet")}
                        Button(onClick={if(!Settings.canDrawOverlays(ctx))ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:${ctx.packageName}")))else ContextCompat.startForegroundService(ctx,Intent(ctx,BubbleService::class.java))},modifier=Modifier.fillMaxWidth()){Text("Yüzen logoyu etkinleştir")}
                        OutlinedButton(onClick={keepMode=false;prefs.edit().putBoolean("keepMode",false).apply()},modifier=Modifier.fillMaxWidth()){Text("Kalıcı mod seçimini sıfırla")}
                        Text("Gizlilik: kırpma öncesi ekranı kontrol et; ad, profil fotoğrafı ve özel bilgileri seçime dahil etme. Bu filtre otomatik yüz/metin bulanıklaştırma değildir.",color=Color.Gray,fontSize=11.sp)
                        Text("API anahtarı bu cihazda uygulama tercihleri içinde saklanır. APK içindeki anahtarlar çıkarılabilir; herkese açık dağıtımda sunucu proxy kullan.",color=Color.Gray,fontSize=11.sp)
                        Text("Bildirim erişimi henüz otomatik cevap göndermiyor; bildirim metni arka planda okunmaz.",color=Color.Gray,fontSize=11.sp)
                        TextButton(onClick={page="home"}){Text("Geri")}
                    }
                }
                if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                if(error.isNotBlank())Text(error,color=Color(0xFFFF8191),fontSize=12.sp)
            }
        }
    }
    SideEffect { (ctx as? Activity)?.window?.decorView?.tag={b:Bitmap->screenshot=b;page="crop"} }
}

@Composable private fun Logo(){
    val c=LocalContext.current;val id=remember{c.resources.getIdentifier("flux_logo","drawable",c.packageName)}
    if(id!=0)Image(androidx.compose.ui.res.painterResource(id),null,Modifier.fillMaxWidth().height(210.dp),contentScale=androidx.compose.ui.layout.ContentScale.Fit)
}
@Composable private fun varDummy(text:String,onChange:(String)->Unit){
    var v by remember(text){mutableStateOf(text)}
    OutlinedTextField(v,{v=it;onChange(it)},label={Text("AI'nın okuduğu metni düzelt")},modifier=Modifier.fillMaxWidth(),minLines=2)
}
@Composable private fun Drop(label:String,value:String,items:List<String>,change:(String)->Unit){
    var open by remember{mutableStateOf(false)}
    Box { OutlinedButton(onClick={open=true},modifier=Modifier.fillMaxWidth()){Text("$label: $value",Modifier.weight(1f));Icon(Icons.Default.ArrowDropDown,null)}
        DropdownMenu(open,{open=false}){items.forEach{DropdownMenuItem(text={Text(it)},onClick={change(it);open=false})}}
    }
}
@Composable private fun CropView(src:Bitmap?,confirm:(Bitmap)->Unit){
    var a by remember{mutableStateOf(Offset.Zero)};var b by remember{mutableStateOf(Offset.Zero)}
    var canvasSize by remember{mutableStateOf(androidx.compose.ui.geometry.Size.Zero)}
    val image=src?.asImageBitmap()
    if(src==null){Text("Ekran görüntüsü alınamadı. Ana ekrana dönüp tekrar deneyin.")}
    else {
        Canvas(Modifier.fillMaxWidth().height(430.dp).background(Color.Black).pointerInput(src){
            detectDragGestures(onDragStart={a=it;b=it},onDrag={c,d->c.consume();b+=d})
        }){
            canvasSize=size
            val s=min(size.width/image!!.width,size.height/image.height)
            val w=image.width*s;val h=image.height*s;val x=(size.width-w)/2;val y=(size.height-h)/2
            drawImage(image,dstOffset=androidx.compose.ui.unit.IntOffset(x.toInt(),y.toInt()),dstSize=androidx.compose.ui.unit.IntSize(w.toInt(),h.toInt()))
            if(a!=Offset.Zero||b!=Offset.Zero){val l=min(a.x,b.x).coerceIn(x,x+w);val t=min(a.y,b.y).coerceIn(y,y+h);val r=max(a.x,b.x).coerceIn(x,x+w);val bot=max(a.y,b.y).coerceIn(y,y+h);drawRect(Color(0x55FFFFFF),Offset(l,t),androidx.compose.ui.geometry.Size(r-l,bot-t));drawRect(Color.Red,Offset(l,t),androidx.compose.ui.geometry.Size(r-l,bot-t),style=Stroke(2.dp.toPx()))}
        }
        Button(onClick={
            val s=min(canvasSize.width/image!!.width,canvasSize.height/image.height)
            val w=image.width*s;val h=image.height*s;val x=(canvasSize.width-w)/2;val y=(430.dp.value-h)/2
            val l=min(a.x,b.x).coerceIn(x,x+w);val t=min(a.y,b.y).coerceIn(y,y+h);val r=max(a.x,b.x).coerceIn(x,x+w);val bot=max(a.y,b.y).coerceIn(y,y+h)
            if(r-l>8&&bot-t>8){val sx=((l-x)/w*src.width).toInt().coerceIn(0,src.width-1);val sy=((t-y)/h*src.height).toInt().coerceIn(0,src.height-1);val sw=((r-l)/w*src.width).toInt().coerceIn(1,src.width-sx);val sh=((bot-t)/h*src.height).toInt().coerceIn(1,src.height-sy);confirm(Bitmap.createBitmap(src,sx,sy,sw,sh))}
        },modifier=Modifier.fillMaxWidth()){Text("Onayla")}
    }
}
private object Groq {
    private val http=OkHttpClient.Builder().callTimeout(90,TimeUnit.SECONDS).build()
    fun test(key:String):String {
        if(key.isBlank()) return "API anahtarı boş."
        return try {
            val body=JSONObject().put("model",BuildConfig.GROQ_MODEL).put("messages",JSONArray().put(JSONObject().put("role","user").put("content","Reply with OK"))).put("max_completion_tokens",8)
            val req=Request.Builder().url("https://api.groq.com/openai/v1/chat/completions").addHeader("Authorization","Bearer $key").post(body.toString().toRequestBody("application/json".toMediaType())).build()
            http.newCall(req).execute().use{res->if(res.isSuccessful) "Groq API bağlantısı başarılı." else "Bağlantı başarısız (${res.code}): ${res.body?.string().orEmpty().take(120)}"}
        } catch(e:Exception){"Test başarısız: ${e.message ?: "Bağlantı hatası"}"}
    }
    fun generate(key:String,bmp:Bitmap?,override:String,mode:String,style:String,emoji:String,goal:String,lang:String,length:String,flirt:String,instructions:String,customStyle:String,person:String):Pair<String,List<String>> {
        val prompt="""Analyze the visible chat. Treat RIGHT bubbles as the user and LEFT bubbles as the other person; focus on the latest incoming message. Identify whether the latest message is a joke, question, or serious topic. Estimate interest only cautiously from observable conversational signals; say uncertain when evidence is insufficient. Generate exactly 3 respectful, natural reply suggestions in three styles: Cool, Samimi, Özgüvenli. Relationship mode=$mode; selected style=$style; emoji=$emoji; goal=$goal; output language=$lang; answer length=$length; flirt intensity=$flirt; conversation profile=$person; custom style=$customStyle; user instructions=$instructions. Do not manipulate, pressure, sexualize, or assume romantic interest. Keep flirt setting off unless explicitly selected. Return JSON only: {"read":"short transcript plus concise interest estimate and message type","suggestions":["reply 1","reply 2","reply 3"]}. ${if(override.isNotBlank())"Corrected transcript: $override" else ""}"""
        val parts=JSONArray().put(JSONObject().put("type","text").put("text",prompt))
        if(bmp!=null){val ratio=min(1f,1280f/max(bmp.width,bmp.height));val scaled=if(ratio<1)Bitmap.createScaledBitmap(bmp,(bmp.width*ratio).toInt().coerceAtLeast(1),(bmp.height*ratio).toInt().coerceAtLeast(1),true)else bmp;val out=ByteArrayOutputStream();scaled.compress(Bitmap.CompressFormat.JPEG,78,out);val b64=android.util.Base64.encodeToString(out.toByteArray(),android.util.Base64.NO_WRAP);parts.put(JSONObject().put("type","image_url").put("image_url",JSONObject().put("url","data:image/jpeg;base64,$b64")))}
        val body=JSONObject().put("model",BuildConfig.GROQ_MODEL).put("messages",JSONArray().put(JSONObject().put("role","user").put("content",parts))).put("temperature",0.8).put("max_completion_tokens",420).put("response_format",JSONObject().put("type","json_object"))
        val req=Request.Builder().url("https://api.groq.com/openai/v1/chat/completions").addHeader("Authorization","Bearer $key").post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use{res->val raw=res.body?.string().orEmpty();if(!res.isSuccessful)throw IllegalStateException("Groq API ${res.code}: ${raw.take(200)}");val content=JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");val json=JSONObject(content);val arr=json.optJSONArray("suggestions")?:JSONArray();val list=(0 until arr.length()).map{arr.optString(it)}.filter{it.isNotBlank()}.take(3);if(list.size!=3)throw IllegalStateException("AI üç öneri döndürmedi.");return json.optString("read") to list}
    }
}
