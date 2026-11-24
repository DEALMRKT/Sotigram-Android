from pathlib import Path

base = Path("source/Sotigram-Android/app/src/main/java/com/sotigram/app")
api = base / "SupabaseApi.kt"
text = api.read_text(encoding="utf-8")
needle = """        validateCredentials(username, password)
        val payload = JSONObject()
"""
replacement = """        validateCredentials(username, password)
        val availability = request(
            "/rest/v1/rpc/sotigram_username_available",
            JSONObject().put("_username", username).toString(),
            "POST",
            null
        ).trim()
        if (availability != "true") {
            throw IOException("Это имя пользователя уже занято или зарезервировано. Выбери другое.")
        }
        val payload = JSONObject()
"""
if needle not in text:
    raise SystemExit("Could not locate registration block; refusing to patch an unknown source version.")
text = text.replace(needle, replacement, 1)
api.write_text(text, encoding="utf-8")
print("Patched username availability check in SupabaseApi.register().")

main = base / "MainActivity.kt"
text = main.read_text(encoding="utf-8")
old_items = '''                val items = listOf(Triple("Чаты", Icons.Default.Chat, 0), Triple("Контакты", Icons.Default.PeopleAlt, 1), Triple("Звонки", Icons.Default.Call, 2), Triple("Профиль", Icons.Default.Person, 3))
                items.forEach { (label, icon, index) ->
'''
new_items = '''                val items = mutableListOf(Triple("Чаты", Icons.Default.Chat, 0), Triple("Контакты", Icons.Default.PeopleAlt, 1), Triple("Звонки", Icons.Default.Call, 2), Triple("Профиль", Icons.Default.Person, 3))
                val isOwner = profile?.username?.equals("Razrab", ignoreCase = true) == true
                if (isOwner) items.add(Triple("Владелец", Icons.Default.Settings, 4))
                items.forEach { (label, icon, index) ->
'''
if old_items not in text:
    raise SystemExit("Could not find navigation bar block; refusing to patch unknown UI.")
text = text.replace(old_items, new_items, 1)
old_when = '''                else -> ProfileScreen(profile, starsBalance, onLogout)
'''
new_when = '''                3 -> ProfileScreen(profile, starsBalance, onLogout)
                4 -> if (profile?.username?.equals("Razrab", ignoreCase = true) == true) {
                    OwnerDashboard(
                        ownerName = profile?.displayName ?: "Razrab",
                        usersCount = contacts.size + 1,
                        chatsCount = chats.size,
                        starsBalance = starsBalance,
                        onRefresh = { refresh() }
                    )
                } else {
                    ProfileScreen(profile, starsBalance, onLogout)
                }
'''
if old_when not in text:
    raise SystemExit("Could not find profile tab branch; refusing to patch unknown UI.")
text = text.replace(old_when, new_when, 1)
if "private fun OwnerDashboard(" not in text:
    text += '''

@Composable
private fun OwnerDashboard(
    ownerName: String,
    usersCount: Int,
    chatsCount: Int,
    starsBalance: Long,
    onRefresh: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(Page).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Панель владельца", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("Sotigram • полный доступ", color = Muted, fontSize = 13.sp)
            }
            Icon(Icons.Default.VerifiedUser, contentDescription = "Владелец", tint = TgBlue, modifier = Modifier.size(30.dp))
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(Color.White).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text("ВЛАДЕЛЕЦ", color = TgBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(ownerName, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("@Razrab • роль owner", color = Muted, fontSize = 13.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OwnerMetric("Пользователи", usersCount.toString(), Modifier.weight(1f))
            OwnerMetric("Чаты", chatsCount.toString(), Modifier.weight(1f))
        }
        OwnerMetric("Баланс Sotigram", starsBalance.toString(), Modifier.fillMaxWidth())
        Text("УПРАВЛЕНИЕ", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        OwnerAction("Пользователи", "Просмотр зарегистрированных аккаунтов", Icons.Default.PeopleAlt)
        OwnerAction("Роли и доступ", "Владелец, администраторы и модераторы", Icons.Default.Security)
        OwnerAction("Безопасность", "Права доступа контролируются сервером Supabase", Icons.Default.Shield)
        OwnerAction("Система", "Обновить данные панели и статистику", Icons.Default.Refresh, onRefresh)
        Text(
            "Важно: интерфейс панели показывается аккаунту Razrab. Реальные административные операции должны дополнительно проверять роль owner на сервере.",
            color = Muted, fontSize = 11.sp
        )
    }
}

@Composable
private fun OwnerMetric(title: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(title, color = Muted, fontSize = 12.sp)
        Text(value, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun OwnerAction(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: (() -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
            .background(Color.White)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = TgBlue, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = Muted, fontSize = 11.sp)
        }
        if (onClick != null) Icon(Icons.Default.ChevronRight, null, tint = Muted)
    }
}
'''
main.write_text(text, encoding="utf-8")
print("Added a Razrab-only owner navigation tab and owner dashboard.")
