package com.webshell.shell;

/**
 * JavaScript injected into every page (and every window.open popup).
 *
 * A WebView does not implement several things a normal browser does, so pages silently do nothing:
 *  - window.print()                          -> routed to the Android print dialog ("Save as PDF" included)
 *  - links/buttons that save a blob: / data:  -> routed to the native downloader
 *    (the blob is cached here, so it still works when the page revokes the URL right after click())
 *  - window.open(blob:/data:/x.pdf)           -> opened in the in-app PDF viewer
 *  - video.requestPictureInPicture()          -> native Picture-in-Picture
 * The script is idempotent and is safe to inject several times.
 */
final class PageHooks {
    private PageHooks() {}

    static final String JS = "(function(){"
            + "if(window.__wsHooks)return;window.__wsHooks=1;"
            + "function D(){return window.WebShellDl}"
            + "function P(){return window.WebShellPrint}"
            + "function M(){return window.WebShellMedia}"

            // ---- keep created blobs reachable even after the page revokes their URL
            + "var B=window.__wsBlobs={},K=[];"
            + "try{var oc=URL.createObjectURL;"
            + "URL.createObjectURL=function(o){var u=oc.call(URL,o);"
            + "try{if(o&&typeof o.size==='number'&&o.size<=67108864){B[u]=o;K.push(u);while(K.length>8){delete B[K.shift()]}}}catch(e){}return u}}catch(e){}"

            // ---- <a download href=blob:/data:> : handled natively (click, a.click() and dispatchEvent)
            + "function grab(a){try{var h=a.href||'';"
            + "if(a.hasAttribute('download')&&/^(blob:|data:)/i.test(h)&&D()){D().request(h,a.getAttribute('download')||'',a.type||'');return true}}catch(e){}return false}"
            + "document.addEventListener('click',function(e){var t=e.target,a=t&&t.closest?t.closest('a'):null;"
            + "if(a&&grab(a)){e.preventDefault();e.stopPropagation()}},true);"
            + "try{var ac=HTMLAnchorElement.prototype.click;"
            + "HTMLAnchorElement.prototype.click=function(){if(grab(this))return;return ac.apply(this,arguments)};"
            + "var ad=EventTarget.prototype.dispatchEvent;"
            + "EventTarget.prototype.dispatchEvent=function(ev){"
            + "if(this instanceof HTMLAnchorElement&&ev&&ev.type==='click'&&grab(this))return true;"
            + "return ad.apply(this,arguments)}}catch(e){}"

            // ---- window.open(pdf) : open in the viewer instead of an empty popup
            + "try{var wo=window.open;window.open=function(u,n,f){try{var s=String(u||'');"
            + "var pdf=/^data:application\\/pdf/i.test(s)||(/^blob:/i.test(s)&&B[s]&&B[s].type==='application/pdf')"
            + "||(/^https?:/i.test(s)&&/\\.pdf([?#].*)?$/i.test(s));"
            + "if(pdf&&D()){D().view(s,'','application/pdf');return null}}catch(e){}"
            + "return wo.apply(window,arguments)}}catch(e){}"

            // ---- window.print()
            + "try{window.print=function(){try{if(P())P().print(document.title||'')}catch(e){}}}catch(e){}"

            // ---- Picture-in-Picture for HTML5 video
            + "try{HTMLVideoElement.prototype.requestPictureInPicture=function(){"
            + "try{if(M())M().pip()}catch(e){}return Promise.resolve({width:0,height:0})};"
            + "Object.defineProperty(document,'pictureInPictureEnabled',{get:function(){return true},configurable:true})}catch(e){}"
            + "})();";

    /** Run in a popup once it has loaded: returns the source of an embedded PDF (iframe/embed/object) or "". */
    static final String FIND_EMBEDDED_PDF = "(function(){try{var n=document.querySelectorAll('iframe,embed,object');"
            + "for(var i=0;i<n.length;i++){var s=n[i].src||n[i].data||'';"
            + "if(/^data:application\\/pdf/i.test(s)||/^blob:/i.test(s)||/\\.pdf([?#].*)?$/i.test(s))return s}}catch(e){}return ''})()";
}
