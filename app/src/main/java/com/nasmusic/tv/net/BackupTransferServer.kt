package com.nasmusic.tv.net

import android.content.Context
import com.google.gson.Gson
import com.nasmusic.tv.R
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.BackupFileUtils
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份传输服务器
 *
 * 在 TV 上启动 HTTP server，手机扫码后浏览器打开备份管理页面，支持：
 * - 查看 TV 端所有备份文件列表
 * - 下载备份到手机
 * - 上传手机上的备份文件到 TV
 * - 直接在手机上触发恢复
 *
 * 生命周期：由 BackupTransferDialog 在打开时 [start]，关闭时 [stop]。
 *
 * @param context 用于访问 BackupFileUtils
 * @param onRestore 恢复备份的回调，传入备份 JSON 内容，返回是否成功。
 *   非挂起类型：调用方负责在 NanoHTTPD 工作线程上同步桥接 suspend 逻辑
 *   （server 不依赖协程库，桥接职责集中在 ViewModel）。
 */
class BackupTransferServer(
    private val context: Context,
    private val onRestore: (String) -> Boolean,
    private val onBackupChanged: () -> Unit = {},
    private val port: Int = DEFAULT_PORT
) {

    companion object {
        const val DEFAULT_PORT = 18081
        private const val TAG = "BackupTransferServer"

        /**
         * 上传体量上限（32MB）。
         * 原实现按客户端 Content-Length 直接 `ByteArray(contentLength.toInt())`，
         * 同 LAN 攻击者谎报 1.5GB 即触发 OutOfMemoryError——该 Error 不被 catch(Exception) 捕获，
         * 直接崩进程（DoS）。
         */
        const val MAX_UPLOAD_BYTES = 32L * 1024 * 1024
    }

    private var server: Impl? = null

    /** P1-1：一次性鉴权 token，随二维码 URL 下发（server 重启即更换） */
    private val authToken = LocalServerAuth.newToken()

    /** P1-1：带 token 的二维码 URL（手机浏览器打开后种 Cookie） */
    fun buildUrl(ip: String): String = "http://$ip:$port/?t=$authToken"

    fun start(): Boolean {
        AppLog.i(TAG, "start: called, server=$server")
        if (server != null) {
            AppLog.i(TAG, "start: already running, returning true")
            return true
        }
        val impl = Impl(context, onRestore, onBackupChanged, port, authToken)
        return try {
            impl.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = impl
            AppLog.i(TAG, "start: started on port $port")
            true
        } catch (e: IOException) {
            AppLog.e(TAG, "start: failed on port $port", e)
            false
        }
    }

    fun stop() {
        AppLog.i(TAG, "stop: called, server=$server")
        server?.let {
            try {
                it.stop()
                AppLog.i(TAG, "stop: stopped")
            } catch (e: Exception) {
                AppLog.w(TAG, "stop: error", e)
            }
        }
        server = null
    }

    // ---- NanoHTTPD 实现 ----

    private class Impl(
        private val context: Context,
        private val onRestore: (String) -> Boolean,
        private val onBackupChanged: () -> Unit,
        port: Int,
        private val authToken: String
    ) : NanoHTTPD(port) {

        private val gson = Gson()
        private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        override fun serve(session: IHTTPSession): Response {
            // P1-1：一次性 token 鉴权（query t= 或 Cookie auth=），拒绝同网段未授权访问。
            // 该 server 的 /api/restore 可整体覆盖用户数据，是鉴权收益最大的一处。
            if (!LocalServerAuth.isAuthorized(session, authToken)) return LocalServerAuth.forbidden(session)
            // 落盘日志：手机报 "Failed to fetch" 时，这一行的有无直接区分两种病因
            // ——「请求根本没到电视」（待机 / 弹窗已关 / 浏览器走了云端代理）
            //   与「到了但传输层被拒」。AppLog.d/i 在 release 会被 R8 折掉，必须用 w。
            AppLog.w(
                TAG,
                "请求: ${session.method} ${session.uri} " +
                    "cl=${session.headers["content-length"] ?: "-"} " +
                    "te=${session.headers["transfer-encoding"] ?: "-"} " +
                    "ua=${session.headers["user-agent"]?.take(24) ?: "-"}"
            )
            return when {
                session.uri == "/" && session.method == Method.GET -> servePage()
                session.uri == "/api/list" && session.method == Method.GET -> handleList()
                session.uri.startsWith("/api/download") && session.method == Method.GET -> handleDownload(session)
                session.uri == "/api/upload" && session.method == Method.POST -> handleUpload(session)
                session.uri.startsWith("/api/restore") && session.method == Method.POST -> handleRestore(session)
                else -> {
                    AppLog.w(TAG, "serve: 404 for ${session.method} ${session.uri}")
                    newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
                }
            }
        }

        private fun servePage(): Response {
            val response = newFixedLengthResponse(
                Response.Status.OK,
                "text/html; charset=UTF-8",
                buildBackupPageHtml(context)
            )
            response.addHeader("Set-Cookie", LocalServerAuth.cookieHeader(authToken))
            return response
        }

        private fun buildBackupPageHtml(context: Context): String {
            // 用 Gson 生成 STR JSON，自动转义换行符/引号等特殊字符
            // 避免 Android strings.xml 中的 \n 被解析为真实换行后破坏 JS 单引号字符串
            val strMap = mapOf(
                "empty" to context.getString(R.string.html_backup_empty),
                "loadError" to context.getString(R.string.html_backup_load_error),
                "downloading" to context.getString(R.string.html_backup_status_downloading),
                "downloadStarted" to context.getString(R.string.html_backup_status_download_started),
                "confirmRestore" to context.getString(R.string.html_backup_confirm_restore),
                "restoring" to context.getString(R.string.html_backup_status_restoring),
                "restoreFailed" to context.getString(R.string.html_backup_status_restore_failed),
                "uploading" to context.getString(R.string.html_backup_uploading),
                "uploadFailed" to context.getString(R.string.html_backup_status_upload_failed),
                "offline" to context.getString(R.string.html_backup_status_offline),
                "diagReachable" to context.getString(R.string.html_backup_status_diag_reachable),
                "diagUnreachable" to context.getString(R.string.html_backup_status_diag_unreachable),
                "diagEmpty" to context.getString(R.string.html_backup_status_diag_empty),
                "uploadBtn" to context.getString(R.string.html_backup_upload_btn)
            )
            val strJson = gson.toJson(strMap)
            return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>${context.getString(R.string.html_backup_title)}</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;background:#1a1a2e;color:#eee;min-height:100vh;padding:16px}
.card{background:#16213e;border-radius:16px;padding:24px;max-width:640px;margin:0 auto;box-shadow:0 8px 32px rgba(0,0,0,.3)}
h2{text-align:center;margin-bottom:4px;font-size:20px}
.sub{text-align:center;color:#888;font-size:13px;margin-bottom:20px}
.section{margin-bottom:20px}
.section h3{font-size:15px;color:#e94560;margin-bottom:10px}
.backup-item{background:#0a0a23;border-radius:10px;padding:12px 14px;margin-bottom:8px;display:flex;flex-direction:column;gap:4px}
.backup-name{font-size:13px;word-break:break-all;color:#eee}
.backup-time{font-size:12px;color:#666}
.backup-actions{display:flex;gap:8px;margin-top:6px}
.btn{padding:8px 16px;font-size:13px;border:none;border-radius:8px;cursor:pointer;font-weight:600}
.btn-download{background:#0f3460;color:#eee}
.btn-download:active{background:#1a4a80}
.btn-restore{background:#e94560;color:#fff}
.btn-restore:active{background:#c73e54}
.btn-upload{width:100%;padding:14px;font-size:16px;background:#e94560;color:#fff;border:none;border-radius:10px;font-weight:600;cursor:pointer;margin-top:10px}
.btn-upload:active{background:#c73e54}
.btn-upload:disabled{background:#555;color:#999}
input[type=file]{width:100%;padding:10px;font-size:14px;background:#0a0a23;border:2px solid #0f3460;border-radius:8px;color:#eee;margin-bottom:4px}
.status{text-align:center;margin-top:12px;font-size:14px;min-height:20px}
.status.ok{color:#4ecca3}
.status.err{color:#e94560}
.empty{text-align:center;color:#555;font-size:14px;padding:20px}
.loading{text-align:center;color:#666;font-size:14px;padding:20px}
</style>
</head>
<body>
<div class="card">
<h2>NAS Music TV</h2>
<p class="sub">${context.getString(R.string.html_backup_subtitle)}</p>

<div class="section">
<h3>${context.getString(R.string.html_backup_section_files)}</h3>
<div id="backupList" class="loading">${context.getString(R.string.html_backup_loading)}</div>
</div>

<div class="section">
<h3>${context.getString(R.string.html_backup_section_upload)}</h3>
<input type="file" id="fileInput" accept=".json,application/json">
<button class="btn-upload" id="uploadBtn" onclick="uploadBackup()">${context.getString(R.string.html_backup_upload_btn)}</button>
</div>

<div id="status" class="status"></div>
</div>

<script>
var STR = $strJson;
// 部分手机浏览器（含小米自带）不给「IP 站点」存 Cookie，Cookie 鉴权会全线 403。
// 二维码 URL 里本来就带一次性 token，页面把它显式带回每个接口，不依赖 Cookie。
function apiUrl(p){
  var m=location.search.match(/[?&]t=([^&]+)/);
  if(!m)return p;
  return p+(p.indexOf('?')>=0?'&':'?')+'t='+encodeURIComponent(m[1]);
}
// 修复（2026-09-22 审查）：备份文件名可能含 HTML/属性敏感字符（RemoteControlHtml 的
// esc/escAttr 教训）——展示名走 escHtml，onclick 里的文件名走 JSON.stringify+escAttr
//（结构引号与数据引号统一过实体层，浏览器解码后仍是合法 JS 字符串字面量）。
function escHtml(s){return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;');}
function escAttr(s){return String(s==null?'':s).replace(/&/g,'&amp;').replace(/'/g,'&#39;').replace(/"/g,'&quot;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}
function loadBackups(){
  fetch(apiUrl('/api/list'))
    .then(function(r){return r.json()})
    .then(function(d){
      var list=document.getElementById('backupList');
      if(!d.backups||d.backups.length===0){
        list.innerHTML='<div class="empty">'+STR.empty+'</div>';
        return;
      }
      list.innerHTML=d.backups.map(function(b){
        var nameJs = escAttr(JSON.stringify(b.name));
        return '<div class="backup-item">'+
          '<div class="backup-name">'+escHtml(b.name)+'</div>'+
          '<div class="backup-time">'+escHtml(b.time)+'</div>'+
          '<div class="backup-actions">'+
            '<button class="btn btn-download" onclick=\'downloadBackup('+nameJs+')\'>${context.getString(R.string.html_backup_download)}</button>'+
            '<button class="btn btn-restore" onclick=\'restoreBackup('+nameJs+')\'>${context.getString(R.string.html_backup_restore)}</button>'+
          '</div>'+
        '</div>';
      }).join('');
    })
    .catch(function(e){
      document.getElementById('backupList').innerHTML='<div class="empty">'+STR.loadError+'</div>';
    });
}

function downloadBackup(name){
  showStatus(STR.downloading,'');
  window.location=apiUrl('/api/download?name='+encodeURIComponent(name));
  setTimeout(function(){showStatus(STR.downloadStarted,'ok');},1000);
}

function restoreBackup(name){
  if(!confirm(STR.confirmRestore.replace('%s',name)))return;
  showStatus(STR.restoring,'');
  fetch(apiUrl('/api/restore?name='+encodeURIComponent(name)),{method:'POST'})
    .then(function(r){return r.json()})
    .then(function(d){
      showStatus(d.message, d.ok?'ok':'err');
      if(d.ok) setTimeout(loadBackups,2000);
    })
    .catch(function(e){showStatus(STR.restoreFailed,'err');});
}

// 上传失败时的自检：用同一个接口前缀发一个 GET 探测。
// 收到任何 HTTP 响应（含 403）= 电视在线，问题出在这个 POST 本身（浏览器拦截/改写）；
// 探测也无响应 = 电视端服务已停或端口不通。电视侧此时没有任何请求日志，只能由客户端给出判别。
function probe(base){
  var done=false;
  function finish(reachable,detail){
    if(done)return;
    done=true;
    showStatus(base+' | '+(reachable?STR.diagReachable:STR.diagUnreachable).replace('%s',detail),'err');
  }
  setTimeout(function(){finish(false,'timeout 4s')},4000);
  fetch(apiUrl('/api/list'),{method:'GET',cache:'no-store'})
    .then(function(r){finish(true,'HTTP '+r.status)})
    .catch(function(e){finish(false,(e&&e.message)?String(e.message):'error')});
}

function uploadBackup(){
  var input=document.getElementById('fileInput');
  var btn=document.getElementById('uploadBtn');
  if(!input.files||input.files.length===0){
    showStatus(STR.uploadFailed,'err');
    return;
  }
  var file=input.files[0];
  btn.disabled=true;
  btn.textContent=STR.uploading;
  showStatus(STR.uploading.replace('%s',file.name),'');
  var reader=new FileReader();
  reader.onload=function(){
    var blob=new Blob([reader.result],{type:'application/json'});
    // 部分国产浏览器从云端/内容提供器取文件时读出 0 字节——必须在发请求前就告知，
    // 否则服务端只会记录「请求体为空」，用户端看到的却是含义不明的「上传失败」
    if(blob.size===0){
      showStatus(STR.diagEmpty.replace('%s',file.name+' ('+file.size+' B)'),'err');
      btn.disabled=false;
      btn.textContent=STR.uploadBtn;
      return;
    }
    var tag=' ['+blob.size+'/'+file.size+' B]';
    fetch(apiUrl('/api/upload'),{method:'POST',body:blob})
      .then(function(r){
        if(!r.ok)throw new Error('HTTP '+r.status);
        return r.json();
      })
      .then(function(d){
        showStatus(d.message, d.ok?'ok':'err');
        if(d.ok){
          input.value='';
          loadBackups();
        }
      })
      .catch(function(e){
        var m=(e&&e.message)?String(e.message):'';
        // fetch 自身被拒时浏览器只给 "Failed to fetch"，属传输层失败（连不上电视），
        // 与服务端返回的业务错误不同——必须分开报，否则用户看不到该做什么
        if(/failed to fetch|network|load failed|aborted|connection|err_/i.test(m)||m===''){
          probe(STR.offline+tag);
        }else{
          showStatus(STR.uploadFailed+': '+m+tag,'err');
        }
      })
      .finally(function(){
        btn.disabled=false;
        btn.textContent=STR.uploadBtn;
      });
  };
  reader.onerror=function(){
    showStatus(STR.uploadFailed+': reader error','err');
    btn.disabled=false;
    btn.textContent=STR.uploadBtn;
  };
  reader.readAsArrayBuffer(file);
}

function showStatus(msg,type){
  var s=document.getElementById('status');
  s.textContent=msg;
  s.className='status'+(type?' '+type:'');
}

loadBackups();
</script>
</body>
</html>
""".trimIndent()
        }

        /** 列出 TV 端备份文件 */
        private fun handleList(): Response {
            return try {
                val backups = BackupFileUtils.listBackups(context)
                val items = backups.map { bf ->
                    mapOf(
                        "name" to bf.displayName,
                        "time" to timeFormat.format(Date(bf.lastModified))
                    )
                }
                val json = gson.toJson(mapOf("backups" to items))
                newFixedLengthResponse(Response.Status.OK, "application/json; charset=UTF-8", json)
            } catch (e: Exception) {
                AppLog.e(TAG, "handleList failed", e)
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json; charset=UTF-8",
                    """{"backups":[]}"""
                )
            }
        }

        /** 下载指定备份文件 */
        private fun handleDownload(session: IHTTPSession): Response {
            return try {
                val name = getQueryParam(session, "name") ?: return notFound(context.getString(R.string.backup_msg_missing_name))
                val backups = BackupFileUtils.listBackups(context)
                val target = backups.find { it.displayName == name }
                    ?: return notFound(context.getString(R.string.backup_msg_not_found, name))

                val result = BackupFileUtils.read(context, target.uri)
                val json = result.getOrElse { e ->
                    return newFixedLengthResponse(
                        Response.Status.INTERNAL_ERROR,
                        "text/plain; charset=UTF-8",
                        context.getString(R.string.backup_msg_read_failed, e.message)
                    )
                }

                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json; charset=UTF-8",
                    json
                ).apply {
                    // 触发浏览器下载
                    addHeader("Content-Disposition", "attachment; filename=\"$name\"")
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "handleDownload failed", e)
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "text/plain; charset=UTF-8",
                    context.getString(R.string.backup_msg_download_failed, e.message)
                )
            }
        }

        /** 上传备份文件（raw body = JSON 内容） */
        private fun handleUpload(session: IHTTPSession): Response {
            // 直接从 inputStream 读取原始字节，用 UTF-8 解码
            // 不用 parseBody()：某些 ROM 上 Charset.defaultCharset() 非 UTF-8，导致中文乱码
            //
            // 不在此处判 Content-Length：交给 HttpRequestBody 统一处理定长与 chunked，
            // 并保证「任何拒绝路径都不把未消费的 body 留在 keep-alive 连接上」。
            val contentLength = session.headers["content-length"] ?: "-"
            val transferEncoding = session.headers["transfer-encoding"] ?: "-"
            return try {
                when (val body = session.readRequestBody(MAX_UPLOAD_BYTES)) {
                    is HttpRequestBody.Result.OverLimit -> {
                        AppLog.w(TAG, "上传拒绝: 体量超上限 ${MAX_UPLOAD_BYTES / 1024 / 1024}MB (content-length=$contentLength transfer-encoding=$transferEncoding)")
                        jsonResponse(false, context.getString(R.string.backup_msg_upload_too_large, MAX_UPLOAD_BYTES / 1024 / 1024)).noKeepAlive()
                    }
                    is HttpRequestBody.Result.Malformed -> {
                        AppLog.w(TAG, "上传失败: 请求体不完整 ${body.detail} (content-length=$contentLength transfer-encoding=$transferEncoding)")
                        jsonResponse(false, context.getString(R.string.backup_msg_upload_malformed, body.detail)).noKeepAlive()
                    }
                    is HttpRequestBody.Result.Read -> {
                        AppLog.w(TAG, "上传到达: ${body.bytes.size} 字节 (content-length=$contentLength transfer-encoding=$transferEncoding)")
                        var json = String(body.bytes, Charsets.UTF_8)
                        // 去掉 UTF-8 BOM（如果有）
                        if (json.startsWith("\uFEFF")) {
                            json = json.substring(1)
                        }
                        if (json.isBlank()) {
                            AppLog.w(TAG, "上传失败: 请求体解码后为空")
                            return jsonResponse(false, context.getString(R.string.backup_msg_upload_empty))
                        }
                        val result = BackupFileUtils.export(context, json)
                        if (result.isSuccess) {
                            val savedName = result.getOrThrow()
                            AppLog.w(TAG, "上传已保存: $savedName (${json.length} 字符)")
                            onBackupChanged.invoke()
                            jsonResponse(true, context.getString(R.string.backup_msg_saved, savedName))
                        } else {
                            AppLog.w(TAG, "上传落盘失败: ${result.exceptionOrNull()?.message}")
                            jsonResponse(false, context.getString(R.string.backup_msg_save_failed, result.exceptionOrNull()?.message))
                        }
                    }
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "handleUpload failed", e)
                jsonResponse(false, context.getString(R.string.backup_msg_upload_failed, e.message)).noKeepAlive()
            }
        }

        /** 恢复指定备份文件 */
        private fun handleRestore(session: IHTTPSession): Response {
            return try {
                val name = getQueryParam(session, "name") ?: return jsonResponse(false, context.getString(R.string.backup_msg_missing_name))
                val backups = BackupFileUtils.listBackups(context)
                val target = backups.find { it.displayName == name }
                    ?: return jsonResponse(false, context.getString(R.string.backup_msg_not_found, name))

                val result = BackupFileUtils.read(context, target.uri)
                val json = result.getOrElse { e ->
                    return jsonResponse(false, context.getString(R.string.backup_msg_read_failed, e.message))
                }

                // 调用恢复回调（非挂起；调用方在工作线程上同步桥接 suspend 逻辑）
                val ok = onRestore.invoke(json)
                if (ok) {
                    jsonResponse(true, context.getString(R.string.backup_msg_restore_ok))
                } else {
                    jsonResponse(false, context.getString(R.string.backup_msg_restore_format))
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "handleRestore failed", e)
                jsonResponse(false, context.getString(R.string.backup_msg_restore_failed, e.message))
            }
        }

        private fun getQueryParam(session: IHTTPSession, key: String): String? {
            return session.parameters[key]?.firstOrNull()
        }

        private fun jsonResponse(ok: Boolean, message: String): Response {
            val json = gson.toJson(mapOf("ok" to ok, "message" to message))
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json; charset=UTF-8",
                json
            )
        }

        private fun notFound(msg: String): Response {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=UTF-8", msg)
        }
    }
}
