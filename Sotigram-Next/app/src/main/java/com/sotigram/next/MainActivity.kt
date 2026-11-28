package com.sotigram.next

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Ink=Color(0xFF101828)
private val Muted=Color(0xFF8490A5)
private val Accent=Color(0xFF5B7CFA)
private val Canvas=Color(0xFFF4F6FB)
private val SurfaceWhite=Color.White

class MainActivity:ComponentActivity(){
    private lateinit var api:SupabaseApi
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        api=SupabaseApi(this)
        setContent{SotigramApp(api)}
    }
}

private data class ActiveCall(val id:String,val peerName:String,val caller:Boolean,val video:Boolean)
private data class StoryCard(val username:String,val caption:String,val created:String)

@Composable
private fun SotigramApp(api:SupabaseApi){
    var session by remember{mutableStateOf(api.savedSession())}
    var chats by remember{mutableStateOf(emptyList<ChatItem>())}
    var selected by remember{mutableStateOf<ChatItem?>(null)}
    var activeCall by remember{mutableStateOf<ActiveCall?>(null)}
    var incoming by remember{mutableStateOf<CallTicket?>(null)}
    var incomingName by remember{mutableStateOf("Пользователь Sotigram")}
    var page by remember{mutableStateOf("Чаты")}
    var banner by remember{mutableStateOf("")}
    val scope=rememberCoroutineScope()

    LaunchedEffect(session?.userId){
        if(session==null){chats= emptyList();return@LaunchedEffect}
        while(true){
            try{
                val data=withContext(Dispatchers.IO){api.conversations()}
                chats=data
                val call=withContext(Dispatchers.IO){api.incomingCall()}
                if(call!=null&&activeCall==null&&incoming?.id!=call.id){
                    incoming=call
                    incomingName=withContext(Dispatchers.IO){
                        api.searchUsers(call.callerId).firstOrNull()?.displayName?: "Входящий звонок"
                    }
                }
            }catch(_:Exception){}
            delay(2500)
        }
    }

    MaterialTheme(colorScheme=lightColorScheme(primary=Accent,background=Canvas,surface=SurfaceWhite)){
        when{
            session==null->AuthScreen(api){session=it}
            activeCall!=null->CallScreen(api,activeCall!!,onFinish={
                scope.launch{withContext(Dispatchers.IO){try{api.callAction("end",activeCall!!.id)}catch(_:Exception){}};activeCall=null}
            },onError={banner=it})
            selected!=null->ChatScreen(api,selected!!,onBack={selected=null},onCall={video->
                val chat=selected!!
                if(chat.otherUserId==null){banner="Звонки доступны в личных чатах";return@ChatScreen}
                scope.launch{
                    try{
                        val ticket=withContext(Dispatchers.IO){api.createCall(chat.otherUserId,video)}
                        activeCall=ActiveCall(ticket.id,chat.title,true,ticket.video)
                    }catch(e:Exception){banner=e.message?:"Не удалось начать звонок"}
                }
            })
            else->MainShell(api,session!!,chats,page,onPage={page=it},onChat={selected=it},onDirect={user->
                scope.launch{
                    try{
                        val id=withContext(Dispatchers.IO){api.direct(user.id)}
                        chats=withContext(Dispatchers.IO){api.conversations()}
                        selected=chats.firstOrNull{it.id==id}?:ChatItem(id,"direct",user.displayName,user.id,user.username)
                    }catch(e:Exception){banner=e.message?:"Не удалось открыть чат"}
                }
            },onLogout={api.signOut();session=null;selected=null},onBanner={banner=it})
        }
        if(incoming!=null&&activeCall==null&&session!=null){
            AlertDialog(onDismissRequest={},title={Text("Входящий звонок")},text={Text("$incomingName звонит тебе")},confirmButton={
                TextButton(onClick={
                    val c=incoming!!
                    scope.launch{
                        try{withContext(Dispatchers.IO){api.callAction("accept",c.id)};activeCall=ActiveCall(c.id,incomingName,false,c.video)}
                        catch(e:Exception){banner=e.message?:"Не удалось принять звонок"}
                        incoming=null
                    }
                }){Text("Принять")}
            },dismissButton={TextButton(onClick={
                val c=incoming!!
                scope.launch{withContext(Dispatchers.IO){try{api.callAction("decline",c.id)}catch(_:Exception){}};incoming=null}
            }){Text("Отклонить")}})
        }
        if(banner.isNotBlank()){
            AlertDialog(onDismissRequest={banner=""},confirmButton={TextButton(onClick={banner=""}){Text("ОК")}},text={Text(banner)})
        }
    }
}

@Composable
private fun AuthScreen(api:SupabaseApi,onSuccess:(SotigramSession)->Unit){
    var register by remember{mutableStateOf(true)}
    var username by remember{mutableStateOf("")}
    var display by remember{mutableStateOf("")}
    var password by remember{mutableStateOf("")}
    var reveal by remember{mutableStateOf(false)}
    var loading by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    val scope=rememberCoroutineScope()
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF0C1731),Color(0xFF172B50),Color(0xFF304B80))))){
        Box(Modifier.size(260.dp).offset(x=190.dp,y=(-65).dp).blur(65.dp).background(Color(0x665B7CFA),CircleShape))
        Box(Modifier.size(220.dp).offset(x=(-100).dp,y=540.dp).blur(70.dp).background(Color(0x5554D6C8),CircleShape))
        Column(Modifier.fillMaxSize().padding(horizontal=24.dp),horizontalAlignment=Alignment.CenterHorizontally){
            Spacer(Modifier.height(54.dp))
            Box(Modifier.size(68.dp).clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(Color(0xFF8DA5FF),Color(0xFF536FF0)))),contentAlignment=Alignment.Center){Icon(Icons.Default.Send,null,tint=Color.White,modifier=Modifier.size(31.dp))}
            Spacer(Modifier.height(15.dp))
            Text("sotigram",color=Color.White,fontSize=31.sp,fontWeight=FontWeight.ExtraBold,letterSpacing=(-1).sp)
            Text("ТВОЁ ОБЩЕНИЕ. ТВОИ ПРАВИЛА.",color=Color(0xFFB8C6E4),fontSize=10.sp,letterSpacing=2.sp)
            Spacer(Modifier.height(38.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(Color(0xF7FFFFFF)).border(1.dp,Color(0x66FFFFFF),RoundedCornerShape(30.dp)).padding(23.dp)){
                Text(if(register)"Создать аккаунт" else "С возвращением",color=Ink,fontSize=25.sp,fontWeight=FontWeight.Bold)
                Spacer(Modifier.height(7.dp))
                Text(if(register)"Твой новый мир общения начинается здесь" else "Войди, чтобы продолжить общение",color=Muted,fontSize=13.sp)
                Spacer(Modifier.height(22.dp))
                AuthField("Логин",username,{username=it},Icons.Default.AlternateEmail)
                if(register){Spacer(Modifier.height(12.dp));AuthField("Имя в профиле",display,{display=it},Icons.Default.Person)}
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value=password,onValueChange={password=it},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("Пароль")},leadingIcon={Icon(Icons.Default.Lock,null)},trailingIcon={IconButton(onClick={reveal=!reveal}){Icon(if(reveal)Icons.Default.VisibilityOff else Icons.Default.Visibility,null)}},visualTransformation=if(reveal)VisualTransformation.None else PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),shape=RoundedCornerShape(17.dp),colors=fieldColors())
                if(error.isNotBlank()){Spacer(Modifier.height(10.dp));Text(error,color=Color(0xFFCA3E56),fontSize=12.sp)}
                Spacer(Modifier.height(19.dp))
                Button(onClick={
                    loading=true;error=""
                    scope.launch{
                        try{val result=withContext(Dispatchers.IO){if(register)api.register(username,password,display)else api.login(username,password)};onSuccess(result)}
                        catch(e:Exception){error=e.message?:"Ошибка"}
                        finally{loading=false}
                    }
                },enabled=!loading&&username.isNotBlank()&&password.isNotBlank(),modifier=Modifier.fillMaxWidth().height(54.dp),shape=RoundedCornerShape(17.dp),colors=ButtonDefaults.buttonColors(containerColor=Accent)){
                    if(loading)CircularProgressIndicator(Modifier.size(20.dp),color=Color.White,strokeWidth=2.dp) else Text(if(register)"Создать аккаунт  →" else "Войти  →",fontWeight=FontWeight.Bold)
                }
                Spacer(Modifier.height(15.dp))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){
                    Text(if(register)"Уже есть аккаунт?" else "Впервые в Sotigram?",color=Muted,fontSize=12.sp)
                    Text(if(register)" Войти" else " Создать",color=Accent,fontSize=12.sp,fontWeight=FontWeight.Bold,modifier=Modifier.clickable{register=!register;error=""})
                }
            }
            Spacer(Modifier.weight(1f))
            Text("SOTIGRAM • PRIVATE MESSENGER",color=Color(0xFF9AABD0),fontSize=10.sp,letterSpacing=1.5.sp)
            Spacer(Modifier.height(20.dp))
        }
    }
}
@Composable private fun fieldColors()=OutlinedTextFieldDefaults.colors(focusedBorderColor=Accent,unfocusedBorderColor=Color(0xFFE2E7F0),focusedLabelColor=Accent,cursorColor=Accent)
@Composable private fun AuthField(label:String,value:String,onChange:(String)->Unit,leading:androidx.compose.ui.graphics.vector.ImageVector){
    OutlinedTextField(value=value,onValueChange=onChange,modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text(label)},leadingIcon={Icon(leading,null)},shape=RoundedCornerShape(17.dp),colors=fieldColors())
}

@Composable
private fun MainShell(api:SupabaseApi,session:SotigramSession,chats:List<ChatItem>,page:String,onPage:(String)->Unit,onChat:(ChatItem)->Unit,onDirect:(UserItem)->Unit,onLogout:()->Unit,onBanner:(String)->Unit){
    var query by remember{mutableStateOf("")}
    var users by remember{mutableStateOf(emptyList<UserItem>())}
    var showGroup by remember{mutableStateOf(false)}
    var groupTitle by remember{mutableStateOf("")}
    var wallet by remember{mutableStateOf(0L)}
    var nfts by remember{mutableStateOf(emptyList<CatalogItem>())}
    var nftCatalog by remember{mutableStateOf(emptyList<CatalogItem>())}
    var gifts by remember{mutableStateOf(emptyList<CatalogItem>())}
    var role by remember{mutableStateOf("user")}
    var stories by remember{mutableStateOf(emptyList<StoryCard>())}
    val scope=rememberCoroutineScope()
    LaunchedEffect(page,session.userId){
        if(page=="Профиль"||page=="Маркет"){
            try{wallet=withContext(Dispatchers.IO){api.walletBalance()};nfts=withContext(Dispatchers.IO){api.myNfts()};nftCatalog=withContext(Dispatchers.IO){api.nftCatalog()};gifts=withContext(Dispatchers.IO){api.gifts()};role=withContext(Dispatchers.IO){api.myRole()}}catch(_:Exception){}
        }
        if(page=="Истории"){
            try{
                val a=withContext(Dispatchers.IO){api.stories()}
                val rows=mutableListOf<StoryCard>()
                for(i in 0 until a.length()){
                    val o=a.getJSONObject(i);val user=withContext(Dispatchers.IO){api.profileById(o.optString("user_id"))}
                    rows.add(StoryCard(user?.displayName?:user?.username?: "Пользователь",o.optString("caption"),o.optString("created_at")))
                }
                stories=rows
            }catch(_:Exception){}
        }
    }
    Scaffold(containerColor=Canvas,bottomBar={
        NavigationBar(containerColor=Color.White,tonalElevation=12.dp){
            listOf("Чаты" to Icons.Default.ChatBubble,"Контакты" to Icons.Default.People,"Истории" to Icons.Default.AutoAwesome,"Профиль" to Icons.Default.Person).forEach{(label,icon)->
                NavigationBarItem(selected=page==label,onClick={onPage(label)},icon={Icon(icon,null)},label={Text(label,fontSize=10.sp)})
            }
        }
    },floatingActionButton={
        if(page=="Чаты")FloatingActionButton(onClick={showGroup=true},containerColor=Accent,contentColor=Color.White){Icon(Icons.Default.Add,null)}
    }){padding->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=18.dp)){
            Spacer(Modifier.height(15.dp))
            Row(verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){Text("Sotigram",color=Ink,fontSize=27.sp,fontWeight=FontWeight.ExtraBold);Text(when(page){"Чаты"->"Твои разговоры в одном месте";"Контакты"->"Найди друзей по логину";"Истории"->"Моменты сообщества";else->"Твой аккаунт и коллекция"},color=Muted,fontSize=12.sp)}
                if(page=="Чаты")IconButton(onClick={showGroup=true}){Icon(Icons.Default.GroupAdd,null,tint=Ink)}
            }
            Spacer(Modifier.height(16.dp))
            when(page){
                "Чаты"->{
                    LazyColumn(verticalArrangement=Arrangement.spacedBy(9.dp)){
                        items(chats,key={it.id}){chat->ChatRow(chat,onClick={onChat(chat)})}
                    }
                }
                "Контакты"->{
                    OutlinedTextField(value=query,onValueChange={query=it},modifier=Modifier.fillMaxWidth(),singleLine=true,placeholder={Text("Поиск по логину или имени")},leadingIcon={Icon(Icons.Default.Search,null)},shape=RoundedCornerShape(18.dp))
                    LaunchedEffect(query){if(query.length>=2){delay(300);users=withContext(Dispatchers.IO){api.searchUsers(query).filter{it.id!=session.userId}}}else users= emptyList()}
                    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){
                        items(users,key={it.id}){user->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White).clickable{onDirect(user)}.padding(14.dp),verticalAlignment=Alignment.CenterVertically){
                                Avatar(user.displayName.ifBlank{user.username})
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)){Text(user.displayName.ifBlank{user.username},fontWeight=FontWeight.SemiBold,color=Ink);Text("@${user.username}",color=Muted,fontSize=12.sp)}
                                Text("Написать",color=Accent,fontWeight=FontWeight.SemiBold,fontSize=12.sp)
                            }
                        }
                    }
                }
                "Истории"->{
                    if(stories.isEmpty())EmptyState("Пока нет историй","Когда пользователи начнут публиковать истории, они появятся здесь",Icons.Default.AutoAwesome)
                    else LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)){items(stories){s->Surface(color=Color.White,shape=RoundedCornerShape(18.dp)){Column(Modifier.padding(15.dp)){Text(s.username,color=Ink,fontWeight=FontWeight.Bold);Text(s.caption,color=Muted)}}}}
                }
                "Профиль"->{
                    Surface(color=Color.White,shape=RoundedCornerShape(24.dp),modifier=Modifier.fillMaxWidth()){
                        Column(Modifier.padding(20.dp)){
                            Row(verticalAlignment=Alignment.CenterVertically){Avatar(session.username);Spacer(Modifier.width(12.dp));Column{Text("@${session.username}",fontSize=20.sp,fontWeight=FontWeight.Bold,color=Ink);Text("SOTIGRAM MEMBER",fontSize=10.sp,color=Muted,letterSpacing=1.sp)}}
                            Spacer(Modifier.height(18.dp))
                            Text("Коллекционный номер",color=Muted,fontSize=12.sp)
                            Text("ST-${session.userId.replace("-","").take(8).uppercase()}",color=Accent,fontWeight=FontWeight.ExtraBold,fontSize=20.sp)
                            Spacer(Modifier.height(14.dp))
                            Text("⭐  $wallet Stars",color=Ink,fontWeight=FontWeight.Bold,fontSize=18.sp)
                            Spacer(Modifier.height(10.dp))
                            Text("Твоя коллекция • ${nfts.size} предметов",color=Ink,fontWeight=FontWeight.SemiBold)
                            nfts.take(4).forEach{Text("${it.emoji}  ${it.title}",color=Muted,modifier=Modifier.padding(top=6.dp))}
                            Spacer(Modifier.height(14.dp))
                            Button(onClick={onPage("Маркет")},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp)){Text("Открыть маркет подарков и NFT")}
                            if(role=="owner"||role=="admin"||role=="moderator"){
                                OutlinedButton(onClick={scope.launch{try{val data=withContext(Dispatchers.IO){api.ownerOverview()};onBanner(data.toString(2))}catch(e:Exception){onBanner(e.message?:"Нет доступа")}}},modifier=Modifier.fillMaxWidth()){Text("Панель управления • $role")}
                            }
                            OutlinedButton(onClick=onLogout,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Logout,null);Spacer(Modifier.width(7.dp));Text("Выйти из аккаунта")}
                        }
                    }
                }
                "Маркет"->{
                    Text("Коллекционные NFT",fontWeight=FontWeight.Bold,color=Ink,fontSize=18.sp)
                    LazyColumn(verticalArrangement=Arrangement.spacedBy(9.dp)){
                        items(nftCatalog,key={it.code}){item->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White).padding(14.dp),verticalAlignment=Alignment.CenterVertically){
                                Text(item.emoji,fontSize=30.sp);Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(item.title,color=Ink,fontWeight=FontWeight.Bold);Text("${item.rarity} • ${item.price} Stars",color=Muted,fontSize=11.sp)}
                                TextButton(onClick={scope.launch{try{withContext(Dispatchers.IO){api.buyNft(item.code)};wallet=withContext(Dispatchers.IO){api.walletBalance()};nfts=withContext(Dispatchers.IO){api.myNfts()};onBanner("Предмет добавлен в коллекцию")}catch(e:Exception){onBanner(e.message?:"Покупка не выполнена")}}}){Text("Купить")}
                            }
                        }
                        item { Text("Подарки каталога",color=Ink,fontWeight=FontWeight.Bold,fontSize=18.sp,modifier=Modifier.padding(top=10.dp)) }
                        items(gifts,key={it.code}){item->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White).padding(14.dp),verticalAlignment=Alignment.CenterVertically){
                                Text(item.emoji,fontSize=27.sp);Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(item.title,color=Ink,fontWeight=FontWeight.Bold);Text("${item.price} Stars",color=Muted,fontSize=12.sp)}
                            }
                        }
                        item { Text("Мои NFT: ${nfts.size} • Баланс: $wallet Stars",color=Accent,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(vertical=10.dp)) }
                    }
                }
            }
        }
    }
    if(showGroup){
        AlertDialog(onDismissRequest={showGroup=false},title={Text("Новая группа")},text={OutlinedTextField(value=groupTitle,onValueChange={groupTitle=it},label={Text("Название группы")},singleLine=true)},confirmButton={
            TextButton(onClick={
                scope.launch{try{withContext(Dispatchers.IO){api.createGroup(groupTitle, emptyList())};groupTitle="";showGroup=false;onBanner("Группа создана. Добавляй участников через поиск контактов.")}catch(e:Exception){onBanner(e.message?:"Ошибка создания группы")}}
            },enabled=groupTitle.isNotBlank()){Text("Создать")}
        },dismissButton={TextButton(onClick={showGroup=false}){Text("Отмена")}})
    }
}

@Composable
private fun ChatRow(chat:ChatItem,onClick:()->Unit){
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White).clickable(onClick=onClick).padding(13.dp),verticalAlignment=Alignment.CenterVertically){
        Avatar(chat.title)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)){Text(chat.title,color=Ink,fontWeight=FontWeight.SemiBold,fontSize=14.sp);Spacer(Modifier.height(4.dp));Text(chat.lastMessage.ifBlank{when(chat.kind){"group"->"Группа";"channel"->"Канал";else->"Начни общение"}},color=Muted,fontSize=12.sp,maxLines=1)}
        Text(when(chat.kind){"group"->"ГРУППА";"channel"->"КАНАЛ";else->"ЧАТ"},color=Accent,fontSize=9.sp,fontWeight=FontWeight.Bold)
    }
}
@Composable
private fun Avatar(label:String){
    Box(Modifier.size(50.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFFE7ECFF),Color(0xFFD8F5F0)))),contentAlignment=Alignment.Center){Text(label.take(1).uppercase(),color=Accent,fontSize=20.sp,fontWeight=FontWeight.Bold)}
}

@Composable
private fun ChatScreen(api:SupabaseApi,chat:ChatItem,onBack:()->Unit,onCall:(Boolean)->Unit){
    var messages by remember{mutableStateOf(emptyList<MessageItem>())}
    var draft by remember{mutableStateOf("")}
    var loading by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    LaunchedEffect(chat.id){
        while(true){try{messages=withContext(Dispatchers.IO){api.messages(chat.id)}}catch(_:Exception){};delay(2200)}
    }
    Column(Modifier.fillMaxSize().background(Canvas)){
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal=8.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,null)}
            Column(Modifier.weight(1f)){Text(chat.title,color=Ink,fontWeight=FontWeight.Bold,fontSize=17.sp);Text(if(chat.kind=="direct")"личный чат" else if(chat.kind=="group")"группа" else "канал",color=Muted,fontSize=11.sp)}
            if(chat.kind=="direct"){
                IconButton(onClick={onCall(false)}){Icon(Icons.Default.Call,null,tint=Accent)}
                IconButton(onClick={onCall(true)}){Icon(Icons.Default.Videocam,null,tint=Accent)}
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal=13.dp),reverseLayout=false,verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(vertical=14.dp)){
            items(messages,key={it.id}){m->
                val mine=m.senderId==api.savedSession()?.userId
                Row(Modifier.fillMaxWidth(),horizontalArrangement=if(mine)Arrangement.End else Arrangement.Start){
                    Surface(color=if(mine)Color(0xFFE4EAFF)else Color.White,shape=RoundedCornerShape(18.dp),modifier=Modifier.widthIn(max=300.dp)){
                        Column(Modifier.padding(horizontal=13.dp,vertical=9.dp)){
                            Text(m.body,color=Ink,fontSize=14.sp)
                            Text(m.createdAt.take(16).replace("T"," "),color=Muted,fontSize=9.sp,modifier=Modifier.align(Alignment.End))
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(9.dp),verticalAlignment=Alignment.CenterVertically){
            OutlinedTextField(value=draft,onValueChange={draft=it},modifier=Modifier.weight(1f),placeholder={Text("Сообщение…")},maxLines=4,shape=RoundedCornerShape(22.dp),colors=fieldColors())
            Spacer(Modifier.width(7.dp))
            IconButton(enabled=draft.isNotBlank()&&!loading,onClick={
                val text=draft.trim();draft="";loading=true
                scope.launch{try{withContext(Dispatchers.IO){api.sendMessage(chat.id,text)};messages=withContext(Dispatchers.IO){api.messages(chat.id)}}catch(_:Exception){draft=text}finally{loading=false}}
            }){Icon(Icons.Default.Send,null,tint=Accent)}
        }
    }
}

@Composable
private fun CallScreen(api:SupabaseApi,call:ActiveCall,onFinish:()->Unit,onError:(String)->Unit){
    val context= LocalContext.current
    val scope=rememberCoroutineScope()
    var room by remember{mutableStateOf<Room?>(null)}
    var status by remember{mutableStateOf(if(call.caller)"Вызов…" else "Подключение…")}
    var connected by remember{mutableStateOf(false)}
    var mic by remember{mutableStateOf(true)}
    var camera by remember{mutableStateOf(call.video)}
    var failed by remember{mutableStateOf("")}
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){grants->
        if(grants[Manifest.permission.RECORD_AUDIO]==true&&(!call.video||grants[Manifest.permission.CAMERA]==true))connected=true
        else failed="Нужен доступ к микрофону${if(call.video)" и камере" else ""} для звонка"
    }
    LaunchedEffect(call.id){
        val needed=mutableListOf(Manifest.permission.RECORD_AUDIO)
        if(call.video)needed.add(Manifest.permission.CAMERA)
        val missing=needed.filter{ContextCompat.checkSelfPermission(context,it)!=PackageManager.PERMISSION_GRANTED}
        if(missing.isEmpty())connected=true else permissions.launch(missing.toTypedArray())
    }
    LaunchedEffect(connected,call.id){
        if(!connected)return@LaunchedEffect
        try{
            if(call.caller){
                var state="ringing"
                repeat(30){if(state!="ringing")return@repeat;delay(1500);state=withContext(Dispatchers.IO){api.callStatus(call.id)}}
                if(state!="accepted"){failed=if(state=="declined")"Звонок отклонён" else "Нет ответа";return@LaunchedEffect}
            }
            val credentials=withContext(Dispatchers.IO){api.callCredentials(call.id)}
            val lk=LiveKit.create(context.applicationContext,RoomOptions())
            room=lk
            withContext(Dispatchers.IO){lk.connect(credentials.url,credentials.token)}
            lk.localParticipant.setMicrophoneEnabled(true)
            if(call.video)lk.localParticipant.setCameraEnabled(true)
            status=if(call.video)"Видеозвонок подключён" else "Аудиозвонок подключён"
        }catch(e:Exception){failed=e.message?:"Не удалось подключиться к звонку"}
    }
    DisposableEffect(room){
        onDispose{val current=room;if(current!=null)scope.launch{try{current.disconnect()}catch(_:Exception){}}}
    }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF101B36),Color(0xFF1E3764),Color(0xFF0B1428)))),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
        Box(Modifier.size(120.dp).clip(CircleShape).background(Color(0x335B7CFA)),contentAlignment=Alignment.Center){Text(call.peerName.take(1).uppercase(),fontSize=48.sp,color=Color.White,fontWeight=FontWeight.Bold)}
        Spacer(Modifier.height(25.dp))
        Text(call.peerName,color=Color.White,fontSize=26.sp,fontWeight=FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(failed.ifBlank{status},color=Color(0xFFCBD6EF),fontSize=14.sp)
        Spacer(Modifier.height(65.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(22.dp),verticalAlignment=Alignment.CenterVertically){
            FilledIconButton(onClick={scope.launch{try{room?.localParticipant?.setMicrophoneEnabled(!mic);mic=!mic}catch(e:Exception){failed=e.message?:"Ошибка микрофона"}}},colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color(0xFF31466C)),modifier=Modifier.size(62.dp)){Icon(if(mic)Icons.Default.Mic else Icons.Default.MicOff,null,tint=Color.White)}
            if(call.video)FilledIconButton(onClick={scope.launch{try{room?.localParticipant?.setCameraEnabled(!camera);camera=!camera}catch(e:Exception){failed=e.message?:"Ошибка камеры"}}},colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color(0xFF31466C)),modifier=Modifier.size(62.dp)){Icon(if(camera)Icons.Default.Videocam else Icons.Default.VideocamOff,null,tint=Color.White)}
            FilledIconButton(onClick=onFinish,colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color(0xFFE34D62)),modifier=Modifier.size(68.dp)){Icon(Icons.Default.CallEnd,null,tint=Color.White)}
        }
    }
}

@Composable
private fun EmptyState(title:String,subtitle:String,icon:androidx.compose.ui.graphics.vector.ImageVector){
    Column(Modifier.fillMaxWidth().padding(top=90.dp),horizontalAlignment=Alignment.CenterHorizontally){
        Box(Modifier.size(82.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFFE7ECFF)),contentAlignment=Alignment.Center){Icon(icon,null,tint=Accent,modifier=Modifier.size(35.dp))}
        Spacer(Modifier.height(18.dp));Text(title,color=Ink,fontSize=19.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(7.dp));Text(subtitle,color=Muted,fontSize=12.sp)
    }
}
