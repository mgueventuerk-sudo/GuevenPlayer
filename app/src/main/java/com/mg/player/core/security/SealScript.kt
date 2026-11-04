package com.mg.player.core.security

/**
 * Gegenstück zu [ShareKey] für die Handy-Seiten: ChaCha20-Poly1305 in JavaScript (ohne HTTPS gibt es im Browser
 * kein WebCrypto). shareKey() liest den Schlüssel aus #k=…, seal(key, text) liefert {n, c} für [ShareKey.open].
 */
internal object SealScript {
    const val JS = """
function rd(b,i){return (b[i]|b[i+1]<<8|b[i+2]<<16|b[i+3]<<24)>>>0;}
function rl(a,n){return ((a<<n)|(a>>>(32-n)))>>>0;}
function q(x,a,b,c,d){x[a]=x[a]+x[b];x[d]=rl(x[d]^x[a],16);x[c]=x[c]+x[d];x[b]=rl(x[b]^x[c],12);
  x[a]=x[a]+x[b];x[d]=rl(x[d]^x[a],8);x[c]=x[c]+x[d];x[b]=rl(x[b]^x[c],7);}
function chacha(key,nonce,data,ctr){
  var out=new Uint8Array(data.length),st=new Uint32Array(16),w=new Uint32Array(16),i;
  for(var p=0;p<data.length;p+=64){
    st[0]=0x61707865;st[1]=0x3320646e;st[2]=0x79622d32;st[3]=0x6b206574;
    for(i=0;i<8;i++)st[4+i]=rd(key,i*4);
    st[12]=ctr++;st[13]=rd(nonce,0);st[14]=rd(nonce,4);st[15]=rd(nonce,8);
    w.set(st);
    for(i=0;i<10;i++){q(w,0,4,8,12);q(w,1,5,9,13);q(w,2,6,10,14);q(w,3,7,11,15);
      q(w,0,5,10,15);q(w,1,6,11,12);q(w,2,7,8,13);q(w,3,4,9,14);}
    for(i=0;i<16;i++)w[i]=w[i]+st[i];
    for(i=0;i<64&&p+i<data.length;i++)out[p+i]=data[p+i]^((w[i>>2]>>>((i&3)*8))&255);
  }
  return out;
}
function le(b){var x=BigInt(0);for(var i=b.length-1;i>=0;i--)x=(x<<BigInt(8))|BigInt(b[i]);return x;}
function poly(k,m){
  var r=le(k.subarray(0,16))&BigInt('0x0ffffffc0ffffffc0ffffffc0fffffff'),s=le(k.subarray(16,32));
  var p=(BigInt(1)<<BigInt(130))-BigInt(5),a=BigInt(0);
  for(var i=0;i<m.length;i+=16){var b=m.subarray(i,i+16);a=(a+le(b)+(BigInt(1)<<BigInt(8*b.length)))*r%p;}
  a=(a+s)&((BigInt(1)<<BigInt(128))-BigInt(1));
  var t=new Uint8Array(16);for(var j=0;j<16;j++){t[j]=Number(a&BigInt(255));a=a>>BigInt(8);}
  return t;
}
function b64(a){var s='';for(var i=0;i<a.length;i++)s+=String.fromCharCode(a[i]);return btoa(s);}
function unb64(s){s=s.replace(/-/g,'+').replace(/_/g,'/');while(s.length%4)s+='=';
  var b=atob(s),a=new Uint8Array(b.length);for(var i=0;i<b.length;i++)a[i]=b.charCodeAt(i);return a;}
function shareKey(){var m=/[#&]k=([A-Za-z0-9_-]+)/.exec(location.hash);var k=m?unb64(m[1]):null;return k&&k.length===32?k:null;}
function seal(key,text){
  var nonce=crypto.getRandomValues(new Uint8Array(12));
  var c=chacha(key,nonce,new TextEncoder().encode(text),1);
  var pad=(16-c.length%16)%16,m=new Uint8Array(c.length+pad+16),len=c.length;
  m.set(c,0);
  for(var i=0;i<8;i++){m[c.length+pad+8+i]=len%256;len=Math.floor(len/256);}
  var out=new Uint8Array(c.length+16);
  out.set(c,0);out.set(poly(chacha(key,nonce,new Uint8Array(32),0),m),c.length);
  return {n:b64(nonce),c:b64(out)};
}
"""
}
