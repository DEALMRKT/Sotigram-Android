from pathlib import Path
import re
base = Path("source/Sotigram-Android/app/src/main/java/com/sotigram/app")
api = base / "SupabaseApi.kt"
s = api.read_text(encoding="utf-8")
s = s.replace('''    val id: String,
    val title: String,
    val subtitle: String,
    val otherUserId: String,
    val lastMessage: String,
    val updatedAt: String
)
data class SotigramMessage''','''    val id: String,
    val kind: String,
    val title: String,
    val subtitle: String,
    val otherUserId: String,
    val lastMessage: String,
    val updatedAt: String,
    val canPost: Boolean = true
)
data class SotigramMessage''',1)
s = s.replace('data class SotigramMessage(val id: String, val senderId: String, val body: String, val createdAt: String)',
'''data class SotigramMessage(val id: String, val senderId: String, val body: String, val createdAt: String)
data class SotigramChannel(val id: String, val title: String, val description: String, val subscribers: Long)''',1)
a=s.index('    suspend fun chats(token: String, currentUserId: String): List<SotigramChat>')
b=s.index('\n    suspend fun messages(',a)
s=s[:a]+'''    suspend fun chats(token: String, currentUserId: String): List<SotigramChat> = withContext(Dispatchers.IO) {
        val memberships = getArray("/rest/v1/conversation_members?user_id=eq.$currentUserId&select=conversation_id,member_role", token)
        val result = mutableListOf<SotigramChat>()
        for (i in 0 until memberships.length()) {
            val membership = memberships.getJSONObject(i)
            val conversationId = membership.getString("conversation_id")
            val role = membership.optString("member_role", "member")
            val conv = getArray("/rest/v1/conversations?id=eq.$conversationId&select=id,title,kind,updated_at", token).optJSONObject(0) ?: continue
            val kind = conv.optString("kind", "direct")
            val members = getArray("/rest/v1/conversation_members?conversation_id=eq.$conversationId&user_id=neq.$currentUserId&select=user_id", token)
            val otherId = if (kind == "direct" && members.length() > 0) members.getJSONObject(0).optString("user_id") else ""
            val person = if (otherId.isNotBlank()) getArray("/rest/v1/profiles?id=eq.$otherId&select=username,display_name", token).optJSONObject(0) else null
            val latest = getArray("/rest/v1/messages?conversation_id=eq.$conversationId&select=body,created_at&order=created_at.desc&limit=1", token).optJSONObject(0)
            val title = if (kind == "direct") person?.optString("display_name")?.takeIf { it.isNotBlank() } ?: conv.optString("title", "Чат") else conv.optString("title", if (kind == "channel") "Канал" else "Группа")
            val subtitle = when (kind) { "channel" -> "Канал Sotigram"; "group" -> "Групповой чат"; else -> person?.optString("username")?.let { "@" + it } ?: "Sotigram" }
            result.add(SotigramChat(conversationId, kind, title, subtitle, otherId, latest?.optString("body") ?: "Начните общение", conv.optString("updated_at"), kind != "channel" || role in listOf("owner", "admin", "editor")))
        }
        result.sortedByDescending { it.updatedAt }
    }

    suspend fun createGroup(token: String, title: String, memberIds: List<String>): String = withContext(Dispatchers.IO) {
        val ids = JSONArray()
        memberIds.distinct().forEach { ids.put(it) }
        request("/rest/v1/rpc/sotigram_create_group", JSONObject().put("group_title", title.trim()).put("member_ids", ids).toString(), "POST", token).trim().trim('"')
    }

    suspend fun createChannel(token: String, title: String, description: String): String = withContext(Dispatchers.IO) {
        request("/rest/v1/rpc/sotigram_create_channel", JSONObject().put("channel_title", title.trim()).put("channel_description", description.trim()).toString(), "POST", token).trim().trim('"')
    }

    suspend fun discoverChannels(token: String): List<SotigramChannel> = withContext(Dispatchers.IO) {
        val rows = JSONArray(request("/rest/v1/rpc/sotigram_discover_channels", "{}", "POST", token))
        (0 until rows.length()).map { i -> rows.getJSONObject(i).let { SotigramChannel(it.getString("id"), it.optString("title"), it.optString("description"), it.optLong("subscribers")) } }
    }

    suspend fun joinChannel(token: String, channelId: String) = withContext(Dispatchers.IO) {
        request("/rest/v1/rpc/sotigram_join_channel", JSONObject().put("target_channel", channelId).toString(), "POST", token)
    }
''' + s[b:]
a=s.index('    suspend fun openDirectChat(')
b=s.index('\n    suspend fun sendMessage(',a)
s=s[:a]+'''    suspend fun openDirectChat(token: String, currentUserId: String, otherUserId: String, displayTitle: String): String = withContext(Dispatchers.IO) {
        val id = request("/rest/v1/rpc/sotigram_create_direct_conversation", JSONObject().put("other_user", otherUserId).toString(), "POST", token).trim().trim('"')
        if (id.isBlank()) throw IOException("Не удалось открыть диалог.")
        id
    }
'''+s[b:]
api.write_text(s)

main=base/"MainActivity.kt"
s=main.read_text()
s=s.replace('    var contacts by remember { mutableStateOf<List<SotigramProfile>>(emptyList()) }','    var contacts by remember { mutableStateOf<List<SotigramProfile>>(emptyList()) }\n    var channels by remember { mutableStateOf<List<SotigramChannel>>(emptyList()) }\n    var createDialog by remember { mutableStateOf(false) }',1)
s=s.replace('''                val freshBalance = SupabaseApi.walletBalance(session.accessToken, session.userId)
                Triple(freshChats, freshContacts, freshBalance)
            }.onSuccess { (c, u, balance) -> chats = c; contacts = u; starsBalance = balance }''','''                val freshBalance = SupabaseApi.walletBalance(session.accessToken, session.userId)
                val freshChannels = SupabaseApi.discoverChannels(session.accessToken)
                chats = freshChats; contacts = freshContacts; starsBalance = freshBalance; channels = freshChannels
            }.onSuccess { }''',1)
s=s.replace('Triple("Звонки", Icons.Default.Call, 2)','Triple("Каналы", Icons.Default.Campaign, 2)',1)
s=s.replace('if (tab == 0 || tab == 1) FloatingActionButton(onClick = { tab = 1 }, containerColor = TgBlue, contentColor = Color.White, shape = CircleShape) { Icon(Icons.Default.Edit, "Новое сообщение") }','if (tab == 0 || tab == 2) FloatingActionButton(onClick = { createDialog = true }, containerColor = TgBlue, contentColor = Color.White, shape = CircleShape) { Icon(Icons.Default.Add, "Создать группу или канал") }',1)
s=s.replace('''                    if (loading && chats.isEmpty()) LoadingState()
                    else if (chats.isEmpty()) EmptyState("Пока нет сообщений", "Найди человека в контактах и начни диалог", Icons.Default.ChatBubbleOutline)
                    else LazyColumn(Modifier.fillMaxSize()) {
                        items(chats.filter { it.title.contains(search, true) || it.subtitle.contains(search, true) }, key = { it.id }) { chat ->
                            ChatRow(chat) { selectedChat = chat }
                        }
                    }''','''                    val myChats = chats.filter { it.kind != "channel" }
                    if (loading && myChats.isEmpty()) LoadingState()
                    else if (myChats.isEmpty()) EmptyState("Пока нет сообщений", "Начни диалог или создай группу кнопкой +", Icons.Default.ChatBubbleOutline)
                    else LazyColumn(Modifier.fillMaxSize()) {
                        items(myChats.filter { it.title.contains(search, true) || it.subtitle.contains(search, true) }, key = { it.id }) { chat -> ChatRow(chat) { selectedChat = chat } }
                    }''',1)
s=re.sub(r'SotigramChat\(id, user\.displayName, .*?user\.id, "Начните общение", ""\)',
         'SotigramChat(id, "direct", user.displayName, "@" + user.username, user.id, "Начните общение", "", true)',s,count=1)
s=s.replace('''                2 -> {
                    TopHeader("Звонки", "Sotigram", { }, Icons.Default.Info)
                    EmptyState("Звонки появятся позже", "Голосовые и видеозвонки пока не подключены", Icons.Default.Call)
                }
                3 -> ProfileScreen(profile, starsBalance, onLogout)''','''                2 -> {
                    TopHeader("Каналы", "Публичные каналы Sotigram", { refresh() }, Icons.Default.Refresh)
                    SearchBar(search, { search = it }, "Найти канал")
                    if (channels.isEmpty()) EmptyState("Пока нет каналов", "Создай свой канал кнопкой +", Icons.Default.Campaign)
                    else LazyColumn(Modifier.fillMaxSize()) {
                        items(channels.filter { it.title.contains(search, true) || it.description.contains(search, true) }, key = { it.id }) { channel ->
                            ChannelRow(channel, chats.any { it.id == channel.id }) {
                                val joined = chats.firstOrNull { it.id == channel.id }
                                if (joined != null) selectedChat = joined else scope.launch {
                                    runCatching { SupabaseApi.joinChannel(session.accessToken, channel.id) }
                                        .onSuccess { refresh(); selectedChat = SotigramChat(channel.id, "channel", channel.title, "Канал", "", "", "", false) }
                                        .onFailure { error = it.message }
                                }
                            }
                        }
                    }
                }
                3 -> ProfileScreen(profile, starsBalance, onLogout)''',1)
needle='''            error?.let { Text(it, color = Color(0xFFD34B4B), fontSize = 12.sp, modifier = Modifier.padding(12.dp)) }
        }
    }
}

@Composable
private fun TopHeader'''
repl='''            error?.let { Text(it, color = Color(0xFFD34B4B), fontSize = 12.sp, modifier = Modifier.padding(12.dp)) }
        }
        if (createDialog) CreateConversationDialog(contacts, if (tab == 2) "channel" else "group", { createDialog = false }) { kind, title, description, ids ->
            createDialog = false
            scope.launch {
                runCatching { if (kind == "group") SupabaseApi.createGroup(session.accessToken, title, ids) else SupabaseApi.createChannel(session.accessToken, title, description) }
                    .onSuccess { id -> refresh(); selectedChat = SotigramChat(id, kind, title, if (kind == "channel") "Канал" else "Групповой чат", "", "", "", true) }
                    .onFailure { error = it.message }
            }
        }
    }
}

@Composable
private fun ChannelRow(channel: SotigramChannel, joined: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color.White).clickable(onClick = onClick).padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0xFFE5F3FC)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Campaign, null, tint = TgBlue) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(channel.title, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(channel.description.ifBlank { "Публичный канал" }, color = Muted, fontSize = 12.sp)
            Text(channel.subscribers.toString() + " подписчиков", color = Muted, fontSize = 10.sp)
        }
        Text(if (joined) "Открыть" else "Подписаться", color = TgBlue, fontSize = 12.sp)
    }
    HorizontalDivider(color = Divider)
}

@Composable
private fun CreateConversationDialog(contacts: List<SotigramProfile>, initialKind: String, onDismiss: () -> Unit, onCreate: (String,String,String,List<String>) -> Unit) {
    var kind by remember { mutableStateOf(initialKind) }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Создать в Sotigram") }, text = {
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(kind == "group", { kind = "group" }, label = { Text("Группа") }, leadingIcon = { Icon(Icons.Default.Groups, null) })
                FilterChip(kind == "channel", { kind = "channel" }, label = { Text("Канал") }, leadingIcon = { Icon(Icons.Default.Campaign, null) })
            }
            OutlinedTextField(title, { title = it.take(80) }, label = { Text(if (kind == "group") "Название группы" else "Название канала") }, singleLine = true)
            if (kind == "channel") OutlinedTextField(description, { description = it.take(500) }, label = { Text("Описание канала") }, minLines = 2)
            else {
                Text("Участники", color = Muted, fontSize = 12.sp)
                LazyColumn(Modifier.heightIn(max = 180.dp)) {
                    items(contacts, key = { it.id }) { user ->
                        Row(Modifier.fillMaxWidth().clickable { selected = if (user.id in selected) selected-user.id else selected+user.id }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(user.id in selected, { checked -> selected = if (checked) selected+user.id else selected-user.id })
                            Text(user.displayName + "  @" + user.username)
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { if (title.isNotBlank()) onCreate(kind,title.trim(),description.trim(),selected.toList()) }, enabled = title.isNotBlank()) { Text("Создать") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

@Composable
private fun TopHeader'''
if needle not in s: raise SystemExit("Main UI insertion point not found")
s=s.replace(needle,repl,1)
s=s.replace('''        Avatar(chat.title)
        Spacer(Modifier.width(12.dp))''','''        if (chat.kind == "channel") Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0xFFE5F3FC)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Campaign, null, tint = TgBlue) }
        else if (chat.kind == "group") Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0xFFEAF6EF)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Groups, null, tint = Color(0xFF299D65)) }
        else Avatar(chat.title)
        Spacer(Modifier.width(12.dp))''',1)
s=s.replace('''                Text(chat.subtitle, color = Muted, fontSize = 11.sp)''','''                Text(if (chat.kind == "channel") "Канал • публикации" else chat.subtitle, color = Muted, fontSize = 11.sp)''',1)
s=s.replace('''        Row(Modifier.fillMaxWidth().background(Color(0xFFF7F8FA)).padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {}) { Icon(Icons.Default.AttachFile, null, tint = Muted) }''','''        if (!chat.canPost) Text("Публиковать могут только администраторы канала", color = Muted, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().background(Color.White).padding(12.dp))
        else Row(Modifier.fillMaxWidth().background(Color(0xFFF7F8FA)).padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {}) { Icon(Icons.Default.AttachFile, null, tint = Muted) }''',1)
main.write_text(s)
print("Integrated groups and channels.")
