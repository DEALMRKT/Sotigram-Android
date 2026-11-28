package com.sotigram.next

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class SotigramSession(val accessToken:String,val userId:String,val username:String)
data class ChatItem(val id:String,val kind:String,val title:String,val otherUserId:String?=null,val otherUsername:String?=null,val lastMessage:String="")
data class UserItem(val id:String,val username:String,val displayName:String,val collectionNumber:String="")
data class MessageItem(val id:String,val conversationId:String,val senderId:String,val body:String,val createdAt:String)
data class CallTicket(val id:String,val status:String,val callerId:String="",val calleeId:String="",val video:Boolean=false)
data class CallCredentials(val url:String,val token:String,val callId:String)
data class CatalogItem(val code:String,val title:String,val description:String,val emoji:String,val price:Long,val rarity:String="")

class SupabaseApi(context:Context) {
    companion object {
        const val BASE_URL="https://tryhiueaukgahtonattf.supabase.co"
        private const val PUBLIC_KEY="sb_publishable_u5mIqR_t_UTj2rIgcCqR7A_tmIbTXSs"
        private val JSON="application/json; charset=utf-8".toMediaType()
    }
    private val prefs=context.getSharedPreferences("sotigram_session",Context.MODE_PRIVATE)
    private val http=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(25,TimeUnit.SECONDS).build()

    fun savedSession():SotigramSession? {
        val token=prefs.getString("token",null)?:return null
        val uid=prefs.getString("uid",null)?:return null
        val username=prefs.getString("username",null)?:return null
        return SotigramSession(token,uid,username)
    }
    fun signOut(){prefs.edit().clear().apply()}

    @Synchronized fun register(usernameRaw:String,password:String,displayName:String):SotigramSession {
        val username=usernameRaw.trim().removePrefix("@").lowercase()
        require(username.matches(Regex("^[a-z0-9_]{3,32}$"))){"Логин: 3–32 символа, латиница, цифры и _"}
        require(password.length>=8){"Пароль должен содержать минимум 8 символов"}
        val auth=authRequest("/auth/v1/signup",JSONObject().put("email","${username}@users.sotigram.invalid").put("password",password)
            .put("data",JSONObject().put("username",username).put("display_name",displayName.ifBlank{username})))
        val access=auth.optString("access_token")
        val user=auth.optJSONObject("user")?:auth
        val uid=user.optString("id")
        if(access.isBlank()||uid.isBlank()) throw IllegalStateException("Supabase не выдал сессию. Для входа по логину отключи подтверждение email в Auth или настрой реальную почту.")
        val profile=JSONObject().put("id",uid).put("username",username).put("display_name",displayName.ifBlank{username})
            .put("collection_number","ST-"+uid.replace("-","").take(8).uppercase())
        request("/rest/v1/profiles?on_conflict=id","POST",profile,access,"resolution=merge-duplicates,return=minimal")
        save(access,auth.optString("refresh_token"),uid,username)
        return SotigramSession(access,uid,username)
    }
    @Synchronized fun login(usernameRaw:String,password:String):SotigramSession {
        val username=usernameRaw.trim().removePrefix("@").lowercase()
        require(username.matches(Regex("^[a-z0-9_]{3,32}$"))){"Проверь логин"}
        val auth=authRequest("/auth/v1/token?grant_type=password",JSONObject().put("email","${username}@users.sotigram.invalid").put("password",password))
        val access=auth.optString("access_token")
        val uid=auth.optJSONObject("user")?.optString("id").orEmpty()
        if(access.isBlank()||uid.isBlank()) throw IllegalStateException("Не удалось войти")
        save(access,auth.optString("refresh_token"),uid,username)
        return SotigramSession(access,uid,username)
    }
    private fun save(token:String,refresh:String,uid:String,username:String) {
        prefs.edit().putString("token",token).putString("refresh",refresh).putString("uid",uid).putString("username",username).apply()
    }
    private fun token()=prefs.getString("token",null)?:throw IllegalStateException("Сначала войди в аккаунт")
    private fun authRequest(path:String,body:JSONObject)=JSONObject(request(path,"POST",body,null,null,true))
    private fun enc(s:String)=URLEncoder.encode(s,"UTF-8")
    private fun request(path:String,method:String="GET",body:JSONObject?=null,bearer:String?=token(),prefer:String?=null,auth:Boolean=false):String {
        val b=Request.Builder().url(BASE_URL+path).addHeader("apikey",PUBLIC_KEY).addHeader("Accept","application/json")
        if(auth) b.addHeader("Content-Type","application/json")
        else { if(bearer!=null)b.addHeader("Authorization","Bearer $bearer") }
        if(prefer!=null)b.addHeader("Prefer",prefer)
        val rb=body?.toString()?.toRequestBody(JSON)
        b.method(method,if(method=="GET"||method=="DELETE")null else (rb?: "{}".toRequestBody(JSON)))
        http.newCall(b.build()).execute().use { res ->
            val raw=res.body?.string().orEmpty()
            if(!res.isSuccessful) {
                val msg=try { JSONObject(raw).optString("message").ifBlank{JSONObject(raw).optString("msg").ifBlank{JSONObject(raw).optString("hint")}} } catch(_:Exception){raw}
                throw IllegalStateException(msg.ifBlank{"Сервер ответил ${res.code}"})
            }
            return raw
        }
    }
    private fun array(path:String)=JSONArray(request(path))
    private fun rpc(name:String,args:JSONObject=JSONObject())=request("/rest/v1/rpc/$name","POST",args)
    fun myRole():String {
        val a=array("/rest/v1/user_roles?select=role&user_id=eq.${savedSession()?.userId}")
        return if(a.length()>0)a.getJSONObject(0).optString("role") else "user"
    }
    fun searchUsers(q:String):List<UserItem> {
        val term=enc("username.ilike.*${q.trim()}*,display_name.ilike.*${q.trim()}*")
        val a=array("/rest/v1/profiles?select=id,username,display_name,collection_number&or=($term)&limit=30&order=username.asc")
        return (0 until a.length()).map { val o=a.getJSONObject(it); UserItem(o.getString("id"),o.getString("username"),o.optString("display_name"),o.optString("collection_number")) }
    }
    fun conversations():List<ChatItem> {
        val uid=savedSession()?.userId?:return emptyList()
        val a=array("/rest/v1/conversation_members?select=conversation_id,member_role,conversations(id,kind,title,description,created_by)&user_id=eq.$uid&order=joined_at.desc")
        val out=mutableListOf<ChatItem>()
        for(i in 0 until a.length()) {
            val row=a.getJSONObject(i); val c=row.optJSONObject("conversations")?:continue
            val id=c.optString("id"); val kind=c.optString("kind")
            val title=if(kind=="direct") "Личный чат" else c.optString("title","Sotigram")
            var otherId:String?=null; var otherName:String?=null; var otherUsername:String?=null
            if(kind=="direct") {
                val m=array("/rest/v1/conversation_members?select=user_id&conversation_id=eq.$id&user_id=neq.$uid&limit=1")
                if(m.length()>0){otherId=m.getJSONObject(0).optString("user_id"); val p=array("/rest/v1/profiles?select=username,display_name&id=eq.$otherId&limit=1");if(p.length()>0){otherUsername=p.getJSONObject(0).optString("username");otherName=p.getJSONObject(0).optString("display_name").ifBlank{otherUsername.orEmpty()}}}
            }
            val msg=array("/rest/v1/messages?select=body&conversation_id=eq.$id&deleted_at=is.null&order=created_at.desc&limit=1")
            out.add(ChatItem(id,kind,if(kind=="direct")otherName?:title else title,otherId,otherUsername,msg.optJSONObject(0)?.optString("body").orEmpty()))
        }
        return out
    }
    fun profileById(userId:String):UserItem? { val a=array("/rest/v1/profiles?select=id,username,display_name,collection_number&id=eq.$userId&limit=1"); if(a.length()==0)return null; val o=a.getJSONObject(0); return UserItem(o.getString("id"),o.getString("username"),o.optString("display_name"),o.optString("collection_number")) }
    fun direct(userId:String):String=rpc("sotigram_create_direct_conversation",JSONObject().put("other_user",userId))
    fun createChannel(title:String,description:String="")=rpc("sotigram_create_channel",JSONObject().put("channel_title",title).put("channel_description",description)).trim('"').trim('"')
    fun createGroup(title:String,memberIds:List<String>):String {
        val ids=JSONArray();memberIds.forEach{ids.put(it)}
        return rpc("sotigram_create_group",JSONObject().put("group_title",title).put("member_ids",ids)).trim('"')
    }
    fun messages(conversationId:String):List<MessageItem> {
        val a=array("/rest/v1/messages?select=id,conversation_id,sender_id,body,created_at&conversation_id=eq.$conversationId&deleted_at=is.null&order=created_at.asc&limit=150")
        return (0 until a.length()).map{val o=a.getJSONObject(it);MessageItem(o.getString("id"),o.getString("conversation_id"),o.getString("sender_id"),o.optString("body"),o.optString("created_at"))}
    }
    fun sendMessage(conversationId:String,body:String):MessageItem {
        val uid=savedSession()?.userId?:error("Нет сессии")
        val a=JSONArray(request("/rest/v1/messages","POST",JSONObject().put("conversation_id",conversationId).put("sender_id",uid).put("body",body),token(),"return=representation"))
        val o=a.getJSONObject(0)
        return MessageItem(o.getString("id"),o.getString("conversation_id"),o.getString("sender_id"),o.optString("body"),o.optString("created_at"))
    }
    fun createCall(calleeId:String,video:Boolean):CallTicket {
        val o=JSONObject(function("sotigram-calls",JSONObject().put("action","create").put("calleeId",calleeId).put("video",video))).getJSONObject("call")
        return CallTicket(o.getString("id"),o.optString("status"),savedSession()?.userId.orEmpty(),calleeId,o.optString("call_type")=="video")
    }
    fun incomingCall():CallTicket? {
        val uid=savedSession()?.userId?:return null
        val a=array("/rest/v1/sotigram_calls?select=id,status,caller_id,callee_id,call_type&callee_id=eq.$uid&status=eq.ringing&order=created_at.desc&limit=1")
        if(a.length()==0)return null
        val o=a.getJSONObject(0);return CallTicket(o.getString("id"),o.optString("status"),o.optString("caller_id"),o.optString("callee_id"),o.optString("call_type")=="video")
    }
    fun callStatus(id:String):String {
        val a=array("/rest/v1/sotigram_calls?select=status&id=eq.$id&limit=1")
        return a.optJSONObject(0)?.optString("status")?:"ended"
    }
    fun callAction(action:String,id:String)=JSONObject(function("sotigram-calls",JSONObject().put("action",action).put("callId",id)))
    fun callCredentials(id:String):CallCredentials {
        val o=JSONObject(function("sotigram-calls",JSONObject().put("action","token").put("callId",id)))
        return CallCredentials(o.getString("url"),o.getString("token"),o.getString("callId"))
    }
    private fun function(name:String,body:JSONObject):String {
        val b=Request.Builder().url("$BASE_URL/functions/v1/$name").post(body.toString().toRequestBody(JSON))
            .addHeader("apikey",PUBLIC_KEY).addHeader("Authorization","Bearer ${token()}").addHeader("Content-Type","application/json")
        http.newCall(b.build()).execute().use{r->val raw=r.body?.string().orEmpty();if(!r.isSuccessful)throw IllegalStateException(try{JSONObject(raw).optString("error").ifBlank{raw}}catch(_:Exception){raw});return raw}
    }
    fun gifts():List<CatalogItem> {
        val a=JSONArray(rpc("sotigram_gift_catalog"))
        return (0 until a.length()).map{val o=a.getJSONObject(it);CatalogItem(o.optString("code"),o.optString("title"),o.optString("description"),o.optString("emoji"),o.optLong("price_stars"))}
    }
    fun nfts():List<CatalogItem> {
        val a=JSONArray(rpc("sotigram_nft_catalog"))
        return (0 until a.length()).map{val o=a.getJSONObject(it);CatalogItem(o.optString("code"),o.optString("title"),o.optString("description"),o.optString("emoji"),o.optLong("price_stars"),o.optString("rarity"))}
    }
    fun nftCatalog():List<CatalogItem> { val a=JSONArray(rpc("sotigram_nft_catalog")); return (0 until a.length()).map{val o=a.getJSONObject(it);CatalogItem(o.optString("code"),o.optString("title"),o.optString("description"),o.optString("emoji"),o.optLong("price_stars"),o.optString("rarity"))} }
    fun myNfts():List<CatalogItem> {
        val a=JSONArray(rpc("sotigram_my_nfts"))
        return (0 until a.length()).map{val o=a.getJSONObject(it);CatalogItem(o.optString("catalog_code"),o.optString("title"),o.optString("description"),o.optString("emoji"),0,o.optString("rarity"))}
    }
    fun buyNft(code:String)=rpc("sotigram_nft_buy",JSONObject().put("nft_code",code))
    fun sendGift(recipientUsername:String,code:String,message:String="")=rpc("sotigram_send_gift",JSONObject().put("recipient_username",recipientUsername).put("gift_code",code).put("gift_message",message))
    fun myGifts()=JSONArray(rpc("sotigram_my_gifts"))
    fun stories():JSONArray=array("/rest/v1/stories?select=id,user_id,media_path,media_type,caption,created_at,expires_at&expires_at=gt.${enc(java.time.Instant.now().toString())}&order=created_at.desc&limit=100")
    fun ownerOverview()=JSONObject(rpc("sotigram_owner_overview"))
    fun walletBalance():Long {
        val uid=savedSession()?.userId?:return 0
        val a=array("/rest/v1/sotigram_wallets?select=balance&user_id=eq.$uid&limit=1")
        return a.optJSONObject(0)?.optLong("balance")?:0
    }
}
