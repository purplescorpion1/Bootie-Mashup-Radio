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

public class MediaPlaybackService extends Service {

    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "media_playback_channel";

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
        Intent stopIntent = new Intent(this, MediaPlaybackService.class);
        stopIntent.setAction("STOP_ACTION");
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent muteIntent = new Intent(this, MediaPlaybackService.class);
        muteIntent.setAction("MUTE_ACTION");
        PendingIntent mutePendingIntent = PendingIntent.getService(this, 0, muteIntent, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Pirate Radio")
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_notification)
                .setLargeIcon(artwork)
                .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)
                .addAction(R.drawable.ic_mute, "Mute", mutePendingIntent)
                .setStyle(new MediaStyle()
                        .setShowActionsInCompactView(0, 1)
                        .setMediaSession(mediaSession.getSessionToken()))
                .setPriority(NotificationCompat.PRIORITY_LOW);

        return builder.build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case "STOP_ACTION":
                    stop();
                    stopSelf();
                    break;
                case "MUTE_ACTION":
                    mute();
                    break;
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        mediaSession.release();
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
}
