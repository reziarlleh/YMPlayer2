package dev.petrov.mediamonitor;

import android.app.Notification;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.content.Context;
import android.os.Build;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import org.json.JSONArray;
import org.json.JSONObject;
import java.security.MessageDigest;
import static dev.petrov.mediamonitor.ReportStore.*;

final class Snapshots {
    static JSONObject session(MediaController c) {
        JSONObject o=json(); put(o,"flags",c.getFlags()); put(o,"sessionActivity",c.getSessionActivity()!=null);
        MediaMetadata m=c.getMetadata();
        JSONObject fields=json();
        if (m != null) {
            String[] keys={MediaMetadata.METADATA_KEY_TITLE,MediaMetadata.METADATA_KEY_ARTIST,MediaMetadata.METADATA_KEY_ALBUM,
                MediaMetadata.METADATA_KEY_ALBUM_ARTIST,MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION};
            for(String key:keys) put(fields,key,text(m.getText(key)));
            put(fields,"duration",m.getLong(MediaMetadata.METADATA_KEY_DURATION));
            for(String key:new String[]{MediaMetadata.METADATA_KEY_ART,MediaMetadata.METADATA_KEY_ALBUM_ART,MediaMetadata.METADATA_KEY_DISPLAY_ICON}) put(fields,key,bitmap(m.getBitmap(key)));
            for(String key:new String[]{MediaMetadata.METADATA_KEY_ART_URI,MediaMetadata.METADATA_KEY_ALBUM_ART_URI,MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI}) put(fields,key+"_present",m.containsKey(key));
            if(Build.VERSION.SDK_INT>=26) put(fields,MediaMetadata.METADATA_KEY_MEDIA_URI+"_present",m.containsKey(MediaMetadata.METADATA_KEY_MEDIA_URI));
        }
        put(o,"metadata",fields); put(o,"state",state(c.getPlaybackState()));
        put(o,"queueCount",c.getQueue()==null ? 0 : c.getQueue().size()); put(o,"queueTitle",text(c.getQueueTitle()));
        MediaController.PlaybackInfo info=c.getPlaybackInfo();
        if(info!=null) {
            JSONObject a=json(); put(a,"type",info.getPlaybackType()); put(a,"volumeControl",info.getVolumeControl());
            put(a,"volume",info.getCurrentVolume()); put(a,"maxVolume",info.getMaxVolume());
            put(a,"usage",info.getAudioAttributes().getUsage()); put(a,"contentType",info.getAudioAttributes().getContentType()); put(o,"audio",a);
        }
        return o;
    }
    static JSONObject state(PlaybackState s) {
        JSONObject o=json(); put(o,"present",s!=null);
        if(s!=null) { put(o,"state",s.getState()); put(o,"speed",s.getPlaybackSpeed()); put(o,"positionMs",s.getPosition());
            put(o,"bufferedMs",s.getBufferedPosition()); put(o,"updatedElapsedMs",s.getLastPositionUpdateTime()); put(o,"actions",s.getActions()); }
        return o;
    }
    static JSONObject bitmap(Bitmap b) {
        JSONObject o=json(); put(o,"present",b!=null);
        if(b!=null) { put(o,"width",b.getWidth()); put(o,"height",b.getHeight()); put(o,"bytes",b.getByteCount());
            put(o,"config",String.valueOf(b.getConfig()));
            Bitmap sample=null;
            try {
                sample=Bitmap.createScaledBitmap(b,32,32,false);
                int[] pixels=new int[1024]; sample.getPixels(pixels,0,32,0,0,32,32);
                MessageDigest digest=MessageDigest.getInstance("SHA-256");
                for(int pixel:pixels) { digest.update((byte)(pixel>>24)); digest.update((byte)(pixel>>16)); digest.update((byte)(pixel>>8)); digest.update((byte)pixel); }
                StringBuilder hash=new StringBuilder(); for(byte part:digest.digest()) hash.append(String.format(java.util.Locale.ROOT,"%02x",part & 255));
                put(o,"sample32sha256",hash.toString());
            } catch(Exception e) { put(o,"sampleUnavailable",e.getClass().getSimpleName()); }
            finally { if(sample!=null && sample!=b) sample.recycle(); }
        }
        return o;
    }
    static JSONObject notification(Context context, StatusBarNotification sbn) {
        Notification n=sbn.getNotification(); Bundle e=n.extras; JSONObject o=json();
        put(o,"id",sbn.getId()); put(o,"category",n.category); put(o,"flags",n.flags); put(o,"visibility",n.visibility);
        put(o,"group",text(n.getGroup())); put(o,"groupSummary",(n.flags & Notification.FLAG_GROUP_SUMMARY)!=0);
        put(o,"ongoing",sbn.isOngoing()); put(o,"postTime",sbn.getPostTime());
        if(e!=null) {
            for(String k:new String[]{Notification.EXTRA_TITLE,Notification.EXTRA_TEXT,Notification.EXTRA_SUB_TEXT,Notification.EXTRA_INFO_TEXT}) put(o,k,text(e.getCharSequence(k)));
            put(o,"template",text(e.getString(Notification.EXTRA_TEMPLATE)));
            put(o,"tokenPresent",e.containsKey(Notification.EXTRA_MEDIA_SESSION));
            Object art=e.get(Notification.EXTRA_LARGE_ICON);
            if(art instanceof Bitmap) put(o,"largeBitmap",bitmap((Bitmap)art));
        }
        Icon icon=n.getLargeIcon(); put(o,"largeIconPresent",icon!=null);
        if(icon!=null && Build.VERSION.SDK_INT>=28) {
            int type=icon.getType(); put(o,"largeIconType",type);
            // Never dereference an app-controlled URI, including content/file/network artwork.
            if(type==Icon.TYPE_BITMAP || type==Icon.TYPE_ADAPTIVE_BITMAP) {
                try { Drawable d=icon.loadDrawable(context); if(d instanceof BitmapDrawable) put(o,"largeBitmap",bitmap(((BitmapDrawable)d).getBitmap())); }
                catch(Exception failure){ put(o,"largeIconUnavailable",failure.getClass().getSimpleName()); }
            }
        }
        put(o,"contentIntent",n.contentIntent!=null);
        JSONArray actions=new JSONArray();
        if(n.actions!=null) for(int i=0;i<Math.min(12,n.actions.length);i++) {
            JSONObject a=json(); put(a,"title",text(n.actions[i].title)); put(a,"intent",n.actions[i].actionIntent!=null); actions.put(a);
        }
        put(o,"actions",actions); return o;
    }
}
