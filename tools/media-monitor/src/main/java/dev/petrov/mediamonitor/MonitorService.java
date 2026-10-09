package dev.petrov.mediamonitor;

import android.content.ComponentName;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.MediaMetadata;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.app.Notification;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import static dev.petrov.mediamonitor.ReportStore.*;

public final class MonitorService extends NotificationListenerService {
    static volatile MonitorService live;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Map<MediaSession.Token,Watch> watches=new HashMap<>();
    private MediaSessionManager manager;
    private boolean observing;
    private int nextId=1;
    private final MediaSessionManager.OnActiveSessionsChangedListener sessions=list -> update(list);
    private final Runnable tick=new Runnable() { public void run() {
        if(recording(MonitorService.this)) {
            if(expired(MonitorService.this)) stop(MonitorService.this,"30-minute or 4-MiB limit / reboot");
            else if(!observing) begin();
        }
        if(!recording(MonitorService.this) && observing) detach();
        handler.postDelayed(this,1000);
    }};
    @Override public void onListenerConnected() {
        live=this; manager=(MediaSessionManager)getSystemService(MEDIA_SESSION_SERVICE);
        write(this,"LISTENER_CONNECTED",null,json()); handler.removeCallbacks(tick); handler.post(tick);
    }
    @Override public void onListenerDisconnected() {
        write(this,"LISTENER_DISCONNECTED",null,json()); live=null; detach(); handler.removeCallbacks(tick);
    }
    @Override public void onDestroy() { live=null; detach(); handler.removeCallbacksAndMessages(null); super.onDestroy(); }
    static void refresh() { MonitorService s=live; if(s!=null) s.handler.post(()->{ if(recording(s)) s.begin(); else s.detach(); }); }
    private void error(String operation, Exception e) { write(this,"ERROR",null,with(operation,e.getClass().getSimpleName())); }
    private void begin() {
        if(observing || manager==null) return;
        try {
            manager.addOnActiveSessionsChangedListener(sessions,new ComponentName(this,MonitorService.class),handler);
            observing=true; update(manager.getActiveSessions(new ComponentName(this,MonitorService.class)));
            StatusBarNotification[] ns=getActiveNotifications();
            if(ns!=null) for(StatusBarNotification n:ns) capture(n,"NOTIFICATION_INITIAL");
            write(this,"OBSERVING",null,json());
        } catch(Exception e) { detach(); error("subscribe",e); }
    }
    private void detach() {
        if(manager!=null && observing) manager.removeOnActiveSessionsChangedListener(sessions);
        observing=false;
        for(Watch w:watches.values()) w.controller.unregisterCallback(w.callback);
        watches.clear();
    }
    private void update(List<MediaController> controllers) {
        if(!recording(this)) return;
        if(controllers==null) controllers=Collections.emptyList();
        JSONArray list=new JSONArray();
        for(int i=0;i<controllers.size();i++) {
            MediaController c=controllers.get(i);
            if(!allowed(c.getPackageName())) continue;
            JSONObject item=with("package",c.getPackageName()); put(item,"systemIndex",i); list.put(item);
            watch(c);
        }
        JSONObject order=with("players",list); put(order,"totalSessions",controllers.size());
        write(this,"ACTIVE_SESSIONS",null,order);
        // Notification-backed inactive sessions remain observed until destruction; capped at 24.
        for(Watch w:new ArrayList<>(watches.values())) {
            w.snapshot("SESSION_REFRESH");
        }
    }
    private void watch(MediaController c) {
        if(!allowed(c.getPackageName()) || watches.containsKey(c.getSessionToken()) || watches.size()>=24) return;
        Watch w=new Watch(c,nextId++); watches.put(c.getSessionToken(),w);
        c.registerCallback(w.callback,handler); w.snapshot("SESSION_ADDED");
    }
    private boolean media(StatusBarNotification n) {
        if(!allowed(n.getPackageName())) return false;
        Notification v=n.getNotification();
        return Notification.CATEGORY_TRANSPORT.equals(v.category) || (v.extras!=null && v.extras.containsKey(Notification.EXTRA_MEDIA_SESSION));
    }
    private void capture(StatusBarNotification n,String event) {
        if(!recording(this) || !media(n)) return;
        try {
            write(this,event,n.getPackageName(),Snapshots.notification(this,n));
            if(n.getNotification().extras!=null) {
                Object token=n.getNotification().extras.get(Notification.EXTRA_MEDIA_SESSION);
                if(token instanceof MediaSession.Token) {
                    MediaController c=new MediaController(this,(MediaSession.Token)token);
                    if(c.getPackageName().equals(n.getPackageName())) watch(c);
                    else write(this,"TOKEN_PACKAGE_MISMATCH",n.getPackageName(),json());
                }
            }
        } catch(Exception e) { error("notification",e); }
    }
    @Override public void onNotificationPosted(StatusBarNotification n) { capture(n,"NOTIFICATION_POSTED"); }
    @Override public void onNotificationRemoved(StatusBarNotification n) { capture(n,"NOTIFICATION_REMOVED"); }
    private final class Watch {
        final MediaController controller; final int id;
        Watch(MediaController c,int i) { controller=c; id=i; }
        void snapshot(String kind) {
            try { JSONObject o=Snapshots.session(controller); put(o,"session",id); write(MonitorService.this,kind,controller.getPackageName(),o); }
            catch(Exception e) { error("session",e); }
        }
        final MediaController.Callback callback=new MediaController.Callback() {
            @Override public void onMetadataChanged(MediaMetadata m) { snapshot("METADATA"); }
            @Override public void onPlaybackStateChanged(android.media.session.PlaybackState s) { snapshot("PLAYBACK"); }
            @Override public void onQueueChanged(List<MediaSession.QueueItem> q) { snapshot("QUEUE"); }
            @Override public void onAudioInfoChanged(MediaController.PlaybackInfo info) { snapshot("AUDIO_INFO"); }
            @Override public void onSessionDestroyed() {
                write(MonitorService.this,"SESSION_DESTROYED",controller.getPackageName(),with("session",id));
                controller.unregisterCallback(this); watches.remove(controller.getSessionToken());
            }
        };
    }
}
