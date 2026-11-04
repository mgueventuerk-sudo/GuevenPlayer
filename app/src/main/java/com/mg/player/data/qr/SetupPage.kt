package com.mg.player.data.qr

import com.mg.player.core.security.SealScript

/** HTML-Seiten, die das Smartphone nach dem Scannen des QR-Codes angezeigt bekommt. */
internal object SetupPage {

    private const val STYLE = """
        *{box-sizing:border-box}body{margin:0;font-family:system-ui,-apple-system,sans-serif;background:#07090f;color:#f4f5fa}
        main{max-width:480px;margin:0 auto;padding:24px 16px 48px}h1{font-size:22px;margin:8px 0 4px}
        p{color:#a2a8b8;line-height:1.5}label{display:block;font-size:14px;margin:16px 0 6px;color:#c9cdd8}
        input{width:100%;padding:14px;border-radius:10px;border:1px solid #262b3b;background:#10131c;color:#fff;font-size:16px}
        .tabs{display:flex;gap:8px;margin:20px 0 4px}.tabs label{flex:1;margin:0;text-align:center;padding:12px;border-radius:10px;
        border:1px solid #262b3b;background:#10131c;cursor:pointer}.tabs input{display:none}
        .tabs input:checked+span{color:#fff}.tabs label:has(input:checked){border-color:#5b5cf6;background:#1d1e4d}
        button{width:100%;margin-top:28px;padding:16px;border:0;border-radius:10px;background:linear-gradient(90deg,#5b5cf6,#22d3ee);color:#fff;font-size:17px;font-weight:600}
        .hide{display:none}.hint{font-size:13px;color:#7c8396;margin-top:6px}
    """

    private fun escape(value: String) = value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

    fun form(token: String): String = """
        <!doctype html><html lang="de"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1"><title>GuevenPlayer einrichten</title>
        <style>$STYLE</style></head><body><main>
        <h1>GuevenPlayer einrichten</h1>
        <p>Gib hier deine Zugangsdaten ein. Sie werden im Browser verschlüsselt und direkt an deinen Fernseher im selben WLAN gesendet.</p>
        <form id="f">
          <input type="hidden" name="t" value="${escape(token)}">
          <div class="tabs">
            <label><input type="radio" name="type" value="xtream" checked onchange="sw()"><span>Xtream Codes</span></label>
            <label><input type="radio" name="type" value="m3u" onchange="sw()"><span>M3U-Link</span></label>
          </div>
          <label for="name">Name (optional)</label><input id="name" name="name" placeholder="z. B. Mein IPTV">
          <div id="x">
            <label for="server">Server-URL</label>
            <input id="server" name="server" inputmode="url" autocapitalize="off" autocorrect="off" placeholder="http://anbieter.tv:8080">
            <label for="user">Benutzername</label><input id="user" name="user" autocapitalize="off" autocorrect="off">
            <label for="pass">Passwort</label><input id="pass" name="pass" type="password" autocapitalize="off">
          </div>
          <div id="m" class="hide">
            <label for="m3u">M3U-URL</label>
            <input id="m3u" name="m3u" inputmode="url" autocapitalize="off" autocorrect="off" placeholder="http://…/get.php?…">
            <div class="hint">Tipp: get.php-Links werden automatisch als Xtream-Zugang erkannt (mit Covern &amp; Beschreibungen).</div>
          </div>
          <label for="epg">EPG-URL (optional)</label>
          <input id="epg" name="epg" inputmode="url" autocapitalize="off" autocorrect="off" placeholder="http://…/epg.xml.gz">
          <button type="submit">An Fernseher senden</button>
          <div id="status"></div>
        </form></main>
        <script>${SealScript.JS}
        var key=shareKey(),st=document.getElementById('status');
        if(!key){st.textContent='Bitte den QR-Code am Fernseher scannen – dieser Link ist unvollständig.';}
        document.getElementById('f').onsubmit=function(e){
          e.preventDefault();
          if(!key){return;}
          st.textContent='Wird verschlüsselt gesendet…';
          fetch('/submit?t=${escape(token)}',{method:'POST',headers:{'Content-Type':'application/json'},
            body:JSON.stringify(seal(key,new URLSearchParams(new FormData(this)).toString()))})
            .then(function(r){return r.text();})
            .then(function(html){document.open();document.write(html);document.close();})
            .catch(function(){st.textContent='Senden fehlgeschlagen. Sind Handy und Fernseher im selben WLAN?';});
        };
        function sw(){var m=document.querySelector('input[name=type]:checked').value==='m3u';
        document.getElementById('x').className=m?'hide':'';document.getElementById('m').className=m?'':'hide';}</script>
        </body></html>
    """.trimIndent()

    fun message(title: String, text: String): String = """
        <!doctype html><html lang="de"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1"><title>${escape(title)}</title>
        <style>$STYLE</style></head><body><main><h1>${escape(title)}</h1><p>${escape(text)}</p></main></body></html>
    """.trimIndent()
}
