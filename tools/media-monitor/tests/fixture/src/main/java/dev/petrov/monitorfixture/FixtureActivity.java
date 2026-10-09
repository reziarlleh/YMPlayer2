package dev.petrov.monitorfixture;

import android.app.*;
import android.os.*;
import android.media.*;
import android.media.session.*;
import android.graphics.*;
import android.widget.*;

/** Synthetic media publisher. Never use its measurements as evidence about the official app. */
public final class FixtureActivity extends Activity {
    static MediaSession session;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        boolean unrelated=!getPackageName().equals("ru.yandex.music");
        String title=unrelated?"PRIVATE_UNRELATED_CANARY":"FIXTURE_TRACK_ONE";
        if(session==null) session=new MediaSession(this,"fixture");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS); session.setActive(true);
        Bitmap bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888); bitmap.eraseColor(Color.MAGENTA);
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST,"FIXTURE_ARTIST").putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE,title)
            .putBitmap(MediaMetadata.METADATA_KEY_ART,bitmap).putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART,bitmap)
            .putLong(MediaMetadata.METADATA_KEY_DURATION,180000).build());
        session.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING,5000,1)
            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE).build());
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("fixture","Fixture",NotificationManager.IMPORTANCE_LOW));
        nm.notify(5,new Notification.Builder(this,"fixture").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title).setContentText("FIXTURE_ARTIST").setSubText("FIXTURE_SUBTEXT").setLargeIcon(bitmap)
            .setCategory(Notification.CATEGORY_TRANSPORT).setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(true)
            .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken())).build());
        LinearLayout body=new LinearLayout(this); body.setOrientation(1); setContentView(body);
        for(String label:new String[]{"Pause fixture","Next fixture","Destroy fixture"}) {
            Button b=new Button(this); b.setText(label); body.addView(b);
            b.setOnClickListener(v->{
                if(label.startsWith("Pause")) session.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED,5500,0).build());
                else if(label.startsWith("Next")) session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,"FIXTURE_TRACK_TWO").build());
                else { nm.cancel(5); session.release(); session=null; finish(); }
            });
        }
    }
}
