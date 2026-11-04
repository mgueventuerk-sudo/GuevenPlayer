package com.mg.player.data.share

import com.mg.player.core.security.SealScript

/** HTML-Seiten fürs Handy: Sicherung hochladen, VPN einrichten und Rückmeldungen. */
internal object SharePages {

    private const val STYLE = """
        *{box-sizing:border-box}body{margin:0;font-family:system-ui,-apple-system,sans-serif;background:#07090f;color:#f4f5fa}
        main{max-width:480px;margin:0 auto;padding:24px 16px 48px}h1{font-size:22px;margin:8px 0 4px}
        p{color:#a2a8b8;line-height:1.5}input{width:100%;margin-top:10px;padding:14px;border-radius:10px;border:1px solid #262b3b;background:#10131c;color:#fff;font-size:16px}
        button{width:100%;margin-top:20px;padding:16px;border:0;border-radius:10px;background:linear-gradient(90deg,#5b5cf6,#22d3ee);color:#fff;font-size:17px;font-weight:600}
        #status{margin-top:16px;color:#e8eaf0}
    """

    private fun escape(value: String) = value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

    /** Sicherung herunterladen: verschlüsselt mit dem Kennwort, das am Fernseher festgelegt wurde. */
    fun backupPage(token: String): String = """
        <!doctype html><html lang="de"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1"><title>Sicherung herunterladen</title>
        <style>$STYLE</style></head><body><main>
        <h1>Sicherung herunterladen</h1>
        <p>Die Datei ist mit dem Kennwort verschlüsselt, das du am Fernseher festgelegt hast. Ohne dieses Kennwort
        lässt sie sich nicht wiederherstellen – bitte gut merken.</p>
        <a href="/backup/file?t=${escape(token)}"><button type="button">Verschlüsselte Sicherung herunterladen</button></a>
        </main></body></html>
    """.trimIndent()

    fun restoreForm(token: String): String = """
        <!doctype html><html lang="de"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1"><title>Sicherung wiederherstellen</title>
        <style>$STYLE</style></head><body><main>
        <h1>Sicherung wiederherstellen</h1>
        <p>Wähle die Datei <b>guevenplayer-sicherung.json</b>. Das Kennwort gibst du danach am Fernseher ein –
        es wird nicht übers Netzwerk geschickt. Achtung: Die Playlists und Einstellungen auf dem Fernseher werden ersetzt.</p>
        <input id="file" type="file" accept=".json,application/json">
        <button id="send">An Fernseher senden</button>
        <div id="status"></div>
        </main>
        <script>
        document.getElementById('send').onclick=function(){
          var f=document.getElementById('file').files[0];var st=document.getElementById('status');
          if(!f){st.textContent='Bitte zuerst eine Datei wählen.';return;}
          st.textContent='Wird gesendet…';
          var r=new FileReader();
          r.onload=function(){
            var payload=JSON.stringify({backup:r.result});
            fetch('/restore?t=${escape(token)}',{method:'POST',headers:{'Content-Type':'application/json'},body:payload})
              .then(function(res){return res.text();})
              .then(function(html){document.open();document.write(html);document.close();})
              .catch(function(){st.textContent='Senden fehlgeschlagen. Sind Handy und Fernseher im selben WLAN?';});
          };
          r.readAsText(f);
        };
        </script>
        </body></html>
    """.trimIndent()

    /**
     * VPN einrichten: .conf wählen oder einfügen. Der Browser verschlüsselt mit ChaCha20-Poly1305
     * ([SealScript]); der Schlüssel steht nur im URL-Fragment des QR-Codes (#k=…),
     * das der Browser nie mitschickt.
     */
    fun vpnForm(token: String): String = """
        <!doctype html><html lang="de"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1"><title>VPN einrichten</title>
        <style>$STYLE textarea{width:100%;min-height:140px;margin-top:10px;padding:12px;border-radius:10px;border:1px solid #262b3b;background:#10131c;color:#fff;font:13px monospace}</style>
        </head><body><main>
        <h1>VPN einrichten</h1>
        <p>Wähle die WireGuard-Datei (<b>.conf</b>) aus deinem VPN-Konto (z. B. Surfshark → Manuelle Einrichtung → WireGuard)
        oder füge ihren Inhalt unten ein. Die Datei wird hier im Browser verschlüsselt – der Schlüssel dafür steht nur
        im QR-Code und geht nicht übers Netzwerk.</p>
        <input id="file" type="file" accept=".conf,text/plain">
        <textarea id="txt" placeholder="…oder Inhalt einfügen ([Interface] …)" autocomplete="off" spellcheck="false"></textarea>
        <button id="send">An Fernseher senden</button>
        <div id="status"></div>
        </main>
        <script>${SealScript.JS}
        var st=document.getElementById('status');
        var key=shareKey();
        if(!key){st.textContent='Bitte den QR-Code am Fernseher scannen – dieser Link ist unvollständig.';}
        function send(text,name){
          if(!key){return;}
          if(text.indexOf('[Interface]')<0){st.textContent='Das sieht nicht nach einer WireGuard-Konfiguration aus.';return;}
          var sealed=seal(key,text);
          st.textContent='Wird verschlüsselt gesendet…';
          fetch('/vpn?t=${escape(token)}',{method:'POST',headers:{'Content-Type':'application/json'},
            body:JSON.stringify({n:sealed.n,c:sealed.c,f:name||''})})
            .then(function(res){return res.text();})
            .then(function(html){document.getElementById('txt').value='';document.open();document.write(html);document.close();})
            .catch(function(){st.textContent='Senden fehlgeschlagen. Sind Handy und Fernseher im selben WLAN?';});
        }
        document.getElementById('send').onclick=function(){
          var f=document.getElementById('file').files[0];var t=document.getElementById('txt').value;
          if(f){var r=new FileReader();r.onload=function(){send(r.result,f.name);};r.readAsText(f);}
          else if(t.trim()){send(t,'');}
          else{st.textContent='Bitte zuerst eine Datei wählen oder den Inhalt einfügen.';}
        };
        </script>
        </body></html>
    """.trimIndent()

    fun message(title: String, text: String): String = """
        <!doctype html><html lang="de"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1"><title>${escape(title)}</title>
        <style>$STYLE</style></head><body><main><h1>${escape(title)}</h1><p>${escape(text)}</p></main></body></html>
    """.trimIndent()
}
