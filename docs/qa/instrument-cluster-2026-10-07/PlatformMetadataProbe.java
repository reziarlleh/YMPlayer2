import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.graphics.Bitmap;
import org.json.JSONObject;
import java.util.HashMap;

/** Read-only external framework consumer, run only with emulator shell UID. */
public final class PlatformMetadataProbe {
  public static void main(String[] args) throws Exception {
    android.os.Looper.prepareMainLooper();
    // app_process does not run Zygote's modular media initializer on API35.
    try {
      Class<?> managerType = Class.forName("android.media.MediaServiceManager");
      Class.forName("android.media.MediaFrameworkPlatformInitializer")
          .getMethod("setMediaServiceManager",managerType)
          .invoke(null,managerType.getConstructor().newInstance());
    } catch (ClassNotFoundException olderAndroid) { }
    Class<?> activityThread = Class.forName("android.app.ActivityThread");
    Object thread = activityThread.getMethod("systemMain").invoke(null);
    Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
    MediaSessionManager manager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
    HashMap<String,String> previous = new HashMap<>();
    long until = System.currentTimeMillis() + (args.length == 0 ? 1000 : Long.parseLong(args[0]));
    do {
      for (MediaController controller : manager.getActiveSessions(null)) {
        String pkg = controller.getPackageName();
        if (!pkg.startsWith("dev.petrov.ymplayer2")) continue;
        JSONObject item = new JSONObject().put("package",pkg).put("flags",controller.getFlags());
        MediaMetadata metadata = controller.getMetadata();
        PlaybackState state = controller.getPlaybackState();
        item.put("state",state == null ? -1 : state.getState());
        item.put("speed",state == null ? 0 : state.getPlaybackSpeed());
        item.put("actions",state == null ? 0 : state.getActions());
        if (metadata != null) {
          JSONObject fields = new JSONObject();
          for (String key : new String[]{MediaMetadata.METADATA_KEY_TITLE,MediaMetadata.METADATA_KEY_ARTIST,MediaMetadata.METADATA_KEY_ALBUM,MediaMetadata.METADATA_KEY_DISPLAY_TITLE,MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE})
            fields.put(key,metadata.getString(key) == null ? JSONObject.NULL : metadata.getString(key));
          fields.put("duration",metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
          for (String key : new String[]{MediaMetadata.METADATA_KEY_ART,MediaMetadata.METADATA_KEY_ALBUM_ART,MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
            Bitmap image = metadata.getBitmap(key);
            fields.put(key,image == null ? JSONObject.NULL : image.getWidth()+"x"+image.getHeight());
          }
          for (String key : new String[]{MediaMetadata.METADATA_KEY_ART_URI,MediaMetadata.METADATA_KEY_ALBUM_ART_URI,MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI}) fields.put(key,metadata.containsKey(key));
          item.put("metadata",fields);
          android.media.MediaDescription description = metadata.getDescription();
          Bitmap icon = description.getIconBitmap();
          item.put("description", new JSONObject()
              .put("title", description.getTitle() == null ? JSONObject.NULL : description.getTitle().toString())
              .put("subtitle", description.getSubtitle() == null ? JSONObject.NULL : description.getSubtitle().toString())
              .put("icon", icon == null ? JSONObject.NULL : icon.getWidth()+"x"+icon.getHeight()));
        } else item.put("metadata",JSONObject.NULL);
        String current = item.toString();
        if (!current.equals(previous.put(pkg,current))) System.out.println(current);
      }
      Thread.sleep(100);
    } while (System.currentTimeMillis() < until);
  }
}
