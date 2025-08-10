package com.example.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.session.MediaSessionCompat;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import org.json.JSONObject;

public class MediaPlaybackService extends Service {

    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "media_playback_channel";
    private String lastTitle = "";

    private MediaPlayer mediaPlayer;
    private boolean isPreparing = false;
    private MediaSessionCompat mediaSession;
    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        MediaPlaybackService getService() {
            return MediaPlaybackService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        mediaSession = new MediaSessionCompat(this, "MediaPlaybackService");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                play();
            }

            @Override
            public void onPause() {
                pause();
            }

            @Override
            public void onStop() {
                stop();
                stopSelf();
            }
        });
        mediaSession.setMediaButtonReceiver(null);
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                isPreparing = false;
                mp.start();
                startForeground(NOTIFICATION_ID, buildNotification("Playing", null));
            }
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Media Playback",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private Notification buildNotification(String contentText, Bitmap artwork) {
        // Play/Pause action
        Intent playPauseIntent = new Intent(this, MediaPlaybackService.class);
        int playPauseIcon;
        String playPauseTitle;
        if (isPlaying()) {
            playPauseIntent.setAction("PAUSE_ACTION");
            playPauseIcon = R.drawable.ic_pause;
            playPauseTitle = "Pause";
        } else {
            playPauseIntent.setAction("PLAY_ACTION");
            playPauseIcon = R.drawable.ic_play;
            playPauseTitle = "Play";
        }
        PendingIntent playPausePendingIntent = PendingIntent.getService(this, 0, playPauseIntent, PendingIntent.FLAG_IMMUTABLE);

        // Mute/Unmute action
        Intent muteUnmuteIntent = new Intent(this, MediaPlaybackService.class);
        int muteUnmuteIcon;
        String muteUnmuteTitle;
        if (isMuted) {
            muteUnmuteIntent.setAction("UNMUTE_ACTION");
            muteUnmuteIcon = R.drawable.ic_unmute;
            muteUnmuteTitle = "Unmute";
        } else {
            muteUnmuteIntent.setAction("MUTE_ACTION");
            muteUnmuteIcon = R.drawable.ic_mute;
            muteUnmuteTitle = "Mute";
        }
        PendingIntent muteUnmutePendingIntent = PendingIntent.getService(this, 0, muteUnmuteIntent, PendingIntent.FLAG_IMMUTABLE);


        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Bootie Mashup Radio")
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_notification)
                .setLargeIcon(artwork)
                .addAction(playPauseIcon, playPauseTitle, playPausePendingIntent)
                .addAction(muteUnmuteIcon, muteUnmuteTitle, muteUnmutePendingIntent)
                .setStyle(new MediaStyle()
                        .setShowActionsInCompactView(0, 1)
                        .setMediaSession(mediaSession.getSessionToken()))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true);

        return builder.build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case "PLAY_ACTION":
                    play();
                    break;
                case "PAUSE_ACTION":
                    pause();
                    break;
                case "MUTE_ACTION":
                    mute();
                    break;
                case "UNMUTE_ACTION":
                    unmute();
                    break;
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private final Handler trackInfoHandler = new Handler(Looper.getMainLooper());
    private final Runnable trackInfoRunnable = new Runnable() {
        @Override
        public void run() {
            new FetchTrackInfoTask().execute();
            trackInfoHandler.postDelayed(this, 5000); // Check every 5 seconds
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        mediaSession = new MediaSessionCompat(this, "MediaPlaybackService");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                play();
            }

            @Override
            public void onPause() {
                pause();
            }

            @Override
            public void onStop() {
                stop();
                stopSelf();
            }
        });
        mediaSession.setMediaButtonReceiver(null);
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                isPreparing = false;
                mp.start();
                startForeground(NOTIFICATION_ID, buildNotification("Playing", null));
            }
        });
        trackInfoHandler.post(trackInfoRunnable);
    }

    @Override
    public void onDestroy() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        mediaSession.release();
        trackInfoHandler.removeCallbacks(trackInfoRunnable);
        super.onDestroy();
    }

    public void play() {
        if (!mediaPlayer.isPlaying() && !isPreparing) {
            isPreparing = true;
            Uri uri = Uri.parse("https://c7.radioboss.fm:18205/stream");
            try {
                mediaPlayer.reset();
                mediaPlayer.setDataSource(this, uri);
                mediaPlayer.prepareAsync();
                mediaSession.setActive(true);
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
            } catch (Exception e) {
                e.printStackTrace();
                isPreparing = false;
            }
        }
    }

    public void stop() {
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.stop();
            mediaPlayer.reset();
            updateNotification();
            mediaSession.setActive(false);
            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED);
        }
    }

    public void pause() {
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.stop();
            mediaPlayer.reset();
            updateNotification();
            mediaSession.setActive(false);
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
        }
    }

    public boolean isPlaying() {
        return mediaPlayer.isPlaying();
    }

    private boolean isMuted = false;
    private int previousVolume = -1;

    public void mute() {
        if (mediaPlayer.isPlaying()) {
            AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (!isMuted) {
                previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
                isMuted = true;
                updateNotification();
            }
        }
    }

    public void unmute() {
        if (mediaPlayer.isPlaying()) {
            AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (isMuted) {
                if (previousVolume != -1) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, previousVolume, 0);
                }
                isMuted = false;
                updateNotification();
            }
        }
    }

    public void updateNotification(String contentText, Bitmap artwork) {
        Notification notification = buildNotification(contentText, artwork);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification);
    }

    private void updateNotification() {
        // Overloaded method for internal use
        updateNotification(isMuted ? "Muted" : "Playing", null);
    }

    private void updatePlaybackState(int state) {
        PlaybackStateCompat.Builder playbackStateBuilder = new PlaybackStateCompat.Builder();
        long actions = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE;
        playbackStateBuilder.setActions(actions);
        playbackStateBuilder.setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f);
        mediaSession.setPlaybackState(playbackStateBuilder.build());
    }

    public void updateMetadata(String title, Bitmap artwork) {
        MediaMetadataCompat.Builder metadataBuilder = new MediaMetadataCompat.Builder();
        metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, title);
        metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, " ");
        metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, artwork);
        mediaSession.setMetadata(metadataBuilder.build());
    }

    private class FetchTrackInfoTask extends android.os.AsyncTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... voids) {
            try {
                // Fetch Now Playing Info
                java.net.URL url = new java.net.URL("https://c7.radioboss.fm/w/nowplayinginfo?u=205");
                java.net.HttpURLConnection urlConnection = (java.net.HttpURLConnection) url.openConnection();
                try {
                    java.io.InputStream in = new java.io.BufferedInputStream(urlConnection.getInputStream());
                    java.util.Scanner s = new java.util.Scanner(in).useDelimiter("\\A");
                    String result = s.hasNext() ? s.next() : "";
                    org.json.JSONObject json = new org.json.JSONObject(result);
                    String title = json.getString("nowplaying");

                    if (!title.equals(lastTitle)) {
                        lastTitle = title;

                        // Fetch Artwork
                        java.net.URL imageUrl = new java.net.URL("https://c7.radioboss.fm/w/artwork/205.jpg");
                        Bitmap artwork = android.graphics.BitmapFactory.decodeStream(imageUrl.openConnection().getInputStream());

                        updateNotification(title, artwork);
                        updateMetadata(title, artwork);
                    }
                } finally {
                    urlConnection.disconnect();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        }
    }
}
