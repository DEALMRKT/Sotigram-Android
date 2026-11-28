package com.sotigram.next

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF101828)
private val Muted = Color(0xFF8490A5)
private val Accent = Color(0xFF5B7CFA)
private val Canvas = Color(0xFFF4F6FB)
private val Surface = Color(0xFFFFFFFF)

class MainActivity : ComponentActivity() {
    private lateinit var api: SupabaseApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = SupabaseApi(this)
        setContent { SotigramApp(api) }
    }
}

@Composable
private fun SotigramApp(api: SupabaseApi) {
    var session by remember { mutableStateOf(api.savedSession()) }
    var page by remember { mutableStateOf("Чаты") }
    MaterialTheme(colorScheme = lightColorScheme(primary = Accent, background = Canvas, surface = Surface)) {
        if (session == null) AuthScreen(api) { session = it }
        else MainShell(session!!, page, onPage = { page = it }, onLogout = { api.signOut(); session = null })
    }
}

@Composable
private fun AuthScreen(api: SupabaseApi, onSuccess: (SotigramSession) -> Unit) {
    var register by remember { mutableStateOf(true) }
    var username by remember { mutableStateOf("") }
    var display by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF0C1731), Color(0xFF172B50), Color(0xFF304B80))))) {
        Box(Modifier.size(260.dp).offset(x = 190.dp, y = (-65).dp).blur(65.dp).background(Color(0x665B7CFA), CircleShape))
        Box(Modifier.size(220.dp).offset(x = (-100).dp, y = 540.dp).blur(70.dp).background(Color(0x5554D6C8), CircleShape))
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(54.dp))
            Box(Modifier.size(68.dp).clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(Color(0xFF8DA5FF), Color(0xFF536FF0)))), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Send, null, tint = Color.White, modifier = Modifier.size(31.dp))
            }
            Spacer(Modifier.height(15.dp))
            Text("sotigram", color = Color.White, fontSize = 31.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
            Text("ТВОЁ ОБЩЕНИЕ. ТВОИ ПРАВИЛА.", color = Color(0xFFB8C6E4), fontSize = 10.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(38.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(Color(0xF7FFFFFF)).border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(30.dp)).padding(23.dp)) {
                Text(if (register) "Создать аккаунт" else "С возвращением", color = Ink, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(7.dp))
                Text(if (register) "Твой новый мир общения начинается здесь" else "Войди, чтобы продолжить общение", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(22.dp))
                AuthField("Логин", username, { username = it }, leading = Icons.Default.AlternateEmail)
                if (register) {
                    Spacer(Modifier.height(12.dp))
                    AuthField("Имя в профиле", display, { display = it }, leading = Icons.Default.Person)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Пароль") }, leadingIcon = { Icon(Icons.Default.Lock, null) }, trailingIcon = { IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) } }, visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), shape = RoundedCornerShape(17.dp), colors = fieldColors())
                if (error.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(error, color = Color(0xFFCA3E56), fontSize = 12.sp)
                }
                Spacer(Modifier.height(19.dp))
                Button(onClick = {
                    loading = true; error = ""
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                if (register) api.register(username, password, display) else api.login(username, password)
                            }
                            onSuccess(result)
                        } catch (e: Exception) { error = e.message ?: "Неизвестная ошибка" }
                        finally { loading = false }
                    }
                }, enabled = !loading && username.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(17.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                    if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text(if (register) "Создать аккаунт  →" else "Войти  →", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(15.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (register) "Уже есть аккаунт?" else "Впервые в Sotigram?", color = Muted, fontSize = 12.sp)
                    Text(if (register) " Войти" else " Создать", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { register = !register; error = "" })
                }
            }
            Spacer(Modifier.weight(1f))
            Text("SOTIGRAM • PRIVATE MESSENGER", color = Color(0xFF9AABD0), fontSize = 10.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = Color(0xFFE2E7F0), focusedLabelColor = Accent, cursorColor = Accent)

@Composable
private fun AuthField(label: String, value: String, onChange: (String) -> Unit, leading: androidx.compose.ui.graphics.vector.ImageVector) {
    OutlinedTextField(value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(label) }, leadingIcon = { Icon(leading, null) }, shape = RoundedCornerShape(17.dp), colors = fieldColors())
}

@Composable
private fun MainShell(session: SotigramSession, page: String, onPage: (String) -> Unit, onLogout: () -> Unit) {
    Scaffold(containerColor = Canvas, bottomBar = {
        NavigationBar(containerColor = Color.White, tonalElevation = 12.dp) {
            listOf("Чаты" to Icons.Default.ChatBubble, "Контакты" to Icons.Default.People, "Истории" to Icons.Default.Circle, "Профиль" to Icons.Default.Person).forEach { (label, icon) ->
                NavigationBarItem(selected = page == label, onClick = { onPage(label) }, icon = { Icon(icon, null) }, label = { Text(label, fontSize = 10.sp) })
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(15.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sotigram", color = Ink, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
                    Text(when(page) { "Чаты" -> "Твои разговоры в одном месте"; "Контакты" -> "Люди Sotigram"; "Истории" -> "Моменты на 24 часа"; else -> "Твой аккаунт" }, color = Muted, fontSize = 12.sp)
                }
                IconButton(onClick = {}) { Icon(Icons.Default.Search, null, tint = Ink) }
                IconButton(onClick = {}) { Icon(Icons.Default.Edit, null, tint = Ink) }
            }
            Spacer(Modifier.height(20.dp))
            when (page) {
                "Чаты" -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        listOf("Все", "Личные", "Группы", "Каналы").forEachIndexed { i, label ->
                            Surface(color = if (i == 0) Accent else Color.White, shape = RoundedCornerShape(50), modifier = Modifier.clickable { }) {
                                Text(label, color = if (i == 0) Color.White else Muted, modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(6) { i ->
                            val names = listOf("Избранное", "Sotigram News", "Общий чат", "Александр", "Дизайн-команда", "Идеи и предложения")
                            val previews = listOf("Сохраняй важные сообщения", "Новая версия уже скоро ✨", "Кто сегодня на связи?", "Отправил тебе фото", "Макеты готовы", "Есть новая идея")
                            ChatRow(names[i], previews[i], listOf("★","S","✦","A","D","✧")[i], i)
                        }
                    }
                }
                "Контакты" -> EmptyState("Люди рядом с тобой", "Здесь появятся пользователи и поиск по логину", Icons.Default.People)
                "Истории" -> EmptyState("Твои истории", "Делись фото и видео, которые исчезнут через 24 часа", Icons.Default.Circle)
                else -> {
                    Surface(color = Color.White, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp)) {
                            Text("@${session.username}", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Ink)
                            Text("Коллекционный номер", color = Muted, fontSize = 12.sp)
                            Text("ST-${session.userId.replace("-", "").take(8).uppercase()}", color = Accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Spacer(Modifier.height(18.dp))
                            listOf("⭐ Stars и история операций", "🎁 Подарки и коллекция", "💎 NFT-маркет", "🛡️ Панель управления").forEach { Text(it, color = Ink, modifier = Modifier.fillMaxWidth().clickable { }.padding(vertical = 13.dp)) }
                            OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("Выйти из аккаунта") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatRow(title: String, preview: String, letter: String, index: Int) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White).clickable { }.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(listOf(Color(0xFFE8EDFF), Color(0xFFE1F6F2), Color(0xFFFFEBD9))[index % 3]), contentAlignment = Alignment.Center) {
            Text(letter, color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text(preview, color = Muted, fontSize = 12.sp, maxLines = 1)
        }
        Text(if (index == 1) "12:40" else "вчера", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(Modifier.fillMaxWidth().padding(top = 90.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(82.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFFE7ECFF)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Accent, modifier = Modifier.size(35.dp)) }
        Spacer(Modifier.height(18.dp))
        Text(title, color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(7.dp))
        Text(subtitle, color = Muted, fontSize = 12.sp)
    }
}
